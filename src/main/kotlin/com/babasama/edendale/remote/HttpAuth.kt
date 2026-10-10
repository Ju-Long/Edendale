package com.babasama.edendale.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A server login (username and password). [toString] never shows the password. */
data class ServerLogin(val user: String, val password: String) {
    /** No username and no password: connect as a guest. */
    val isGuest: Boolean get() = user.isEmpty() && password.isEmpty()

    override fun toString() = "ServerLogin($user, <redacted>)"
}

/**
 * HTTP Basic and Digest authentication (RFC 7617, RFC 7616) for servers
 * reached with a saved login, such as WebDAV. Pure.
 */
object HttpAuth {

    data class Challenge(val scheme: String, val params: Map<String, String>) {
        fun param(name: String): String? = params[name.lowercase()]
    }

    /**
     * The challenges in a `WWW-Authenticate` value, such as
     * `Digest realm="x", nonce="y", qop="auth", Basic realm="x"`. A quoted
     * value may hold commas.
     */
    fun parseChallenges(header: String?): List<Challenge> {
        if (header.isNullOrBlank()) return emptyList()
        val challenges = mutableListOf<Challenge>()
        var scheme: String? = null
        var params = linkedMapOf<String, String>()
        var index = 0
        val text = header

        fun skipSpaceAndCommas() {
            while (index < text.length && (text[index] == ' ' || text[index] == ',' || text[index] == '\t')) index++
        }

        fun readToken(): String {
            val start = index
            while (index < text.length && text[index] !in " ,=\t") index++
            return text.substring(start, index)
        }

        while (true) {
            skipSpaceAndCommas()
            if (index >= text.length) break
            val token = readToken()
            if (token.isEmpty()) {
                index++
                continue
            }
            var look = index
            while (look < text.length && text[look] == ' ') look++
            if (look < text.length && text[look] == '=' && scheme != null) {
                // key=value or key="quoted value"
                index = look + 1
                while (index < text.length && text[index] == ' ') index++
                val value = if (index < text.length && text[index] == '"') {
                    index++
                    val out = StringBuilder()
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\' && index + 1 < text.length) index++
                        out.append(text[index])
                        index++
                    }
                    index++ // closing quote
                    out.toString()
                } else {
                    readToken()
                }
                params[token.lowercase()] = value
            } else if (look < text.length && text[look] == '=' && scheme == null) {
                // A token68 or stray parameter before any scheme: skip it.
                index = look + 1
                readToken()
            } else {
                scheme?.let { challenges += Challenge(it, params) }
                scheme = token
                params = linkedMapOf()
            }
        }
        scheme?.let { challenges += Challenge(it, params) }
        return challenges
    }

    fun basic(login: ServerLogin): String =
        "Basic " + Base64.getEncoder().encodeToString("${login.user}:${login.password}".toByteArray(Charsets.UTF_8))

    /**
     * A Digest `Authorization` value for [method] and [uri] (the request
     * target's path and query). SHA-256 and MD5, their `-sess` forms, and
     * `qop=auth`; null for a challenge it can't answer (`auth-int` only, or
     * an unknown algorithm).
     */
    fun digest(
        challenge: Challenge,
        login: ServerLogin,
        method: String,
        uri: String,
        nonceCount: Int,
        cnonce: String = newCnonce(),
    ): String? {
        val realm = challenge.param("realm") ?: return null
        val nonce = challenge.param("nonce") ?: return null
        val algorithmName = challenge.param("algorithm") ?: "MD5"
        val session = algorithmName.endsWith("-sess", ignoreCase = true)
        val hashName = when (algorithmName.removeSuffix("-sess").removeSuffix("-SESS").uppercase()) {
            "MD5" -> "MD5"
            "SHA-256" -> "SHA-256"
            else -> return null
        }
        fun hash(value: String): String =
            MessageDigest.getInstance(hashName).digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        val qops = challenge.param("qop")?.split(',')?.map { it.trim().lowercase() }.orEmpty()
        val qop = when {
            qops.isEmpty() -> null
            "auth" in qops -> "auth"
            else -> return null
        }
        val nc = "%08x".format(nonceCount)
        var ha1 = hash("${login.user}:$realm:${login.password}")
        if (session) ha1 = hash("$ha1:$nonce:$cnonce")
        val ha2 = hash("$method:$uri")
        val response = if (qop != null) hash("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else hash("$ha1:$nonce:$ha2")

        return buildList {
            add("username=\"${quote(login.user)}\"")
            add("realm=\"${quote(realm)}\"")
            add("nonce=\"${quote(nonce)}\"")
            add("uri=\"${quote(uri)}\"")
            add("algorithm=$algorithmName")
            add("response=\"$response\"")
            if (qop != null) {
                add("qop=$qop")
                add("nc=$nc")
                add("cnonce=\"${quote(cnonce)}\"")
            }
            challenge.param("opaque")?.let { add("opaque=\"${quote(it)}\"") }
        }.joinToString(", ", prefix = "Digest ")
    }

    private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private val random = SecureRandom()

    private fun newCnonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** The request target Digest signs: the path and query of [url]. */
    fun requestTarget(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash).substringBefore('#')
    }
}

/**
 * The login one server uses, and the challenge it last sent (H.3). Answers
 * Digest with a fresh nonce count each time, and Basic only once the server
 * asked for it, so a password never goes to a server that didn't. Thread-safe.
 */
class HttpAuthSession(val login: ServerLogin?) {
    private var challenge: HttpAuth.Challenge? = null
    private var nonceCount = 0

    /** [request] with an `Authorization` header when a challenge is known. */
    @Synchronized
    fun authorize(request: RemoteRequest): RemoteRequest {
        val login = login?.takeUnless { it.isGuest } ?: return request
        val current = challenge ?: return request
        val value = when {
            current.scheme.equals("Digest", ignoreCase = true) ->
                HttpAuth.digest(current, login, request.method, HttpAuth.requestTarget(request.url), ++nonceCount)
            current.scheme.equals("Basic", ignoreCase = true) -> HttpAuth.basic(login)
            else -> null
        } ?: return request
        return request.with("Authorization", value)
    }

    /**
     * Learns the challenge in a 401's `WWW-Authenticate`, preferring Digest
     * (SHA-256, then MD5) over Basic. False when there's no login to answer
     * with or nothing usable was offered.
     */
    @Synchronized
    fun learn(wwwAuthenticate: String?): Boolean {
        val login = login?.takeUnless { it.isGuest } ?: return false
        val challenges = HttpAuth.parseChallenges(wwwAuthenticate)
        val digests = challenges.filter { it.scheme.equals("Digest", ignoreCase = true) }
            .filter { HttpAuth.digest(it, login, "GET", "/", 1, "probe") != null }
        val chosen = digests.firstOrNull { it.param("algorithm")?.startsWith("SHA-256", ignoreCase = true) == true }
            ?: digests.firstOrNull()
            ?: challenges.firstOrNull { it.scheme.equals("Basic", ignoreCase = true) }
            ?: return false
        challenge = chosen
        nonceCount = 0
        return true
    }

    /** Sends [request], answering one authentication challenge with the login. */
    fun execute(http: RemoteHttp, request: RemoteRequest, bodyLimit: Int): RemoteResponse {
        val first = http.newCall(authorize(request), bodyLimit).execute()
        if (first.status != 401 || !learn(first.header("WWW-Authenticate"))) return first
        return http.newCall(authorize(request), bodyLimit).execute()
    }
}
