package com.babasama.edendale.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I.3: what Edendale publishes to the TV home screen's Watch Next row, and
 * how that reconciles with the rows the TV provider already holds.
 */
class WatchNextTest {

    private fun movie(tmdbId: Int = 603, lastWatched: Long = 1_000L, backdrop: String? = "https://image/wide.jpg") = ContinueEntry(
        uri = "content://tree/movies/$tmdbId.mkv",
        title = "The Matrix",
        subtitle = "1999 · 2h 16m",
        posterUrl = "https://image/poster.jpg",
        fraction = 0.25f,
        tmdbId = tmdbId,
        isEpisode = false,
        backdropUrl = backdrop,
        positionMillis = 1_800_000L,
        durationMillis = 7_200_000L,
        lastWatchedEpochMillis = lastWatched,
    )

    private fun episode(
        tmdbId: Int,
        number: Int,
        lastWatched: Long,
        nextUp: Boolean = false,
        show: Int? = 95396,
    ) = ContinueEntry(
        uri = "content://tree/shows/s01e0$number.mkv",
        title = "Severance",
        subtitle = "S01E0$number",
        posterUrl = "https://image/severance.jpg",
        fraction = if (nextUp) 0f else 0.5f,
        tmdbId = tmdbId,
        isEpisode = true,
        showTmdbId = show,
        season = 1,
        episode = number,
        isNextUp = nextUp,
        episodeTitle = "Episode $number",
        backdropUrl = "https://image/still-$number.jpg",
        positionMillis = if (nextUp) null else 1_000_000L,
        durationMillis = 3_000_000L,
        lastWatchedEpochMillis = lastWatched,
    )

    // MARK: - Programs

    @Test
    fun aTitleInProgressIsAContinueProgram() {
        val program = WatchNext.programs(listOf(movie())).single()

        assertEquals("edendale:movie:603", program.key)
        assertFalse(program.isNext)
        assertFalse(program.isEpisode)
        assertEquals("The Matrix", program.title)
        assertEquals("https://image/wide.jpg", program.artworkUrl)
        assertTrue(program.isWideArtwork)
        assertEquals(1_800_000L, program.positionMillis)
        assertEquals(7_200_000L, program.durationMillis)
        assertEquals(1_000L, program.lastEngagementMillis)
        // The row opens what the shelf's card opens.
        assertEquals(WatchNextPlay("content://tree/movies/603.mkv", "The Matrix", 603, false, null, null, null), program.play)
    }

    @Test
    fun aShowsNextEpisodeIsANextProgramWithoutAPosition() {
        val program = WatchNext.programs(listOf(episode(3002, 2, 5_000L, nextUp = true))).single()

        assertEquals("edendale:show:95396", program.key)
        assertTrue(program.isNext)
        assertTrue(program.isEpisode)
        assertEquals("Episode 2", program.episodeTitle)
        assertEquals(1, program.season)
        assertEquals(2, program.episode)
        assertNull(program.positionMillis)
        assertEquals(WatchNextPlay("content://tree/shows/s01e02.mkv", "Severance", 3002, true, 95396, 1, 2), program.play)
    }

    @Test
    fun withoutWideArtworkThePosterStandsIn() {
        val program = WatchNext.program(movie(backdrop = null))
        assertEquals("https://image/poster.jpg", program.artworkUrl)
        assertFalse(program.isWideArtwork)
    }

    @Test
    fun aShowGetsOneRowForItsMostRecentEpisode() {
        // Continue Watching is newest first.
        val programs = WatchNext.programs(listOf(episode(3005, 5, 2_000L), movie(), episode(3002, 2, 500L)))

        assertEquals(listOf("edendale:show:95396", "edendale:movie:603"), programs.map { it.key })
        assertEquals(5, programs.first().episode)
        // An episode whose show has no id stands alone.
        assertEquals("edendale:episode:3009", WatchNext.key(episode(3009, 9, 1L, show = null)))
    }

    @Test
    fun theRowHoldsNoMoreThanTheShelf() {
        val programs = WatchNext.programs((1..20).map { movie(tmdbId = it, lastWatched = 100L - it) })
        assertEquals(CONTINUE_WATCHING_LIMIT, programs.size)
        assertEquals("edendale:movie:1", programs.first().key)
    }

    @Test
    fun theSignatureFollowsTheContentAndIsStable() {
        val program = WatchNext.program(movie())
        assertEquals(program.signature, WatchNext.program(movie()).signature)
        assertNotEquals(program.signature, program.copy(positionMillis = 1_900_000L).signature)
        assertNotEquals(program.signature, program.copy(title = "The Matrix Reloaded").signature)
    }

    // MARK: - Reconciling with the provider

    private fun row(
        rowId: Long,
        program: WatchNextProgram?,
        key: String? = program?.key,
        browsable: Boolean = true,
        engaged: Long = program?.lastEngagementMillis ?: 0L,
        signature: Long? = program?.signature,
    ) = PublishedWatchNextProgram(rowId, key, browsable, engaged, signature)

    @Test
    fun aNewTitleIsInserted() {
        val matrix = WatchNext.program(movie())
        assertEquals(WatchNextChanges(inserts = listOf(matrix)), WatchNext.changes(listOf(matrix), emptyList()))
    }

    @Test
    fun anUnchangedRowIsLeftAlone() {
        val matrix = WatchNext.program(movie())
        assertEquals(WatchNextChanges(), WatchNext.changes(listOf(matrix), listOf(row(7, matrix))))
    }

    @Test
    fun aChangedRowIsUpdatedInPlace() {
        val before = WatchNext.program(movie())
        val after = before.copy(positionMillis = 2_400_000L, lastEngagementMillis = 2_000L)
        assertEquals(WatchNextChanges(updates = listOf(7L to after)), WatchNext.changes(listOf(after), listOf(row(7, before))))
    }

    @Test
    fun aTitleNoLongerWantedIsDeleted() {
        // Finished, deleted, hidden by the audience filter, or the setting turned off.
        val matrix = WatchNext.program(movie())
        assertEquals(WatchNextChanges(deletes = listOf(7L)), WatchNext.changes(emptyList(), listOf(row(7, matrix))))
    }

    @Test
    fun rowsEdendaleDidNotWriteAreLeftAlone() {
        val changes = WatchNext.changes(
            emptyList(),
            listOf(row(1, null, key = null), row(2, null, key = "someone-else:42")),
        )
        assertEquals(WatchNextChanges(), changes)
    }

    @Test
    fun aSecondRowForOneTitleIsDeletedKeepingTheMostRecent() {
        val matrix = WatchNext.program(movie(lastWatched = 3_000L))
        val changes = WatchNext.changes(
            listOf(matrix),
            listOf(row(1, matrix, engaged = 1_000L), row(2, matrix)),
        )
        assertEquals(WatchNextChanges(deletes = listOf(1L)), changes)
    }

    @Test
    fun aRowTheViewerRemovedStaysRemovedUntilTheyWatchAgain() {
        val removed = WatchNext.program(movie(lastWatched = 1_000L))
        val hidden = row(7, removed, browsable = false)

        // The same program, or a change from the library alone: nothing comes back.
        assertEquals(WatchNextChanges(), WatchNext.changes(listOf(removed), listOf(hidden)))
        assertEquals(WatchNextChanges(), WatchNext.changes(listOf(removed.copy(title = "Matrix")), listOf(hidden)))

        // Watching it again: the old row goes and a new one takes its place.
        val watched = removed.copy(lastEngagementMillis = 2_000L, positionMillis = 2_400_000L)
        assertEquals(
            WatchNextChanges(inserts = listOf(watched), deletes = listOf(7L)),
            WatchNext.changes(listOf(watched), listOf(hidden)),
        )
    }
}
