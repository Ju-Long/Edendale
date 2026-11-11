package com.babasama.edendale.sync

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory location and store, shared by the engine tests and the log-hygiene test. */
class MemorySyncStorage : SyncStorage {
    val spaces = mutableMapOf<String, MutableMap<String, Pair<ByteArray, Int>>>()
    var failListing = false
    var failWrites = false
    val reads = mutableListOf<String>()
    val writes = mutableListOf<String>()

    fun put(space: String, name: String, bytes: ByteArray) {
        val files = spaces.getOrPut(space) { mutableMapOf() }
        files[name] = bytes to ((files[name]?.second ?: 0) + 1)
    }

    override suspend fun ensureSpace(space: String) {
        spaces.getOrPut(space) { mutableMapOf() }
    }

    override suspend fun list(space: String): List<RemoteSyncFile> {
        if (failListing) throw IOException("offline")
        return spaces[space].orEmpty().map { (name, file) -> RemoteSyncFile(name, "v${file.second}", file.first.size.toLong()) }
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray {
        reads += name
        return spaces[space]?.get(name)?.first ?: throw IOException("missing")
    }

    override suspend fun write(space: String, name: String, bytes: ByteArray) {
        if (failWrites) throw IOException("read-only")
        writes += name
        put(space, name, bytes)
    }

    override suspend fun delete(space: String, name: String) {
        spaces[space]?.remove(name)
    }
}

class MemorySyncStore(initial: SyncLedger = SyncLedger()) : SyncLocalStore {
    var current = initial
    var dirty = false
    var stamps = mapOf<String, String>()
    val applied = mutableListOf<SyncLedger.Merge>()

    override suspend fun ledger() = current
    override suspend fun applyMerge(merge: SyncLedger.Merge) {
        current = merge.ledger
        applied += merge
        if (merge.changed) dirty = true
    }
    override suspend fun stamps() = stamps
    override suspend fun saveStamps(stamps: Map<String, String>) {
        this.stamps = stamps
    }
    override suspend fun isDirty() = dirty
    override suspend fun markWritten() {
        dirty = false
    }

    /** A local write, stamped (§7.2). */
    fun write(now: Long, make: (Long) -> UserMediaSyncRecord) {
        val (stamp, raised) = current.stamp(now)
        current = raised.writing(make(stamp))
        dirty = true
    }
}

/** SYNC-PROTOCOL.md §8.2: one sync end to end against an in-memory location. */
class SyncEngineTest {
    private val a = SyncDeviceInfo("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", "Phone", "android", "27.1")
    private val b = SyncDeviceInfo("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b", "TV", "androidtv", "27.1")
    private var now = 1_762_000_010_000L

    private fun fileB(vararg media: UserMediaSyncRecord) = DeviceFileCodec.encode(DeviceFile("Family", b, emptyList(), media.toList()))

    @Test
    fun `reads changed files, merges, writes only when dirty, and skips unchanged files next time`() = runBlocking {
        val storage = MemorySyncStorage()
        val store = MemorySyncStore()
        val engine = SyncEngine(storage, store, a, "Family") { now }
        storage.put("Family", DeviceFile.fileName(b.id), fileB(UserMediaSyncRecord(SyncMediaType.MOVIE, 603, favourite = true, device = b.id, updateTime = 1_762_000_001_000)))
        storage.put("Family", "notes.txt", "hello".toByteArray())

        val first = engine.sync()
        assertEquals(2, first.filesListed)
        assertEquals(1, first.filesRead)
        assertEquals(1, first.recordsChanged)
        assertTrue(first.wrote)
        assertTrue(first.warnings.isEmpty())
        assertTrue(store.current.userMedia.getValue("movie:603").favourite)
        assertEquals(1_762_000_001_000, store.current.highWater)
        // This device's file now sits beside B's, holding B's record verbatim.
        val own = storage.spaces.getValue("Family").getValue(DeviceFile.fileName(a.id)).first
        val decoded = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(own, "Family", DeviceFile.fileName(a.id)))
        assertEquals(b.id, decoded.file.userMedia.single().device)

        // Nothing changed: no read, no write.
        val second = engine.sync()
        assertEquals(0, second.filesRead)
        assertFalse(second.wrote)
        assertEquals(listOf(DeviceFile.fileName(b.id)), storage.reads)

        // A local change writes; B's file is still unchanged and unread.
        store.write(now) { stamp -> UserMediaSyncRecord(SyncMediaType.TV, 1399, watchlist = true, device = a.id, updateTime = stamp) }
        val third = engine.sync()
        assertTrue(third.wrote)
        assertEquals(0, third.filesRead)
        assertEquals(2, storage.writes.size)

        // B writes again: read once, merged, and since the merge changed the store the file is rewritten.
        storage.put("Family", DeviceFile.fileName(b.id), fileB(UserMediaSyncRecord(SyncMediaType.MOVIE, 603, device = b.id, updateTime = 1_762_000_020_000)))
        val fourth = engine.sync()
        assertEquals(1, fourth.filesRead)
        assertTrue(store.current.userMedia.getValue("movie:603").isTombstone)
        assertTrue(fourth.wrote)
    }

    @Test
    fun `a failed listing leaves the store untouched and a failed write is retried`() = runBlocking {
        val storage = MemorySyncStorage()
        val store = MemorySyncStore()
        store.write(now) { stamp -> UserMediaSyncRecord(SyncMediaType.MOVIE, 1, favourite = true, device = a.id, updateTime = stamp) }
        val before = store.current
        val engine = SyncEngine(storage, store, a, "Family") { now }
        storage.failListing = true
        assertFailsWith<IOException> { engine.sync() }
        assertEquals(before, store.current)
        assertTrue(store.dirty)
        storage.failListing = false
        storage.failWrites = true
        assertFailsWith<IOException> { engine.sync() }
        assertTrue(store.dirty)
        storage.failWrites = false
        assertTrue(engine.sync().wrote)
        assertFalse(store.dirty)
    }

    @Test
    fun `skipped files warn by device name and never remove local data, and an empty space clears nothing`() = runBlocking {
        val storage = MemorySyncStorage()
        val store = MemorySyncStore()
        store.write(now) { stamp -> UserMediaSyncRecord(SyncMediaType.MOVIE, 1, favourite = true, device = a.id, updateTime = stamp) }
        val engine = SyncEngine(storage, store, a, "Family") { now }
        // B's file claims to be in another space, and a third file is 9 MiB by its listing.
        storage.put("Family", DeviceFile.fileName(b.id), DeviceFileCodec.encode(DeviceFile("Other", b, emptyList(), emptyList())))
        val c = "0c0c0c0c-0c0c-4c0c-8c0c-0c0c0c0c0c0c"
        storage.spaces.getValue("Family")[DeviceFile.fileName(c)] = ByteArray(0) to 1
        val huge = object : SyncStorage by storage {
            override suspend fun list(space: String) = storage.list(space).map { if (it.name == DeviceFile.fileName(c)) it.copy(size = 9L shl 20) else it }
        }
        val report = SyncEngine(huge, store, a, "Family") { now }.sync()
        assertEquals(
            setOf(
                SyncWarning.FileSkipped(DeviceFile.fileName(b.id), "TV", DeviceFileCodec.SkipReason.WRONG_SPACE),
                SyncWarning.FileSkipped(DeviceFile.fileName(c), null, DeviceFileCodec.SkipReason.TOO_LARGE),
            ),
            report.warnings.toSet(),
        )
        assertTrue(store.current.userMedia.getValue("movie:1").favourite)
        // The oversized file was never downloaded.
        assertEquals(listOf(DeviceFile.fileName(b.id)), storage.reads)

        // The space is emptied by someone: nothing local goes away.
        storage.spaces.getValue("Family").clear()
        engine.sync()
        assertTrue(store.current.userMedia.getValue("movie:1").favourite)
        assertTrue(store.stamps.isEmpty())
    }

    @Test
    fun `the one-off flush job is needed only while a write is pending`() = runBlocking {
        // FEATURES-27.1.md §8 (R.7): leaving the foreground with a pending write hands it to a one-off job.
        assertFalse(SyncTriggers.flushJobNeeded(syncOn = false, writePending = true))
        assertFalse(SyncTriggers.flushJobNeeded(syncOn = true, writePending = false))
        val storage = MemorySyncStorage()
        val store = MemorySyncStore()
        store.write(now) { stamp -> UserMediaSyncRecord(SyncMediaType.MOVIE, 603, favourite = true, device = a.id, updateTime = stamp) }
        assertTrue(SyncTriggers.flushJobNeeded(syncOn = true, writePending = store.isDirty()))
        // The sync started on leaving wrote the file: the job, when it runs, has nothing left to do.
        assertTrue(SyncEngine(storage, store, a, "Family") { now }.sync().wrote)
        assertFalse(SyncTriggers.flushJobNeeded(syncOn = true, writePending = store.isDirty()))
    }

    @Test
    fun `the far-future guard warns and the removed device's file is deleted`() = runBlocking {
        val storage = MemorySyncStorage()
        val store = MemorySyncStore()
        val engine = SyncEngine(storage, store, a, "Family") { now }
        storage.put("Family", DeviceFile.fileName(b.id), fileB(UserMediaSyncRecord(SyncMediaType.MOVIE, 603, favourite = true, device = b.id, updateTime = now + 2 * 86_400_000)))
        val report = engine.sync()
        assertEquals(listOf(SyncWarning.FarFuture("TV", 1)), report.warnings)
        assertNull(store.current.userMedia["movie:603"])
        assertEquals(0L, store.current.highWater)
        engine.removeDevice(b.id)
        assertNull(storage.spaces.getValue("Family")[DeviceFile.fileName(b.id)])
        engine.removeOwnFile()
        assertTrue(storage.spaces.getValue("Family").isEmpty())
    }
}
