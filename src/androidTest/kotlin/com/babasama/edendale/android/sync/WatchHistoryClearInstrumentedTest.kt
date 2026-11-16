package com.babasama.edendale.android.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babasama.edendale.android.data.EdendaleDatabase
import com.babasama.edendale.android.data.SyncProgressEntity
import com.babasama.edendale.android.data.SyncUserMediaEntity
import com.babasama.edendale.android.data.WatchProgressEntity
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Delete Watch History against a real Room database: with sync on, every key
 * becomes a tombstone and this device's rows go in one transaction, with the
 * store dirty; with sync off, both progress tables empty and nothing is
 * written. User media stays either way. The database is in memory and the
 * sync preferences live under a test-only name, so the installed app's data
 * is never touched.
 */
@RunWith(AndroidJUnit4::class)
class WatchHistoryClearInstrumentedTest {

    /** Keeps [SyncSettingsStore]'s preferences apart from the app's real `edendale_sync`. */
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$PREFIX$name", mode)
        override fun deleteSharedPreferences(name: String): Boolean = super.deleteSharedPreferences("$PREFIX$name")

        companion object {
            const val PREFIX = "watch-history-test-"
        }
    }

    private val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var database: EdendaleDatabase
    private lateinit var settings: SyncSettingsStore
    private lateinit var store: RoomSyncLocalStore

    private val me = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
    private val other = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b"
    private val now = 1_762_000_100_000L

    @Before
    fun open() {
        context.deleteSharedPreferences(SyncSettingsStore.FILE_NAME)
        database = Room.inMemoryDatabaseBuilder(context, EdendaleDatabase::class.java).build()
        settings = SyncSettingsStore(context)
        store = RoomSyncLocalStore(database, settings)
    }

    @After
    fun close() {
        database.close()
        context.deleteSharedPreferences(SyncSettingsStore.FILE_NAME)
    }

    private fun live(key: String, type: String, tmdbId: Int, season: Int?, episode: Int?, device: String, updateTime: Long) =
        SyncProgressEntity(key, type, tmdbId, season, episode, 600.0, 2_700.0, false, false, device, updateTime)

    /** A movie here and in the ledger, an episode only here, an episode row with no show (never synced), another device's episode, a cleared movie, and a favourite. */
    private suspend fun seed() {
        val rows = database.watchProgressDao()
        rows.upsert(WatchProgressEntity.fromDomain(WatchProgress(603, WatchMediaType.MOVIE, position = 0.4, lastWatchedEpochMillis = 1_762_000_000_000)))
        rows.upsert(WatchProgressEntity.fromDomain(WatchProgress(62085, WatchMediaType.EPISODE, position = 1.0, isCompleted = true, showTmdbId = 1399, seasonNumber = 1, episodeNumber = 2)))
        rows.upsert(WatchProgressEntity.fromDomain(WatchProgress(777, WatchMediaType.EPISODE, position = 0.5)))
        val sync = database.syncDao()
        sync.upsertProgress(
            listOf(
                live("movie:603", "movie", 603, null, null, me, 1_762_000_000_000),
                live("tv:95396:2:1", "tv", 95396, 2, 1, other, 1_762_000_000_500),
                SyncProgressEntity("movie:550", "movie", 550, null, null, null, null, null, true, other, 1_762_000_000_200),
            ),
        )
        sync.upsertUserMedia(listOf(SyncUserMediaEntity("movie:603", "movie", 603, true, false, 9.0, null, other, 1_762_000_000_300)))
        settings.raiseHighWater(1_762_000_000_500)
    }

    @Test
    fun syncOnTombstonesEveryKeyAndEmptiesThisDevice() = runBlocking {
        seed()
        settings.dirty = false
        val stamp = settings.nextStamp(now)

        assertEquals(3, store.clearAllProgress(me, stamp))

        val ledger = database.syncDao().allProgress().associateBy { it.key }
        assertEquals(setOf("movie:603", "tv:1399:1:2", "tv:95396:2:1", "movie:550"), ledger.keys)
        listOf("movie:603", "tv:1399:1:2", "tv:95396:2:1").forEach { key ->
            val record = ledger.getValue(key)
            assertTrue(record.cleared)
            assertEquals(me, record.device)
            assertEquals(stamp, record.updateTime)
            assertEquals(null, record.position)
        }
        // A tombstone already held keeps its writer and stamp.
        assertEquals(other, ledger.getValue("movie:550").device)
        assertEquals(1_762_000_000_200, ledger.getValue("movie:550").updateTime)
        assertTrue(database.watchProgressDao().all().isEmpty())
        assertEquals(0, database.watchProgressDao().observeCount().first())
        assertEquals(0, database.syncDao().observeLiveProgressCount().first())
        assertEquals(1, database.syncDao().allUserMedia().size)
        assertTrue(settings.dirty)
        assertEquals(stamp, settings.highWater)
    }

    @Test
    fun syncOffForgetsBothTablesAndWritesNothing() = runBlocking {
        seed()
        settings.dirty = false
        assertEquals(3, database.watchProgressDao().observeCount().first())
        assertEquals(2, database.syncDao().observeLiveProgressCount().first())

        store.forgetAllProgress()

        assertTrue(database.syncDao().allProgress().isEmpty())
        assertTrue(database.watchProgressDao().all().isEmpty())
        assertEquals(1, database.syncDao().allUserMedia().size)
        assertFalse(settings.dirty)
    }

    @Test
    fun nothingToClearOnlyEmptiesRowsThatNeverSynced() = runBlocking {
        database.watchProgressDao().upsert(WatchProgressEntity.fromDomain(WatchProgress(777, WatchMediaType.EPISODE, position = 0.5)))
        settings.dirty = false

        assertEquals(0, store.clearAllProgress(me, settings.nextStamp(now)))

        assertTrue(database.watchProgressDao().all().isEmpty())
        assertTrue(database.syncDao().allProgress().isEmpty())
        assertFalse(settings.dirty)
    }
}
