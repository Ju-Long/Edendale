package com.babasama.edendale.android.theater

import com.babasama.edendale.android.player.PlayerPlaylist
import com.babasama.edendale.android.player.PlaylistEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HORIZON.md G.15: the Up Next card and the next episode's handover in the theater. */
class TheaterUpNextRulesTest {

    private fun episode(season: Int, number: Int) = PlaylistEntry(
        uri = "smb://nas/show/s${season}e$number.mkv",
        title = "Episode $number",
        detail = "S%02dE%02d".format(season, number),
        tmdbId = 1000 + season * 100 + number,
        isEpisode = true,
        showTmdbId = 77,
        season = season,
        episode = number,
        stillPath = "/still$number.jpg",
    )

    private val show = PlayerPlaylist(isEpisodeList = true, entries = listOf(episode(1, 1), episode(1, 2), episode(2, 1)))
    private val hour = 3_600_000L

    @Test
    fun theCardShowsInAnEpisodesLastThirtySeconds() {
        val current = show.entries[0].uri
        assertEquals(show.entries[1], TheaterUpNextRules.upcoming(show, current, isEpisode = true, positionMillis = hour - 30_000, durationMillis = hour))
        assertNull(TheaterUpNextRules.upcoming(show, current, isEpisode = true, positionMillis = hour - 31_000, durationMillis = hour))
        assertNull(TheaterUpNextRules.upcoming(show, current, isEpisode = true, positionMillis = hour, durationMillis = hour))
    }

    @Test
    fun theLastEpisodeOfASeasonLeadsIntoTheNext() {
        assertEquals(show.entries[2], TheaterUpNextRules.upcoming(show, show.entries[1].uri, true, hour - 1_000, hour))
        assertNull(TheaterUpNextRules.upcoming(show, show.entries[2].uri, true, hour - 1_000, hour))
    }

    @Test
    fun aMovieAFolderOrAnUnknownFileHasNoCard() {
        assertNull(TheaterUpNextRules.upcoming(show, show.entries[0].uri, isEpisode = false, positionMillis = hour - 1_000, durationMillis = hour))
        assertNull(TheaterUpNextRules.upcoming(show.copy(isEpisodeList = false), show.entries[0].uri, true, hour - 1_000, hour))
        assertNull(TheaterUpNextRules.upcoming(show, "smb://nas/other.mkv", true, hour - 1_000, hour))
        assertNull(TheaterUpNextRules.upcoming(null, show.entries[0].uri, true, hour - 1_000, hour))
        assertNull(TheaterUpNextRules.upcoming(show, show.entries[0].uri, true, 0, 0))
    }

    @Test
    fun theCardNamesTheEpisode() {
        val card = TheaterUpNextRules.card(show.entries[1])
        assertEquals("S01E02", card.code)
        assertEquals("Episode 2", card.title)
        assertEquals("/still2.jpg", card.artworkPath)
        assertEquals("S02E01", TheaterUpNextRules.card(episode(2, 1).copy(detail = null)).code)
    }

    @Test
    fun theNextEpisodeKeepsTheViewersLanguagesAndSpeedButNotTrackIds() {
        val next = TheaterUpNextRules.handover(show.entries[1], resumeMillis = 0, audioLanguage = "ja", subtitlesOff = false, textLanguage = "en", speed = 1.5f)
        assertEquals(show.entries[1].uri, next.uri)
        assertEquals("Episode 2", next.title)
        assertEquals("S01E02", next.subtitle)
        assertEquals(0L, next.positionMillis)
        assertTrue(next.playWhenReady)
        assertEquals(1102, next.tmdbId)
        assertTrue(next.isEpisode)
        assertEquals(77, next.showTmdbId)
        assertEquals(1, next.season)
        assertEquals(2, next.episode)
        assertNull(next.audioTrackId)
        assertEquals("ja", next.audioLanguage)
        assertFalse(next.subtitlesOff)
        assertNull(next.textTrackId)
        assertEquals("en", next.textLanguage)
        assertEquals(1.5f, next.speed)
    }

    @Test
    fun subtitlesOffStayOffAndANonsenseSpeedIsNormal() {
        val next = TheaterUpNextRules.handover(show.entries[1], resumeMillis = -5, audioLanguage = null, subtitlesOff = true, textLanguage = "en", speed = 0f)
        assertTrue(next.subtitlesOff)
        assertNull(next.textLanguage)
        assertEquals(1f, next.speed)
        assertEquals(0L, next.positionMillis)
    }

    @Test
    fun anUnfinishedEpisodeResumesWhereItWasLeft() {
        assertEquals(754_500L, TheaterUpNextRules.resumeMillis(watchedSeconds = 754.5, resumeFraction = 0.3))
        // Outside the player's resume window (or finished): from the start.
        assertEquals(0L, TheaterUpNextRules.resumeMillis(watchedSeconds = 754.5, resumeFraction = null))
        assertEquals(0L, TheaterUpNextRules.resumeMillis(watchedSeconds = null, resumeFraction = null))
        assertEquals(0L, TheaterUpNextRules.resumeMillis(watchedSeconds = Double.NaN, resumeFraction = 0.3))
    }
}
