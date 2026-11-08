package com.babasama.edendale.parental

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinHasherTest {
    // Small Argon2 parameters keep the suite fast; the record carries them, so verification reads them back.
    private val hasher = PinHasher(memoryKib = 4 * 1024, iterations = 1, parallelism = 1, pbkdf2Iterations = 1_000)
    private val salt = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun aFixedSaltIsDeterministicAndAnotherSaltIsNot() {
        val first = hasher.hash("1234".toCharArray(), salt = salt)
        val second = hasher.hash("1234".toCharArray(), salt = salt)
        assertContentEquals(first.hash, second.hash)
        assertEquals(32, first.hash.size)
        val other = hasher.hash("1234".toCharArray(), salt = ByteArray(16) { 9 })
        assertFalse(first.hash.contentEquals(other.hash))
        // Fresh salts differ between records.
        val a = hasher.hash("1234".toCharArray())
        val b = hasher.hash("1234".toCharArray())
        assertEquals(16, a.salt.size)
        assertFalse(a.salt.contentEquals(b.salt))
    }

    @Test
    fun aWrongPinFails() {
        val record = hasher.hash("1234".toCharArray(), salt = salt)
        assertTrue(hasher.verify("1234".toCharArray(), record))
        assertFalse(hasher.verify("1235".toCharArray(), record))
        assertFalse(hasher.verify("12345".toCharArray(), record))
        assertFalse(hasher.verify(CharArray(0), record))
    }

    @Test
    fun aRecordRoundTripsAndNeverPrintsItsSecrets() {
        val record = hasher.hash("4711".toCharArray(), salt = salt)
        val decoded = PinRecord.decode(record.encode())!!
        assertEquals(record, decoded)
        assertEquals(PinHasher.ARGON2ID, decoded.kdf)
        assertEquals(4 * 1024, decoded.m)
        assertEquals(1, decoded.t)
        assertEquals(1, decoded.p)
        assertTrue(hasher.verify("4711".toCharArray(), decoded))

        assertEquals("PinRecord(argon2id)", record.toString())
        assertNull(PinRecord.decode(null))
        assertNull(PinRecord.decode(""))
        assertNull(PinRecord.decode("not json"))
        assertNull(PinRecord.decode("""{"kdf":"argon2id","t":1}"""))
    }

    @Test
    fun theArrayIsWiped() {
        val toHash = "8080".toCharArray()
        val record = hasher.hash(toHash, salt = salt)
        assertTrue(toHash.all { it == '\u0000' })

        val right = "8080".toCharArray()
        assertTrue(hasher.verify(right, record))
        assertTrue(right.all { it == '\u0000' })

        val wrong = "0000".toCharArray()
        assertFalse(hasher.verify(wrong, record))
        assertTrue(wrong.all { it == '\u0000' })

        // Even a refused hash wipes what it was given.
        val short = "1234".toCharArray()
        assertFailsWith<IllegalArgumentException> { hasher.hash(short, salt = ByteArray(2)) }
        assertTrue(short.all { it == '\u0000' })
    }

    @Test
    fun thePbkdf2BranchHashesVerifiesAndMatchesTheKnownVector() {
        val record = hasher.hash("1234".toCharArray(), kdf = PinHasher.PBKDF2, salt = salt)
        assertEquals(PinHasher.PBKDF2, record.kdf)
        assertEquals(0, record.m)
        assertEquals(1_000, record.t)
        assertEquals(0, record.p)
        assertTrue(hasher.verify("1234".toCharArray(), PinRecord.decode(record.encode())!!))
        assertFalse(hasher.verify("1243".toCharArray(), record))

        // PBKDF2-HMAC-SHA256("password", "salt", 1 iteration, 32 bytes), the usual vector.
        val vector = PinRecord(
            kdf = PinHasher.PBKDF2,
            m = 0,
            t = 1,
            p = 0,
            salt = "salt".toByteArray(),
            hash = hex("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"),
        )
        assertTrue(hasher.verify("password".toCharArray(), vector))
        assertFalse(hasher.verify("passwore".toCharArray(), vector))

        // An unknown KDF never verifies, and is never produced.
        assertFalse(hasher.verify("1234".toCharArray(), PinRecord("md5", 0, 1, 0, salt, ByteArray(32))))
        assertFailsWith<IllegalArgumentException> { hasher.hash("1234".toCharArray(), kdf = "md5", salt = salt) }
    }

    @Test
    fun pinsAreFourToEightDigits() {
        assertTrue(PinHasher.isValidPin("1234".toCharArray()))
        assertTrue(PinHasher.isValidPin("12345678".toCharArray()))
        assertFalse(PinHasher.isValidPin("123".toCharArray()))
        assertFalse(PinHasher.isValidPin("123456789".toCharArray()))
        assertFalse(PinHasher.isValidPin("12a4".toCharArray()))
        assertFalse(PinHasher.isValidPin("١٢٣٤".toCharArray()))
    }

    @Test
    fun defaultsAreTheDecidedArgon2Parameters() {
        val record = PinHasher().hash("2468".toCharArray())
        assertEquals(PinHasher.ARGON2ID, record.kdf)
        assertEquals(64 * 1024, record.m)
        assertEquals(3, record.t)
        assertEquals(1, record.p)
        assertEquals(16, record.salt.size)
        assertEquals(32, record.hash.size)
        assertEquals(600_000, PinHasher.DEFAULT_PBKDF2_ITERATIONS)
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
