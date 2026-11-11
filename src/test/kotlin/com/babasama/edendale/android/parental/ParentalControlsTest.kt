package com.babasama.edendale.android.parental

import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.parental.ParentalClock
import com.babasama.edendale.parental.ParentalPolicy
import com.babasama.edendale.parental.PinHasher
import com.babasama.edendale.parental.PinResult
import com.babasama.edendale.parental.RatingCeiling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The controls over the in-memory stores with a fake clock and a fake lists
 * source. Unconfined dispatchers make every step synchronous, so a second
 * object over the same store is a relaunch.
 */
class ParentalControlsTest {
    private class FakeClock(var wall: Long = 1_762_000_000_000L, var elapsed: Long = 100_000L) : ParentalClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        fun advance(millis: Long) {
            wall += millis
            elapsed += millis
        }
    }

    private val hasher = PinHasher(memoryKib = 4 * 1024, iterations = 1, pbkdf2Iterations = 1_000)
    private var listFetches = 0
    private var listsAvailable = true
    private var region = "US"
    private val listsSource: suspend (MediaType) -> JsonObject? = { type ->
        listFetches++
        if (!listsAvailable) null else if (type == MediaType.MOVIE) moviePayload else tvPayload
    }

    private fun controls(
        store: ParentalStore = InMemoryParentalStore(),
        preset: InMemoryAudiencePreferenceStore = InMemoryAudiencePreferenceStore(),
        clock: FakeClock = FakeClock(),
    ) = DeviceParentalControls(
        store = store,
        preset = preset,
        listsSource = listsSource,
        hasher = hasher,
        clock = clock,
        regionProvider = { region },
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
        hashDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun withoutAPinEverythingIsOpenAndThePolicyPersists() = runBlocking {
        val store = InMemoryParentalStore()
        val preset = InMemoryAudiencePreferenceStore()
        val clock = FakeClock()
        val controls = controls(store, preset, clock)
        assertTrue(controls.ready.value)
        assertFalse(controls.hasPin.value)
        assertTrue(controls.isUnlocked(clock.wall))
        assertEquals(PinResult.NoPin, controls.verifyPin("1234".toCharArray(), clock.wall))
        assertEquals(PinResult.NoPin, controls.clearPin("1234".toCharArray(), clock.wall))

        val ceiling = RatingCeiling("US", movie = "PG-13", tv = "TV-14")
        assertTrue(controls.updatePolicy(clock.wall) { it.copy(ceiling = ceiling, lockSettings = true) })
        assertEquals(ceiling, controls.policy.value.ceiling)
        assertTrue(controls.policy.value.lockSettings)
        assertTrue(controls.updatePolicy(clock.wall) { it.copy(youngAudienceFriendly = true) })
        // The preset lives in its own store (O-D7) and the policy follows it.
        assertTrue(preset.enabled)
        assertTrue(controls.policy.value.youngAudienceFriendly)
        preset.enabled = false
        assertFalse(controls.policy.value.youngAudienceFriendly)

        val relaunched = controls(store, preset, clock)
        assertEquals(ceiling, relaunched.policy.value.ceiling)
        assertTrue(relaunched.policy.value.lockSettings)
        assertFalse(relaunched.policy.value.youngAudienceFriendly)
    }

    @Test
    fun aLockedProfileCannotChangeTheCeilingOrThePin() = runBlocking {
        val clock = FakeClock()
        val controls = controls(clock = clock)
        assertEquals(PinResult.Unlocked(clock.wall + DeviceParentalControls.UNLOCK_WINDOW_MILLIS), controls.setPin(null, "1234".toCharArray(), clock.wall))
        assertTrue(controls.hasPin.value)
        assertTrue(controls.isUnlocked(clock.wall))

        controls.lock()
        assertFalse(controls.isUnlocked(clock.wall))
        val before = controls.policy.value
        assertFalse(controls.updatePolicy(clock.wall) { it.copy(ceiling = RatingCeiling("US", "R", null)) })
        assertEquals(before, controls.policy.value)
        assertEquals(PinResult.PinRequired, controls.setPin(null, "9999".toCharArray(), clock.wall))
        assertEquals(PinResult.Wrong(1, 0L), controls.setPin("0000".toCharArray(), "9999".toCharArray(), clock.wall))
        assertEquals(PinResult.Wrong(2, 0L), controls.clearPin("0000".toCharArray(), clock.wall))
        assertTrue(controls.hasPin.value)
        assertFalse(controls.isUnlocked(clock.wall))
        // The wrong attempts did not unlock, and the right PIN still works.
        assertEquals(PinResult.Wrong(3, 0L), controls.verifyPin("9999".toCharArray(), clock.wall))
        assertEquals(PinResult.Unlocked(clock.wall + DeviceParentalControls.UNLOCK_WINDOW_MILLIS), controls.verifyPin("1234".toCharArray(), clock.wall))
        assertTrue(controls.updatePolicy(clock.wall) { it.copy(ceiling = RatingCeiling("US", "R", null)) })
        assertEquals("R", controls.policy.value.ceiling?.movie)

        // Changing the PIN needs the current one even while unlocked; then only the new one matches.
        assertEquals(PinResult.PinRequired, controls.setPin(null, "5678".toCharArray(), clock.wall))
        assertIs<PinResult.Unlocked>(controls.setPin("1234".toCharArray(), "5678".toCharArray(), clock.wall))
        assertIs<PinResult.Wrong>(controls.verifyPin("1234".toCharArray(), clock.wall))
        assertIs<PinResult.Unlocked>(controls.verifyPin("5678".toCharArray(), clock.wall))
        assertIs<PinResult.Unlocked>(controls.clearPin("5678".toCharArray(), clock.wall))
        assertFalse(controls.hasPin.value)
        assertTrue(controls.isUnlocked(clock.wall))
    }

    @Test
    fun theUnlockWindowLastsFiveMinutesOrUntilLock() = runBlocking {
        val clock = FakeClock()
        val controls = controls(clock = clock)
        controls.setPin(null, "1234".toCharArray(), clock.wall)
        controls.lock()
        val result = controls.verifyPin("1234".toCharArray(), clock.wall)
        assertEquals(PinResult.Unlocked(clock.wall + 5 * 60_000L), result)
        assertTrue(controls.isUnlocked(clock.wall + 5 * 60_000L - 1))
        assertFalse(controls.isUnlocked(clock.wall + 5 * 60_000L))
        assertFalse(controls.updatePolicy(clock.wall + 5 * 60_000L) { it.copy(lockLibraryEdits = true) })
        assertTrue(controls.updatePolicy(clock.wall + 60_000L) { it.copy(lockLibraryEdits = true) })

        controls.lock()
        assertFalse(controls.isUnlocked(clock.wall + 60_000L))
    }

    @Test
    fun wrongPinsBackOffAndTheBackOffSurvivesARelaunchAndAReboot() = runBlocking {
        val store = InMemoryParentalStore()
        val clock = FakeClock()
        val controls = controls(store, clock = clock)
        controls.setPin(null, "1234".toCharArray(), clock.wall)
        controls.lock()

        repeat(3) { attempt ->
            assertEquals(PinResult.Wrong(attempt + 1, 0L), controls.verifyPin("0000".toCharArray(), clock.wall))
            assertNull(controls.lockedUntilMillis.value)
        }
        assertEquals(PinResult.Wrong(4, 30_000L), controls.verifyPin("0000".toCharArray(), clock.wall))
        assertEquals(clock.wall + 30_000L, controls.lockedUntilMillis.value)
        // While locked out nothing is checked, not even the right PIN.
        clock.advance(10_000L)
        assertEquals(PinResult.LockedOut(20_000L), controls.verifyPin("1234".toCharArray(), clock.wall))
        assertFalse(controls.isUnlocked(clock.wall))

        // A relaunch after a reboot: the uptime clock restarted, the wall clock still counts.
        val rebooted = FakeClock(wall = clock.wall, elapsed = 2_000L)
        val relaunched = controls(store, clock = rebooted)
        assertEquals(rebooted.wall + 20_000L, relaunched.lockedUntilMillis.value)
        assertEquals(PinResult.LockedOut(20_000L), relaunched.verifyPin("1234".toCharArray(), rebooted.wall))
        // The wall clock set forward by a day does not help while the uptime clock remembers.
        val tricked = FakeClock(wall = clock.wall + 86_400_000L, elapsed = clock.elapsed)
        val stillLocked = controls(store, clock = tricked)
        assertEquals(PinResult.LockedOut(20_000L), stillLocked.verifyPin("1234".toCharArray(), tricked.wall))

        // Once the wait is over the right PIN resets the count; the schedule starts again from free attempts.
        rebooted.advance(20_000L)
        assertIs<PinResult.Unlocked>(relaunched.verifyPin("1234".toCharArray(), rebooted.wall))
        assertNull(relaunched.lockedUntilMillis.value)
        relaunched.lock()
        repeat(3) { relaunched.verifyPin("0000".toCharArray(), rebooted.wall) }
        assertEquals(PinResult.Wrong(4, 30_000L), relaunched.verifyPin("0000".toCharArray(), rebooted.wall))
        // The fifth failure waits a minute.
        rebooted.advance(30_000L)
        assertEquals(PinResult.Wrong(5, 60_000L), relaunched.verifyPin("0000".toCharArray(), rebooted.wall))
    }

    @Test
    fun aPinIsFourToEightDigitsAndNeverStoredAsItself() = runBlocking {
        val store = InMemoryParentalStore()
        val controls = controls(store)
        assertEquals(PinResult.InvalidPin, controls.setPin(null, "123".toCharArray(), 0L))
        assertEquals(PinResult.InvalidPin, controls.setPin(null, "123456789".toCharArray(), 0L))
        assertEquals(PinResult.InvalidPin, controls.setPin(null, "12a4".toCharArray(), 0L))
        assertFalse(controls.hasPin.value)
        assertFalse(store.contains(DeviceParentalControls.KEY_PIN))

        val pin = "1234".toCharArray()
        assertIs<PinResult.Unlocked>(controls.setPin(null, pin, 0L))
        assertTrue(pin.all { it == '\u0000' }, "the array is wiped")
        val stored = assertNotNull(store.getString(DeviceParentalControls.KEY_PIN))
        assertTrue("\"kdf\":\"argon2id\"" in stored)
        assertFalse(":\"1234\"" in stored)
    }

    @Test
    fun listsAreFetchedOnceCachedAndRefreshedAfterADayOrARegionChange() = runBlocking {
        val store = InMemoryParentalStore()
        val clock = FakeClock()
        val controls = controls(store, clock = clock)
        assertNull(controls.lists.value)
        assertEquals(0, listFetches)

        controls.refreshLists()
        assertEquals(2, listFetches)
        assertEquals("US", controls.lists.value?.region)
        assertEquals(6, controls.lists.value?.movie?.size)
        assertEquals(ListsStatus.IDLE, controls.listsStatus.value)
        controls.refreshLists()
        assertEquals(2, listFetches, "fresh lists are not fetched again")

        // A relaunch reads them back without a request.
        val relaunched = controls(store, clock = clock)
        assertEquals("US", relaunched.lists.value?.region)
        relaunched.refreshLists()
        assertEquals(2, listFetches)

        clock.advance(25 * 60 * 60_000L)
        relaunched.refreshLists()
        assertEquals(4, listFetches, "a day-old cache is refreshed")

        region = "GB"
        relaunched.refreshLists()
        assertEquals(6, listFetches, "another device region is fetched")
        assertEquals("GB", relaunched.lists.value?.region)

        relaunched.refreshLists(force = true)
        assertEquals(8, listFetches)
    }

    @Test
    fun aFailedFetchKeepsTheOldListsAndWaitsBeforeRetrying() = runBlocking {
        val clock = FakeClock()
        val controls = controls(clock = clock)
        listsAvailable = false
        controls.refreshLists()
        assertEquals(ListsStatus.FAILED, controls.listsStatus.value)
        assertNull(controls.lists.value)
        val attempts = listFetches

        listsAvailable = true
        controls.refreshLists()
        assertEquals(attempts, listFetches, "no retry within half a minute")
        clock.advance(DeviceParentalControls.RETRY_AFTER_FAILURE_MILLIS)
        controls.refreshLists()
        assertEquals(ListsStatus.IDLE, controls.listsStatus.value)
        assertNotNull(controls.lists.value)

        // A later failure leaves the lists in place.
        listsAvailable = false
        controls.refreshLists(force = true)
        assertEquals(ListsStatus.FAILED, controls.listsStatus.value)
        assertTrue(controls.lists.value != null, "the old lists stay")
    }

    @Test
    fun aCeilingFetchesTheListsAndARegionWithoutListsFallsBackToUs() = runBlocking {
        val clock = FakeClock()
        region = "FR"
        val controls = controls(clock = clock)
        assertTrue(controls.updatePolicy(clock.wall) { it.copy(ceiling = RatingCeiling("US", movie = "PG-13", tv = null)) })
        assertEquals(2, listFetches)
        assertEquals("US", controls.lists.value?.region)

        // A relaunch with a ceiling and stale lists fetches them at start.
        clock.advance(2 * 24 * 60 * 60_000L)
        val store = InMemoryParentalStore()
        val restarted = controls(store, clock = clock)
        restarted.updatePolicy(clock.wall) { it.copy(ceiling = RatingCeiling("US", movie = "R", tv = null)) }
        assertEquals(4, listFetches)
        clock.advance(2 * 24 * 60 * 60_000L)
        controls(store, clock = clock)
        assertEquals(6, listFetches)
    }

    @Test
    fun policyChangesAreOnlyPersistedWhenSomethingChanged() = runBlocking {
        val store = InMemoryParentalStore()
        val controls = controls(store)
        assertTrue(controls.updatePolicy(0L) { it })
        assertFalse(store.contains(DeviceParentalControls.KEY_POLICY))
        assertTrue(controls.updatePolicy(0L) { it.copy(blockTrailersAndLinks = true) })
        assertTrue(store.contains(DeviceParentalControls.KEY_POLICY))
        assertEquals(ParentalPolicy(blockTrailersAndLinks = true), controls.policy.value)
    }

    private val moviePayload by lazy {
        json(
            """
            {"certifications": {
              "US": [
                {"certification": "NR", "meaning": "", "order": 0},
                {"certification": "G", "meaning": "", "order": 1},
                {"certification": "PG", "meaning": "", "order": 2},
                {"certification": "PG-13", "meaning": "", "order": 3},
                {"certification": "R", "meaning": "", "order": 4},
                {"certification": "NC-17", "meaning": "", "order": 5}
              ],
              "GB": [
                {"certification": "U", "meaning": "", "order": 1},
                {"certification": "PG", "meaning": "", "order": 2},
                {"certification": "12A", "meaning": "", "order": 3},
                {"certification": "12", "meaning": "", "order": 4},
                {"certification": "15", "meaning": "", "order": 5},
                {"certification": "18", "meaning": "", "order": 6}
              ]
            }}
            """.trimIndent(),
        )
    }

    private val tvPayload by lazy {
        json(
            """
            {"certifications": {
              "US": [
                {"certification": "NR", "meaning": "", "order": 0},
                {"certification": "TV-Y", "meaning": "", "order": 1},
                {"certification": "TV-Y7", "meaning": "", "order": 2},
                {"certification": "TV-G", "meaning": "", "order": 3},
                {"certification": "TV-PG", "meaning": "", "order": 4},
                {"certification": "TV-14", "meaning": "", "order": 5},
                {"certification": "TV-MA", "meaning": "", "order": 6}
              ],
              "GB": [
                {"certification": "U", "meaning": "", "order": 0},
                {"certification": "PG", "meaning": "", "order": 1},
                {"certification": "12", "meaning": "", "order": 3},
                {"certification": "15", "meaning": "", "order": 4},
                {"certification": "18", "meaning": "", "order": 5}
              ]
            }}
            """.trimIndent(),
        )
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
