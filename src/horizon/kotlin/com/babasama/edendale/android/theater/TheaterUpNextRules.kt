package com.babasama.edendale.android.theater

import com.babasama.edendale.android.player.EpisodeCandidate
import com.babasama.edendale.android.player.EpisodeProgression
import com.babasama.edendale.android.player.PlayerPlaylist
import com.babasama.edendale.android.player.PlaylistEntry
import com.babasama.edendale.android.xr.TheaterPlaybackRules
import com.babasama.edendale.android.xr.TheaterUpNext

/**
 * The theater's Up Next (HORIZON.md G.15): the card android's theater shows
 * in an episode's last 30 seconds, but pressing it plays the next episode on
 * the same screen, in the same room and seat (the owner's choice for the
 * Quest, 2026-11-13; Android XR's N-D7 returns to the 2D player instead).
 * The list is the 2D player's, filtered by the parental policy as the player
 * filters it, so a restricted episode is never offered.
 */
internal object TheaterUpNextRules {

    /**
     * The next episode to offer at [positionMillis] of [durationMillis]: the
     * 2D player's rule with Loop off (`EpisodeProgression.upcomingEpisode`),
     * for an episode in an episode list; null otherwise.
     */
    fun upcoming(
        playlist: PlayerPlaylist?,
        currentUri: String,
        isEpisode: Boolean,
        positionMillis: Long,
        durationMillis: Long,
    ): PlaylistEntry? {
        if (!isEpisode || durationMillis <= 0L) return null
        val entries = playlist?.takeIf { it.isEpisodeList }?.entries ?: return null
        val current = entries.firstOrNull { it.uri == currentUri } ?: return null
        val season = current.season ?: return null
        val episode = current.episode ?: return null
        val candidates = entries.mapNotNull { entry ->
            val s = entry.season ?: return@mapNotNull null
            val e = entry.episode ?: return@mapNotNull null
            EpisodeCandidate(id = entry.uri, season = s, episode = e, title = entry.title)
        }
        val next = EpisodeProgression.upcomingEpisode(
            timeMillis = positionMillis,
            durationMillis = durationMillis,
            loopEnabled = false,
            current = EpisodeCandidate(id = current.uri, season = season, episode = episode, title = current.title),
            episodes = candidates,
        ) ?: return null
        return entries.firstOrNull { it.uri == next.id }
    }

    /** The card: android's theater card (its code, else one made from the numbers; the title; the 16:9 artwork path). */
    fun card(entry: PlaylistEntry): TheaterUpNext = TheaterUpNext(
        code = TheaterPlaybackRules.upNextCode(entry.detail, entry.season, entry.episode),
        title = entry.title,
        artworkPath = entry.artworkPath,
    )

    /**
     * The handover for [entry]: from [resumeMillis] (its unfinished progress,
     * as the 2D player resumes it; 0 for a fresh episode), playing, at the
     * viewer's speed, and with the audio and subtitle languages they had on,
     * so the next episode keeps them where its file has them. Track ids are
     * left out: they name tracks in the previous file.
     */
    fun handover(
        entry: PlaylistEntry,
        resumeMillis: Long,
        audioLanguage: String?,
        subtitlesOff: Boolean,
        textLanguage: String?,
        speed: Float,
    ): TheaterHandover = TheaterHandover(
        uri = entry.uri,
        title = entry.title,
        subtitle = entry.detail,
        positionMillis = resumeMillis.coerceAtLeast(0L),
        playWhenReady = true,
        tmdbId = entry.tmdbId?.takeIf { it > 0 },
        isEpisode = entry.isEpisode,
        showTmdbId = entry.showTmdbId?.takeIf { it > 0 },
        season = entry.season?.takeIf { it >= 0 },
        episode = entry.episode?.takeIf { it > 0 },
        audioTrackId = null,
        audioLanguage = audioLanguage,
        subtitlesOff = subtitlesOff,
        textTrackId = null,
        textLanguage = textLanguage.takeUnless { subtitlesOff },
        speed = speed.takeIf { it > 0f } ?: 1f,
    )

    /** The 2D player's resume point for a stored progress row, in milliseconds: its watched seconds while unfinished and inside the resume window. */
    fun resumeMillis(watchedSeconds: Double?, resumeFraction: Double?): Long =
        if (resumeFraction == null || watchedSeconds == null || !watchedSeconds.isFinite()) 0L else (watchedSeconds * 1000.0).toLong().coerceAtLeast(0L)
}
