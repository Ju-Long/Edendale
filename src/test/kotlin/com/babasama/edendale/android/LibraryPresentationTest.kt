package com.babasama.edendale.android

import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Downloaded/Detail/Search screens' pure logic. Everything here runs on a
 * bare JVM: no emulator, no Room, no network. Domain behaviour has its own
 * Android unit tests and is not re-tested here.
 */
class LibraryPresentationTest {

    // MARK: - Fixtures

    private fun movie(
        uri: String = "content://tree/movies/matrix.mkv",
        title: String = "The Matrix",
        year: Int? = 1999,
        tmdbId: Int? = 603,
        runtime: Int? = 136,
        poster: String? = "/matrix.jpg",
        folderUri: String? = "content://tree/movies",
    ) = LibraryMovieEntity(
        uri = uri,
        folderUri = folderUri,
        fileName = uri.substringAfterLast('/'),
        title = title,
        year = year,
        tmdbId = tmdbId,
        posterPath = poster,
        backdropPath = null,
        overview = null,
        runtimeMinutes = runtime,
        addedAtEpochMillis = 0L,
    )

    private fun show(
        key: String = "severance",
        name: String = "Severance",
        tmdbId: Int? = 95396,
        poster: String? = "/severance.jpg",
        firstAirYear: Int? = 2022,
    ) = LibraryShowEntity(
        key = key,
        name = name,
        tmdbId = tmdbId,
        posterPath = poster,
        backdropPath = null,
        overview = null,
        firstAirYear = firstAirYear,
    )

    private fun episode(
        uri: String = "content://tree/shows/s01e02.mkv",
        showKey: String = "severance",
        season: Int = 1,
        number: Int = 2,
        tmdbId: Int? = 3000,
        title: String? = "Half Loop",
        runtime: Int? = 47,
    ) = LibraryEpisodeEntity(
        uri = uri,
        folderUri = "content://tree/shows",
        showKey = showKey,
        fileName = uri.substringAfterLast('/'),
        season = season,
        episode = number,
        tmdbId = tmdbId,
        title = title,
        stillPath = null,
        runtimeMinutes = runtime,
        addedAtEpochMillis = 0L,
    )

    private fun progress(
        tmdbId: Int,
        type: WatchMediaType = WatchMediaType.MOVIE,
        position: Double = 0.4,
        completed: Boolean = false,
        lastWatched: Long = 1_000L,
    ) = WatchProgress(
        tmdbId = tmdbId,
        mediaType = type,
        position = position,
        lastWatchedEpochMillis = lastWatched,
        isCompleted = completed,
    )

    // MARK: - Watch-progress lookup

    @Test
    fun `progress is found by the domain storage key`() {
        val byKey = listOf(
            progress(603),
            progress(3000, WatchMediaType.EPISODE),
        ).byStorageKey()

        assertEquals(setOf("movie:603", "episode:3000"), byKey.keys)
        assertEquals(603, byKey.forMovie(603)?.tmdbId)
        assertEquals(3000, byKey.forEpisode(3000)?.tmdbId)
    }

    @Test
    fun `an unenriched row has no progress to find`() {
        val byKey = listOf(progress(603)).byStorageKey()

        // A file TMDB has not matched yet carries no id, so there is nothing to
        // key on — and it must not accidentally borrow another row's progress.
        assertNull(byKey.forMovie(null))
        assertNull(byKey.forEpisode(null))
        assertNull(byKey.forMovie(604))
        // Movie and episode ids live in separate namespaces.
        assertNull(byKey.forEpisode(603))
    }

    @Test
    fun `only part-watched titles draw a progress hairline`() {
        assertEquals(0.4f, progress(603, position = 0.4).partialFraction())
        assertNull(progress(603, position = 0.9, completed = true).partialFraction())
        assertNull(progress(603, position = 0.0).partialFraction())
        assertNull(null.partialFraction())
        // Position is clamped by the domain model before it reaches the bar.
        assertEquals(1f, progress(603, position = 4.0).partialFraction())
    }

    // MARK: - Continue Watching

    @Test
    fun `continue watching lists the most recent first`() {
        val entries = continueWatching(
            progress = listOf(
                progress(603, lastWatched = 100L),
                progress(11, lastWatched = 300L),
                progress(22, lastWatched = 200L),
            ),
            movies = listOf(
                movie(uri = "a", title = "The Matrix", tmdbId = 603),
                movie(uri = "b", title = "Star Wars", tmdbId = 11),
                movie(uri = "c", title = "Alien", tmdbId = 22),
            ),
            episodes = emptyList(),
            shows = emptyList(),
        )

        assertEquals(listOf("Star Wars", "Alien", "The Matrix"), entries.map { it.title })
    }

    @Test
    fun `finished and unmatched records never reach the shelf`() {
        val entries = continueWatching(
            progress = listOf(
                progress(603, position = 0.4),
                progress(11, completed = true, position = 1.0),
                progress(999), // no imported file behind it
                progress(22, position = 0.0), // opened but never watched
            ),
            movies = listOf(
                movie(uri = "a", tmdbId = 603),
                movie(uri = "b", tmdbId = 11),
                movie(uri = "c", tmdbId = 22),
            ),
            episodes = emptyList(),
            shows = emptyList(),
        )

        assertEquals(1, entries.size)
        assertEquals(603, entries.single().tmdbId)
    }

    @Test
    fun `an episode borrows its show's poster and identity`() {
        val entry = continueWatching(
            progress = listOf(progress(3000, WatchMediaType.EPISODE, position = 0.25)),
            movies = emptyList(),
            episodes = listOf(episode(tmdbId = 3000, season = 1, number = 2, title = "Half Loop")),
            shows = listOf(show(tmdbId = 95396)),
        ).single()

        assertEquals("Severance", entry.title)
        assertEquals("S01E02 · Half Loop", entry.subtitle)
        assertEquals("https://image.tmdb.org/t/p/w342/severance.jpg", entry.posterUrl)
        assertTrue(entry.isEpisode)
        assertEquals(95396, entry.showTmdbId)
        assertEquals(1, entry.season)
        assertEquals(2, entry.episode)
        assertEquals(0.25f, entry.fraction)
    }

    @Test
    fun `an episode whose show is unknown falls back to its file name`() {
        val entry = continueWatching(
            progress = listOf(progress(3000, WatchMediaType.EPISODE)),
            movies = emptyList(),
            episodes = listOf(episode(uri = "content://x/orphan.mkv", showKey = "gone", title = null)),
            shows = emptyList(),
        ).single()

        assertEquals("orphan.mkv", entry.title)
        assertEquals("S01E02", entry.subtitle)
        assertNull(entry.posterUrl)
        assertNull(entry.showTmdbId)
    }

    @Test
    fun `the shelf stays a shelf`() {
        val count = CONTINUE_WATCHING_LIMIT + 5
        val entries = continueWatching(
            progress = (1..count).map { progress(it, lastWatched = it.toLong()) },
            movies = (1..count).map { movie(uri = "file-$it", title = "Film $it", tmdbId = it) },
            episodes = emptyList(),
            shows = emptyList(),
        )

        assertEquals(CONTINUE_WATCHING_LIMIT, entries.size)
        // Newest first, so the tail is what gets dropped.
        assertEquals("Film $count", entries.first().title)
    }

    @Test
    fun `no watch history means no shelf`() {
        assertTrue(
            continueWatching(emptyList(), listOf(movie()), listOf(episode()), listOf(show())).isEmpty(),
        )
    }

    // MARK: - Grid layout

    @Test
    fun `grid cells tile the content width exactly`() {
        val (columns, cell) = libraryGridMetrics(
            availableWidth = 1000.dp,
            edgeMargin = 20.dp,
            spacing = 14.dp,
            preferredWidth = 150.dp,
        )

        val content = 1000.dp - 40.dp
        val used = cell * columns + 14.dp * (columns - 1)
        assertEquals(content.value, used.value, 0.01f)
    }

    @Test
    fun `a wider window fits more columns`() {
        val phone = libraryGridMetrics(411.dp, 20.dp, 14.dp, 150.dp).first
        val tablet = libraryGridMetrics(1280.dp, 48.dp, 14.dp, 170.dp).first
        assertTrue(tablet > phone, "expected the tablet grid to be wider: $tablet vs $phone")
    }

    @Test
    fun `a narrow window still gets two columns`() {
        // One poster would be wider than the window; a single-column "grid"
        // reads as a list, so two is the floor.
        val (columns, cell) = libraryGridMetrics(200.dp, 20.dp, 14.dp, 150.dp)
        assertEquals(2, columns)
        assertTrue(cell.value > 0f)
    }

    // MARK: - Formatting

    @Test
    fun `subtitles use whichever halves are known`() {
        assertEquals("1999 · 136 min", mediaSubtitle(1999, 136))
        assertEquals("1999", mediaSubtitle(1999, null))
        assertEquals("136 min", mediaSubtitle(null, 136))
        assertNull(mediaSubtitle(null, null))
        // A runtime TMDB reported as zero is missing data, not a zero-minute film.
        assertEquals("1999", mediaSubtitle(1999, 0))
        assertNull(formatRuntime(0))
        assertNull(formatRuntime(-5))
    }

    @Test
    fun `source paths render without their SAF wrapper`() {
        assertEquals(
            "primary:Movies/Archive",
            sourceDisplayPath(
                "content://com.android.externalstorage.documents/tree/primary%3AMovies%2FArchive",
            ),
        )
        // Network sources are already readable, and carry no credentials.
        assertEquals("smb://192.168.1.10/media/", sourceDisplayPath("smb://192.168.1.10/media/"))
        // Anything that is not a tree URI is shown as-is rather than truncated.
        assertEquals("file:///storage/movies", sourceDisplayPath("file:///storage/movies"))
    }

    // MARK: - Critical Consensus

    @Test
    fun `a movie scorecard carries the score and the vote count`() {
        val tiles = consensusTiles(
            MediaDetail(
                ref = MediaRef(603, MediaType.MOVIE),
                title = "The Matrix",
                score = 8.217,
                voteCount = 24_800,
            ),
        )

        assertEquals(listOf("TMDB", "Votes"), tiles.map { it.source })
        assertEquals("8.2", tiles[0].value)
        assertEquals("/10", tiles[0].suffix)
        assertEquals("24800", tiles[1].value)
        assertNull(tiles[1].suffix)
    }

    @Test
    fun `a show scorecard adds its catalogue`() {
        val tiles = consensusTiles(
            MediaDetail(
                ref = MediaRef(95396, MediaType.TV),
                title = "Severance",
                score = 8.4,
                voteCount = 3_100,
                seasonCount = 2,
                episodeCount = 19,
            ),
        )

        assertEquals(listOf("TMDB", "Votes", "Seasons", "Episodes"), tiles.map { it.source })
        assertEquals(listOf("8.4", "3100", "2", "19"), tiles.map { it.value })
    }

    @Test
    fun `unrated titles show no scorecard at all`() {
        val tiles = consensusTiles(
            MediaDetail(
                ref = MediaRef(1, MediaType.MOVIE),
                title = "Unreleased",
                score = 0.0,
                voteCount = 0,
                seasonCount = 0,
            ),
        )
        assertTrue(tiles.isEmpty())
        assertTrue(consensusTiles(MediaDetail(MediaRef(1, MediaType.MOVIE), "Bare")).isEmpty())
    }

    // MARK: - Star rating

    @Test
    fun `tapping a star cycles full then half then away`() {
        // Star 3 from nothing: 6.0 → 5.0 → back to the two full stars before it.
        assertEquals(6.0, nextStarRating(index = 3, current = 0.0))
        assertEquals(5.0, nextStarRating(index = 3, current = 6.0))
        assertEquals(4.0, nextStarRating(index = 3, current = 5.0))
    }

    @Test
    fun `the first star clears the rating`() {
        assertEquals(2.0, nextStarRating(index = 1, current = 0.0))
        assertEquals(1.0, nextStarRating(index = 1, current = 2.0))
        // Nothing before star 1 to fall back to, so the rating is removed.
        assertNull(nextStarRating(index = 1, current = 1.0))
    }

    @Test
    fun `tapping a different star jumps straight to it`() {
        assertEquals(10.0, nextStarRating(index = 5, current = 4.0))
        assertEquals(2.0, nextStarRating(index = 1, current = 9.0))
    }

    // MARK: - Search: local matches

    @Test
    fun `local matching is a case-insensitive contains`() {
        val matches = localMatches(
            term = "mat",
            scope = SearchScope.ALL,
            hasActivePerson = false,
            // Lower-case query against a mixed-case title, and upper-case
            // against a lower-case one, so the comparison is pinned both ways.
            movies = listOf(movie(title = "The Matrix"), movie(uri = "b", title = "Alien")),
            shows = listOf(show(name = "MATLOCK", key = "matlock"), show(name = "Severance", key = "sev")),
        )

        assertEquals(listOf("The Matrix"), matches.movies.map { it.title })
        assertEquals(listOf("MATLOCK"), matches.shows.map { it.name })
        assertFalse(matches.isEmpty)
    }

    @Test
    fun `a keyword scope narrows which half is searched`() {
        val movies = listOf(movie(title = "Severance Package"))
        val shows = listOf(show(name = "Severance"))

        val moviesOnly = localMatches("severance", SearchScope.MOVIES, false, movies, shows)
        assertEquals(1, moviesOnly.movies.size)
        assertTrue(moviesOnly.shows.isEmpty())

        val showsOnly = localMatches("severance", SearchScope.SHOWS, false, movies, shows)
        assertTrue(showsOnly.movies.isEmpty())
        assertEquals(1, showsOnly.shows.size)
    }

    @Test
    fun `filmography and empty queries suppress the library section`() {
        val movies = listOf(movie(title = "The Matrix"))
        val shows = listOf(show())

        // A person filter and a people scope are both TMDB-only views.
        assertTrue(localMatches("matrix", SearchScope.ALL, true, movies, shows).isEmpty)
        assertTrue(localMatches("matrix", SearchScope.PEOPLE, false, movies, shows).isEmpty)
        // Nothing typed yet, or only whitespace.
        assertTrue(localMatches("", SearchScope.ALL, false, movies, shows).isEmpty)
        assertTrue(localMatches("   ", SearchScope.ALL, false, movies, shows).isEmpty)
    }
}
