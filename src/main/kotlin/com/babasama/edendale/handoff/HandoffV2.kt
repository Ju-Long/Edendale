package com.babasama.edendale.handoff

import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.sync.SpaceName
import com.babasama.edendale.sync.SyncProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handoff version 2 (SYNC-PROTOCOL.md §9; FEATURES-27.1.md §6): the
 * cryptography, the QR code, and the messages that let any two Edendale
 * devices pair and move a sync location and its logins. Version 1's framing
 * and J-PAKE path stay ([AccountHandoff], [HandoffCrypto]); this adds QR
 * pairing (X25519 plus a 128-bit secret from the QR code, HKDF-SHA256 over
 * the transcript, HMAC proofs both ways) and the AES-256-GCM channel with
 * the §9.5 nonce and AAD layout. Only native and Bouncy Castle primitives;
 * Appendix A's vectors are `HandoffV2Test`. Pure Kotlin.
 */
object HandoffV2 {
    const val VERSION = 2
    const val LABEL = "edendale-handoff-v2"
    val LABEL_BYTES: ByteArray = LABEL.toByteArray(Charsets.US_ASCII)

    /** J-PAKE participant IDs for the typed code (§9.4). */
    const val HOST_ID = "edendale-host"
    const val GUEST_ID = "edendale-guest"

    /** A QR code or typed code is valid for this long and one attempt (§9.3, §9.4). */
    const val CODE_VALIDITY_MILLIS = 120_000L

    /** After this many wrong codes in a row the host waits [CODE_LOCKOUT_MILLIS] before showing another (§9.4). */
    const val MAX_CODE_FAILURES = 5
    const val CODE_LOCKOUT_MILLIS = 60_000L

    const val SECRET_SIZE = 16
    const val KEY_SIZE = 32

    const val QR_SCHEME = "edendale"
    const val QR_HOST = "handoff"

    // MARK: - X25519

    /** An ephemeral X25519 key pair; [destroy] zeroes the secret when the connection closes (§9.3). */
    class KeyPair internal constructor(private val secret: X25519PrivateKeyParameters) {
        val publicKey: ByteArray = secret.generatePublicKey().encoded

        /** `X25519(sk, peerPublic)`: the 32-byte shared secret. */
        fun agree(peerPublic: ByteArray): ByteArray {
            if (peerPublic.size != X25519PublicKeyParameters.KEY_SIZE) throw HandoffException.MalformedMessage
            val agreement = X25519Agreement()
            agreement.init(secret)
            val shared = ByteArray(agreement.agreementSize)
            try {
                agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), shared, 0)
            } catch (error: IllegalStateException) {
                // Bouncy Castle refuses a low-order point (an all-zero output, RFC 7748 §6.1).
                throw HandoffException.MalformedMessage
            } catch (error: IllegalArgumentException) {
                throw HandoffException.MalformedMessage
            }
            if (shared.all { it == 0.toByte() }) throw HandoffException.MalformedMessage
            return shared
        }

        fun destroy() = Unit

        override fun toString() = "KeyPair(pk=${hex(publicKey)})"
    }

    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair = KeyPair(X25519PrivateKeyParameters(random))

    /** A key pair from a known 32-byte secret, for the RFC 7748 vectors in tests. */
    fun keyPairFromSecret(secret: ByteArray): KeyPair = KeyPair(X25519PrivateKeyParameters(secret, 0))

    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SECRET_SIZE).also(random::nextBytes)

    // MARK: - Key schedule (§9.3)

    /** `label ‖ pkH ‖ pkG`, 83 bytes. */
    fun transcript(hostPublicKey: ByteArray, guestPublicKey: ByteArray): ByteArray = LABEL_BYTES + hostPublicKey + guestPublicKey

    /** `HMAC-SHA256(key = secret, msg = "guest" ‖ transcript)`: the guest's proof it read the QR code. */
    fun proof(secret: ByteArray, transcript: ByteArray): ByteArray = hmac(secret, "guest".toByteArray(Charsets.US_ASCII) + transcript)

    /** `HKDF-SHA256(ikm = shared, salt = secret, info = transcript, L = 32)`: the channel key. */
    fun key(shared: ByteArray, secret: ByteArray, transcript: ByteArray): ByteArray = hkdf(shared, secret, transcript, KEY_SIZE)

    /** `HMAC-SHA256(key = key, msg = "host" ‖ transcript)`: the host's confirmation. */
    fun confirm(key: ByteArray, transcript: ByteArray): ByteArray = hmac(key, "host".toByteArray(Charsets.US_ASCII) + transcript)

    /** The typed-code channel key (§9.4): `HKDF-SHA256(ikm = J-PAKE key, salt = empty, info = label)`. */
    fun codeKey(jpakeKeyingMaterial: ByteArray): ByteArray = hkdf(jpakeKeyingMaterial, null, LABEL_BYTES, KEY_SIZE)

    fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    /** HKDF-SHA256 (RFC 5869); a null or empty [salt] is the RFC's default. */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt?.takeIf { it.isNotEmpty() }, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // MARK: - QR code (§9.3)

    /**
     * What the host's QR code carries: its name, where to connect (optional:
     * the guest may discover it), its ephemeral public key, and the secret.
     * [toString] never shows the secret.
     */
    class QrPayload(
        val name: String,
        val host: String?,
        val port: Int?,
        val hostPublicKey: ByteArray,
        val secret: ByteArray,
    ) {
        /** `edendale://handoff?v=2&n=<name>&h=<host>&p=<port>&k=<b64u(pkH)>&s=<b64u(secret)>`. */
        fun uri(): String = buildString {
            append("$QR_SCHEME://$QR_HOST?v=$VERSION&n=").append(URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
            host?.let { append("&h=").append(URLEncoder.encode(it, "UTF-8")) }
            port?.let { append("&p=").append(it) }
            append("&k=").append(b64u(hostPublicKey))
            append("&s=").append(b64u(secret))
        }

        override fun toString() = "QrPayload($name, pk=${hex(hostPublicKey)}, secret=<redacted>)"

        companion object {
            /** Parses a scanned or pasted QR link; null for anything that isn't a version-2 handoff link. */
            fun parse(uri: String): QrPayload? {
                val trimmed = uri.trim()
                val prefix = "$QR_SCHEME://$QR_HOST"
                if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
                val query = trimmed.substringAfter('?', "").substringBefore('#')
                val parameters = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                    URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                }
                if (parameters["v"]?.toIntOrNull() != VERSION) return null
                val key = parameters["k"]?.let(::decodeB64u)?.takeIf { it.size == X25519PublicKeyParameters.KEY_SIZE } ?: return null
                val secret = parameters["s"]?.let(::decodeB64u)?.takeIf { it.size == SECRET_SIZE } ?: return null
                val port = parameters["p"]?.let { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } ?: return null }
                return QrPayload(parameters["n"]?.takeIf { it.isNotBlank() } ?: "Edendale", parameters["h"]?.takeIf { it.isNotBlank() }, port, key, secret)
            }
        }
    }

    // MARK: - Frames before the channel (§9.2, §9.3, §9.9)

    enum class ErrorReason(val raw: String) {
        VERSION("version"), SECRET("secret"), CODE("code"), ROLE("role"), BUSY("busy"), MALFORMED("malformed");

        companion object {
            fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw }
        }
    }

    fun helloFrame(guestPublicKey: ByteArray, proof: ByteArray): ByteArray = frame("hello") {
        put("pk", JsonPrimitive(b64u(guestPublicKey)))
        put("proof", JsonPrimitive(b64u(proof)))
    }

    fun confirmFrame(mac: ByteArray): ByteArray = frame("confirm") { put("mac", JsonPrimitive(b64u(mac))) }

    fun errorFrame(reason: ErrorReason): ByteArray = frame("error") { put("reason", JsonPrimitive(reason.raw)) }

    class Hello(val guestPublicKey: ByteArray, val proof: ByteArray)

    fun decodeHello(body: ByteArray): Hello {
        val fields = open(body, "hello")
        return Hello(
            fields.bytes("pk")?.takeIf { it.size == X25519PublicKeyParameters.KEY_SIZE } ?: throw HandoffException.MalformedMessage,
            fields.bytes("proof")?.takeIf { it.size == 32 } ?: throw HandoffException.MalformedMessage,
        )
    }

    fun decodeConfirm(body: ByteArray): ByteArray = open(body, "confirm").bytes("mac")?.takeIf { it.size == 32 } ?: throw HandoffException.MalformedMessage

    /** Throws the exception a plaintext `error` frame carries; any other frame passes through. */
    fun checkError(body: ByteArray): ByteArray {
        if (peek(body).second == "error") open(body, "<none>")
        return body
    }

    /** The type of a frame (`hello`, `round1`, `error`, …) and its version, for the host to tell version 1 from 2 and QR from code. */
    fun peek(body: ByteArray): Pair<Int?, String?> {
        val fields = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull() ?: return null to null
        return (fields["v"] as? JsonPrimitive)?.intOrNull to fields.string("type")
    }

    /** Throws the error a plaintext or in-channel `error` frame carries, else returns the fields for the expected [type]. */
    internal fun open(body: ByteArray, type: String, inChannel: Boolean = false): JsonObject {
        val fields = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull() ?: throw HandoffException.MalformedMessage
        if (!inChannel) {
            val version = (fields["v"] as? JsonPrimitive)?.intOrNull ?: throw HandoffException.MalformedMessage
            if (version != VERSION) throw HandoffException.UnsupportedVersion(version)
        }
        when (fields.string("type")) {
            type -> return fields
            "error" -> throw when (ErrorReason.fromRaw(fields.string("reason"))) {
                ErrorReason.SECRET -> HandoffException.WrongSecret
                ErrorReason.CODE -> HandoffException.WrongCode
                ErrorReason.ROLE -> HandoffException.SameRole
                ErrorReason.BUSY -> HandoffException.Busy
                ErrorReason.VERSION -> HandoffException.UnsupportedVersion(VERSION)
                ErrorReason.MALFORMED, null -> HandoffException.MalformedMessage
            }
            else -> throw HandoffException.MalformedMessage
        }
    }

    // MARK: - Messages inside the channel (§9.6, §9.7)

    enum class Role(val raw: String) {
        SENDER("sender"), RECEIVER("receiver");

        companion object {
            fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw }
        }
    }

    /** Each side's first message: who sends, and what to call the device. [platform] is a §3 platform name. */
    data class RoleMessage(val role: Role, val name: String, val platform: String)

    /**
     * The sync location as the offer carries it (§9.6): for a folder only the
     * provider and space; for a server the login and the credential-free root
     * URL of the folder that holds `Edendale/` (`url`: `davs://host[:port]/path/`,
     * `smb://host/share/path/`, `sftp://host[:port]/path/`, the spelling the
     * Apple branch chose, since the spec's example omits it); for a cloud
     * account the account, or across Google ecosystems the hint and folder ID.
     */
    data class LocationPayload(
        val provider: SyncProvider,
        val space: String,
        val account: AccountHandoff.Account? = null,
        val login: AccountHandoff.Login? = null,
        val path: String? = null,
        val googleAccountHint: String? = null,
        val googleFolderId: String? = null,
    ) {
        override fun toString() = "LocationPayload(${provider.raw}, $space)"
    }

    /** What the sender sends on approval (§9.6 step 3), or a decline. */
    data class Offer(
        val approved: Boolean,
        val location: LocationPayload? = null,
        val accounts: List<AccountHandoff.Account> = emptyList(),
        val logins: List<AccountHandoff.Login> = emptyList(),
    ) {
        companion object {
            val DECLINED = Offer(approved = false)
        }
    }

    /** What the receiver stored and what failed (§9.6 step 4). Items are `location`, `account:<kind>:<subject>`, or `login:<kind>:<host>`. */
    data class ResultMessage(val stored: List<String>, val failed: List<Failure>) {
        data class Failure(val item: String, val reason: String?)
    }

    fun encodeRole(message: RoleMessage): ByteArray = channelFrame("role") {
        put("role", JsonPrimitive(message.role.raw))
        put("name", JsonPrimitive(message.name))
        put("platform", JsonPrimitive(message.platform))
    }

    fun decodeRole(body: ByteArray): RoleMessage {
        val fields = open(body, "role", inChannel = true)
        return RoleMessage(
            Role.fromRaw(fields.string("role")) ?: throw HandoffException.MalformedMessage,
            fields.string("name") ?: throw HandoffException.MalformedMessage,
            fields.string("platform").orEmpty(),
        )
    }

    fun encodeOffer(offer: Offer): ByteArray = channelFrame("offer") {
        put("status", JsonPrimitive(if (offer.approved) "approved" else "declined"))
        offer.location?.let { location ->
            put(
                "location",
                buildJsonObject {
                    put("provider", JsonPrimitive(location.provider.raw))
                    put("space", JsonPrimitive(location.space))
                    location.account?.let { put("account", AccountHandoff.accountJson(it)) }
                    location.login?.let { put("login", AccountHandoff.loginJson(it)) }
                    location.path?.let { put("url", JsonPrimitive(it)) }
                    location.googleAccountHint?.let { put("googleAccountHint", JsonPrimitive(it)) }
                    location.googleFolderId?.let { put("googleFolderId", JsonPrimitive(it)) }
                },
            )
        }
        put("accounts", JsonArray(offer.accounts.map(AccountHandoff::accountJson)))
        put("logins", JsonArray(offer.logins.map(AccountHandoff::loginJson)))
    }

    fun decodeOffer(body: ByteArray): Offer {
        val fields = open(body, "offer", inChannel = true)
        val approved = when (fields.string("status")) {
            "approved" -> true
            "declined" -> false
            else -> throw HandoffException.MalformedMessage
        }
        if (!approved) return Offer.DECLINED
        val location = (fields["location"] as? JsonObject)?.let { json ->
            val provider = SyncProvider.fromRaw(json.string("provider")) ?: throw HandoffException.MalformedMessage
            val space = json.string("space")?.let(SpaceName::normalize) ?: throw HandoffException.MalformedMessage
            LocationPayload(
                provider = provider,
                space = space,
                account = (json["account"] as? JsonObject)?.let(AccountHandoff::decodeAccount),
                login = (json["login"] as? JsonObject)?.let(AccountHandoff::decodeLogin),
                path = json.string("url") ?: json.string("path"),
                googleAccountHint = json.string("googleAccountHint"),
                googleFolderId = json.string("googleFolderId"),
            )
        }
        val accounts = (fields["accounts"] as? JsonArray)?.map { AccountHandoff.decodeAccount(it as? JsonObject ?: throw HandoffException.MalformedMessage) }.orEmpty()
        val logins = (fields["logins"] as? JsonArray)?.map { AccountHandoff.decodeLogin(it as? JsonObject ?: throw HandoffException.MalformedMessage) }.orEmpty()
        return Offer(true, location, accounts, logins)
    }

    fun encodeResult(result: ResultMessage): ByteArray = channelFrame("result") {
        put("stored", JsonArray(result.stored.map(::JsonPrimitive)))
        put(
            "failed",
            JsonArray(
                result.failed.map { failure ->
                    buildJsonObject {
                        put("item", JsonPrimitive(failure.item))
                        put("reason", failure.reason?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            ),
        )
    }

    fun decodeResult(body: ByteArray): ResultMessage {
        val fields = open(body, "result", inChannel = true)
        val stored = (fields["stored"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: throw HandoffException.MalformedMessage }.orEmpty()
        val failed = (fields["failed"] as? JsonArray)?.map { element ->
            val json = element as? JsonObject ?: throw HandoffException.MalformedMessage
            ResultMessage.Failure(json.string("item") ?: throw HandoffException.MalformedMessage, json.string("reason"))
        }.orEmpty()
        return ResultMessage(stored, failed)
    }

    /** An error inside the channel (§9.9). */
    fun channelErrorFrame(reason: ErrorReason): ByteArray = channelFrame("error") { put("reason", JsonPrimitive(reason.raw)) }

    // MARK: - Google Drive across ecosystems (§9.8)

    /** The Google OAuth client a platform uses, or null for an unknown platform. */
    fun googleEcosystem(platform: String): String? = when (platform.lowercase()) {
        "ios", "ipados", "macos", "tvos", "visionos" -> "apple"
        "android", "androidtv", "horizon" -> "android"
        "windows", "xbox" -> "windows"
        else -> null
    }

    /** Whether a Google refresh token from [senderPlatform] works on [receiverPlatform]: same client, so same ecosystem. */
    fun googleLoginTransfers(senderPlatform: String, receiverPlatform: String): Boolean {
        val sender = googleEcosystem(senderPlatform) ?: return false
        return sender == googleEcosystem(receiverPlatform)
    }

    /** Whether [platform] can sign in to Google itself; TVs can't (§9.8). */
    fun canSignInToGoogle(platform: String): Boolean = platform.lowercase() !in setOf("tvos", "androidtv")

    /**
     * The offer as the sender builds it for [receiverPlatform]: Google
     * accounts travel only within an ecosystem; otherwise the location keeps
     * the account's email as a hint and the folder ID, and other Google
     * accounts are left out.
     */
    fun offerFor(
        location: LocationPayload?,
        accounts: List<AccountHandoff.Account>,
        logins: List<AccountHandoff.Login>,
        senderPlatform: String,
        receiverPlatform: String,
    ): Offer {
        val googleTransfers = googleLoginTransfers(senderPlatform, receiverPlatform)
        val adjustedLocation = location?.let { payload ->
            if (payload.provider == SyncProvider.GOOGLE_DRIVE && !googleTransfers) {
                payload.copy(account = null, googleAccountHint = payload.account?.email ?: payload.googleAccountHint)
            } else {
                payload
            }
        }
        val adjustedAccounts = accounts.filter { googleTransfers || it.kind != com.babasama.edendale.connectors.MediaSourceKind.GOOGLE_DRIVE }
        return Offer(true, adjustedLocation, adjustedAccounts, logins)
    }

    // MARK: - Plumbing

    private fun frame(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        put("type", JsonPrimitive(type))
        body()
    }.toString().toByteArray(Charsets.UTF_8)

    private fun channelFrame(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray = buildJsonObject {
        put("type", JsonPrimitive(type))
        body()
    }.toString().toByteArray(Charsets.UTF_8)

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeB64u(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bytes(name: String): ByteArray? = string(name)?.let(::decodeB64u)
}

/**
 * The version-2 channel (§9.5): AES-256-GCM under the agreed key, the 12-byte
 * nonce a 4-byte big-endian direction tag (1 guest → host, 2 host → guest)
 * followed by an 8-byte big-endian counter from 0, and the ASCII label as
 * additional authenticated data. A frame that fails to open ends the
 * connection; a replayed or reordered frame fails.
 */
class SecureChannelV2(key: ByteArray, private val isHost: Boolean) {
    private val secret = SecretKeySpec(key, "AES")
    private var sent = 0L
    private var received = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce(if (isHost) HOST_TO_GUEST else GUEST_TO_HOST, sent++)))
        cipher.updateAAD(HandoffV2.LABEL_BYTES)
        return cipher.doFinal(plaintext)
    }

    @Synchronized
    fun open(ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonce(if (isHost) GUEST_TO_HOST else HOST_TO_GUEST, received)))
        cipher.updateAAD(HandoffV2.LABEL_BYTES)
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw HandoffException.MalformedMessage
        }
        received += 1
        return plaintext
    }

    override fun toString() = "SecureChannelV2(${if (isHost) "host" else "guest"}, sent=$sent, received=$received)"

    companion object {
        const val GUEST_TO_HOST = 1
        const val HOST_TO_GUEST = 2

        /** `tag ‖ counter`, both big-endian (§9.5; Appendix A.3). */
        fun nonce(direction: Int, counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()
    }
}
