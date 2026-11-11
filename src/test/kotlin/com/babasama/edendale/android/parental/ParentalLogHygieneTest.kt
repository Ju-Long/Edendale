package com.babasama.edendale.android.parental

import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.parental.ParentalClock
import com.babasama.edendale.parental.PinHasher
import com.babasama.edendale.parental.PinRecord
import com.babasama.edendale.parental.RatingCeiling
import com.babasama.edendale.sync.SyncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hard rule 2 for Section O: no PIN, salt, or hash reaches the log sink. A
 * whole life of a PIN (set, wrong tries into a lockout, right try, change,
 * removal) and a policy change with a list fetch run through the recording
 * sink; the lines are then searched for every secret in plain, hex, Base64,
 * and base64url form.
 */
class ParentalLogHygieneTest {
    private val recording = SyncLog.Recording()

    @BeforeTest
    fun install() {
        SyncLog.sink = recording
    }

    @AfterTest
    fun tearDown() {
        SyncLog.sink = SyncLog.Sink { _, _ -> }
    }

    private class FixedClock(var wall: Long = 1_762_000_000_000L, var elapsed: Long = 100_000L) : ParentalClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
    }

    @Test
    fun `no PIN, salt, or hash reaches the log`() = runBlocking {
        val store = InMemoryParentalStore()
        val clock = FixedClock()
        val payload = Json.parseToJsonElement(
            """{"certifications": {"US": [{"certification": "G", "meaning": "", "order": 1}, {"certification": "PG-13", "meaning": "", "order": 3}]}}""",
        ).jsonObject
        val controls = DeviceParentalControls(
            store = store,
            preset = InMemoryAudiencePreferenceStore(),
            listsSource = { _: MediaType -> payload },
            hasher = PinHasher(memoryKib = 4 * 1024, iterations = 1),
            clock = clock,
            regionProvider = { "US" },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            hashDispatcher = Dispatchers.Unconfined,
        )
        val pin = "4711"
        val wrong = "2580"
        val changed = "6093"

        val results = mutableListOf<Any>()
        results += controls.setPin(null, pin.toCharArray(), clock.wall)
        val first = PinRecord.decode(store.getString(DeviceParentalControls.KEY_PIN))!!
        controls.lock()
        repeat(4) { results += controls.verifyPin(wrong.toCharArray(), clock.wall) }
        results += controls.verifyPin(pin.toCharArray(), clock.wall)
        clock.wall += 30_000L
        clock.elapsed += 30_000L
        results += controls.verifyPin(pin.toCharArray(), clock.wall)
        results += controls.setPin(pin.toCharArray(), changed.toCharArray(), clock.wall)
        val second = PinRecord.decode(store.getString(DeviceParentalControls.KEY_PIN))!!
        controls.updatePolicy(clock.wall) { it.copy(ceiling = RatingCeiling("US", "PG-13", null)) }
        controls.refreshLists(force = true)
        results += controls.clearPin(changed.toCharArray(), clock.wall)

        val lines = recording.lines
        assertTrue(lines.isNotEmpty(), "the sink saw log lines")
        val forbidden = listOf(pin, wrong, changed) +
            listOf(first, second).flatMap { record ->
                listOf(record.salt, record.hash).flatMap { bytes ->
                    listOf(hex(bytes), Base64.getEncoder().encodeToString(bytes), Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
                }
            }
        forbidden.forEach { secret ->
            assertFalse(lines.any { secret in it }, "a log line carries '$secret'")
        }
        // Nothing that prints a result or a record prints a secret either.
        (results.map { it.toString() } + first.toString() + second.toString()).forEach { text ->
            forbidden.forEach { secret -> assertFalse(secret in text, text) }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
