package com.babasama.edendale.android.theater

import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HORIZON.md G.15: the theater's own parental check and the player's tickets. */
class TheaterAdmissionTest {

    private val episode = handover(uri = "smb://nas/show/s01e02.mkv", tmdbId = 501, isEpisode = true, showTmdbId = 77)
    private val movie = handover(uri = "content://media/1", tmdbId = 603, isEpisode = false, showTmdbId = null)

    @AfterTest
    fun forgetTickets() = TheaterTickets.clear()

    @Test
    fun aTicketOrNoRuleInForcePlaysAtOnce() {
        assertEquals(TheaterAdmission.FirstLook.PLAY, TheaterAdmission.firstLook(ticketRedeemed = true, controlsReady = false, policyActive = true))
        assertEquals(TheaterAdmission.FirstLook.PLAY, TheaterAdmission.firstLook(ticketRedeemed = false, controlsReady = true, policyActive = false))
    }

    @Test
    fun anythingElseIsChecked() {
        assertEquals(TheaterAdmission.FirstLook.CHECK, TheaterAdmission.firstLook(ticketRedeemed = false, controlsReady = true, policyActive = true))
        // Controls still reading their store: fail closed, as the player does.
        assertEquals(TheaterAdmission.FirstLook.CHECK, TheaterAdmission.firstLook(ticketRedeemed = false, controlsReady = false, policyActive = false))
    }

    @Test
    fun anEpisodeIsJudgedByItsShowAndAMovieByItself() {
        assertEquals(MediaRef(77, MediaType.TV), TheaterAdmission.ref(episode))
        assertEquals(MediaRef(603, MediaType.MOVIE), TheaterAdmission.ref(movie))
        assertNull(TheaterAdmission.ref(movie.copy(tmdbId = null)))
        assertNull(TheaterAdmission.ref(episode.copy(showTmdbId = null)))
    }

    @Test
    fun whileARuleIsInForceOnlyAnAllowedTitlePlays() {
        val allowed = MediaRef(603, MediaType.MOVIE)
        assertTrue(TheaterAdmission.permits(policyActive = true, ref = allowed) { it == allowed })
        assertFalse(TheaterAdmission.permits(policyActive = true, ref = MediaRef(77, MediaType.TV)) { it == allowed })
        // A file without a TMDB id has no title to judge (O-D8).
        assertFalse(TheaterAdmission.permits(policyActive = true, ref = null) { true })
        assertTrue(TheaterAdmission.permits(policyActive = false, ref = null) { false })
    }

    @Test
    fun aTicketWorksOnceForItsOwnTitle() {
        val ticket = TheaterTickets.issue(episode)
        assertTrue(TheaterTickets.redeem(ticket, episode))
        assertFalse(TheaterTickets.redeem(ticket, episode), "spent")
    }

    @Test
    fun aTicketForAnotherTitleIsRefusedAndSpent() {
        val ticket = TheaterTickets.issue(movie)
        assertFalse(TheaterTickets.redeem(ticket, movie.copy(tmdbId = 604)))
        assertFalse(TheaterTickets.redeem(ticket, movie))
        // The same file under another show is another title too.
        val other = TheaterTickets.issue(episode)
        assertFalse(TheaterTickets.redeem(other, episode.copy(showTmdbId = 78)))
    }

    @Test
    fun noTicketOrAnUnknownOneIsRefused() {
        assertFalse(TheaterTickets.redeem(null, movie))
        assertFalse(TheaterTickets.redeem("not-a-ticket", movie))
    }

    /** Track choices, speed, and position aren't the title: a ticket survives them. */
    @Test
    fun theTicketBindsTheTitleNotThePlace() {
        val ticket = TheaterTickets.issue(episode)
        assertTrue(TheaterTickets.redeem(ticket, episode.copy(positionMillis = 99_000, speed = 1.5f, subtitlesOff = true)))
    }

    @Test
    fun unredeemedTicketsDontPileUp() {
        val first = TheaterTickets.issue(movie)
        repeat(8) { TheaterTickets.issue(episode) }
        assertFalse(TheaterTickets.redeem(first, movie), "the oldest went")
        assertNotEquals(TheaterTickets.issue(movie), TheaterTickets.issue(movie))
    }

    @Test
    fun theHandoverReachesAndroidsPlaybackWhole() {
        val playback = episode.copy(audioTrackId = "1:2", audioLanguage = "ja", textTrackId = "3:0", textLanguage = "en", speed = 1.25f).toPlayback()
        assertEquals("smb://nas/show/s01e02.mkv", playback.uri)
        assertEquals("Episode", playback.title)
        assertEquals("S01E02", playback.subtitle)
        assertEquals(12_345L, playback.positionMillis)
        assertEquals(false, playback.playWhenReady)
        assertEquals(501, playback.tmdbId)
        assertEquals(true, playback.isEpisode)
        assertEquals(77, playback.showTmdbId)
        assertEquals(1, playback.season)
        assertEquals(2, playback.episode)
        assertEquals("1:2", playback.audioTrackId)
        assertEquals("ja", playback.audioLanguage)
        assertEquals(false, playback.subtitlesOff)
        assertEquals("3:0", playback.textTrackId)
        assertEquals("en", playback.textLanguage)
        assertEquals(1.25f, playback.speed)
    }

    private fun handover(uri: String, tmdbId: Int?, isEpisode: Boolean, showTmdbId: Int?) = TheaterHandover(
        uri = uri,
        title = "Episode",
        subtitle = "S01E02",
        positionMillis = 12_345L,
        playWhenReady = false,
        tmdbId = tmdbId,
        isEpisode = isEpisode,
        showTmdbId = showTmdbId,
        season = 1,
        episode = 2,
        audioTrackId = null,
        audioLanguage = null,
        subtitlesOff = false,
        textTrackId = null,
        textLanguage = null,
        speed = 1f,
    )
}
