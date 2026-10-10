package com.babasama.edendale.handoff

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.S3Configuration
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.remote.ServerLogin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Brings a linked account or saved server login from a phone to an Android
 * TV (I.2, Apple's `AccountHandoff`). Google's device sign-in can't grant
 * `drive.readonly` and Dropbox has none, so the TV asks a phone running
 * Edendale on the same network instead, device to device:
 *
 *    1. TV: Link Source → provider → Continue on a Phone registers
 *       `_edendale-handoff._tcp` with Network Service Discovery and shows a
 *       six-digit code (HandoffHost).
 *    2. Phone: Settings → Accounts → Link to a TV lists the TVs found, and
 *       the viewer picks one and types the code.
 *    3. Both sides run J-PAKE keyed by the code ([HandoffCrypto.Pake]); a
 *       wrong code fails the key confirmation before anything is sent.
 *    4. Over the AES-GCM channel the TV sends a [Request], the phone answers
 *       with a [Response] once the viewer confirms (an account already linked,
 *       a fresh sign-in, or a saved login), and the TV validates and stores
 *       it and reports a [Result].
 *
 * Messages are JSON behind a 4-byte big-endian length, at most 64 KiB, with
 * `"v": 1`; unknown versions and oversized frames close the connection.
 * Google allows 100 refresh tokens per account per client, so the TV reuses
 * the phone's token rather than minting another. Pure Kotlin.
 */
object AccountHandoff {
    /** The DNS-SD service type the TV registers. */
    const val SERVICE_TYPE = "_edendale-handoff._tcp."
    const val VERSION = 1
    const val MAX_MESSAGE_SIZE = 64 * 1024

    /** What the TV asks for: a provider or server kind, and its own name for the phone's confirmation. */
    data class Request(val kind: MediaSourceKind, val deviceName: String)

    enum class Status(val raw: String) {
        APPROVED("approved"),
        DECLINED("declined"),
        ;

        companion object {
            fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw }
        }
    }

    /** The phone's answer: approved with an account or a login, or declined. */
    data class Response(val status: Status, val account: Account? = null, val login: Login? = null) {
        companion object {
            val DECLINED = Response(Status.DECLINED)
        }
    }

    /** A cloud account, as the TV stores it. */
    data class Account(
        val kind: MediaSourceKind,
        val subject: String,
        val email: String?,
        val displayName: String?,
        val refreshToken: String,
        val scopes: List<String>,
        val driveId: String?,
    ) {
        constructor(account: CloudAccount) : this(
            account.kind, account.subject, account.email, account.displayName, account.refreshToken, account.scopes, account.driveId,
        )

        val cloudAccount: CloudAccount
            get() = CloudAccount(kind, subject, email, displayName, refreshToken, scopes, driveId)

        override fun toString() = "Account(${kind.raw}, ${email ?: subject})"
    }

    /**
     * A saved server login (SMB, SFTP, WebDAV, S3), with what the TV needs
     * to trust the server the way the phone does: an SFTP host key, a
     * pinned certificate (D10), and for S3 the bucket's location. [addresses]
     * are the phone's linked sources on that login, so the TV can start
     * browsing from one.
     */
    data class Login(
        val kind: MediaSourceKind,
        /** The server host, or the account key for S3. */
        val host: String,
        val port: Int?,
        val user: String,
        val password: String,
        val hostKeyFingerprint: String? = null,
        val certificateFingerprint: String? = null,
        val s3: S3Configuration? = null,
        val addresses: List<String> = emptyList(),
    ) {
        val serverLogin: ServerLogin get() = ServerLogin(user, password)

        override fun toString() = "Login(${kind.raw}, $user @ $host${port?.let { ":$it" }.orEmpty()})"
    }

    /** What the TV did with the response: stored it, or why not (a message in the TV's language). */
    data class Result(val stored: Boolean, val message: String? = null)

    sealed class HandoffException(message: String) : IOException(message) {
        class UnsupportedVersion(val version: Int) : HandoffException("Handoff version $version isn't supported")
        object MalformedMessage : HandoffException("Malformed handoff message")
        object MessageTooLarge : HandoffException("Handoff message too large")
        object Declined : HandoffException("Declined on the other device")
        object WrongCode : HandoffException("The code didn't match")
        object WrongKind : HandoffException("The other device answered for another provider")
        object TimedOut : HandoffException("The other device didn't answer in time")
        object ConnectionFailed : HandoffException("Couldn't connect to the other device")

        /** The TV couldn't use what the phone sent; [reason] is the TV's message. */
        class Rejected(val reason: String?) : HandoffException(reason ?: "Rejected by the other device")
    }

    // MARK: - Framing

    /** A length-prefixed frame. */
    fun frame(body: ByteArray): ByteArray {
        if (body.isEmpty() || body.size > MAX_MESSAGE_SIZE) throw HandoffException.MessageTooLarge
        val length = body.size
        return byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()) + body
    }

    /** The body length a 4-byte frame header announces. */
    fun bodyLength(header: ByteArray): Int {
        if (header.size != 4) throw HandoffException.MalformedMessage
        val length = header.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        if (length <= 0 || length > MAX_MESSAGE_SIZE) throw HandoffException.MessageTooLarge
        return length.toInt()
    }

    // MARK: - Encoding

    fun encodeRequest(request: Request): ByteArray = envelope("request") {
        put("kind", JsonPrimitive(request.kind.raw))
        put("deviceName", JsonPrimitive(request.deviceName))
    }

    fun encodeResponse(response: Response): ByteArray = envelope("response") {
        put("status", JsonPrimitive(response.status.raw))
        response.account?.let { account ->
            put(
                "account",
                buildJsonObject {
                    put("kind", JsonPrimitive(account.kind.raw))
                    put("subject", JsonPrimitive(account.subject))
                    put("email", account.email?.let(::JsonPrimitive) ?: JsonNull)
                    put("displayName", account.displayName?.let(::JsonPrimitive) ?: JsonNull)
                    put("refreshToken", JsonPrimitive(account.refreshToken))
                    put("scopes", JsonArray(account.scopes.map(::JsonPrimitive)))
                    put("driveId", account.driveId?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }
        response.login?.let { login ->
            put(
                "login",
                buildJsonObject {
                    put("kind", JsonPrimitive(login.kind.raw))
                    put("host", JsonPrimitive(login.host))
                    put("port", login.port?.let(::JsonPrimitive) ?: JsonNull)
                    put("user", JsonPrimitive(login.user))
                    put("password", JsonPrimitive(login.password))
                    put("hostKeyFingerprint", login.hostKeyFingerprint?.let(::JsonPrimitive) ?: JsonNull)
                    put("certificateFingerprint", login.certificateFingerprint?.let(::JsonPrimitive) ?: JsonNull)
                    login.s3?.let { s3 ->
                        put(
                            "s3",
                            buildJsonObject {
                                put("endpoint", JsonPrimitive(s3.endpoint))
                                put("region", JsonPrimitive(s3.region))
                                put("bucket", JsonPrimitive(s3.bucket))
                                put("pathStyle", JsonPrimitive(s3.usesPathStyle))
                            },
                        )
                    }
                    put("addresses", JsonArray(login.addresses.map(::JsonPrimitive)))
                },
            )
        }
    }

    fun encodeResult(result: Result): ByteArray = envelope("result") {
        put("stored", JsonPrimitive(result.stored))
        put("message", result.message?.let(::JsonPrimitive) ?: JsonNull)
    }

    // MARK: - Decoding

    fun decodeRequest(body: ByteArray): Request {
        val fields = open(body, "request")
        return Request(
            kind = fields.kind("kind") ?: throw HandoffException.MalformedMessage,
            deviceName = fields.string("deviceName") ?: throw HandoffException.MalformedMessage,
        )
    }

    fun decodeResponse(body: ByteArray): Response {
        val fields = open(body, "response")
        val status = Status.fromRaw(fields.string("status")) ?: throw HandoffException.MalformedMessage
        val account = fields["account"]?.takeUnless { it is JsonNull }?.let { element ->
            val json = element as? JsonObject ?: throw HandoffException.MalformedMessage
            Account(
                kind = json.kind("kind")?.takeIf { it.isCloudAccount } ?: throw HandoffException.MalformedMessage,
                subject = json.string("subject") ?: throw HandoffException.MalformedMessage,
                email = json.string("email"),
                displayName = json.string("displayName"),
                refreshToken = json.string("refreshToken") ?: throw HandoffException.MalformedMessage,
                scopes = (json["scopes"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                driveId = json.string("driveId"),
            )
        }
        val login = fields["login"]?.takeUnless { it is JsonNull }?.let { element ->
            val json = element as? JsonObject ?: throw HandoffException.MalformedMessage
            val s3 = (json["s3"] as? JsonObject)?.let { s3 ->
                S3Configuration(
                    endpoint = s3.string("endpoint") ?: throw HandoffException.MalformedMessage,
                    region = s3.string("region") ?: throw HandoffException.MalformedMessage,
                    bucket = s3.string("bucket") ?: throw HandoffException.MalformedMessage,
                    usesPathStyle = s3["pathStyle"]?.jsonPrimitive?.content == "true",
                )
            }
            Login(
                kind = json.kind("kind")?.takeIf { it.usesServerLogin } ?: throw HandoffException.MalformedMessage,
                host = json.string("host") ?: throw HandoffException.MalformedMessage,
                port = (json["port"] as? JsonPrimitive)?.intOrNull,
                user = json.string("user") ?: throw HandoffException.MalformedMessage,
                password = json.string("password") ?: throw HandoffException.MalformedMessage,
                hostKeyFingerprint = json.string("hostKeyFingerprint"),
                certificateFingerprint = json.string("certificateFingerprint"),
                s3 = s3,
                addresses = (json["addresses"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }
        if (status == Status.APPROVED && account == null && login == null) throw HandoffException.MalformedMessage
        return Response(status, account, login)
    }

    fun decodeResult(body: ByteArray): Result {
        val fields = open(body, "result")
        val stored = (fields["stored"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: throw HandoffException.MalformedMessage
        return Result(stored, fields.string("message"))
    }

    // MARK: - Plumbing

    private fun envelope(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray =
        buildJsonObject {
            put("v", JsonPrimitive(VERSION))
            put("type", JsonPrimitive(type))
            body()
        }.toString().toByteArray(Charsets.UTF_8)

    /**
     * Parses a message and checks its version before reading anything else
     * in it, then that it is the [type] expected.
     */
    internal fun open(body: ByteArray, type: String): JsonObject {
        val fields = parse(body)
        val version = (fields["v"] as? JsonPrimitive)?.intOrNull ?: throw HandoffException.MalformedMessage
        if (version != VERSION) throw HandoffException.UnsupportedVersion(version)
        if (fields.string("type") != type) throw HandoffException.MalformedMessage
        return fields
    }

    internal fun parse(body: ByteArray): JsonObject =
        runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull() ?: throw HandoffException.MalformedMessage

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.kind(name: String): MediaSourceKind? = MediaSourceKind.fromRaw(string(name))
}
