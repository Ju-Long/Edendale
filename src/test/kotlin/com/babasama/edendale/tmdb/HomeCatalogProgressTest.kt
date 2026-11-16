package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail

/**
 * The home hero's Continue Watching scene resumes the unfinished record
 * watched last — for a show, the episode watched last — and follows the
 * watch rows as they change: a later episode, a newer position, another
 * title, or what a sync brings from another device. Once there's nothing
 * left to resume (Delete Watch History, Clear Progress, a synced clear, or
 * the title finished) the scene stays, as a featured title.
 */
class HomeCatalogProgressTest {
    private val matrix = MediaDetail(MediaRef(603, MediaType.MOVIE), "The Matrix", runtimeMinutes = 136)
    private val severance = MediaDetail(MediaRef(95396, MediaType.TV), "Severance")
    private val dune = MediaDetail(MediaRef(438631, MediaType.MOVIE), "Dune")
    private val inProgress = WatchProgress(tmdbId = 603, mediaType = WatchMediaType.MOVIE, position = 0.5, lastWatchedEpochMillis = 1_000L)

    private fun episode(id: Int, number: Int, at: Long, completed: Boolean = false, position: Double = 0.4) = WatchProgress(
        tmdbId = id,
        mediaType = WatchMediaType.EPISODE,
        position = if (completed) 1.0 else position,
        lastWatchedEpochMillis = at,
        isCompleted = completed,
        showTmdbId = 95396,
        seasonNumber = 1,
        episodeNumber = number,
        durationSeconds = 3_000.0,
    )

    private val e1 = episode(1_001, 1, at = 1_000L)
    private val e2 = episode(1_002, 2, at = 3_000L, completed = true)
    private val e3 = episode(1_003, 3, at = 2_000L)
    private val e4 = episode(1_004, 4, at = 4_000L, position = 0.1)

    private fun catalog(vararg scenes: HeroScene) = HomeCatalog(scenes.toList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private val noFetch: suspend (MediaRef) -> MediaDetail? = { fail("fetched $it") }

    @Test
    fun `the hero resumes the episode watched last, not the show's first record`() {
        // E1 is the show's first record; E2 is newer but finished; E3 is the latest unfinished.
        assertEquals(e3, listOf(e1, e2, e3, inProgress).heroResumeRecord())
        assertEquals(MediaRef(95396, MediaType.TV), e3.heroRef())
        // An episode without its show can't be shown, so an older title leads.
        assertEquals(inProgress, listOf(inProgress, e4.copy(showTmdbId = null)).heroResumeRecord())
        assertNull(listOf(e2).heroResumeRecord())
    }

    @Test
    fun `loadHome leads with the latest episode's record`() = runBlocking {
        val transport = DetailTransport()
        val home = BrowseRepository(TmdbApi(transport)).loadHome(listOf(e1, e2, e3))
        val lead = home.heroScenes.first()
        assertEquals("Severance", lead.detail.title)
        assertEquals(e3, lead.progress)
        assertEquals(listOf("/tv/95396"), transport.details)
    }

    @Test
    fun `a later episode of the same show takes over the lead without a fetch`() = runCommonTest {
        val before = catalog(HeroScene(severance, e3), HeroScene(matrix))
        val after = before.followingProgress(listOf(e1, e2, e3, e4), noFetch)
        assertEquals(listOf(severance, matrix), after.heroScenes.map { it.detail })
        assertEquals(e4, after.heroScenes.first().progress)
        // A newer position of the same episode is followed too.
        val moved = e4.copy(position = 0.7, lastWatchedEpochMillis = 5_000L)
        assertEquals(moved, after.followingProgress(listOf(moved), noFetch).heroScenes.first().progress)
        // The same record leaves the same catalog.
        assertSame(after, after.followingProgress(listOf(e1, e4), noFetch))
    }

    @Test
    fun `a title watched since leads, replacing the scene it takes over from`() = runCommonTest {
        val watched = WatchProgress(tmdbId = 438631, mediaType = WatchMediaType.MOVIE, position = 0.2, lastWatchedEpochMillis = 9_000L)
        val before = catalog(HeroScene(severance, e3), HeroScene(matrix))
        val fetched = mutableListOf<MediaRef>()
        val after = before.followingProgress(listOf(e3, watched)) { ref -> fetched += ref; dune }
        assertEquals(listOf(dune, matrix), after.heroScenes.map { it.detail })
        assertEquals(watched, after.heroScenes.first().progress)
        assertEquals(listOf(MediaRef(438631, MediaType.MOVIE)), fetched)

        // A featured title the hero already holds moves to the front with no fetch, once.
        val featured = before.followingProgress(listOf(e3, inProgress.copy(lastWatchedEpochMillis = 9_000L)), noFetch)
        assertEquals(listOf(matrix), featured.heroScenes.map { it.detail })
        assertEquals(603, featured.heroScenes.first().progress?.tmdbId)
    }

    @Test
    fun `when the new title can't be fetched, the scenes keep their titles and follow their records`() = runCommonTest {
        val watched = WatchProgress(tmdbId = 438631, mediaType = WatchMediaType.MOVIE, position = 0.2, lastWatchedEpochMillis = 9_000L)
        val moved = e3.copy(position = 0.6)
        val before = catalog(HeroScene(severance, e3), HeroScene(matrix))
        val after = before.followingProgress(listOf(moved, watched)) { null }
        assertEquals(listOf(severance, matrix), after.heroScenes.map { it.detail })
        assertEquals(moved, after.heroScenes.first().progress)
    }

    @Test
    fun `a hero with nothing left to resume stays as a featured title`() = runCommonTest {
        val before = catalog(HeroScene(matrix, inProgress), HeroScene(severance))
        val after = before.followingProgress(emptyList(), noFetch)
        assertEquals(listOf(matrix, severance), after.heroScenes.map { it.detail })
        assertNull(after.heroScenes.first().progress)
        assertFalse(after.heroScenes.first().isContinueWatching)
        assertNull(after.heroScenes.first().remainingText)
        // Finished counts as nothing left.
        val finished = before.followingProgress(listOf(inProgress.copy(isCompleted = true, position = 1.0)), noFetch)
        assertNull(finished.heroScenes.first().progress)
        // With nothing to resume and nothing resuming, the catalog is the same one.
        val plain = catalog(HeroScene(severance))
        assertSame(plain, plain.followingProgress(emptyList(), noFetch))
        // An episode record of the same TMDB id is a different key and keeps nothing alive.
        assertNull(before.refreshingProgressIn(listOf(inProgress.copy(mediaType = WatchMediaType.EPISODE))).heroScenes.first().progress)
    }

    @Test
    fun `what's left is measured on the record's own file when the player knew it`() {
        assertEquals("00:45:00 left", HeroScene(severance, e1.copy(position = 0.1)).remainingText)
        // No length on the record (a Mark Watched or an import): TMDB's runtime.
        assertEquals("01:08:00 left", HeroScene(matrix, inProgress).remainingText)
    }
}

/** TMDB with empty shelves, answering a title's detail with its id. */
private class DetailTransport : TmdbTransport {
    override val isConfigured: Boolean = true
    val details = mutableListOf<String>()

    override suspend fun get(path: String, parameters: Map<String, String>): String {
        val match = Regex("^/(movie|tv)/(\\d+)$").find(path) ?: return """{"results": [], "genres": []}"""
        details += path
        val id = match.groupValues[2]
        return """{"id": $id, "title": "Movie $id", "name": "Severance"}"""
    }
}

private fun runCommonTest(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                outcome = result
            }
        },
    )
    checkNotNull(outcome) { "Test coroutine unexpectedly suspended." }.getOrThrow()
}
