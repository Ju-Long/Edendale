package com.babasama.edendale.android

import com.babasama.edendale.android.parental.DeviceParentalControls
import com.babasama.edendale.android.parental.InMemoryAudiencePreferenceStore
import com.babasama.edendale.android.parental.InMemoryParentalStore
import com.babasama.edendale.android.parental.ParentalStore
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.parental.AudienceDecision
import com.babasama.edendale.parental.ParentalClock
import com.babasama.edendale.parental.ParentalPolicy
import com.babasama.edendale.parental.ParentalRules
import com.babasama.edendale.parental.PinHasher
import com.babasama.edendale.parental.PinResult
import com.babasama.edendale.parental.RatingCeiling
import com.babasama.edendale.tmdb.ContentCertificationLookup
import com.babasama.edendale.tmdb.ContentCertificationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YoungAudienceFilterTest {

    @Test
    fun presetDecisionFailsClosedForEverythingButPgAndPg13() {
        val preset = ParentalPolicy(youngAudienceFriendly = true)
        assertEquals(
            AudienceDecision.ALLOWED,
            ParentalRules.decision(ContentCertificationLookup.Found("PG"), MediaType.MOVIE, preset, null),
        )
        assertEquals(
            AudienceDecision.ALLOWED,
            ParentalRules.decision(ContentCertificationLookup.Found("PG-13"), MediaType.MOVIE, preset, null),
        )
        assertEquals(
            AudienceDecision.ALLOWED,
            ParentalRules.decision(ContentCertificationLookup.Found("TV-14"), MediaType.TV, preset, null),
        )
        assertEquals(
            AudienceDecision.BLOCKED,
            ParentalRules.decision(ContentCertificationLookup.Found("R"), MediaType.MOVIE, preset, null),
        )
        // A TV label never qualifies a movie.
        assertEquals(
            AudienceDecision.BLOCKED,
            ParentalRules.decision(ContentCertificationLookup.Found("TV-14"), MediaType.MOVIE, preset, null),
        )
        // Unrated fails closed; an error can be retried.
        assertEquals(
            AudienceDecision.BLOCKED,
            ParentalRules.decision(ContentCertificationLookup.Unrated, MediaType.TV, preset, null),
        )
        assertEquals(
            AudienceDecision.UNAVAILABLE,
            ParentalRules.decision(ContentCertificationLookup.Unavailable, MediaType.MOVIE, preset, null),
        )
    }

    @Test
    fun visibleKeepsOnlyAllowedTitlesInOrderWhenEnabled() {
        val pg = item(10, MediaType.MOVIE)
        val restricted = item(20, MediaType.MOVIE)
        val tv14 = item(30, MediaType.TV)
        val unknown = item(40, MediaType.TV)
        val items = listOf(tv14, restricted, pg, unknown)
        val decisions = mapOf(
            pg.ref to AudienceDecision.ALLOWED,
            restricted.ref to AudienceDecision.BLOCKED,
            tv14.ref to AudienceDecision.ALLOWED,
            // `unknown` is absent — unverified titles fail closed.
        )

        // Preserves source order among the allowed titles.
        assertEquals(listOf(tv14, pg), visibleItems(items, decisions, enabled = true))
        // Off is a verbatim bypass, even with decisions present.
        assertEquals(items, visibleItems(items, decisions, enabled = false))
    }

    // MARK: - The filter over the controls

    private val g = item(10, MediaType.MOVIE)
    private val r = item(20, MediaType.MOVIE)
    private val pg13 = item(30, MediaType.MOVIE)
    private val unrated = item(40, MediaType.MOVIE)
    private val tv14 = item(50, MediaType.TV)
    private val tvMa = item(60, MediaType.TV)
    private val items = listOf(tv14, r, g, tvMa, pg13, unrated)
    private val refs = items.map { it.ref }
    private val lookups = mapOf(
        10 to ContentCertificationLookup.Found("G"),
        20 to ContentCertificationLookup.Found("R"),
        30 to ContentCertificationLookup.Found("PG-13"),
        40 to ContentCertificationLookup.Unrated,
        50 to ContentCertificationLookup.Found("TV-14"),
        60 to ContentCertificationLookup.Found("TV-MA"),
    )

    @Test
    fun aCeilingAllowsAtOrUnderAndHidesAboveAndUnrated() = runBlocking {
        val world = World()
        assertTrue(world.controls.updatePolicy(world.clock.wall) { it.copy(ceiling = RatingCeiling("US", movie = "PG-13", tv = "TV-14")) })
        val filter = world.filter()
        assertTrue(filter.isEnabled)
        assertTrue(filter.isVerifying(refs))
        assertEquals(emptyList(), filter.visible(items), "nothing shows before verification")

        filter.verify(refs)
        assertFalse(filter.isVerifying(refs))
        assertTrue(filter.allows(g.ref))
        assertTrue(filter.allows(pg13.ref))
        assertTrue(filter.allows(tv14.ref))
        assertFalse(filter.allows(r.ref))
        assertFalse(filter.allows(unrated.ref))
        assertFalse(filter.allows(tvMa.ref))
        assertEquals(listOf(tv14, g, pg13), filter.visible(items))
        assertTrue(filter.contextIdentifier.startsWith("US|"))
        // Every title was looked up once.
        assertEquals(refs.toSet(), world.provider.requested.toSet())
        assertEquals(refs.size, world.provider.requested.size)
    }

    @Test
    fun aCeilingChangeClearsTheDecisionsAsARegionChangeDoes() = runBlocking {
        val world = World()
        world.controls.updatePolicy(world.clock.wall) { it.copy(ceiling = RatingCeiling("US", movie = "PG-13", tv = null)) }
        val filter = world.filter()
        filter.verify(refs)
        val before = filter.contextIdentifier
        assertFalse(filter.allows(r.ref))

        world.controls.updatePolicy(world.clock.wall) { it.copy(ceiling = RatingCeiling("US", movie = "R", tv = null)) }
        assertNotEquals(before, filter.contextIdentifier)
        assertTrue(filter.isVerifying(refs), "the cache is cleared")
        filter.verify(refs)
        assertTrue(filter.allows(r.ref))
        assertFalse(filter.allows(unrated.ref))
        // A switch that only gates the UI leaves the cache alone.
        val after = filter.contextIdentifier
        world.controls.updatePolicy(world.clock.wall) { it.copy(lockSettings = true) }
        assertEquals(after, filter.contextIdentifier)
        assertFalse(filter.isVerifying(refs))
    }

    @Test
    fun thePresetAndTheCeilingMustBothAllow() = runBlocking {
        val world = World()
        world.controls.updatePolicy(world.clock.wall) {
            it.copy(youngAudienceFriendly = true, ceiling = RatingCeiling("US", movie = "R", tv = "TV-MA"))
        }
        val filter = world.filter()
        filter.verify(refs)
        assertFalse(filter.allows(g.ref), "G is under the ceiling but not in the preset")
        assertTrue(filter.allows(pg13.ref))
        assertFalse(filter.allows(r.ref), "R is in neither")
        assertTrue(filter.allows(tv14.ref))
        assertFalse(filter.allows(tvMa.ref))
    }

    @Test
    fun hidingOffShowsRestrictedTitlesInListsButStillBlocksThem() = runBlocking {
        val world = World()
        world.controls.updatePolicy(world.clock.wall) {
            it.copy(ceiling = RatingCeiling("US", movie = "PG-13", tv = null), hideRestrictedTitles = false)
        }
        val filter = world.filter()
        assertTrue(filter.isEnabled)
        assertEquals(items, filter.visible(items), "lists show everything")
        assertTrue(filter.shows(r.ref))
        assertFalse(filter.allows(r.ref), "but the gate still fails closed until verified")
        filter.verify(refs)
        assertTrue(filter.shows(r.ref))
        assertFalse(filter.allows(r.ref))
        assertTrue(filter.allows(g.ref))

        world.controls.updatePolicy(world.clock.wall) { it.copy(hideRestrictedTitles = true) }
        assertFalse(filter.shows(r.ref))
        // No TV limit in this policy, so TV-MA stays; the unrated movie and R go.
        assertEquals(listOf(tv14, g, tvMa, pg13), filter.visible(items))
    }

    @Test
    fun offIsAVerbatimBypassAndTheFilterFailsClosedUntilTheStoreIsRead() = runBlocking {
        val gate = CountDownLatch(1)
        val world = World(store = GatedStore(gate))
        val filter = world.filter()
        // The store has not been read: nothing shows, nothing is allowed, and everything is verifying.
        assertFalse(world.controls.ready.value)
        assertTrue(filter.isEnabled)
        assertFalse(filter.allows(g.ref))
        assertTrue(filter.isVerifying(refs))
        assertEquals(emptyList(), filter.visible(items))
        assertTrue(filter.contextIdentifier.contains("|loading|"))

        gate.countDown()
        filter.verify(refs)
        assertTrue(world.controls.ready.value)
        assertFalse(filter.isEnabled)
        assertEquals(items, filter.visible(items))
        assertTrue(filter.allows(r.ref))
        assertFalse(filter.isVerifying(refs))
        assertEquals(0, world.provider.requested.size, "an inactive policy asks TMDB nothing")
    }

    @Test
    fun settingIsEnabledTogglesThePresetUnlessLocked() = runBlocking {
        val world = World()
        val filter = world.filter()
        assertIs<PinResult.Unlocked>(world.controls.setPin(null, "1234".toCharArray(), world.clock.wall))
        world.controls.lock()

        filter.isEnabled = true
        assertFalse(filter.isEnabled, "refused while locked")
        assertFalse(world.preset.enabled)

        assertIs<PinResult.Unlocked>(world.controls.verifyPin("1234".toCharArray(), world.clock.wall))
        filter.isEnabled = true
        assertTrue(filter.isEnabled)
        assertTrue(world.preset.enabled)
        assertTrue(filter.policy!!.youngAudienceFriendly)
        filter.verify(refs)
        assertEquals(listOf(tv14, pg13), filter.visible(items))

        filter.isEnabled = false
        assertFalse(filter.isEnabled)
        assertEquals(items, filter.visible(items))
    }

    @Test
    fun theLookupsFollowTheListsRegion() = runBlocking {
        val world = World(deviceRegion = "FR")
        val filter = world.filter()
        filter.verify(refs)
        assertTrue(filter.contextIdentifier.startsWith("FR|"), "the device region until there are lists")

        // TMDB has no FR list in this payload: the ceiling and the lookups both move to US (O-D5).
        world.controls.updatePolicy(world.clock.wall) { it.copy(ceiling = RatingCeiling("US", movie = "PG-13", tv = null)) }
        filter.verify(refs)
        assertTrue(filter.contextIdentifier.startsWith("US|"), filter.contextIdentifier)
        assertEquals("US", world.provider.regions.last())
        assertTrue(filter.allows(g.ref))
    }

    private class FakeProvider(private val region: () -> String, private val lookups: Map<Int, ContentCertificationLookup>) : ContentCertificationProvider {
        val requested = mutableListOf<MediaRef>()
        val regions = mutableListOf<String>()

        override val contextIdentifier: String get() = region().uppercase()

        override suspend fun certification(ref: MediaRef): ContentCertificationLookup {
            synchronized(requested) {
                requested += ref
                regions += contextIdentifier
            }
            return lookups[ref.id] ?: ContentCertificationLookup.Unrated
        }
    }

    private class FakeClock(var wall: Long = 1_762_000_000_000L, var elapsed: Long = 100_000L) : ParentalClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
    }

    /** A store whose first read waits on [gate], so the controls stay unready for a while. */
    private class GatedStore(private val gate: CountDownLatch) : ParentalStore {
        private val delegate = InMemoryParentalStore()

        override fun getString(key: String): String? {
            gate.await(10, TimeUnit.SECONDS)
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

    /** The controls over in-memory stores, a fake provider, and the filter, all on unconfined dispatchers. */
    private inner class World(
        store: ParentalStore = InMemoryParentalStore(),
        deviceRegion: String = "US",
    ) {
        val clock = FakeClock()
        val preset = InMemoryAudiencePreferenceStore()
        val controls = DeviceParentalControls(
            store = store,
            preset = preset,
            listsSource = { type -> if (type == MediaType.MOVIE) moviePayload else tvPayload },
            hasher = PinHasher(memoryKib = 4 * 1024, iterations = 1),
            clock = clock,
            regionProvider = { deviceRegion },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = if (store is GatedStore) Dispatchers.IO else Dispatchers.Unconfined,
            hashDispatcher = Dispatchers.Unconfined,
        )
        val provider = FakeProvider({ ParentalRules.effectiveRegion(deviceRegion, controls.lists.value) }, lookups)

        fun filter() = YoungAudienceFilter(provider, controls, CoroutineScope(Dispatchers.Unconfined), clock)
    }

    private val moviePayload: JsonObject by lazy {
        json(
            """
            {"certifications": {"US": [
              {"certification": "NR", "meaning": "", "order": 0},
              {"certification": "G", "meaning": "", "order": 1},
              {"certification": "PG", "meaning": "", "order": 2},
              {"certification": "PG-13", "meaning": "", "order": 3},
              {"certification": "R", "meaning": "", "order": 4},
              {"certification": "NC-17", "meaning": "", "order": 5}
            ]}}
            """.trimIndent(),
        )
    }

    private val tvPayload: JsonObject by lazy {
        json(
            """
            {"certifications": {"US": [
              {"certification": "NR", "meaning": "", "order": 0},
              {"certification": "TV-Y", "meaning": "", "order": 1},
              {"certification": "TV-G", "meaning": "", "order": 3},
              {"certification": "TV-PG", "meaning": "", "order": 4},
              {"certification": "TV-14", "meaning": "", "order": 5},
              {"certification": "TV-MA", "meaning": "", "order": 6}
            ]}}
            """.trimIndent(),
        )
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun item(id: Int, type: MediaType) = MediaItem(
        id = id,
        mediaType = type,
        title = "Title $id",
    )
}
