package com.babasama.edendale.android

import android.view.KeyEvent
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * J.T1, Apple's `LibrarySectionsTests`: the Watchlist and Downloaded
 * sections extended navigation opens as pages of their own, the Continue
 * Watching page beside the capped shelf, the fallback when a section
 * empties, the heading scrubber's scroll geometry (J.6), and the keyboard
 * shortcuts (J.3).
 */
class LibrarySectionsTest {

    // MARK: - Sections

    @Test
    fun `downloaded sections list only what has content`() {
        assertEquals(emptyList(), DownloadedSection.available(hasResumeItems = false, hasMovies = false, hasShows = false))
        assertEquals(
            listOf(DownloadedSection.CONTINUE_WATCHING, DownloadedSection.MOVIES, DownloadedSection.SHOWS),
            DownloadedSection.available(hasResumeItems = true, hasMovies = true, hasShows = true),
        )
        assertEquals(listOf(DownloadedSection.SHOWS), DownloadedSection.available(hasResumeItems = false, hasMovies = false, hasShows = true))
    }

    @Test
    fun `watchlist sections follow media types`() {
        assertEquals(emptyList(), WatchlistSection.available(emptyList()))
        assertEquals(listOf(WatchlistSection.MOVIES), WatchlistSection.available(listOf(MediaType.MOVIE)))
        assertEquals(
            listOf(WatchlistSection.MOVIES, WatchlistSection.SHOWS),
            WatchlistSection.available(listOf(MediaType.TV, MediaType.MOVIE)),
        )
    }

    // MARK: - Continue Watching (J.2)

    private fun movie(index: Int) = LibraryMovieEntity(
        uri = "content://tree/movies/$index.mkv",
        folderUri = "content://tree/movies",
        fileName = "$index.mkv",
        title = "Movie $index",
        year = null,
        tmdbId = 10_000 + index,
        posterPath = null,
        backdropPath = null,
        overview = null,
        runtimeMinutes = null,
        addedAtEpochMillis = 0L,
    )

    private fun progress(index: Int) = WatchProgress(
        tmdbId = 10_000 + index,
        mediaType = WatchMediaType.MOVIE,
        position = 0.5,
        lastWatchedEpochMillis = 1_000L - index * 60,
        isCompleted = false,
    )

    @Test
    fun `the continue watching limit caps only the shelf`() {
        val movies = (0 until 3).map(::movie)
        val progress = (0 until 3).map(::progress)

        val all = continueWatching(progress, movies, emptyList(), emptyList(), limit = null)
        assertEquals(listOf("Movie 0", "Movie 1", "Movie 2"), all.map { it.title })
        assertEquals(2, continueWatching(progress, movies, emptyList(), emptyList(), limit = 2).size)
        // Titles the audience filter hides are never offered.
        assertTrue(continueWatching(progress, emptyList(), emptyList(), emptyList(), limit = null).isEmpty())
    }

    @Test
    fun `the continue watching page lists more than the shelf holds`() {
        val count = CONTINUE_WATCHING_LIMIT + 3
        val movies = (0 until count).map(::movie)
        val progress = (0 until count).map(::progress)
        assertEquals(count, continueWatching(progress, movies, emptyList(), emptyList(), limit = null).size)
        assertEquals(CONTINUE_WATCHING_LIMIT, continueWatching(progress, movies, emptyList(), emptyList()).size)
    }

    // MARK: - Navigation rows (J.1)

    @Test
    fun `navigation rows map to root tabs`() {
        AppTab.entries.forEach { tab -> assertEquals(tab, NavigationItem.of(tab).tab) }
        // A route that picks a tab opens its whole page.
        assertEquals(NavigationItem.Downloaded(null), NavigationItem.of(AppTab.DOWNLOADED))
        assertEquals(AppTab.DOWNLOADED, NavigationItem.Downloaded(DownloadedSection.MOVIES).tab)
        assertEquals(AppTab.WATCHLIST, NavigationItem.Watchlist(WatchlistSection.SHOWS).tab)
        assertEquals(NavigationItem.Watchlist(), NavigationItem.Watchlist(WatchlistSection.SHOWS).page)
    }

    @Test
    fun `an emptied section row falls back to its page`() {
        val continueWatching = NavigationItem.Downloaded(DownloadedSection.CONTINUE_WATCHING)
        assertEquals(
            NavigationItem.Downloaded(null),
            continueWatching.resolved(watchlistSections = emptyList(), downloadedSections = listOf(DownloadedSection.MOVIES)),
        )
        assertEquals(
            continueWatching,
            continueWatching.resolved(watchlistSections = emptyList(), downloadedSections = listOf(DownloadedSection.CONTINUE_WATCHING)),
        )
        assertEquals(
            NavigationItem.Watchlist(null),
            NavigationItem.Watchlist(WatchlistSection.SHOWS).resolved(listOf(WatchlistSection.MOVIES), emptyList()),
        )
        assertEquals(NavigationItem.Search, NavigationItem.Search.resolved(emptyList(), emptyList()))
        assertEquals(NavigationItem.Downloaded(null), NavigationItem.Downloaded(null).resolved(emptyList(), emptyList()))
    }

    @Test
    fun `a navigation row survives being saved`() {
        val rows = listOf(
            NavigationItem.Movies,
            NavigationItem.Search,
            NavigationItem.Watchlist(),
            NavigationItem.Watchlist(WatchlistSection.SHOWS),
            NavigationItem.Downloaded(),
            NavigationItem.Downloaded(DownloadedSection.CONTINUE_WATCHING),
        )
        rows.forEach { row ->
            val saved = with(NavigationItem.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(row) }
            assertEquals(row, NavigationItem.decode(saved!!))
        }
        // Something saved by another version opens the page, or nothing at all.
        assertEquals(NavigationItem.Downloaded(), NavigationItem.decode("DOWNLOADED/GONE"))
        assertNull(NavigationItem.decode("ELSEWHERE"))
    }

    // MARK: - Heading scrubber (J.6)

    @Test
    fun `shelf metrics mirror scroll geometry`() {
        val metrics = ShelfScrollMetrics.of(contentOffset = 250f, contentWidth = 2000f, containerWidth = 1000f)
        assertEquals(1000f, metrics.range)
        assertEquals(0.25f, metrics.progress)
        assertEquals(0.5f, metrics.visibleFraction)
        assertTrue(metrics.isScrollable)
    }

    @Test
    fun `a fitting shelf has nothing to scrub`() {
        val metrics = ShelfScrollMetrics.of(contentOffset = 0f, contentWidth = 800f, containerWidth = 1000f)
        assertEquals(0f, metrics.progress)
        assertFalse(metrics.isScrollable)
        assertFalse(ShelfScrollMetrics().isScrollable)
    }

    @Test
    fun `a lazy row of same-width items yields its whole width`() {
        // Ten 280 px cards 16 px apart behind 48 px margins in a 1000 px viewport.
        val metrics = ShelfScrollMetrics.ofUniformRow(
            itemCount = 10,
            itemExtent = 280f,
            spacing = 16f,
            beforePadding = 48f,
            afterPadding = 48f,
            viewport = 1000f,
            firstIndex = 2,
            firstOffset = 100f,
        )
        val content = 48f + 10 * 280f + 9 * 16f + 48f
        assertEquals(content - 1000f, metrics.range)
        assertEquals(2 * 296f + 100f, metrics.offset)
        assertEquals(1000f / content, metrics.visibleFraction)
        assertEquals(ShelfScrollMetrics(), ShelfScrollMetrics.ofUniformRow(0, 0f, 0f, 0f, 0f, 1000f, 0, 0f))
    }

    @Test
    fun `scrubbing lands on the item under that fraction`() {
        val metrics = ShelfScrollMetrics(offset = 0f, range = 1000f, visibleFraction = 0.5f)
        assertEquals(0 to 0, metrics.scrollTarget(0f, itemExtent = 280f, spacing = 20f))
        // Halfway is 500 px: the second card (300 px stride) and 200 px into it.
        assertEquals(1 to 200, metrics.scrollTarget(0.5f, itemExtent = 280f, spacing = 20f))
        assertEquals(3 to 100, metrics.scrollTarget(1f, itemExtent = 280f, spacing = 20f))
        assertEquals(3 to 100, metrics.scrollTarget(1.5f, itemExtent = 280f, spacing = 20f))
    }

    // MARK: - Keyboard shortcuts (J.3)

    private fun library(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyboardShortcuts.libraryCommand(keyCode, ctrl = ctrl, alt = alt, shift = shift, meta = false)

    @Test
    fun `library shortcuts`() {
        assertEquals(LibraryCommand.TOGGLE_NAVIGATION, library(KeyEvent.KEYCODE_B, ctrl = true))
        assertEquals(LibraryCommand.ADD_FOLDER, library(KeyEvent.KEYCODE_N, ctrl = true))
        assertEquals(LibraryCommand.LINK_SOURCE, library(KeyEvent.KEYCODE_N, ctrl = true, alt = true))
        assertEquals(LibraryCommand.RESCAN, library(KeyEvent.KEYCODE_R, ctrl = true))
        assertEquals(LibraryCommand.RESCAN, library(KeyEvent.KEYCODE_F5))
        // Plain letters type; other modifier sets are someone else's.
        assertNull(library(KeyEvent.KEYCODE_N))
        assertNull(library(KeyEvent.KEYCODE_R, ctrl = true, shift = true))
        assertNull(library(KeyEvent.KEYCODE_B, ctrl = true, alt = true))
        assertNull(library(KeyEvent.KEYCODE_F5, ctrl = true))
        assertNull(KeyboardShortcuts.libraryCommand(KeyEvent.KEYCODE_N, ctrl = true, alt = false, shift = false, meta = true))
    }

    @Test
    fun `player shortcuts`() {
        assertEquals(PlayerKeyCommand.PLAY_PAUSE, KeyboardShortcuts.playerCommand(KeyEvent.KEYCODE_SPACE, hasModifiers = false))
        assertEquals(PlayerKeyCommand.SKIP_BACK, KeyboardShortcuts.playerCommand(KeyEvent.KEYCODE_DPAD_LEFT, hasModifiers = false))
        assertEquals(PlayerKeyCommand.SKIP_FORWARD, KeyboardShortcuts.playerCommand(KeyEvent.KEYCODE_DPAD_RIGHT, hasModifiers = false))
        assertEquals(PlayerKeyCommand.CLOSE, KeyboardShortcuts.playerCommand(KeyEvent.KEYCODE_ESCAPE, hasModifiers = false))
        assertNull(KeyboardShortcuts.playerCommand(KeyEvent.KEYCODE_SPACE, hasModifiers = true))
        assertNull(KeyboardShortcuts.playerCommand(KeyEvent.KEYCODE_DPAD_UP, hasModifiers = false))
    }
}
