package com.babasama.edendale.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Delete Watch History with sync on (SYNC-PROTOCOL.md §6.1, §7): every
 * progress key becomes one tombstone stamped as a local write, tombstones
 * already held stay as they are, user media is untouched, and the clear
 * reaches another device through the files like any other write, while that
 * device's later watching still wins. The read before clearing takes every
 * file again, so a key held back behind an unchanged stamp is cleared too.
 */
class SyncHistoryClearTest {
    private val a = SyncDeviceInfo("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", "Phone", "android", "27.1")
    private val b = SyncDeviceInfo("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b", "TV", "androidtv", "27.1")
    private var now = 1_762_000_100_000L

    private fun movie(tmdbId: Int, device: String, updateTime: Long, position: Double = 600.0) =
        ProgressRecord(SyncMediaType.MOVIE, tmdbId, position = position, duration = 8_160.0, completed = false, device = device, updateTime = updateTime)

    private fun episode(show: Int, season: Int, number: Int, device: String, updateTime: Long, completed: Boolean = true) =
        ProgressRecord(SyncMediaType.TV, show, season, number, position = 2_700.0, duration = 2_700.0, completed = completed, device = device, updateTime = updateTime)

    private val favourite = UserMediaSyncRecord(SyncMediaType.MOVIE, 603, favourite = true, rating = 9.0, review = "Still holds up", device = b.id, updateTime = 1_762_000_000_500)

    private fun ledgerWith(vararg progress: ProgressRecord, media: List<UserMediaSyncRecord> = emptyList()) = SyncLedger(
        progress = progress.associateBy { it.key },
        userMedia = media.associateBy { it.key },
        highWater = (progress.map { it.updateTime } + media.map { it.updateTime }).maxOrNull() ?: 0,
    )

    @Test
    fun `every live record becomes a tombstone with one stamp, by this device, and user media stays`() {
        val ledger = ledgerWith(
            movie(603, a.id, 1_762_000_000_000),
            episode(1399, 1, 2, b.id, 1_762_000_001_000),
            media = listOf(favourite),
        )
        val (stamp, raised) = ledger.stamp(now)
        val (cleared, keys) = raised.clearingAllProgress(stamp, a.id)

        assertEquals(setOf("movie:603", "tv:1399:1:2"), keys)
        assertEquals(keys, cleared.progress.keys)
        cleared.progress.values.forEach { record ->
            assertTrue(record.isTombstone)
            assertEquals(a.id, record.device)
            assertEquals(stamp, record.updateTime)
            assertNull(record.position)
            assertNull(record.duration)
            assertNull(record.completed)
        }
        // The episode keeps its identity: the key other devices match it by.
        assertEquals(ProgressRecord.tombstone(SyncMediaType.TV, 1399, 1, 2, a.id, stamp), cleared.progress["tv:1399:1:2"])
        assertEquals(ledger.userMedia, cleared.userMedia)
        assertEquals(stamp, cleared.highWater)
        // The stamp is §7.2's: past everything applied, so each tombstone wins wherever it's read.
        assertTrue(stamp > ledger.highWater)
    }

    @Test
    fun `tombstones already held keep their stamp and device`() {
        val old = ProgressRecord.tombstone(SyncMediaType.MOVIE, 550, null, null, b.id, 1_762_000_000_200)
        val ledger = ledgerWith(old, movie(603, a.id, 1_762_000_000_000))
        val (stamp, raised) = ledger.stamp(now)
        val (cleared, keys) = raised.clearingAllProgress(stamp, a.id)

        assertEquals(setOf("movie:603"), keys)
        assertEquals(old, cleared.progress["movie:550"])
    }

    @Test
    fun `rows the ledger lacks are cleared too, once per key`() {
        val ledger = ledgerWith(movie(603, a.id, 1_762_000_000_000), ProgressRecord.tombstone(SyncMediaType.MOVIE, 550, null, null, b.id, 1_762_000_000_200))
        val (stamp, raised) = ledger.stamp(now)
        // This device's rows as identities (stamped 0): one the ledger holds live, one it holds cleared,
        // and two episode rows that share a key (the same episode under two TMDB ids).
        val known = listOf(
            movie(603, a.id, 0),
            movie(550, a.id, 0),
            episode(1399, 1, 3, a.id, 0),
            episode(1399, 1, 3, a.id, 0, completed = false),
            movie(27205, a.id, 0),
        )
        val (cleared, keys) = raised.clearingAllProgress(stamp, a.id, known)

        assertEquals(setOf("movie:603", "tv:1399:1:3", "movie:27205"), keys)
        assertEquals(4, cleared.progress.size)
        assertEquals(1_762_000_000_200, cleared.progress.getValue("movie:550").updateTime)
        assertTrue(cleared.progress.values.all { it.isTombstone })
        // Written out, the file is valid: no key twice, tombstones without values.
        val bytes = DeviceFileCodec.encode(cleared.file("Family", a), now)
        val decoded = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(bytes, "Family", DeviceFile.fileName(a.id)))
        assertEquals(cleared.progress, decoded.file.watchProgress.associateBy { it.key })
        assertFalse("\"position\"" in bytes.decodeToString())
    }

    @Test
    fun `nothing to clear leaves the same ledger`() {
        val empty = SyncLedger(highWater = 1_762_000_000_000)
        val (same, keys) = empty.clearingAllProgress(now, a.id)
        assertSame(empty, same)
        assertTrue(keys.isEmpty())

        val onlyTombstones = ledgerWith(ProgressRecord.tombstone(SyncMediaType.MOVIE, 550, null, null, b.id, 1_762_000_000_200), media = listOf(favourite))
        val (unchanged, none) = onlyTombstones.clearingAllProgress(now, a.id, listOf(movie(550, a.id, 0)))
        assertSame(onlyTombstones, unchanged)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `a clear reaches the other device through the files, and later watching there still wins`() = runBlocking {
        val storage = MemorySyncStorage()
        val storeA = MemorySyncStore()
        val storeB = MemorySyncStore()
        val engineA = SyncEngine(storage, storeA, a, "Family") { now }
        val engineB = SyncEngine(storage, storeB, b, "Family") { now }

        storeA.watch(now) { movie(603, a.id, it) }
        storeB.watch(now) { episode(1399, 1, 1, b.id, it) }
        storeB.write(now) { favourite.copy(updateTime = it) }
        engineB.sync()
        engineA.sync()
        engineB.sync()
        assertEquals(storeA.current.progress, storeB.current.progress)

        // B watches more after A's last read; the read before clearing (a full one, as the coordinator runs it) takes it in.
        now += 60_000
        storeB.watch(now) { episode(1399, 1, 2, b.id, it, completed = false) }
        engineB.sync()
        engineA.sync(readEveryFile = true)
        val mediaBefore = storeA.current.userMedia
        now += 1_000
        val keys = storeA.clearHistory(now, a.id)
        assertEquals(setOf("movie:603", "tv:1399:1:1", "tv:1399:1:2"), keys)
        assertTrue(engineA.sync().wrote)

        val fileA = storage.spaces.getValue("Family").getValue(DeviceFile.fileName(a.id)).first
        val decodedA = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(fileA, "Family", DeviceFile.fileName(a.id))).file
        assertTrue(decodedA.watchProgress.all { it.isTombstone && it.device == a.id })
        assertEquals(keys, decodedA.watchProgress.map { it.key }.toSet())
        assertEquals(mediaBefore.values.toList(), decodedA.userMedia)

        // B applies the tombstones and writes them on in its own file.
        val writesBefore = storage.writes.size
        val report = engineB.sync()
        assertEquals(3, report.recordsChanged)
        assertTrue(report.wrote)
        assertEquals(writesBefore + 1, storage.writes.size)
        assertEquals(storeA.current.progress, storeB.current.progress)
        assertEquals(favourite.key, storeB.current.userMedia.values.single().key)

        // B watches a cleared episode again: its stamp passes the tombstone's, so it wins on A too.
        now += 60_000
        storeB.watch(now) { episode(1399, 1, 2, b.id, it, completed = false) }
        engineB.sync()
        engineA.sync()
        val back = storeA.current.progress.getValue("tv:1399:1:2")
        assertFalse(back.isTombstone)
        assertEquals(b.id, back.device)
        assertTrue(storeA.current.progress.getValue("movie:603").isTombstone)
    }

    @Test
    fun `the full read before clearing takes in a record an earlier read held back behind an unchanged stamp`() = runBlocking {
        val storage = MemorySyncStorage()
        val storeA = MemorySyncStore()
        val storeB = MemorySyncStore()
        val engineA = SyncEngine(storage, storeA, a, "Family") { now }
        // B's clock runs two days fast, so A's far-future guard (§7.1) refuses B's record at first, and A keeps B's file stamp.
        val skew = 2 * SyncLedger.FAR_FUTURE_MILLIS
        val engineB = SyncEngine(storage, storeB, b, "Family") { now + skew }
        storeA.watch(now) { movie(603, a.id, it) }
        storeB.watch(now + skew) { movie(27205, b.id, it) }
        engineB.sync()
        assertTrue(engineA.sync().warnings.any { it is SyncWarning.FarFuture })
        assertFalse("movie:27205" in storeA.current.progress)

        // A day on the record is in range, but B's file hasn't changed: a plain sync skips it, so a clear then would miss it…
        now += SyncLedger.FAR_FUTURE_MILLIS + 60_000
        assertEquals(0, engineA.sync().filesRead)
        assertEquals(setOf("movie:603"), storeA.current.clearingAllProgress(now, a.id).second)

        // …while the full read takes it in, and the clear covers it with a stamp past B's (§7.2).
        assertEquals(1, engineA.sync(readEveryFile = true).filesRead)
        now += 1_000
        assertEquals(setOf("movie:603", "movie:27205"), storeA.clearHistory(now, a.id))
        assertTrue(engineA.sync().wrote)
        engineB.sync()
        assertTrue(storeB.current.progress.getValue("movie:27205").isTombstone)
        assertEquals(storeA.current.progress, storeB.current.progress)
    }

    /** A local progress write, stamped (§7.2). */
    private fun MemorySyncStore.watch(now: Long, make: (Long) -> ProgressRecord) {
        val (stamp, raised) = current.stamp(now)
        current = raised.writing(make(stamp))
        dirty = true
    }

    /** What the Android store does under the sync lock: one stamp, every key cleared, the file dirty. */
    private fun MemorySyncStore.clearHistory(now: Long, device: String): Set<String> {
        val (stamp, raised) = current.stamp(now)
        val (cleared, keys) = raised.clearingAllProgress(stamp, device)
        current = cleared
        if (keys.isNotEmpty()) dirty = true
        return keys
    }
}
