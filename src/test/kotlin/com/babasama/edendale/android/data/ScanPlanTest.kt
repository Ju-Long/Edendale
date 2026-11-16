package com.babasama.edendale.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What one scan stores (SourceScanRules.plan), worked out before anything is
 * written so it commits in one transaction, and worked out again from what
 * the library holds when a scan is done over after the app was killed.
 */
class ScanPlanTest {

    private val share = "smb://nas/media/"
    private val now = 1_000L

    private fun file(name: String) = "$share$name" to name

    private fun plan(
        files: List<Pair<String, String>>,
        complete: Boolean = true,
        storedMovies: List<String> = emptyList(),
        storedEpisodes: List<String> = emptyList(),
        known: Set<String> = (storedMovies + storedEpisodes).toSet(),
        knownShows: Set<String> = emptySet(),
    ) = SourceScanRules.plan(files, complete, share, storedMovies, storedEpisodes, known, knownShows, now)

    @Test
    fun `a complete listing plans what went and what arrived`() {
        val heat = file("Heat (1995).mkv")
        val alien = file("Alien (1979).mkv")
        val severance = file("Severance.S01E01.mkv")
        val ronin = "${share}Ronin (1998).mkv"
        val finale = "${share}Severance.S01E09.mkv"
        val plan = plan(
            files = listOf(heat, alien, severance),
            storedMovies = listOf(heat.first, ronin),
            storedEpisodes = listOf(finale),
            knownShows = setOf("severance"),
        )
        assertEquals(listOf(ronin), plan.staleMovieUris)
        assertEquals(listOf(finale), plan.staleEpisodeUris)
        assertEquals(listOf(alien.first), plan.newMovies.map { it.uri })
        assertEquals(listOf(severance.first), plan.newEpisodes.map { it.uri })
        assertTrue(plan.newShows.isEmpty())
        val row = plan.newMovies.single()
        assertEquals("Alien", row.title)
        assertEquals(1979, row.year)
        assertEquals(share, row.folderUri)
        assertEquals(now, row.addedAtEpochMillis)
        assertNull(row.tmdbId)
    }

    @Test
    fun `a partial listing deletes nothing but adds what it found`() {
        val plan = plan(
            files = listOf(file("Alien (1979).mkv")),
            complete = false,
            storedMovies = listOf("${share}Heat (1995).mkv"),
            storedEpisodes = listOf("${share}Severance.S01E01.mkv"),
        )
        assertTrue(plan.staleMovieUris.isEmpty())
        assertTrue(plan.staleEpisodeUris.isEmpty())
        assertEquals(listOf("${share}Alien (1979).mkv"), plan.newMovies.map { it.uri })
    }

    @Test
    fun `a scan done again after a kill adds only what is missing, each file once`() {
        val listing = (1..10).map { file("Movie $it (${2010 + it}).mkv") } +
            (1..6).map { file("Show.S01E0$it.mkv") }
        val first = plan(files = listing)
        assertEquals(10, first.newMovies.size)
        assertEquals(6, first.newEpisodes.size)
        assertEquals(listOf("show"), first.newShows.map { it.key })

        // The store before 27.1 wrote a row at a time; a kill kept these.
        val keptMovies = first.newMovies.take(4).map { it.uri }
        val keptEpisodes = first.newEpisodes.take(2).map { it.uri }
        val again = plan(
            // A listing can name a file twice; the last three are not stored yet.
            files = listing + listing.takeLast(3),
            storedMovies = keptMovies,
            storedEpisodes = keptEpisodes,
            knownShows = setOf("show"),
        )
        assertTrue(again.staleMovieUris.isEmpty())
        assertTrue(again.staleEpisodeUris.isEmpty())
        assertEquals(first.newMovies.drop(4), again.newMovies)
        assertEquals(first.newEpisodes.drop(2), again.newEpisodes)
        assertTrue(again.newShows.isEmpty())
        val stored = keptMovies + keptEpisodes + again.newMovies.map { it.uri } + again.newEpisodes.map { it.uri }
        assertEquals(listing.map { it.first }.sorted(), stored.sorted())
    }

    @Test
    fun `a new show brings one row and blank titles fall back to the file name`() {
        val plan = plan(
            files = listOf(
                file("Severance.S01E01.mkv"),
                file("severance.s01e02.mkv"),
                file("The Bear.S02E03.mkv"),
                file("- S01E04.mkv"),
                file("(2020).mkv"),
            ),
            knownShows = setOf("the bear"),
        )
        assertEquals(listOf("severance", "- s01e04"), plan.newShows.map { it.key })
        assertEquals(listOf("Severance", "- S01E04"), plan.newShows.map { it.name })
        assertEquals(listOf("severance", "severance", "the bear", "- s01e04"), plan.newEpisodes.map { it.showKey })
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 3, 1 to 4), plan.newEpisodes.map { it.season to it.episode })
        assertEquals("(2020)", plan.newMovies.single().title)
        assertEquals(2020, plan.newMovies.single().year)
    }

    @Test
    fun `a file another source already holds is neither added nor stale`() {
        // The same file through an overlapping link, stored under that link.
        val elsewhere = "${share}Heat (1995).mkv"
        val plan = plan(
            files = listOf(elsewhere to "Heat (1995).mkv", file("Alien (1979).mkv")),
            known = setOf(elsewhere),
        )
        assertEquals(listOf("${share}Alien (1979).mkv"), plan.newMovies.map { it.uri })
        assertTrue(plan.staleMovieUris.isEmpty())
    }

    @Test
    fun `files added on their own have no source and remove nothing`() {
        val picked = SourceScanRules.plan(
            files = listOf("content://media/external/video/12" to "Heat (1995).mkv"),
            complete = false,
            folderUri = null,
            storedMovieUris = emptyList(),
            storedEpisodeUris = emptyList(),
            knownUris = emptySet(),
            knownShowKeys = emptySet(),
            now = now,
        )
        assertNull(picked.newMovies.single().folderUri)
        assertTrue(picked.staleMovieUris.isEmpty())
    }

    @Test
    fun `stale rows are deleted in batches SQLite can bind`() {
        val uris = (1..2_500).map { "${share}file$it.mkv" }
        val batches = SourceScanRules.statementBatches(uris)
        assertEquals(5, batches.size)
        assertTrue(batches.all { it.size <= 500 })
        assertEquals(uris, batches.flatten())
        assertTrue(SourceScanRules.statementBatches(emptyList()).isEmpty())
    }
}
