package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeProgressionTest {

    private fun ep(season: Int, episode: Int, id: String = "s${season}e${episode}", title: String? = null): EpisodeCandidate =
        EpisodeCandidate(id = id, season = season, episode = episode, title = title)

    // ------------------------------------------------------------------
    // Next episode rules
    // ------------------------------------------------------------------

    @Test
    fun nextEpisodeWithinSameSeason() {
        val s1e1 = ep(1, 1)
        val s1e2 = ep(1, 2)
        val s1e3 = ep(1, 3)
        val episodes = listOf(s1e1, s1e2, s1e3)

        val next = EpisodeProgression.nextEpisode(s1e1, episodes)
        assertEquals(s1e2.id, next?.id)
    }

    @Test
    fun nextEpisodeCrossesSeasonBoundary() {
        val s1e1 = ep(1, 1)
        val s1e3 = ep(1, 3)
        val s2e1 = ep(2, 1)
        val episodes = listOf(s1e1, s1e3, s2e1)

        val next = EpisodeProgression.nextEpisode(s1e3, episodes)
        assertEquals(s2e1.id, next?.id)
    }

    @Test
    fun lastAndOnlyEpisodesHaveNoSuccessor() {
        val s1e1 = ep(1, 1)
        val s1e2 = ep(1, 2)
        val episodes = listOf(s1e1, s1e2)

        assertNull(EpisodeProgression.nextEpisode(s1e2, episodes))

        val singleEpisode = listOf(ep(1, 1))
        assertNull(EpisodeProgression.nextEpisode(singleEpisode[0], singleEpisode))
    }

    @Test
    fun orderingBySeasonAndEpisodeNotInsertion() {
        val s2e1 = ep(2, 1)
        val s1e1 = ep(1, 1)
        val s1e2 = ep(1, 2)
        val episodes = listOf(s2e1, s1e1, s1e2)

        assertEquals(s1e2.id, EpisodeProgression.nextEpisode(s1e1, episodes)?.id)
        assertEquals(s2e1.id, EpisodeProgression.nextEpisode(s1e2, episodes)?.id)
        assertNull(EpisodeProgression.nextEpisode(s2e1, episodes))
    }

    @Test
    fun unknownOrForeignEpisodeReturnsNull() {
        val s1e1 = ep(1, 1)
        val s1e2 = ep(1, 2)
        val episodes = listOf(s1e1, s1e2)

        val orphan = ep(1, 99, id = "orphan")
        assertNull(EpisodeProgression.nextEpisode(orphan, episodes))

        val foreign = ep(1, 1, id = "foreign-show-ep")
        assertNull(EpisodeProgression.nextEpisode(foreign, episodes))
    }

    @Test
    fun progressionSkipsGapsInSeasonNumbers() {
        val s1e1 = ep(1, 1)
        val s3e1 = ep(3, 1)
        val episodes = listOf(s1e1, s3e1)

        assertEquals(s3e1.id, EpisodeProgression.nextEpisode(s1e1, episodes)?.id)
    }

    @Test
    fun mainSeasonNeverRegressesToSeasonZero() {
        val s1e2 = ep(1, 2)
        val s0e1 = ep(0, 1, title = "Behind the Scenes")
        val s0e2 = ep(0, 2, title = "Bloopers")
        val episodes = listOf(s1e2, s0e1, s0e2)

        assertNull(EpisodeProgression.nextEpisode(s1e2, episodes))
    }

    @Test
    fun specialsAdvanceAmongThemselvesThenIntoSeasonOne() {
        val s0e1 = ep(0, 1, title = "OVA 1")
        val s0e2 = ep(0, 2, title = "OVA 2")
        val s1e1 = ep(1, 1)
        val episodes = listOf(s0e1, s0e2, s1e1)

        assertEquals(s0e2.id, EpisodeProgression.nextEpisode(s0e1, episodes)?.id)
        assertEquals(s1e1.id, EpisodeProgression.nextEpisode(s0e2, episodes)?.id)
    }

    @Test
    fun mainSeasonSkipsOverSeasonZero() {
        val s1e1 = ep(1, 1)
        val s0e1 = ep(0, 1, title = "OVA")
        val s1e2 = ep(1, 2)
        val episodes = listOf(s1e1, s0e1, s1e2)

        assertEquals(s1e2.id, EpisodeProgression.nextEpisode(s1e1, episodes)?.id)
    }

    @Test
    fun duplicateEncodesDoNotRepeatTheSameEpisode() {
        val s1e1A = ep(1, 1, id = "s1e1-720p", title = "S1E1 720p")
        val s1e1B = ep(1, 1, id = "s1e1-1080p", title = "S1E1 1080p")
        val s1e2 = ep(1, 2)
        val episodes = listOf(s1e1A, s1e1B, s1e2)

        assertEquals(s1e2.id, EpisodeProgression.nextEpisode(s1e1B, episodes)?.id)

        // All duplicates of finale return null
        val finaleA = ep(2, 5, id = "fin-720p")
        val finaleB = ep(2, 5, id = "fin-1080p")
        val finaleEpisodes = listOf(s1e1A, finaleA, finaleB)
        assertNull(EpisodeProgression.nextEpisode(finaleB, finaleEpisodes))
    }

    @Test
    fun neverAdvancesBackwards() {
        val s1e1 = ep(1, 1)
        val s2e1 = ep(2, 1)
        val s2e3 = ep(2, 3)
        val episodes = listOf(s1e1, s2e1, s2e3)

        assertNull(EpisodeProgression.nextEpisode(s2e3, episodes))
    }

    @Test
    fun complexLibraryProgressesCorrectly() {
        val s0e1 = ep(0, 1, title = "OVA 1")
        val s1e1 = ep(1, 1, id = "s1e1")
        val s1e1Alt = ep(1, 1, id = "s1e1-alt")
        val s1e2 = ep(1, 2)
        val s0e2 = ep(0, 2, title = "OVA 2")
        val s1e3 = ep(1, 3)
        val s2e1 = ep(2, 1)
        val s0e3 = ep(0, 3, title = "Recap")

        val episodes = listOf(s0e1, s1e1, s1e1Alt, s1e2, s0e2, s1e3, s2e1, s0e3)

        assertEquals(s1e2.id, EpisodeProgression.nextEpisode(s1e1, episodes)?.id)
        assertEquals(s1e3.id, EpisodeProgression.nextEpisode(s1e2, episodes)?.id)
        assertEquals(s2e1.id, EpisodeProgression.nextEpisode(s1e3, episodes)?.id)
        assertNull(EpisodeProgression.nextEpisode(s2e1, episodes))
    }

    // ------------------------------------------------------------------
    // Up Next window rules
    // ------------------------------------------------------------------

    @Test
    fun upNextAppearsWithinThirtySecondsOfTheEnd() {
        val first = ep(1, 1)
        val second = ep(1, 2)
        val episodes = listOf(first, second)

        // 28 seconds remaining -> shown
        val shown = EpisodeProgression.upcomingEpisode(
            timeMillis = 272_000L,
            durationMillis = 300_000L,
            loopEnabled = false,
            current = first,
            episodes = episodes,
        )
        assertEquals(second.id, shown?.id)

        // 31 seconds remaining -> hidden
        val hidden = EpisodeProgression.upcomingEpisode(
            timeMillis = 269_000L,
            durationMillis = 300_000L,
            loopEnabled = false,
            current = first,
            episodes = episodes,
        )
        assertNull(hidden)
    }

    @Test
    fun upNextClearsWhenSeekingBackAndReturns() {
        val first = ep(1, 1)
        val second = ep(1, 2)
        val episodes = listOf(first, second)

        // At 275s -> shown
        assertEquals(second.id, EpisodeProgression.upcomingEpisode(275_000L, 300_000L, false, first, episodes)?.id)

        // Seek back to 200s -> hidden
        assertNull(EpisodeProgression.upcomingEpisode(200_000L, 300_000L, false, first, episodes))

        // Play forward to 280s -> shown again
        assertEquals(second.id, EpisodeProgression.upcomingEpisode(280_000L, 300_000L, false, first, episodes)?.id)
    }

    @Test
    fun upNextIsSuppressedWithLoopForMoviesAndWithoutASuccessor() {
        val first = ep(1, 1)
        val second = ep(1, 2)
        val last = ep(1, 3)
        val episodes = listOf(first, second, last)

        // Loop enabled -> null
        assertNull(EpisodeProgression.upcomingEpisode(280_000L, 300_000L, loopEnabled = true, first, episodes))

        // Movie / null episode -> null
        assertNull(EpisodeProgression.upcomingEpisode(280_000L, 300_000L, false, null, episodes))
        assertNull(EpisodeProgression.upcomingEpisode(280_000L, 300_000L, false, first, null))

        // Last episode (no successor) -> null
        assertNull(EpisodeProgression.upcomingEpisode(280_000L, 300_000L, false, last, episodes))
    }

    @Test
    fun upNextNeedsAKnownDuration() {
        val first = ep(1, 1)
        val second = ep(1, 2)
        val episodes = listOf(first, second)

        assertNull(EpisodeProgression.upcomingEpisode(280_000L, durationMillis = null, false, first, episodes))
        assertNull(EpisodeProgression.upcomingEpisode(280_000L, durationMillis = 0L, false, first, episodes))
        assertNull(EpisodeProgression.upcomingEpisode(280_000L, durationMillis = -1L, false, first, episodes))
    }

    @Test
    fun upNextBoundaries() {
        val first = ep(1, 1)
        val second = ep(1, 2)
        val episodes = listOf(first, second)

        // Exactly 30s remaining -> shown
        assertEquals(second.id, EpisodeProgression.upcomingEpisode(270_000L, 300_000L, false, first, episodes)?.id)

        // 30.1s remaining (269_900 ms) -> null
        assertNull(EpisodeProgression.upcomingEpisode(269_900L, 300_000L, false, first, episodes))

        // 0s remaining (at the end) -> null
        assertNull(EpisodeProgression.upcomingEpisode(300_000L, 300_000L, false, first, episodes))

        // Past the end -> null
        assertNull(EpisodeProgression.upcomingEpisode(305_000L, 300_000L, false, first, episodes))
    }

    // ------------------------------------------------------------------
    // Continue Watching next-up rules
    // ------------------------------------------------------------------

    @Test
    fun highestCompletedPerShowSelectsTheFurthest() {
        val entries = listOf(
            CompletedProgressEntry(tmdbId = 101, isEpisode = true, isCompleted = true, showTmdbId = 1, seasonNumber = 1, episodeNumber = 1),
            CompletedProgressEntry(tmdbId = 103, isEpisode = true, isCompleted = true, showTmdbId = 1, seasonNumber = 1, episodeNumber = 3),
            CompletedProgressEntry(tmdbId = 102, isEpisode = true, isCompleted = true, showTmdbId = 1, seasonNumber = 1, episodeNumber = 2),
            CompletedProgressEntry(tmdbId = 104, isEpisode = true, isCompleted = false, showTmdbId = 1, seasonNumber = 1, episodeNumber = 4),
            CompletedProgressEntry(tmdbId = 201, isEpisode = true, isCompleted = true, showTmdbId = 2, seasonNumber = 1, episodeNumber = 1),
            CompletedProgressEntry(tmdbId = 999, isEpisode = false, isCompleted = true),
        )

        val result = EpisodeProgression.highestCompletedPerShow(entries)
        assertEquals(2, result.size)
        assertEquals(1, result[1]?.season)
        assertEquals(3, result[1]?.episode)
        assertEquals(1, result[2]?.season)
        assertEquals(1, result[2]?.episode)
    }

    @Test
    fun completedEpisodeSurfacesNextUpAcrossSeasons() {
        val s1e1 = ep(1, 1, id = "101")
        val s1e2 = ep(1, 2, id = "102")
        val anime = ShowCandidate(tmdbId = 1, name = "Anime", episodes = listOf(s1e1, s1e2))

        val progress1 = listOf(
            CompletedProgressEntry(tmdbId = 101, isEpisode = true, isCompleted = true, showTmdbId = 1, seasonNumber = 1, episodeNumber = 1)
        )
        val nextUp1 = EpisodeProgression.nextUpEpisodes(progress1, emptySet(), listOf(anime))
        assertEquals(1, nextUp1.size)
        assertEquals(s1e2.id, nextUp1[0].episode.id)

        val s1e3 = ep(1, 3, id = "201")
        val s2e1 = ep(2, 1, id = "202")
        val drama = ShowCandidate(tmdbId = 2, name = "Drama", episodes = listOf(s1e3, s2e1))
        val progress2 = listOf(
            CompletedProgressEntry(tmdbId = 201, isEpisode = true, isCompleted = true, showTmdbId = 2, seasonNumber = 1, episodeNumber = 3)
        )
        val nextUp2 = EpisodeProgression.nextUpEpisodes(progress2, emptySet(), listOf(drama))
        assertEquals(1, nextUp2.size)
        assertEquals(s2e1.id, nextUp2[0].episode.id)
    }

    @Test
    fun noNextUpAfterLastEpisodeOrWhileOneIsInProgress() {
        val s1e1 = ep(1, 1, id = "301")
        val finished = ShowCandidate(tmdbId = 3, name = "Finished", episodes = listOf(s1e1))
        val progress1 = listOf(
            CompletedProgressEntry(tmdbId = 301, isEpisode = true, isCompleted = true, showTmdbId = 3, seasonNumber = 1, episodeNumber = 1)
        )
        assertTrue(EpisodeProgression.nextUpEpisodes(progress1, emptySet(), listOf(finished)).isEmpty())

        val sitcom = ShowCandidate(tmdbId = 4, name = "Sitcom", episodes = listOf(ep(1, 1), ep(1, 2), ep(1, 3)))
        val progress2 = listOf(
            CompletedProgressEntry(tmdbId = 401, isEpisode = true, isCompleted = true, showTmdbId = 4, seasonNumber = 1, episodeNumber = 1),
            CompletedProgressEntry(tmdbId = 402, isEpisode = true, isCompleted = false, showTmdbId = 4, seasonNumber = 1, episodeNumber = 2),
        )
        // Show 4 has in-progress episode
        assertTrue(EpisodeProgression.nextUpEpisodes(progress2, setOf(4), listOf(sitcom)).isEmpty())
    }

    @Test
    fun showsWithoutATmdbIdAreExcluded() {
        val unknown = ShowCandidate(tmdbId = null, name = "Unknown", episodes = listOf(ep(1, 1), ep(1, 2)))
        val progress = listOf(
            CompletedProgressEntry(tmdbId = 501, isEpisode = true, isCompleted = true, showTmdbId = null, seasonNumber = 1, episodeNumber = 1)
        )
        assertTrue(EpisodeProgression.nextUpEpisodes(progress, emptySet(), listOf(unknown)).isEmpty())
    }

    @Test
    fun showsAdvanceIndependently() {
        val a1 = ep(1, 1, id = "1001")
        val a2 = ep(1, 2, id = "1002")
        val showA = ShowCandidate(tmdbId = 10, name = "Show A", episodes = listOf(a1, a2))

        val b1 = ep(1, 1, id = "2001")
        val b2 = ep(1, 2, id = "2002")
        val showB = ShowCandidate(tmdbId = 20, name = "Show B", episodes = listOf(b1, b2))

        val progress = listOf(
            CompletedProgressEntry(tmdbId = 1001, isEpisode = true, isCompleted = true, showTmdbId = 10, seasonNumber = 1, episodeNumber = 1),
            CompletedProgressEntry(tmdbId = 2001, isEpisode = true, isCompleted = true, showTmdbId = 20, seasonNumber = 1, episodeNumber = 1),
        )

        val result = EpisodeProgression.nextUpEpisodes(progress, emptySet(), listOf(showA, showB))
        assertEquals(2, result.size)
        val ids = result.map { it.episode.id }.toSet()
        assertTrue(ids.contains(a2.id))
        assertTrue(ids.contains(b2.id))
    }

    @Test
    fun duplicateShowRecordsMergeToOneCard() {
        val local = ShowCandidate(tmdbId = 60, name = "Split", episodes = listOf(ep(1, 1, id = "6001"), ep(1, 3, id = "6003")))
        val nas = ShowCandidate(tmdbId = 60, name = "Split", episodes = listOf(ep(1, 2, id = "6002")))

        val progress = listOf(
            CompletedProgressEntry(tmdbId = 6001, isEpisode = true, isCompleted = true, showTmdbId = 60, seasonNumber = 1, episodeNumber = 1)
        )

        val result = EpisodeProgression.nextUpEpisodes(progress, emptySet(), listOf(local, nas))
        assertEquals(1, result.size)
        assertEquals("6002", result[0].episode.id)
    }

    @Test
    fun nextUpIsOrderedByMostRecentlyWatchedDescending() {
        val olderShow = ShowCandidate(tmdbId = 80, name = "Older", episodes = listOf(ep(1, 1), ep(1, 2, id = "8002")))
        val newerShow = ShowCandidate(tmdbId = 90, name = "Newer", episodes = listOf(ep(1, 1), ep(1, 2, id = "9002")))

        val progress = listOf(
            CompletedProgressEntry(tmdbId = 8001, isEpisode = true, isCompleted = true, showTmdbId = 80, seasonNumber = 1, episodeNumber = 1, lastWatchedEpochMillis = 100L),
            CompletedProgressEntry(tmdbId = 9001, isEpisode = true, isCompleted = true, showTmdbId = 90, seasonNumber = 1, episodeNumber = 1, lastWatchedEpochMillis = 200L),
        )

        val result = EpisodeProgression.nextUpEpisodes(progress, emptySet(), listOf(olderShow, newerShow))
        assertEquals(2, result.size)
        assertEquals("Newer", result[0].show.name)
        assertEquals("Older", result[1].show.name)
    }
}
