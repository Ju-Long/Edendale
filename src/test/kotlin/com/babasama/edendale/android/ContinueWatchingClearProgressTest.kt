package com.babasama.edendale.android

import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Continue Watching card's Clear Progress clears the record a part-watched
 * card resumes from: [progressKey] is that record's storage key, and a
 * next-up card, which is only a suggestion, has none, so it offers nothing.
 * The action is named for what it does: clearing an episode after a finished
 * one leaves its show's Up Next card in place.
 */
class ContinueWatchingClearProgressTest {

    private val matrix = LibraryMovieEntity(
        uri = "content://tree/movies/matrix.mkv", folderUri = "content://tree/movies", fileName = "matrix.mkv", title = "The Matrix",
        year = 1999, tmdbId = 603, posterPath = null, backdropPath = null, overview = null, runtimeMinutes = 136, addedAtEpochMillis = 0L,
    )
    private val show = LibraryShowEntity(key = "anime", name = "Anime", tmdbId = 1, posterPath = null, backdropPath = null, overview = null, firstAirYear = 2020)

    private fun episode(number: Int, tmdbId: Int) = LibraryEpisodeEntity(
        uri = "content://tree/anime/S1E$number.mkv", folderUri = "content://tree/anime", showKey = "anime", fileName = "S1E$number.mkv",
        season = 1, episode = number, tmdbId = tmdbId, title = null, stillPath = null, runtimeMinutes = 24, addedAtEpochMillis = 0L,
    )

    private fun record(tmdbId: Int, type: WatchMediaType, completed: Boolean, lastWatched: Long, number: Int? = null) = WatchProgress(
        tmdbId = tmdbId, mediaType = type, position = if (completed) 1.0 else 0.4, lastWatchedEpochMillis = lastWatched, isCompleted = completed,
        showTmdbId = if (type == WatchMediaType.EPISODE) 1 else null, seasonNumber = number?.let { 1 }, episodeNumber = number,
    )

    @Test
    fun `a part-watched movie or episode clears its own record`() {
        val progress = listOf(
            record(603, WatchMediaType.MOVIE, completed = false, lastWatched = 2_000L),
            record(102, WatchMediaType.EPISODE, completed = false, lastWatched = 1_000L, number = 2),
        )
        val entries = continueWatching(progress, listOf(matrix), listOf(episode(1, 101), episode(2, 102)), listOf(show))
        assertEquals(listOf("movie:603", "episode:102"), entries.map { it.progressKey })
        // The key is the domain's own, so the clear finds the row the card came from.
        assertEquals(progress.map { it.storageKey }, entries.map { it.progressKey })
    }

    @Test
    fun `a next-up card has no record to clear`() {
        val entry = continueWatching(
            progress = listOf(record(101, WatchMediaType.EPISODE, completed = true, lastWatched = 1_000L, number = 1)),
            movies = emptyList(),
            episodes = listOf(episode(1, 101), episode(2, 102)),
            shows = listOf(show),
        ).single()
        assertTrue(entry.isNextUp)
        assertNull(entry.progressKey)
    }

    @Test
    fun `clearing an episode after a finished one leaves Up Next for it, while a first episode or a movie leaves the shelf`() {
        val episodes = listOf(episode(1, 101), episode(2, 102), episode(3, 103))
        val finished = record(101, WatchMediaType.EPISODE, completed = true, lastWatched = 1_000L, number = 1)
        val partWatched = record(102, WatchMediaType.EPISODE, completed = false, lastWatched = 2_000L, number = 2)
        val card = continueWatching(listOf(finished, partWatched), emptyList(), episodes, listOf(show)).single()
        assertEquals("episode:102", card.progressKey)

        // Its record cleared, the show has nothing in progress, so the episode after E01 is up next: E02 again, from the start.
        val after = continueWatching(listOf(finished), emptyList(), episodes, listOf(show)).single()
        assertTrue(after.isNextUp)
        assertEquals(2, after.episode)
        assertEquals(0f, after.fraction)
        assertNull(after.progressKey)

        // With no finished episode before it, or for a movie, nothing takes its place.
        val first = record(101, WatchMediaType.EPISODE, completed = false, lastWatched = 1_000L, number = 1)
        val movie = record(603, WatchMediaType.MOVIE, completed = false, lastWatched = 2_000L)
        assertEquals(listOf("movie:603", "episode:101"), continueWatching(listOf(first, movie), listOf(matrix), episodes, listOf(show)).map { it.progressKey })
        assertEquals(listOf("movie:603"), continueWatching(listOf(movie), listOf(matrix), episodes, listOf(show)).map { it.progressKey })
        assertEquals(listOf("episode:101"), continueWatching(listOf(first), listOf(matrix), episodes, listOf(show)).map { it.progressKey })
    }
}
