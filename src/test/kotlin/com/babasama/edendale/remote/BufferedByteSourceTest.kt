package com.babasama.edendale.remote

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D.1.T1, ported from Apple's BufferedByteSourceTests: the buffered SMB read
 * path against an in-memory server whose connections can be dropped,
 * refused, or slowed. Small chunks keep the fixtures tiny.
 */
class BufferedByteSourceTest {

    /** A file server whose state the tests change while the source reads. */
    private class FakeFileServer(val data: ByteArray) {
        private var generation = 0
        private var refusing = false
        var openCount = 0
            private set
        val readOffsets: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        @Volatile var keepAliveCount = 0
            private set
        @Volatile var delayMillis = 0L
        private var failKeepAlive = false
        private var dropInterval = 0

        /** Kills every open connection; [refuse] also turns new ones away. */
        @Synchronized fun drop(refuse: Boolean = false) {
            generation++
            refusing = refuse
        }

        @Synchronized fun restore() {
            refusing = false
        }

        @Synchronized fun dropEvery(count: Int) {
            dropInterval = count
        }

        @Synchronized fun failKeepAlives() {
            failKeepAlive = true
        }

        @Synchronized fun open(): BufferedFile {
            openCount++
            if (refusing) throw IOException("Host is down")
            return FakeFile(this, generation)
        }

        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int, generation: Int): Int {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            synchronized(this) {
                if (generation != this.generation) throw IOException("Connection reset")
                readOffsets += position
                if (dropInterval > 0 && readOffsets.size % dropInterval == 0) {
                    this.generation++
                    throw IOException("Connection reset")
                }
                if (position >= data.size) return 0
                val count = minOf(length, data.size - position.toInt())
                System.arraycopy(data, position.toInt(), buffer, offset, count)
                return count
            }
        }

        @Synchronized fun keepAlive(generation: Int): Boolean {
            keepAliveCount++
            return !failKeepAlive && generation == this.generation
        }
    }

    private class FakeFile(val server: FakeFileServer, val generation: Int) : BufferedFile {
        override val size: Long get() = server.data.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            server.read(position, buffer, offset, length, generation)
        override fun keepAlive(): Boolean = server.keepAlive(generation)
        override fun close() = Unit
    }

    private val chunk = 1024

    private fun testData(count: Int) = Random(42).nextBytes(count)

    private fun source(
        server: FakeFileServer,
        aheadChunks: Int = 8,
        cacheChunks: Int = 16,
        retryDelays: List<Long> = listOf(10, 10, 10),
        keepAliveMillis: Long = 0,
        retryWait: ((Long) -> Unit)? = null,
    ) = BufferedByteSource(
        host = "nas.local",
        opener = { server.open() },
        config = BufferedSourceConfig(
            chunkSize = chunk,
            readAheadBytes = aheadChunks.toLong() * chunk,
            cacheBytes = cacheChunks.toLong() * chunk,
            retryDelaysMillis = retryDelays,
            keepAliveMillis = keepAliveMillis,
        ),
        retryWait = retryWait,
    )

    private fun read(source: BufferedByteSource, position: Long, length: Int): Pair<Int, ByteArray> {
        val buffer = ByteArray(length)
        val count = source.read(position, buffer, 0, length)
        return count to buffer.copyOf(maxOf(count, 0))
    }

    private fun readAll(source: BufferedByteSource, count: Int, step: Int = 700): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < count) {
            val (read, bytes) = read(source, out.size().toLong(), step)
            if (read <= 0) break
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun waitUntil(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun readsTheWholeFileAndReadsAhead() {
        val server = FakeFileServer(testData(32 * 1024))
        source(server).use { source ->
            assertEquals(-1, source.length)
            val (count, bytes) = read(source, 0, 100)
            assertEquals(100, count)
            assertContentEquals(server.data.copyOf(100), bytes)
            assertEquals(server.data.size.toLong(), source.length)
            // Chunks up to eight ahead of the read position load unasked, and no further.
            waitUntil { server.readOffsets.contains(8L * 1024) }
            assertTrue(server.readOffsets.contains(8L * 1024))
            Thread.sleep(100)
            assertFalse(server.readOffsets.contains(9L * 1024))

            assertContentEquals(server.data, readAll(source, server.data.size))
            assertEquals(-1, read(source, server.data.size.toLong(), 10).first)
            // Every fetch was a whole, chunk-aligned chunk, and none repeated.
            val offsets = server.readOffsets.toList()
            assertTrue(offsets.all { it % chunk == 0L })
            assertEquals(offsets.size, offsets.toSet().size)
            assertEquals(1, server.openCount)
        }
    }

    @Test
    fun openWaitsForTheConnectionAndReportsTheSize() {
        val server = FakeFileServer(testData(5_000))
        source(server).use { source ->
            assertEquals(5_000, source.open(4_000))
            assertEquals(5_000, source.length)
            val (count, bytes) = read(source, 4_000, 2_000)
            // A read never crosses a chunk boundary: 4000 is in chunk 3, which ends at 4096.
            assertEquals(96, count)
            assertContentEquals(server.data.copyOfRange(4_000, 4_096), bytes)
        }
    }

    @Test
    fun aSeekJumpsTheReadAheadQueue() {
        val server = FakeFileServer(testData(256 * 1024))
        server.delayMillis = 30
        source(server, aheadChunks = 64, cacheChunks = 80).use { source ->
            read(source, 0, 100)
            // Read-ahead now has 64 chunks (about 2 s) to fetch; the seek waits
            // for one or two of them, not all.
            val started = System.currentTimeMillis()
            val (count, bytes) = read(source, 200_000, 100)
            assertEquals(100, count)
            assertContentEquals(server.data.copyOfRange(200_000, 200_100), bytes)
            assertTrue(System.currentTimeMillis() - started < 500)
        }
    }

    @Test
    fun reconnectsAfterADroppedConnection() {
        val server = FakeFileServer(testData(40 * 1024))
        source(server, aheadChunks = 2, cacheChunks = 4).use { source ->
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < server.data.size) {
                if (out.size() == 10 * 1024 || out.size() == 25 * 1024) server.drop()
                val (count, bytes) = read(source, out.size().toLong(), 1024)
                assertTrue(count > 0)
                out.write(bytes)
            }
            assertContentEquals(server.data, out.toByteArray())
            assertEquals(3, server.openCount)
            assertNull(source.failureReason)
        }
    }

    @Test
    fun reconnectsFollowTheDelaySequence() {
        val server = FakeFileServer(testData(16 * 1024))
        val waits = Collections.synchronizedList(mutableListOf<Long>())
        source(
            server,
            aheadChunks = 1,
            cacheChunks = 3,
            retryDelays = listOf(250, 500, 1_000, 2_000, 4_000, 8_000),
            // Virtual time: record the delay instead of sleeping it.
            retryWait = { waits += it },
        ).use { source ->
            read(source, 0, 100)
            waitUntil { server.readOffsets.contains(1024L) }
            server.drop(refuse = true)
            assertFailsWith<RemoteConnectionLostException> { read(source, 8L * 1024, 100) }
            assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L), waits.toList())
        }
    }

    @Test
    fun ridesOutAShortOutage() {
        val server = FakeFileServer(testData(16 * 1024))
        source(server, aheadChunks = 1, cacheChunks = 3, retryDelays = listOf(50, 100, 200, 400)).use { source ->
            read(source, 0, 100)
            waitUntil { server.readOffsets.contains(1024L) }
            server.drop(refuse = true)
            CompletableFuture.runAsync({ server.restore() }, CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS))
            val (count, bytes) = read(source, 8L * 1024, 100)
            assertEquals(100, count)
            assertContentEquals(server.data.copyOfRange(8192, 8292), bytes)
        }
    }

    @Test
    fun failsTheReadOnceEveryRetryHasAndRecoversLater() {
        val server = FakeFileServer(testData(16 * 1024))
        source(server, aheadChunks = 1, cacheChunks = 3).use { source ->
            read(source, 0, 100)
            waitUntil { server.readOffsets.contains(1024L) }
            server.drop(refuse = true)
            val opensBefore = server.openCount
            val error = assertFailsWith<RemoteConnectionLostException> { read(source, 8L * 1024, 100) }
            // One open per retry.
            assertEquals(3, server.openCount - opensBefore)
            assertEquals("nas.local", error.host)
            assertTrue(source.failureReason!!.contains("Lost the connection to nas.local"))
            assertTrue(source.failureReason!!.contains("Host is down"))

            server.restore()
            val (later, bytes) = read(source, 8L * 1024, 100)
            assertEquals(100, later)
            assertContentEquals(server.data.copyOfRange(8192, 8292), bytes)
        }
    }

    @Test
    fun aFailedFirstLoginFailsAtOnce() {
        val server = FakeFileServer(testData(4096))
        server.drop(refuse = true)
        source(server, retryDelays = listOf(1_000, 1_000, 1_000)).use { source ->
            val started = System.currentTimeMillis()
            val error = assertFailsWith<RemoteOpenException> { read(source, 0, 100) }
            assertTrue(System.currentTimeMillis() - started < 500)
            assertEquals(1, server.openCount)
            assertEquals("Host is down", error.message)
            assertEquals("Host is down", source.failureReason)
            // Every later read and open fails the same way, without reconnecting.
            assertFailsWith<RemoteOpenException> { read(source, 0, 100) }
            assertFailsWith<RemoteOpenException> { source.open(0) }
            assertEquals(1, server.openCount)
        }
    }

    @Test
    fun anInterruptFailsOnlyTheBlockedRead() {
        val server = FakeFileServer(testData(4096))
        server.delayMillis = 500
        source(server, aheadChunks = 1, cacheChunks = 3).use { source ->
            val started = System.currentTimeMillis()
            var failure: Throwable? = null
            val reader = Thread {
                failure = runCatching { read(source, 0, 64) }.exceptionOrNull()
            }
            reader.start()
            Thread.sleep(100)
            reader.interrupt()
            reader.join(2_000)
            assertTrue(failure is InterruptedIOException)
            assertTrue(System.currentTimeMillis() - started < 450)
            assertNull(source.failureReason)
            val (next, bytes) = read(source, 0, 64)
            assertEquals(64, next)
            assertContentEquals(server.data.copyOf(64), bytes)
        }
    }

    @Test
    fun closeFailsABlockedReadAtOnce() {
        val server = FakeFileServer(testData(4096))
        server.delayMillis = 2_000
        val source = source(server)
        val started = System.currentTimeMillis()
        val result = CompletableFuture.supplyAsync { runCatching { read(source, 0, 64) }.exceptionOrNull() }
        Thread.sleep(150)
        source.close()
        assertTrue(result.get(2, TimeUnit.SECONDS) is IOException)
        assertTrue(System.currentTimeMillis() - started < 1_500)
        assertFailsWith<IOException> { read(source, 0, 64) }
    }

    @Test
    fun keepsAnIdleConnectionAliveAndReopensADeadOne() {
        val server = FakeFileServer(testData(8 * 1024))
        source(server, aheadChunks = 1, cacheChunks = 3, keepAliveMillis = 100).use { source ->
            read(source, 0, 100)
            waitUntil { server.keepAliveCount >= 2 }
            assertTrue(server.keepAliveCount >= 2)
            assertEquals(1, server.openCount)

            // A failed keep-alive drops the connection; the next fetch reopens it.
            server.failKeepAlives()
            val before = server.keepAliveCount
            waitUntil { server.keepAliveCount > before }
            val (count, bytes) = read(source, 6L * 1024, 100)
            assertEquals(100, count)
            assertContentEquals(server.data.copyOfRange(6144, 6244), bytes)
            assertEquals(2, server.openCount)
        }
    }

    @Test
    fun evictsChunksFarBehindTheReadPositionAndStaysWithinTheCache() {
        val server = FakeFileServer(testData(32 * 1024))
        source(server, aheadChunks = 2, cacheChunks = 6).use { source ->
            assertContentEquals(server.data, readAll(source, server.data.size))
            assertTrue(source.cachedChunkCount <= 6)
            val fetchesOfFirstChunk = server.readOffsets.count { it == 0L }
            val (count, bytes) = read(source, 0, 100)
            assertEquals(100, count)
            assertContentEquals(server.data.copyOf(100), bytes)
            assertEquals(fetchesOfFirstChunk + 1, server.readOffsets.count { it == 0L })
        }
    }

    @Test
    fun readsTheWholeFileWhileTheConnectionKeepsDropping() {
        val server = FakeFileServer(testData(64 * 1024 + 123))
        server.dropEvery(7)
        source(server, aheadChunks = 4, cacheChunks = 8).use { source ->
            assertContentEquals(server.data, readAll(source, server.data.size, step = 333))
            assertTrue(server.openCount > 3)
        }
    }

    @Test
    fun lowMemoryDevicesKeepLessAhead() {
        val normal = BufferedSourceConfig.forDevice(isLowRamDevice = false, memoryClassMb = 256)
        assertEquals(48L shl 20, normal.readAheadBytes)
        assertEquals(64L shl 20, normal.cacheBytes)
        assertEquals(1 shl 20, normal.chunkSize)
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L), normal.retryDelaysMillis)
        assertEquals(20_000L, normal.keepAliveMillis)

        val lowRam = BufferedSourceConfig.forDevice(isLowRamDevice = true, memoryClassMb = 512)
        val smallHeap = BufferedSourceConfig.forDevice(isLowRamDevice = false, memoryClassMb = 191)
        for (config in listOf(lowRam, smallHeap)) {
            assertEquals(16L shl 20, config.readAheadBytes)
            assertEquals(24L shl 20, config.cacheBytes)
        }
        assertEquals(48L shl 20, BufferedSourceConfig.forDevice(false, 192).readAheadBytes)
    }
}
