package com.babasama.edendale.android

import android.content.Intent
import android.media.tv.TvContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babasama.edendale.android.player.PlayerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I.3: rows reach the system TV provider's Watch Next table and come back as
 * written, and an update and a delete reach the same row. Runs only where the
 * TV provider exists (Android TV and Google TV); elsewhere it's skipped.
 */
@RunWith(AndroidJUnit4::class)
class WatchNextStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = TvProviderWatchNextStore(context)

    private val program = WatchNextProgram(
        key = "${WatchNext.KEY_PREFIX}test:movie:603",
        isNext = false,
        isEpisode = false,
        title = "The Matrix",
        episodeTitle = null,
        season = null,
        episode = null,
        artworkUrl = "https://image.tmdb.org/t/p/w780/matrix-wide.jpg",
        isWideArtwork = true,
        positionMillis = 1_800_000L,
        durationMillis = 7_200_000L,
        lastEngagementMillis = 1_000L,
        play = WatchNextPlay("content://tree/movies/matrix.mkv", "The Matrix", 603, false, null, null, null),
    )

    private fun mine() = store.published()!!.filter { it.key == program.key }

    @Before
    fun needsTheTvProvider() {
        assumeTrue("no TV provider on this device", store.published() != null)
        cleanUp()
    }

    @After
    fun cleanUp() {
        store.published()?.filter { it.key?.startsWith("${WatchNext.KEY_PREFIX}test:") == true }
            ?.let { rows -> store.apply(WatchNextChanges(deletes = rows.map { it.rowId })) }
    }

    @Test
    fun aRowIsWrittenUpdatedAndRemoved() {
        store.apply(WatchNextChanges(inserts = listOf(program)))
        val written = mine().single()
        assertTrue(written.isBrowsable)
        assertEquals(program.signature, written.signature)
        assertEquals(program.lastEngagementMillis, written.lastEngagementMillis)

        // The row opens the player with the shelf card's request.
        val intentUri = context.contentResolver.query(
            TvContract.buildWatchNextProgramUri(written.rowId),
            arrayOf(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
            null,
            null,
            null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, cursor.getInt(1))
            cursor.getString(0)
        }
        val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
        assertEquals(PlayerActivity::class.java.name, intent.component?.className)
        assertEquals(
            PlayerActivity.intent(context, "content://tree/movies/matrix.mkv", "The Matrix", tmdbId = 603).extras?.keySet(),
            intent.extras?.keySet(),
        )

        val later = program.copy(positionMillis = 2_400_000L, lastEngagementMillis = 2_000L)
        store.apply(WatchNext.changes(listOf(later), store.published()!!))
        val updated = mine().single()
        assertEquals(written.rowId, updated.rowId)
        assertEquals(later.signature, updated.signature)

        store.apply(WatchNext.changes(emptyList(), store.published()!!.filter { it.key == program.key }))
        assertNotNull(store.published())
        assertTrue(mine().isEmpty())
    }
}
