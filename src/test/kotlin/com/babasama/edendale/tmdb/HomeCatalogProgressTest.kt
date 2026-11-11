package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The home hero's resume state is a snapshot; once its record is deleted
 * (Delete Watch History, Continue Watching's Clear Progress, a synced clear) the
 * scene stays, as a featured title, and stops offering to resume.
 */
class HomeCatalogProgressTest {
    private val matrix = MediaDetail(MediaRef(603, MediaType.MOVIE), "The Matrix", runtimeMinutes = 136)
    private val severance = MediaDetail(MediaRef(95396, MediaType.TV), "Severance")
    private val inProgress = WatchProgress(tmdbId = 603, mediaType = WatchMediaType.MOVIE, position = 0.5, lastWatchedEpochMillis = 1_000L)

    private fun catalog(vararg scenes: HeroScene) = HomeCatalog(scenes.toList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `a hero whose record is gone stops offering to resume`() {
        val before = catalog(HeroScene(matrix, inProgress), HeroScene(severance))
        val after = before.keepingProgressIn(emptyList())
        assertEquals(listOf(matrix, severance), after.heroScenes.map { it.detail })
        assertNull(after.heroScenes.first().progress)
        assertFalse(after.heroScenes.first().isContinueWatching)
        assertNull(after.heroScenes.first().remainingText)
    }

    @Test
    fun `while the record is there, or with nothing to resume, the catalog is the same one`() {
        val resuming = catalog(HeroScene(matrix, inProgress), HeroScene(severance))
        assertSame(resuming, resuming.keepingProgressIn(listOf(inProgress.copy(position = 0.6))))
        val featured = catalog(HeroScene(severance))
        assertSame(featured, featured.keepingProgressIn(emptyList()))
        // An episode record of the same TMDB id is a different key and keeps nothing alive.
        assertNull(resuming.keepingProgressIn(listOf(inProgress.copy(mediaType = WatchMediaType.EPISODE))).heroScenes.first().progress)
    }
}
