package com.babasama.edendale.android.sync

import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.sync.SyncMediaType
import com.babasama.edendale.sync.SyncLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Delete Watch History also clears this device's rows the ledger never
 * held: [SyncRows.identities] turns them into sync keys, and an episode row
 * with no show, season, or episode number, which never synced, drops out.
 */
class SyncRowsTest {
    private val device = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"

    private val movie = WatchProgress(tmdbId = 603, mediaType = WatchMediaType.MOVIE, position = 0.4, watchedSeconds = 3_264.0, lastWatchedEpochMillis = 1_762_000_000_000, durationSeconds = 8_160.0)
    private val episode = WatchProgress(
        tmdbId = 62085, mediaType = WatchMediaType.EPISODE, position = 1.0, lastWatchedEpochMillis = 1_762_000_001_000, isCompleted = true,
        showTmdbId = 1399, seasonNumber = 1, episodeNumber = 2,
    )

    @Test
    fun `rows become sync keys by TMDB identity, stamped 0 by this device`() {
        val records = SyncRows.identities(listOf(movie, episode), device)
        assertEquals(listOf("movie:603", "tv:1399:1:2"), records.map { it.key })
        assertEquals(listOf(SyncMediaType.MOVIE, SyncMediaType.TV), records.map { it.type })
        assertTrue(records.all { it.device == device && it.updateTime == 0L && !it.cleared })
    }

    @Test
    fun `an episode row missing its show, season, or episode never synced and drops out`() {
        val rows = listOf(
            episode.copy(tmdbId = 1, showTmdbId = null),
            episode.copy(tmdbId = 2, seasonNumber = null),
            episode.copy(tmdbId = 3, episodeNumber = null),
            movie,
        )
        assertEquals(listOf("movie:603"), SyncRows.identities(rows, device).map { it.key })
        assertTrue(SyncRows.identities(emptyList(), device).isEmpty())
    }

    @Test
    fun `two rows for one episode give one tombstone`() {
        // The same episode under two TMDB ids (a re-matched file) shares one sync key.
        val rows = listOf(episode, episode.copy(tmdbId = 99999, isCompleted = false, position = 0.5))
        val identities = SyncRows.identities(rows, device)
        assertEquals(2, identities.size)
        val (cleared, keys) = SyncLedger().clearingAllProgress(1_762_000_100_000, device, identities)
        assertEquals(setOf("tv:1399:1:2"), keys)
        assertEquals(1, cleared.progress.size)
    }
}
