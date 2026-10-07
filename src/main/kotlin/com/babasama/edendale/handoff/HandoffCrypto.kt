package com.babasama.edendale.handoff

import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKECurve
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKECurves
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKEParticipant
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKERound1Payload
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKERound2Payload
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKERound3Payload
import org.bouncycastle.crypto.agreement.ecjpake.ECSchnorrZKP
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The handoff's cryptography (I.2.1): J-PAKE over P-256 keyed by the code
 * the TV shows (Bouncy Castle's `ecjpake`, already in the app for sshj), so a
 * passive listener learns nothing and an active attacker gets one guess per
 * handshake; then HKDF-SHA256 to an AES-256-GCM key, with each message's
 * nonce its direction and counter. Pure Kotlin.
 */
object HandoffCrypto {
    /** J-PAKE participant IDs; the two sides must differ. */
    const val PHONE = "edendale-phone"
    const val TV = "edendale-tv"

    const val CODE_LENGTH = 6
    private const val KEY_INFO = "edendale-handoff-v1"

    /** Six digits from a secure source, zero-padded: one in a million per guess. */
    fun newCode(random: SecureRandom = SecureRandom()): String = "%06d".format(random.nextInt(1_000_000))

    /** The payload of one J-PAKE round as the other side sends it, or the plaintext error the TV sends for a wrong code. */
    internal fun type(body: ByteArray): String? =
        runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()?.string("type")

    /** The plaintext frame the TV answers a wrong code's key confirmation with. */
    fun wrongCodeFrame(): ByteArray = buildJsonObject {
        put("v", JsonPrimitive(AccountHandoff.VERSION))
        put("type", JsonPrimitive("error"))
        put("reason", JsonPrimitive("code"))
    }.toString().toByteArray()

    /**
     * One side of the key agreement. Rounds go out as JSON frames; the third
     * confirms the key, so a wrong code fails there with [HandoffException.WrongCode]
     * before any account travels.
     */
    class Pake(participantId: String, code: String, private val curve: ECJPAKECurve = ECJPAKECurves.NIST_P256) {
        private val participant = ECJPAKEParticipant(participantId, code.toCharArray(), curve)
        private var keyingMaterial: BigInteger? = null

        // Bouncy Castle's participant creates each round's own payload before it
        // validates the other side's, whichever order the frames arrive in, so
        // each payload is made once, on first use, and kept.
        private var round1Bytes: ByteArray? = null
        private var round2Bytes: ByteArray? = null
        private var round3Bytes: ByteArray? = null

        fun round1(): ByteArray = round1Bytes ?: run {
            val payload = participant.createRound1PayloadToSend()
            message("round1") {
                put("id", JsonPrimitive(payload.participantId))
                put("gx1", point(payload.gx1))
                put("gx2", point(payload.gx2))
                put("zkp1", zkp(payload.knowledgeProofForX1))
                put("zkp2", zkp(payload.knowledgeProofForX2))
            }.also { round1Bytes = it }
        }

        fun receiveRound1(body: ByteArray) {
            round1()
            val fields = roundFields(body, "round1")
            val payload = try {
                ECJPAKERound1Payload(
                    fields.string("id") ?: throw HandoffException.MalformedMessage,
                    point(fields, "gx1"),
                    point(fields, "gx2"),
                    zkp(fields, "zkp1"),
                    zkp(fields, "zkp2"),
                )
            } catch (error: IllegalArgumentException) {
                throw HandoffException.MalformedMessage
            }
            validate { participant.validateRound1PayloadReceived(payload) }
        }

        fun round2(): ByteArray = round2Bytes ?: run {
            val payload = participant.createRound2PayloadToSend()
            message("round2") {
                put("id", JsonPrimitive(payload.participantId))
                put("a", point(payload.a))
                put("zkp", zkp(payload.knowledgeProofForX2s))
            }.also { round2Bytes = it }
        }

        fun receiveRound2(body: ByteArray) {
            round2()
            val fields = roundFields(body, "round2")
            val payload = try {
                ECJPAKERound2Payload(fields.string("id") ?: throw HandoffException.MalformedMessage, point(fields, "a"), zkp(fields, "zkp"))
            } catch (error: IllegalArgumentException) {
                throw HandoffException.MalformedMessage
            }
            validate { participant.validateRound2PayloadReceived(payload) }
        }

        /** Derives the key, then the confirmation tag for the other side to check. */
        fun round3(): ByteArray = round3Bytes ?: run {
            val material = keyingMaterial ?: participant.calculateKeyingMaterial().also { keyingMaterial = it }
            val payload = participant.createRound3PayloadToSend(material)
            message("round3") {
                put("id", JsonPrimitive(payload.participantId))
                put("mac", JsonPrimitive(base64(payload.macTag.toByteArray())))
            }.also { round3Bytes = it }
        }

        /** Checks the other side's confirmation: a wrong code fails here, and only here. */
        fun receiveRound3(body: ByteArray) {
            if (type(body) == "error") throw HandoffException.WrongCode
            round3()
            val fields = roundFields(body, "round3")
            val material = keyingMaterial ?: error("No keying material")
            val payload = ECJPAKERound3Payload(
                fields.string("id") ?: throw HandoffException.MalformedMessage,
                BigInteger(decode(fields.string("mac") ?: throw HandoffException.MalformedMessage)),
            )
            try {
                participant.validateRound3PayloadReceived(payload, material)
            } catch (error: CryptoException) {
                throw HandoffException.WrongCode
            } catch (error: IllegalStateException) {
                throw HandoffException.MalformedMessage
            }
        }

        /** The 32-byte AES key, once both confirmations passed. */
        fun sessionKey(): ByteArray {
            check(participant.state == ECJPAKEParticipant.STATE_ROUND_3_VALIDATED) { "Key confirmation hasn't completed" }
            val material = keyingMaterial ?: error("No keying material")
            return hkdf(material.toByteArray(), KEY_INFO.toByteArray(), 32)
        }

        private inline fun validate(block: () -> Unit) {
            try {
                block()
            } catch (error: CryptoException) {
                throw HandoffException.MalformedMessage
            } catch (error: IllegalStateException) {
                throw HandoffException.MalformedMessage
            }
        }

        private fun roundFields(body: ByteArray, type: String): JsonObject {
            val fields = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()
                ?: throw HandoffException.MalformedMessage
            val version = (fields["v"] as? JsonPrimitive)?.intOrNull ?: throw HandoffException.MalformedMessage
            if (version != AccountHandoff.VERSION) throw HandoffException.UnsupportedVersion(version)
            if (fields.string("type") != type) throw HandoffException.MalformedMessage
            return fields
        }

        private fun message(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray =
            buildJsonObject {
                put("v", JsonPrimitive(AccountHandoff.VERSION))
                put("type", JsonPrimitive(type))
                body()
            }.toString().toByteArray()

        private fun point(point: ECPoint) = JsonPrimitive(base64(point.getEncoded(true)))

        private fun point(fields: JsonObject, name: String): ECPoint =
            runCatching { curve.curve.decodePoint(decode(fields.string(name) ?: throw HandoffException.MalformedMessage)) }
                .getOrElse { throw HandoffException.MalformedMessage }

        private fun zkp(proof: ECSchnorrZKP) = buildJsonObject {
            put("v", point(proof.v))
            put("r", JsonPrimitive(base64(proof.getr().toByteArray())))
        }

        private fun zkp(fields: JsonObject, name: String): ECSchnorrZKP {
            val json = fields[name] as? JsonObject ?: throw HandoffException.MalformedMessage
            return newZkp(point(json, "v"), BigInteger(decode(json.string("r") ?: throw HandoffException.MalformedMessage)))
        }
    }

    /** `ECSchnorrZKP`'s constructor is package-private, so the proof is rebuilt through reflection on the two fields it holds. */
    private fun newZkp(v: ECPoint, r: BigInteger): ECSchnorrZKP {
        val constructor = ECSchnorrZKP::class.java.getDeclaredConstructor(ECPoint::class.java, BigInteger::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(v, r)
    }

    /** HKDF-SHA256 (RFC 5869) with an empty salt. */
    fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, null, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(text) }.getOrElse { throw HandoffException.MalformedMessage }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}

/**
 * AES-256-GCM over the agreed key: each side seals with its own direction
 * byte and a counter, so a replayed or reordered message fails to open.
 */
class SecureChannel(key: ByteArray, private val sendDirection: Byte, private val receiveDirection: Byte) {
    private val secret = SecretKeySpec(key, "AES")
    private var sent = 0L
    private var received = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce(sendDirection, sent++)))
        return cipher.doFinal(plaintext)
    }

    @Synchronized
    fun open(ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonce(receiveDirection, received)))
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw HandoffException.MalformedMessage
        }
        received += 1
        return plaintext
    }

    private fun nonce(direction: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(direction).put(ByteArray(3)).putLong(counter).array()

    companion object {
        const val PHONE_TO_TV: Byte = 1
        const val TV_TO_PHONE: Byte = 2

        fun forPhone(key: ByteArray) = SecureChannel(key, PHONE_TO_TV, TV_TO_PHONE)
        fun forTelevision(key: ByteArray) = SecureChannel(key, TV_TO_PHONE, PHONE_TO_TV)
    }
}
