package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hermetic JVM tests for [PlaybackTransitions] porting the rules and cases from
 * Apple's `PlayerSessionTransitionTests` and Windows `PlaybackTransitions` tests (DIFF §3.4).
 */
class PlaybackTransitionsTest {

    private fun episode(season: Int, episode: Int, uri: String = "s${season}e${episode}.mkv"): EpisodeCandidate =
        EpisodeCandidate(id = uri, season = season, episode = episode, title = "Episode $episode")

    @Test
    fun naturalEndAdvancesToNextEpisode() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val current = episode(1, 2)
        val next = episode(2, 1)
        val episodes = listOf(current, next)

        val action = transitions.onNaturalEnd(
            loopEnabled = false,
            currentEpisode = current,
            episodes = episodes,
        )

        val advance = assertIs<NaturalEndAction.Advance>(action)
        assertEquals(next.id, advance.nextEpisode.id)
        assertTrue(transitions.currentCompleted)
        assertTrue(transitions.claim(advance.ticket))
    }

    @Test
    fun manualSelectionWinsOverAQueuedAutomaticAdvance() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val current = episode(1, 2)
        val next = episode(2, 1)
        val episodes = listOf(current, next)

        val action = transitions.onNaturalEnd(
            loopEnabled = false,
            currentEpisode = current,
            episodes = episodes,
        )
        val advance = assertIs<NaturalEndAction.Advance>(action)

        // Manual selection happens before the queued advance ticket is claimed
        transitions.present()

        assertFalse(transitions.claim(advance.ticket))
    }

    @Test
    fun endingSessionCancelsQueuedAdvance() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val current = episode(1, 2)
        val next = episode(2, 1)
        val episodes = listOf(current, next)

        val action = transitions.onNaturalEnd(
            loopEnabled = false,
            currentEpisode = current,
            episodes = episodes,
        )
        val advance = assertIs<NaturalEndAction.Advance>(action)

        // Player session ends
        transitions.end()

        assertFalse(transitions.claim(advance.ticket))
    }

    @Test
    fun duplicateAdvanceRequestsMoveOnOnlyOnce() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val first = transitions.requestAdvance()
        val second = transitions.requestAdvance()

        assertTrue(transitions.claim(first))
        assertFalse(transitions.claim(second))
    }

    @Test
    fun lastEpisodeFinishes() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val first = episode(1, 1)
        val last = episode(1, 2)
        val episodes = listOf(first, last)

        val action = transitions.onNaturalEnd(
            loopEnabled = false,
            currentEpisode = last,
            episodes = episodes,
        )

        assertIs<NaturalEndAction.Finish>(action)
        assertTrue(transitions.currentCompleted)
    }

    @Test
    fun loopOnRestartsInsteadOfAdvancing() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val current = episode(1, 1)
        val next = episode(1, 2)
        val episodes = listOf(current, next)

        val action = transitions.onNaturalEnd(
            loopEnabled = true,
            currentEpisode = current,
            episodes = episodes,
        )

        assertIs<NaturalEndAction.LoopRestart>(action)
        assertTrue(transitions.currentCompleted)
    }

    @Test
    fun completionIsWrittenBeforeTheSwitchAndNeverOverwrittenByPartialPosition() {
        val transitions = PlaybackTransitions()
        transitions.present()

        assertTrue(transitions.shouldWriteProgress)
        assertFalse(transitions.currentCompleted)

        // Current episode finishes naturally
        transitions.markCurrentCompleted()
        assertTrue(transitions.currentCompleted)
        assertFalse(transitions.shouldWriteProgress)

        // Switching to the next episode resets completed state so its progress is recorded
        transitions.present()
        assertFalse(transitions.currentCompleted)
        assertTrue(transitions.shouldWriteProgress)
    }

    @Test
    fun nonEpisodeItemFinishesAtNaturalEnd() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val action = transitions.onNaturalEnd(
            loopEnabled = false,
            currentEpisode = null,
            episodes = null,
        )

        assertIs<NaturalEndAction.Finish>(action)
        assertTrue(transitions.currentCompleted)
    }

    @Test
    fun episodeNotInListFinishesAtNaturalEnd() {
        val transitions = PlaybackTransitions()
        transitions.present()

        val foreign = episode(1, 1, "foreign.mkv")
        val episodes = listOf(episode(1, 1, "local.mkv"), episode(1, 2, "local2.mkv"))

        val action = transitions.onNaturalEnd(
            loopEnabled = false,
            currentEpisode = foreign,
            episodes = episodes,
        )

        assertIs<NaturalEndAction.Finish>(action)
        assertTrue(transitions.currentCompleted)
    }
}
