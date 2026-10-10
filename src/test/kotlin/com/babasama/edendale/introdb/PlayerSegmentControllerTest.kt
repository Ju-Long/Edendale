package com.babasama.edendale.introdb

import com.babasama.edendale.android.player.InMemoryPlayerPreferencesStore
import com.babasama.edendale.android.player.PlayerPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerSegmentControllerTest {
    private val media = IntroDbMedia.create(278)!!
    private val intro = PlaybackSegment(
        kind = SegmentKind.INTRO,
        startMs = 5000,
        endMs = 20000,
        reachesEnd = false,
    )

    @Test
    fun legacyAutoSkipDoesNotEnableNetworkAndLookupWaitsForDuration() = runBlocking {
        val store = InMemoryPlayerPreferencesStore()
        store.putBoolean("player.skipRecap", true)
        store.putBoolean("player.skipCredits", true)
        val prefs = PlayerPreferences(store)

        val requests = mutableListOf<IntroDbRequest>()
        val controller = PlayerSegmentController(
            preferences = prefs,
            scope = this,
            lookup = {
                requests.add(it)
                listOf(intro)
            },
        )

        controller.begin("item-1", media)
        controller.update(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)
        assertFalse(controller.isEnabled)
        assertTrue(requests.isEmpty())

        controller.update(timeSeconds = 10.0, durationSeconds = null, isSeekable = true)
        controller.isEnabled = true
        assertTrue(requests.isEmpty())

        controller.update(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)
        waitForLookup(controller)
        assertEquals(intro, controller.activeSegment)
        assertTrue(store.getBoolean("player.segmentPromptsEnabled", false))
        assertEquals(1, requests.size)
        controller.end()
    }

    @Test
    fun manualSkipRevalidatesTimeAndAllowsRewindWithoutRepeatedPresses() = runBlocking {
        val controller = PlayerSegmentController(
            initialEnabled = true,
            scope = this,
            lookup = { listOf(intro) },
        )

        controller.begin("item-1", media)
        controller.update(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)
        waitForLookup(controller)

        // Outside segment
        assertNull(controller.consumeSkip(timeSeconds = 30.0, durationSeconds = 120.0, isSeekable = true))
        // Inside segment, but not seekable
        assertNull(controller.consumeSkip(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = false))
        // Inside segment, seekable
        val action = controller.consumeSkip(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)
        assertEquals(SkipAction.Seek(20000L), action)
        assertEquals(20.0, (action as SkipAction.Seek).targetSeconds)

        // Prompt is now suppressed
        assertNull(controller.activeSegment)
        assertNull(controller.consumeSkip(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true))

        // Exiting the segment clears suppression
        controller.update(timeSeconds = 21.0, durationSeconds = 120.0, isSeekable = true)
        // Rewinding back into the segment restores the prompt
        controller.update(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)
        assertEquals(intro, controller.activeSegment)
        controller.end()
    }

    @Test
    fun onlyTerminalCreditsCompletePlayback() = runBlocking {
        val boundedCredits = PlaybackSegment(
            kind = SegmentKind.CREDITS,
            startMs = 90000,
            endMs = 100000,
            reachesEnd = false,
        )
        val terminalCredits = PlaybackSegment(
            kind = SegmentKind.CREDITS,
            startMs = 110000,
            endMs = 120000,
            reachesEnd = true,
        )

        val controller = PlayerSegmentController(
            initialEnabled = true,
            scope = this,
            lookup = { listOf(boundedCredits, terminalCredits) },
        )

        controller.begin("item-1", media)
        controller.update(timeSeconds = 95.0, durationSeconds = 120.0, isSeekable = true)
        waitForLookup(controller)

        assertEquals(
            SkipAction.Seek(100000L),
            controller.consumeSkip(timeSeconds = 95.0, durationSeconds = 120.0, isSeekable = true),
        )
        assertNull(controller.consumeSkip(timeSeconds = 105.0, durationSeconds = 120.0, isSeekable = true))
        assertEquals(
            SkipAction.Finish,
            controller.consumeSkip(timeSeconds = 115.0, durationSeconds = 120.0, isSeekable = true),
        )
        controller.end()
    }

    @Test
    fun lookupIsDeduplicatedAndCacheSeparatesRuntimesAndEndsWithSession() = runBlocking {
        val requests = mutableListOf<IntroDbRequest>()
        val controller = PlayerSegmentController(
            initialEnabled = true,
            scope = this,
            lookup = {
                requests.add(it)
                emptyList()
            },
        )

        for (duration in listOf(120.0, 120.0, 125.0)) {
            controller.begin(UUID.randomUUID().toString(), media)
            for (time in 0 until 30) {
                controller.update(timeSeconds = time.toDouble(), durationSeconds = duration, isSeekable = true)
            }
            waitForLookup(controller)
        }
        assertEquals(2, requests.size)

        controller.end()
        controller.begin(UUID.randomUUID().toString(), media)
        controller.update(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)
        waitForLookup(controller)
        assertEquals(3, requests.size)
        controller.end()
    }

    @Test
    fun cacheCapOf12Entries() = runBlocking {
        val requests = mutableListOf<IntroDbRequest>()
        val controller = PlayerSegmentController(
            initialEnabled = true,
            scope = this,
            lookup = {
                requests.add(it)
                emptyList()
            },
        )

        // Load 12 distinct duration requests
        for (i in 1..12) {
            controller.begin(UUID.randomUUID().toString(), media)
            controller.update(timeSeconds = 1.0, durationSeconds = 100.0 + i, isSeekable = true)
            waitForLookup(controller)
        }
        assertEquals(12, requests.size)

        // 13th request triggers cache clearance
        controller.begin(UUID.randomUUID().toString(), media)
        controller.update(timeSeconds = 1.0, durationSeconds = 200.0, isSeekable = true)
        waitForLookup(controller)
        assertEquals(13, requests.size)

        // Re-requesting the first one (duration 101.0) must now make a new network call because cache was cleared
        controller.begin(UUID.randomUUID().toString(), media)
        controller.update(timeSeconds = 1.0, durationSeconds = 101.0, isSeekable = true)
        waitForLookup(controller)
        assertEquals(14, requests.size)
        controller.end()
    }

    @Test
    fun failuresDoNotRetryOnTimeEventsOrLeavePrompts() = runBlocking {
        var callCount = 0
        val controller = PlayerSegmentController(
            initialEnabled = true,
            scope = this,
            lookup = {
                callCount++
                throw RuntimeException("Network error")
            },
        )

        controller.begin("item-1", media)
        controller.update(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)
        waitForLookup(controller)

        for (time in 11 until 30) {
            controller.update(timeSeconds = time.toDouble(), durationSeconds = 120.0, isSeekable = true)
        }
        assertEquals(1, callCount)
        assertNull(controller.activeSegment)
        controller.end()
    }

    @Test
    fun lateResponsesCannotAffectNewItemsDisabledSettingsOrEndedSessions() = runBlocking {
        for (action in listOf("switch", "disable", "end")) {
            val gate = CompletableDeferred<List<PlaybackSegment>>()
            var started = false
            val controller = PlayerSegmentController(
                initialEnabled = true,
                scope = this,
                lookup = {
                    started = true
                    gate.await()
                },
            )

            controller.begin("item-1", media)
            controller.update(timeSeconds = 10.0, durationSeconds = 120.0, isSeekable = true)

            for (i in 0 until 100) {
                if (started) break
                delay(5)
            }
            assertTrue(started)

            when (action) {
                "switch" -> controller.begin("item-2", null)
                "disable" -> controller.isEnabled = false
                "end" -> controller.end()
            }

            gate.complete(listOf(intro))
            delay(20)

            assertTrue(controller.segments.isEmpty())
            assertNull(controller.activeSegment)
            controller.end()
        }
    }

    private suspend fun waitForLookup(controller: PlayerSegmentController) {
        withTimeout(2000) {
            while (controller.isLoading) {
                delay(5)
            }
        }
    }
}
