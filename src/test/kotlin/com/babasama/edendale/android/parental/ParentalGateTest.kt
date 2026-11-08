package com.babasama.edendale.android.parental

import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.parental.GatedAction
import com.babasama.edendale.parental.ParentalClock
import com.babasama.edendale.parental.ParentalGateRules
import com.babasama.edendale.parental.PinHasher
import com.babasama.edendale.parental.PinResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate over the in-memory controls: what runs at once, what waits for the
 * PIN dialog, how the unlock window ends, and the pairing-link rule (O-D13).
 * The dialog itself is Compose; its answer is [ParentalGate.finish].
 */
class ParentalGateTest {
    private class FakeClock(var wall: Long = 1_762_000_000_000L, var elapsed: Long = 100_000L) : ParentalClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
    }

    private val clock = FakeClock()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun controls(store: ParentalStore = InMemoryParentalStore(), ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) =
        DeviceParentalControls(
            store = store,
            preset = InMemoryAudiencePreferenceStore(),
            listsSource = { null },
            hasher = PinHasher(memoryKib = 4 * 1024, iterations = 1),
            clock = clock,
            scope = scope,
            ioDispatcher = ioDispatcher,
            hashDispatcher = Dispatchers.Unconfined,
        )

    private class Outcome {
        var ran = 0
        var refused = 0
        val run: () -> Unit = { ran++ }
        val refuse: () -> Unit = { refused++ }
    }

    @Test
    fun withoutAPinEveryActionRunsAtOnce() {
        val gate = ParentalGate(controls(), scope) { clock.wall }
        GatedAction.entries.forEach { action ->
            val outcome = Outcome()
            gate.require(action, onRefused = outcome.refuse, onUnlocked = outcome.run)
            assertEquals(1, outcome.ran, "$action")
            assertEquals(0, outcome.refused)
            assertNull(gate.pending)
            assertFalse(gate.asks(action))
        }
    }

    @Test
    fun aLockedSettingsSwitchHoldsTheRequestUntilTheDialogAnswers(): Unit = runBlocking {
        val controls = controls()
        val gate = ParentalGate(controls, scope) { clock.wall }
        assertIs<PinResult.Unlocked>(controls.setPin(null, "1234".toCharArray(), clock.wall))
        assertTrue(controls.updatePolicy(clock.wall) { it.copy(lockSettings = true) })
        controls.lock()

        val settings = Outcome()
        assertTrue(gate.asks(GatedAction.SETTINGS))
        gate.require(GatedAction.SETTINGS, onRefused = settings.refuse, onUnlocked = settings.run)
        assertEquals(0, settings.ran)
        assertEquals(GatedAction.SETTINGS, assertNotNull(gate.pending).action)

        // A second question while one waits is refused rather than queued.
        val second = Outcome()
        gate.require(GatedAction.SYNC, onRefused = second.refuse, onUnlocked = second.run)
        assertEquals(1, second.refused)
        assertEquals(0, second.ran)

        // Cancelled: nothing runs.
        gate.finish(unlocked = false)
        assertNull(gate.pending)
        assertEquals(1, settings.refused)
        assertEquals(0, settings.ran)

        // Asked again, the PIN typed right, the dialog reports it: the action runs.
        gate.require(GatedAction.SETTINGS, onRefused = settings.refuse, onUnlocked = settings.run)
        assertNotNull(gate.pending)
        assertIs<PinResult.Unlocked>(controls.verifyPin("1234".toCharArray(), clock.wall))
        gate.finish(unlocked = true)
        assertEquals(1, settings.ran)

        // Library edits were never switched on, and Settings is inside the window now.
        val edit = Outcome()
        gate.require(GatedAction.LIBRARY_EDIT, onUnlocked = edit.run)
        assertEquals(1, edit.ran)
        gate.require(GatedAction.SETTINGS, onUnlocked = settings.run)
        assertEquals(2, settings.ran)
        assertNull(gate.pending)
    }

    @Test
    fun theUnlockWindowEndsAfterFiveMinutesOrWhenTheSheetCloses(): Unit = runBlocking {
        val controls = controls()
        val gate = ParentalGate(controls, scope) { clock.wall }
        controls.setPin(null, "1234".toCharArray(), clock.wall)
        controls.updatePolicy(clock.wall) { it.copy(lockSettings = true, lockLibraryEdits = true) }
        controls.lock()
        assertIs<PinResult.Unlocked>(controls.verifyPin("1234".toCharArray(), clock.wall))

        val edit = Outcome()
        clock.wall += DeviceParentalControls.UNLOCK_WINDOW_MILLIS - 1
        gate.require(GatedAction.LIBRARY_EDIT, onUnlocked = edit.run)
        assertEquals(1, edit.ran, "still inside the window")

        clock.wall += 1
        gate.require(GatedAction.LIBRARY_EDIT, onUnlocked = edit.run)
        assertEquals(1, edit.ran, "the window has closed")
        assertNotNull(gate.pending)
        gate.finish(unlocked = false)

        // The sheet closing locks at once, however much of the window is left.
        assertIs<PinResult.Unlocked>(controls.verifyPin("1234".toCharArray(), clock.wall))
        gate.require(GatedAction.SETTINGS, onUnlocked = edit.run)
        assertEquals(2, edit.ran)
        controls.lock()
        gate.require(GatedAction.SETTINGS, onUnlocked = edit.run)
        assertEquals(2, edit.ran)
        assertNotNull(gate.pending)
    }

    @Test
    fun aPairingLinkAsksWhateverTheSwitchesSayAndIsDroppedWhenRefused(): Unit = runBlocking {
        val controls = controls()
        val gate = ParentalGate(controls, scope) { clock.wall }
        controls.setPin(null, "1234".toCharArray(), clock.wall)
        controls.lock()
        assertFalse(controls.policy.value.lockSettings)
        assertFalse(controls.policy.value.lockLibraryEdits)

        var link: String? = "edendale://handoff?v=2&n=TV"
        var opened: String? = null
        gate.require(GatedAction.PAIRING_LINK, onRefused = { link = null }) { opened = link }
        assertEquals(GatedAction.PAIRING_LINK, assertNotNull(gate.pending).action)
        // The dialog drops the link on the first wrong PIN (O-D13) rather than asking again.
        assertTrue(ParentalGateRules.dropsOnWrongPin(GatedAction.PAIRING_LINK))
        assertIs<PinResult.Wrong>(controls.verifyPin("0000".toCharArray(), clock.wall))
        gate.finish(unlocked = false)
        assertNull(link)
        assertNull(opened)

        // Sync and the parental section ask too, with both switches off.
        assertTrue(gate.asks(GatedAction.SYNC))
        assertTrue(gate.asks(GatedAction.PARENTAL_CHANGE))
        assertFalse(gate.asks(GatedAction.SETTINGS))
        assertFalse(gate.asks(GatedAction.LIBRARY_EDIT))

        // With the right PIN the link opens.
        link = "edendale://handoff?v=2&n=TV"
        gate.require(GatedAction.PAIRING_LINK, onRefused = { link = null }) { opened = link }
        assertIs<PinResult.Unlocked>(controls.verifyPin("1234".toCharArray(), clock.wall))
        gate.finish(unlocked = true)
        assertEquals("edendale://handoff?v=2&n=TV", opened)
    }

    @Test
    fun aRequestBeforeTheStoreIsReadWaitsForIt() {
        val latch = CountDownLatch(1)
        val controls = controls(store = GatedStore(latch), ioDispatcher = Dispatchers.IO)
        assertFalse(controls.ready.value)
        val gate = ParentalGate(controls, scope) { clock.wall }
        val outcome = Outcome()
        gate.require(GatedAction.SETTINGS, onUnlocked = outcome.run)
        assertEquals(0, outcome.ran, "nothing is decided before the store is read")
        assertFalse(gate.asks(GatedAction.SETTINGS))
        latch.countDown()
        runBlocking { controls.awaitReady() }
        // The deciding coroutine resumes on the reading thread; give it a moment.
        val deadline = System.currentTimeMillis() + 5_000L
        while (outcome.ran == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertEquals(1, outcome.ran, "no PIN: the action runs once the store is read")
    }

    /** A store whose first read waits on [latch], so the controls stay unready for a while. */
    private class GatedStore(private val latch: CountDownLatch) : ParentalStore {
        private val delegate = InMemoryParentalStore()

        override fun getString(key: String): String? {
            latch.await(10, TimeUnit.SECONDS)
            return delegate.getString(key)
        }

        override fun putString(key: String, value: String) = delegate.putString(key, value)
        override fun getBoolean(key: String, default: Boolean) = delegate.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
        override fun getLong(key: String, default: Long) = delegate.getLong(key, default)
        override fun putLong(key: String, value: Long) = delegate.putLong(key, value)
        override fun contains(key: String) = delegate.contains(key)
        override fun remove(key: String) = delegate.remove(key)
        override fun addListener(listener: (String) -> Unit) = delegate.addListener(listener)
    }

    @Suppress("unused")
    private val mediaTypeReference = MediaType.MOVIE
}
