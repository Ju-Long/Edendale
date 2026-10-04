package com.babasama.edendale.oauth

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.remote.RemoteHttp
import com.babasama.edendale.remote.RemoteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A dependency-free OAuth 2.0 client for a public (secret-less) app (H.6,
 * Apple's `OAuthClient`): the authorization-code flow with PKCE (RFC 7636,
 * S256), refresh, and the device authorization grant (RFC 8628) for
 * OneDrive on TV. No provider SDK and no client secret. Nothing here logs a
 * token or puts one in a message.
 */
object Pkce {
    private val random = SecureRandom()

    /** A 43-character verifier from 32 random bytes, in the unreserved alphabet. */
    fun makeVerifier(): String = base64Url(ByteArray(32).also(random::nextBytes))

    /** `BASE64URL(SHA256(ASCII(verifier)))`, the S256 challenge. */
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** An unguessable `state` tying the redirect to this request. */
    fun makeState(): String = base64Url(ByteArray(16).also(random::nextBytes))

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
}

data class OAuthConfiguration(
    val kind: MediaSourceKind,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    /** RFC 8628 endpoint, when the provider grants the scopes Edendale needs through it (Microsoft yes, Google no). */
    val deviceAuthorizationEndpoint: String?,
    val redirectUri: String,
    /** The custom scheme the redirect activity answers. */
    val callbackScheme: String,
    val scopes: List<String>,
    val additionalAuthorizationParameters: Map<String, String> = emptyMap(),
    /** Microsoft wants the scopes repeated when redeeming and refreshing. */
    val sendsScopeToTokenEndpoint: Boolean = false,
)

/** A token endpoint's answer. [toString] never shows a token. */
data class OAuthTokens(
    val accessToken: String,
    val expiresInSeconds: Int? = null,
    val refreshToken: String? = null,
    val scope: String? = null,
    val idToken: String? = null,
    /** Dropbox returns the account with the token. */
    val accountId: String? = null,
) {
    val grantedScopes: List<String>? get() = scope?.split(' ')?.filter { it.isNotEmpty() }

    override fun toString() = "OAuthTokens(<redacted>, expiresIn=$expiresInSeconds)"
}

/** What the TV shows while the viewer approves on another device (RFC 8628). */
data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    /** Microsoft doesn't send one. */
    val verificationUriComplete: String?,
    val expiresAtMillis: Long,
    /** Seconds between polls; `slow_down` adds five. */
    val intervalSeconds: Int,
) {
    override fun toString() = "DeviceAuthorization($userCode, $verificationUri)"
}

/** Why a sign-in failed (Apple's `OAuthError`); messages are the Android layer's. */
sealed interface OAuthFailure {
    data object NotConfigured : OAuthFailure
    data object Cancelled : OAuthFailure
    data object StateMismatch : OAuthFailure
    data object MissingAuthorizationCode : OAuthFailure
    /** The viewer declined on the consent or device-code page. */
    data object AuthorizationDenied : OAuthFailure
    /** The refresh token was revoked or expired: sign in again. */
    data object InvalidGrant : OAuthFailure
    data object DeviceCodeExpired : OAuthFailure
    data class Server(val code: String, val description: String?) : OAuthFailure
    data class Http(val status: Int) : OAuthFailure
    data object MalformedResponse : OAuthFailure
}

class OAuthException(val kind: MediaSourceKind, val failure: OAuthFailure, cause: Throwable? = null) :
    IOException("${kind.raw}: $failure", cause)

class OAuthClient(
    val configuration: OAuthConfiguration,
    private val http: RemoteHttp,
) {
    private val kind get() = configuration.kind

    // MARK: - Authorization code with PKCE

    fun authorizationUrl(state: String, codeChallenge: String): String {
        val parameters = listOf(
            "client_id" to configuration.clientId,
            "response_type" to "code",
            "redirect_uri" to configuration.redirectUri,
            "scope" to configuration.scopes.joinToString(" "),
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        ) + configuration.additionalAuthorizationParameters.toSortedMap().toList()
        return configuration.authorizationEndpoint + "?" + formEncode(parameters)
    }

    suspend fun exchange(code: String, verifier: String): OAuthTokens = tokenRequest(
        buildList {
            add("grant_type" to "authorization_code")
            add("code" to code)
            add("client_id" to configuration.clientId)
            add("redirect_uri" to configuration.redirectUri)
            add("code_verifier" to verifier)
            if (configuration.sendsScopeToTokenEndpoint) add("scope" to configuration.scopes.joinToString(" "))
        },
    )

    suspend fun refresh(refreshToken: String): OAuthTokens = tokenRequest(
        buildList {
            add("grant_type" to "refresh_token")
            add("refresh_token" to refreshToken)
            add("client_id" to configuration.clientId)
            if (configuration.sendsScopeToTokenEndpoint) add("scope" to configuration.scopes.joinToString(" "))
        },
    )

    // MARK: - Device authorization (RFC 8628)

    suspend fun startDeviceAuthorization(nowMillis: Long = System.currentTimeMillis()): DeviceAuthorization {
        val endpoint = configuration.deviceAuthorizationEndpoint ?: throw OAuthException(kind, OAuthFailure.NotConfigured)
        val (status, body) = post(endpoint, listOf("client_id" to configuration.clientId, "scope" to configuration.scopes.joinToString(" ")))
        if (status !in 200..299) throw error(status, body)
        val fields = parse(body) ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
        val uri = fields.string("verification_uri") ?: fields.string("verification_url")
        val deviceCode = fields.string("device_code")
        val userCode = fields.string("user_code")
        val expiresIn = fields.int("expires_in")
        if (uri == null || deviceCode == null || userCode == null || expiresIn == null) {
            throw OAuthException(kind, OAuthFailure.MalformedResponse)
        }
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            verificationUriComplete = fields.string("verification_uri_complete"),
            expiresAtMillis = nowMillis + expiresIn * 1_000L,
            intervalSeconds = maxOf(fields.int("interval") ?: 5, 1),
        )
    }

    sealed interface DevicePoll {
        data object Pending : DevicePoll
        data object SlowDown : DevicePoll
        data class Approved(val tokens: OAuthTokens) : DevicePoll
    }

    /** One poll of the token endpoint. A declined or expired code throws. */
    suspend fun pollDeviceAuthorization(authorization: DeviceAuthorization): DevicePoll {
        val (status, body) = post(
            configuration.tokenEndpoint,
            listOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to authorization.deviceCode,
                "client_id" to configuration.clientId,
            ),
        )
        if (status in 200..299) return DevicePoll.Approved(tokens(body))
        return when (parse(body)?.string("error")) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "access_denied", "authorization_declined" -> throw OAuthException(kind, OAuthFailure.AuthorizationDenied)
            "expired_token", "code_expired" -> throw OAuthException(kind, OAuthFailure.DeviceCodeExpired)
            else -> throw error(status, body)
        }
    }

    /** Polls until the viewer approves, declines, or the code expires. */
    suspend fun waitForDeviceAuthorization(
        authorization: DeviceAuthorization,
        nowMillis: () -> Long = System::currentTimeMillis,
        sleepSeconds: suspend (Int) -> Unit = { delay(it * 1_000L) },
    ): OAuthTokens {
        var interval = authorization.intervalSeconds
        while (true) {
            sleepSeconds(interval)
            if (nowMillis() >= authorization.expiresAtMillis) throw OAuthException(kind, OAuthFailure.DeviceCodeExpired)
            when (val poll = pollDeviceAuthorization(authorization)) {
                DevicePoll.Pending -> Unit
                DevicePoll.SlowDown -> interval += 5
                is DevicePoll.Approved -> return poll.tokens
            }
        }
    }

    // MARK: - Plumbing

    private suspend fun tokenRequest(parameters: List<Pair<String, String>>): OAuthTokens {
        val (status, body) = post(configuration.tokenEndpoint, parameters)
        if (status !in 200..299) throw error(status, body)
        return tokens(body)
    }

    private fun tokens(body: ByteArray): OAuthTokens {
        val fields = parse(body) ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
        val access = fields.string("access_token")?.takeIf { it.isNotEmpty() } ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
        return OAuthTokens(
            accessToken = access,
            expiresInSeconds = fields.int("expires_in"),
            refreshToken = fields.string("refresh_token"),
            scope = fields.string("scope"),
            idToken = fields.string("id_token"),
            accountId = fields.string("account_id"),
        )
    }

    private suspend fun post(url: String, form: List<Pair<String, String>>): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val request = RemoteRequest(
            url = url,
            headers = mapOf("Accept" to "application/json"),
            method = "POST",
            body = formEncode(form).toByteArray(),
            contentType = "application/x-www-form-urlencoded",
        )
        val response = try {
            http.newCall(request, bodyLimit = 1 shl 20).execute()
        } catch (error: IOException) {
            throw OAuthException(kind, OAuthFailure.Http(0), error)
        }
        response.status to response.body
    }

    private fun error(status: Int, body: ByteArray): OAuthException {
        val fields = parse(body)
        val code = fields?.string("error") ?: return OAuthException(kind, OAuthFailure.Http(status))
        return OAuthException(
            kind,
            when (code) {
                "invalid_grant" -> OAuthFailure.InvalidGrant
                "access_denied" -> OAuthFailure.AuthorizationDenied
                else -> OAuthFailure.Server(code, fields.string("error_description"))
            },
        )
    }

    companion object {
        /**
         * The authorization code from the redirect [callbackUri], after checking
         * `state` and any error the provider reported.
         */
        fun authorizationCode(callbackUri: String, expectedState: String, kind: MediaSourceKind): String {
            val query = callbackUri.substringAfter('?', "").substringBefore('#')
            val items = query.split('&').filter { it.isNotEmpty() }.associate {
                formDecode(it.substringBefore('=')) to formDecode(it.substringAfter('=', ""))
            }
            items["error"]?.let { error ->
                if (error == "access_denied") throw OAuthException(kind, OAuthFailure.AuthorizationDenied)
                throw OAuthException(kind, OAuthFailure.Server(error, items["error_description"]))
            }
            if (items["state"] != expectedState) throw OAuthException(kind, OAuthFailure.StateMismatch)
            return items["code"]?.takeIf { it.isNotEmpty() } ?: throw OAuthException(kind, OAuthFailure.MissingAuthorizationCode)
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** `application/x-www-form-urlencoded`, escaping everything outside the unreserved set, so `+`, `&`, `=`, and spaces survive. */
        fun formEncode(parameters: List<Pair<String, String>>): String =
            parameters.joinToString("&") { (name, value) -> "${escape(name)}=${escape(value)}" }

        private fun escape(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (byte >= 0 && char in UNRESERVED) append(char) else append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }

        private fun formDecode(value: String) = com.babasama.edendale.connectors.SourceUrl.decode(value.replace('+', ' '))

        internal fun parse(body: ByteArray): JsonObject? =
            runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()

        internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    }
}

/** A JWT's payload. No signature check: the ID token came straight from Google's token endpoint over TLS (OIDC Core §3.1.3.7). */
fun decodeJwtClaims(jwt: String): JsonObject? {
    val parts = jwt.split('.')
    if (parts.size < 2) return null
    val payload = Pkce.decodeBase64Url(parts[1]) ?: return null
    return runCatching { Json.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull()
}

internal fun JsonObject.claim(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
