package com.babasama.edendale.android.xr

import com.babasama.edendale.domain.WatchMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N.6: the theater player's pure decisions. Which track the handover picks,
 * what progress a position writes, the Up Next code, the identity the
 * TheIntroDB lookup uses, and when a player paused for the system resumes.
 */
class TheaterPlaybackRulesTest {

    private data class Track(val id: String?, val language: String?)

    private val tracks = listOf(
        Track(id = "1", language = "eng"),
        Track(id = "2", language = "fra"),
        Track(id = null, language = "deu"),
    )

    private fun pick(id: String?, language: String?): Track? =
        TheaterPlaybackRules.matching(tracks, id, language, idOf = { it.id }, languageOf = { it.language })

    private fun handover(
        tmdbId: Int? = 603,
        isEpisode: Boolean = false,
        showTmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = XrTheaterHandover(
        uri = "content://media/603",
        title = "The Matrix",
        subtitle = null,
        positionMillis = 0L,
        playWhenReady = true,
        tmdbId = tmdbId,
        isEpisode = isEpisode,
        showTmdbId = showTmdbId,
        season = season,
        episode = episode,
        audioTrackId = null,
        audioLanguage = null,
        subtitlesOff = false,
        textTrackId = null,
        textLanguage = null,
        speed = 1f,
    )

    private val episode = handover(tmdbId = 63056, isEpisode = true, showTmdbId = 1399, season = 1, episode = 2)

    // ------------------------------------------------------------------
    // Matching the handover's tracks
    // ------------------------------------------------------------------

    @Test
    fun theTrackWithTheIdWinsOverTheLanguage() {
        assertEquals(tracks[1], pick(id = "2", language = "eng"))
    }

    @Test
    fun withoutTheIdTheLanguageDecidesIgnoringCase() {
        assertEquals(tracks[0], pick(id = "9", language = "ENG"))
        assertEquals(tracks[2], pick(id = null, language = "deu"))
    }

    @Test
    fun nothingMatchesNothing() {
        assertNull(pick(id = null, language = null))
        assertNull(pick(id = "9", language = "jpn"))
        assertNull(TheaterPlaybackRules.matching(emptyList<Track>(), "1", "eng", { it.id }, { it.language }))
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    @Test
    fun aPositionWritesItsFraction() {
        val record = assertNotNull(
            TheaterPlaybackRules.progress(handover(), 30_000L, 120_000L, completed = false, completedWritten = false, nowMillis = 42L),
        )
        assertEquals(603, record.tmdbId)
        assertEquals(WatchMediaType.MOVIE, record.mediaType)
        assertEquals(0.25, record.position, 1e-9)
        assertEquals(30.0, record.watchedSeconds, 1e-9)
        assertEquals(120.0, record.durationSeconds, 1e-9)
        assertEquals(42L, record.lastWatchedEpochMillis)
        assertFalse(record.isCompleted)
        assertNull(record.showTmdbId)
        assertNull(record.seasonNumber)
        assertNull(record.episodeNumber)
    }

    @Test
    fun ninetyFivePercentCompletes() {
        val almost = assertNotNull(TheaterPlaybackRules.progress(handover(), 113_999L, 120_000L, false, false, 0L))
        assertFalse(almost.isCompleted)
        val done = assertNotNull(TheaterPlaybackRules.progress(handover(), 114_000L, 120_000L, false, false, 0L))
        assertTrue(done.isCompleted)
        assertEquals(1.0, done.position, 1e-9)
        assertEquals(114.0, done.watchedSeconds, 1e-9)
    }

    @Test
    fun theCompletedWriteWinsAtAnyPosition() {
        val record = assertNotNull(
            TheaterPlaybackRules.progress(handover(), 10_000L, 120_000L, completed = true, completedWritten = false, nowMillis = 0L),
        )
        assertTrue(record.isCompleted)
        assertEquals(1.0, record.position, 1e-9)
        assertEquals(10.0, record.watchedSeconds, 1e-9)
    }

    @Test
    fun onceCompletedAnEarlierPositionIsNeverWritten() {
        assertNull(TheaterPlaybackRules.progress(handover(), 10_000L, 120_000L, completed = false, completedWritten = true, nowMillis = 0L))
        // A later completed write is still fine.
        assertNotNull(TheaterPlaybackRules.progress(handover(), 119_000L, 120_000L, completed = false, completedWritten = true, nowMillis = 0L))
    }

    @Test
    fun noIdOrNoDurationWritesNothing() {
        assertNull(TheaterPlaybackRules.progress(handover(tmdbId = null), 30_000L, 120_000L, false, false, 0L))
        assertNull(TheaterPlaybackRules.progress(handover(tmdbId = null), 30_000L, 120_000L, completed = true, completedWritten = false, nowMillis = 0L))
        assertNull(TheaterPlaybackRules.progress(handover(), 30_000L, 0L, false, false, 0L))
        // Media3's C.TIME_UNSET.
        assertNull(TheaterPlaybackRules.progress(handover(), 30_000L, Long.MIN_VALUE + 1, completed = true, completedWritten = false, nowMillis = 0L))
    }

    @Test
    fun anEpisodeCarriesItsShowSeasonAndNumber() {
        val record = assertNotNull(TheaterPlaybackRules.progress(episode, 60_000L, 120_000L, false, false, 0L))
        assertEquals(63056, record.tmdbId)
        assertEquals(WatchMediaType.EPISODE, record.mediaType)
        assertEquals(1399, record.showTmdbId)
        assertEquals(1, record.seasonNumber)
        assertEquals(2, record.episodeNumber)
        assertEquals("episode:63056", record.storageKey)
    }

    @Test
    fun thePositionIsClampedToTheDuration() {
        val past = assertNotNull(TheaterPlaybackRules.progress(handover(), 130_000L, 120_000L, false, false, 0L))
        assertTrue(past.isCompleted)
        assertEquals(120.0, past.watchedSeconds, 1e-9)
        val before = assertNotNull(TheaterPlaybackRules.progress(handover(), -5_000L, 120_000L, false, false, 0L))
        assertEquals(0.0, before.position, 1e-9)
        assertEquals(0.0, before.watchedSeconds, 1e-9)
    }

    // ------------------------------------------------------------------
    // Up Next
    // ------------------------------------------------------------------

    @Test
    fun theUpNextCodeIsTheRowsDetailOrFormattedFromTheNumbers() {
        assertEquals("S01E02", TheaterPlaybackRules.upNextCode("S01E02", 3, 4))
        assertEquals("S03E04", TheaterPlaybackRules.upNextCode(null, 3, 4))
        assertEquals("S00E12", TheaterPlaybackRules.upNextCode(null, 0, 12))
        assertEquals("S10E100", TheaterPlaybackRules.upNextCode(null, 10, 100))
    }

    @Test
    fun noNumbersNoCode() {
        assertNull(TheaterPlaybackRules.upNextCode(null, null, 4))
        assertNull(TheaterPlaybackRules.upNextCode(null, 3, null))
        assertNull(TheaterPlaybackRules.upNextCode(null, null, null))
    }

    // ------------------------------------------------------------------
    // Pausing for the system
    // ------------------------------------------------------------------

    @Test
    fun onlyAPlayerThatWantedToPlayResumes() {
        val playing = TheaterPlaybackRules.SystemHold()
        assertTrue(playing.hold(wantsToPlay = true))
        assertTrue(playing.resumePending)
        assertTrue(playing.release())
        assertFalse(playing.resumePending)

        val paused = TheaterPlaybackRules.SystemHold()
        assertFalse(paused.hold(wantsToPlay = false))
        assertFalse(paused.release())
    }

    @Test
    fun aSecondHoldKeepsThePendingResumeAndAReleaseSpendsIt() {
        val hold = TheaterPlaybackRules.SystemHold()
        assertTrue(hold.hold(wantsToPlay = true))
        // The headset comes off while the activity is already paused: the player is paused by now.
        assertFalse(hold.hold(wantsToPlay = false))
        assertTrue(hold.release())
        assertFalse(hold.release())
    }

    // ------------------------------------------------------------------
    // The TheIntroDB identity
    // ------------------------------------------------------------------

    @Test
    fun aMovieLooksUpItsOwnId() {
        val media = assertNotNull(TheaterPlaybackRules.introDbMedia(handover()))
        assertEquals(603, media.tmdbId)
        assertNull(media.season)
        assertEquals("movie", media.type)
    }

    @Test
    fun anEpisodeLooksUpItsShow() {
        val media = assertNotNull(TheaterPlaybackRules.introDbMedia(episode))
        assertEquals(1399, media.tmdbId)
        assertEquals(1, media.season)
        assertEquals(2, media.episode)
        assertEquals("tv", media.type)
    }

    @Test
    fun anIncompleteOrOutOfRangeIdentityLooksUpNothing() {
        assertNull(TheaterPlaybackRules.introDbMedia(handover(tmdbId = null)))
        assertNull(TheaterPlaybackRules.introDbMedia(handover(tmdbId = 10_000_001)))
        assertNull(TheaterPlaybackRules.introDbMedia(handover(tmdbId = 63056, isEpisode = true, showTmdbId = null, season = 1, episode = 2)))
        assertNull(TheaterPlaybackRules.introDbMedia(handover(tmdbId = 63056, isEpisode = true, showTmdbId = 1399, season = 0, episode = 2)))
        assertNull(TheaterPlaybackRules.introDbMedia(handover(tmdbId = 63056, isEpisode = true, showTmdbId = 1399, season = 1, episode = null)))
    }

    // ------------------------------------------------------------------
    // The dock's lists
    // ------------------------------------------------------------------

    @Test
    fun thePictureAspectUsesThePixelRatioAndIsUnknownWithoutASize() {
        assertEquals(16f / 9f, assertNotNull(TheaterPlaybackRules.aspect(1920, 1080, 1f)), 1e-5f)
        // Anamorphic PAL DVD: 720 × 576 with 16:15 pixels is 4:3.
        assertEquals(4f / 3f, assertNotNull(TheaterPlaybackRules.aspect(720, 576, 16f / 15f)), 1e-5f)
        assertNull(TheaterPlaybackRules.aspect(0, 0, 1f))
        assertNull(TheaterPlaybackRules.aspect(1920, 0, 1f))
        assertNull(TheaterPlaybackRules.aspect(0, 1080, 1f))
    }

    @Test
    fun aChoiceTicksOnlyItsRowUntilThePlayerConfirms() {
        val rows = listOf(
            TheaterTrack("1", "English — 5.1", isSelected = true),
            TheaterTrack("2", "Français — Stereo", isSelected = false),
        )
        assertEquals(listOf(false, true), TheaterPlaybackRules.withSelection(rows, "2").map { it.isSelected })
        assertEquals(listOf(false, false), TheaterPlaybackRules.withSelection(rows, null).map { it.isSelected })
        assertEquals(listOf("1", "2"), TheaterPlaybackRules.withSelection(rows, "2").map { it.id })
    }
}
