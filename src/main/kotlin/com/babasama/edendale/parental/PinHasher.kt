package com.babasama.edendale.parental

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * A stored PIN: the KDF and its parameters beside the salt and the hash, so
 * the cost can be raised later and an old record still verifies. [m] is
 * memory in KiB (0 for PBKDF2), [t] iterations, [p] parallelism (0 for
 * PBKDF2). `toString` names the KDF only: a record is never logged, but a
 * stray print must not leak either.
 */
class PinRecord(
    val kdf: String,
    val m: Int,
    val t: Int,
    val p: Int,
    val salt: ByteArray,
    val hash: ByteArray,
) {
    /** The record as the store keeps it: JSON with the salt and hash in Base64. */
    fun encode(): String = buildJsonObject {
        put("kdf", JsonPrimitive(kdf))
        put("m", JsonPrimitive(m))
        put("t", JsonPrimitive(t))
        put("p", JsonPrimitive(p))
        put("salt", JsonPrimitive(Base64.getEncoder().encodeToString(salt)))
        put("hash", JsonPrimitive(Base64.getEncoder().encodeToString(hash)))
    }.toString()

    override fun toString(): String = "PinRecord($kdf)"

    override fun equals(other: Any?): Boolean =
        other is PinRecord && kdf == other.kdf && m == other.m && t == other.t && p == other.p &&
            salt.contentEquals(other.salt) && hash.contentEquals(other.hash)

    override fun hashCode(): Int = kdf.hashCode() * 31 + hash.contentHashCode()

    companion object {
        /** A stored record, or null when [text] is not one (the PIN then counts as unset). */
        fun decode(text: String?): PinRecord? {
            if (text.isNullOrBlank()) return null
            val fields: JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val kdf = fields.string("kdf") ?: return null
            val salt = fields.string("salt")?.let { base64(it) } ?: return null
            val hash = fields.string("hash")?.let { base64(it) } ?: return null
            if (salt.isEmpty() || hash.isEmpty()) return null
            return PinRecord(
                kdf = kdf,
                m = fields.int("m") ?: 0,
                t = fields.int("t") ?: return null,
                p = fields.int("p") ?: 0,
                salt = salt,
                hash = hash,
            )
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull

        private fun base64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
    }
}

/**
 * Argon2id over the PIN (O-D11: 64 MiB, 3 iterations, parallelism 1, a
 * 16-byte salt, a 32-byte hash) through Bouncy Castle's lightweight API, which
 * the handoff already uses. PBKDF2-HMAC-SHA256 is the branch for a platform
 * without Argon2id; a record names its KDF, so either verifies. Every entry
 * point wipes the PIN array it was given, and the comparison is constant-time.
 */
class PinHasher(
    private val memoryKib: Int = DEFAULT_MEMORY_KIB,
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val pbkdf2Iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    /** A new record for [pin] under [kdf] with a fresh salt, or a fixed [salt] in tests. [pin] is wiped. */
    fun hash(pin: CharArray, kdf: String = ARGON2ID, salt: ByteArray = newSalt()): PinRecord {
        try {
            require(salt.size >= MIN_SALT_BYTES) { "salt too short" }
            return when (kdf) {
                ARGON2ID -> PinRecord(kdf, memoryKib, iterations, parallelism, salt, argon2(pin, salt, memoryKib, iterations, parallelism))
                PBKDF2 -> PinRecord(kdf, 0, pbkdf2Iterations, 0, salt, pbkdf2(pin, salt, pbkdf2Iterations))
                else -> throw IllegalArgumentException("unknown kdf")
            }
        } finally {
            wipe(pin)
        }
    }

    /** Whether [pin] matches [record], in constant time; an unknown KDF never matches. [pin] is wiped. */
    fun verify(pin: CharArray, record: PinRecord): Boolean {
        try {
            val derived = when (record.kdf) {
                ARGON2ID -> argon2(pin, record.salt, record.m, record.t, record.p)
                PBKDF2 -> pbkdf2(pin, record.salt, record.t)
                else -> return false
            }
            return MessageDigest.isEqual(derived, record.hash)
        } finally {
            wipe(pin)
        }
    }

    private fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    private fun argon2(pin: CharArray, salt: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val out = ByteArray(HASH_BYTES)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(pin, out)
        return out
    }

    private fun pbkdf2(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val bytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(pin)
        try {
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(bytes, salt, iterations)
            return (generator.generateDerivedParameters(HASH_BYTES * 8) as KeyParameter).key
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        const val ARGON2ID = "argon2id"
        const val PBKDF2 = "pbkdf2-sha256"

        const val DEFAULT_MEMORY_KIB = 64 * 1024
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_PARALLELISM = 1
        const val DEFAULT_PBKDF2_ITERATIONS = 600_000
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32

        /** A salt shorter than this is refused rather than hashed. */
        const val MIN_SALT_BYTES = 8

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8

        /** O-D2: digits only, 4–8 long. */
        fun isValidPin(pin: CharArray): Boolean =
            pin.size in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

        /** Zeroes a PIN array once it has been hashed or compared. */
        fun wipe(pin: CharArray) {
            pin.fill('\u0000')
        }
    }
}
