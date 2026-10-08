package com.babasama.edendale.remote

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One open remote file, used from one thread at a time (the source's worker).
 * SMB today; Section H's SFTP and NFS connectors implement it too.
 */
interface BufferedFile : Closeable {
    /** Size in bytes, or -1 when the server didn't report it. */
    val size: Long

    /** Reads into [buffer]; returns the byte count, or 0 at the end of the file. Throws on failure. */
    @Throws(IOException::class)
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** A cheap round trip on an idle connection; false means it's dead. */
    fun keepAlive(): Boolean

    /** Fails a call in progress from another thread, when the transport allows it. */
    fun abort() {}
}

/** Connects and opens the file on the source's worker thread; throws when it can't. */
fun interface BufferedFileOpener {
    @Throws(IOException::class)
    fun open(): BufferedFile
}

/** Read-ahead and reconnect tuning (D.1). Tests shrink these. */
data class BufferedSourceConfig(
    val chunkSize: Int = 1 shl 20,
    val readAheadBytes: Long = 48L shl 20,
    val cacheBytes: Long = 64L shl 20,
    /** Delays before each reconnect; their count is the number of retries. */
    val retryDelaysMillis: List<Long> = listOf(250, 500, 1_000, 2_000, 4_000, 8_000),
    /** Idle time before a keep-alive; 0 disables it. */
    val keepAliveMillis: Long = 20_000,
) {
    companion object {
        /** Android addition: low-memory devices keep 16 MiB ahead within 24 MiB. */
        fun forDevice(isLowRamDevice: Boolean, memoryClassMb: Int): BufferedSourceConfig =
            if (isLowRamDevice || memoryClassMb < LOW_MEMORY_CLASS_MB) {
                BufferedSourceConfig(readAheadBytes = 16L shl 20, cacheBytes = 24L shl 20)
            } else {
                BufferedSourceConfig()
            }

        const val LOW_MEMORY_CLASS_MB = 192
    }
}

/** The connection dropped for good mid-file; the message names the host. */
class RemoteConnectionLostException(val host: String, val detail: String?, cause: Throwable? = null) : IOException(
    if (detail.isNullOrBlank()) "Lost the connection to $host." else "Lost the connection to $host: $detail",
    cause,
)

/** The first open failed (bad login, missing path, host down); no retries were made. */
class RemoteOpenException(val host: String, cause: Throwable?) : IOException(
    cause?.message?.takeIf { it.isNotBlank() } ?: "Couldn't connect to $host.",
    cause,
)

/**
 * A byte source over a blocking remote connection that reads ahead on its own
 * thread and survives dropped connections (D.1; Apple's `EDBufferedByteSource`).
 *
 * Media3 reads in small pieces; fetching each one over the network costs a
 * round trip, which over a phone hotspot or a VPN caps throughput below a
 * typical video bitrate. Here a worker thread fetches [BufferedSourceConfig.chunkSize]
 * chunks and keeps up to [BufferedSourceConfig.readAheadBytes] ahead of the read
 * position, so reads are served from memory and a stall shorter than the
 * buffer never reaches the player. The chunk a blocked read needs always goes
 * first, so a seek doesn't wait behind the read-ahead.
 *
 * When a fetch fails after the file opened once, the connection is dropped and
 * reopened after each delay in [BufferedSourceConfig.retryDelaysMillis]; the read
 * fails only after every retry has. An idle connection (paused playback) gets a
 * keep-alive so the server, or a NAT on the way, doesn't drop it. [close]
 * fails blocked reads at once, and so does interrupting the reading thread
 * (Media3's loader does that when it cancels a load).
 *
 * Pure Kotlin: no Android imports, so the JVM suite drives it against a fake
 * server. [nowMillis] and [retryWait] are injectable for tests.
 */
class BufferedByteSource(
    private val host: String,
    private val opener: BufferedFileOpener,
    config: BufferedSourceConfig = BufferedSourceConfig(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    retryWait: ((Long) -> Unit)? = null,
) : Closeable {

    private val chunkSize = config.chunkSize.coerceAtLeast(1)
    private val aheadChunks = (config.readAheadBytes / chunkSize).coerceAtLeast(1)
    private val cacheChunks = maxOf(
        config.cacheBytes.coerceAtLeast(config.readAheadBytes + 2L * chunkSize) / chunkSize,
        aheadChunks + 2,
    )
    private val retryDelays = config.retryDelaysMillis
    private val keepAliveMillis = config.keepAliveMillis
    private val retryWait: (Long) -> Unit = retryWait ?: ::awaitCancellable

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Guarded by lock.
    private val chunks = HashMap<Long, ByteArray>()
    private var started = false
    private var cancelled = false
    private var opened = false
    private var size = -1L

    /** The last chunk of the file, once the size or a short chunk shows it. */
    private var lastChunk = Long.MAX_VALUE

    /** The chunk read last; read-ahead starts here. */
    private var readChunk = 0L

    /** The chunk a blocked read waits for, or -1. */
    private var wanted = -1L

    /** A chunk whose fetch failed while a read waited for it, or -1. */
    private var failedChunk = -1L

    /** The first open failed: every read fails. */
    private var fatal: IOException? = null

    /** A prefetch failed for good; the next read resumes read-ahead. */
    private var prefetchHalted = false
    private var failure: IOException? = null
    private var reason: IOException? = null
    private var liveFile: BufferedFile? = null

    // Worker thread only.
    private var file: BufferedFile? = null
    private var everOpened = false
    private var lastActivity = 0L

    /** The file size once the connection opened, else -1. */
    val length: Long get() = lock.withLock { if (opened) size else -1 }

    /** Why the last failed read failed, or null. */
    val failureReason: String? get() = lock.withLock { reason?.message }

    /**
     * Starts the connection with read-ahead from [position] and waits until
     * the file opened; returns its size (-1 when unknown). Throws when the
     * first open fails or the source is closed.
     */
    @Throws(IOException::class)
    fun open(position: Long): Long = lock.withLock {
        startIfNeeded()
        moveReadPosition(position / chunkSize)
        if (!opened && wanted < 0) {
            wanted = position / chunkSize
            changed.signalAll()
        }
        while (!opened) {
            if (cancelled) throw InterruptedIOException("Closed")
            fatal?.let { throw it }
            awaitChange()
        }
        size
    }

    /**
     * Copies up to [length] bytes at [position] into [buffer]. Returns the
     * count, or -1 at the end of the file. Blocks while the chunk is fetched;
     * throws when the connection is lost for good, the source closes, or the
     * thread is interrupted.
     */
    @Throws(IOException::class)
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        require(position >= 0) { "Negative position" }
        return lock.withLock {
            startIfNeeded()
            val index = position / chunkSize
            moveReadPosition(index)
            try {
                while (true) {
                    if (cancelled) throw InterruptedIOException("Closed")
                    fatal?.let {
                        reason = it
                        throw it
                    }
                    if (opened && size >= 0 && position >= size) return@withLock -1
                    val chunk = chunks[index]
                    if (chunk != null) {
                        val start = (position - index * chunkSize).toInt()
                        // A chunk shorter than the offset ends the file.
                        if (start >= chunk.size) return@withLock -1
                        val count = minOf(length, chunk.size - start)
                        System.arraycopy(chunk, start, buffer, offset, count)
                        return@withLock count
                    }
                    if (failedChunk == index) {
                        failedChunk = -1
                        val error = failure ?: RemoteConnectionLostException(host, null)
                        reason = error
                        throw error
                    }
                    if (wanted != index) {
                        wanted = index
                        changed.signalAll()
                    }
                    awaitChange()
                }
                @Suppress("UNREACHABLE_CODE")
                -1
            } finally {
                if (wanted == index) wanted = -1
            }
        }
    }

    /** Fails blocked reads at once and drops the connection; the source can't be reused. */
    override fun close() {
        val file = lock.withLock {
            cancelled = true
            chunks.clear()
            val live = liveFile
            liveFile = null
            changed.signalAll()
            live
        }
        file?.let { runCatching { it.abort() } }
    }

    /** Bytes held in memory, for tests and diagnostics. */
    internal val cachedChunkCount: Int get() = lock.withLock { chunks.size }

    // ------------------------------------------------------------------
    // Shared helpers (lock held)
    // ------------------------------------------------------------------

    private fun moveReadPosition(index: Long) {
        if (readChunk != index || prefetchHalted) {
            readChunk = index
            prefetchHalted = false
            changed.signalAll()
        }
    }

    /** Waits briefly for the worker; an interrupt fails only this read. */
    private fun awaitChange() {
        try {
            changed.await(50, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted")
        }
    }

    private fun startIfNeeded() {
        if (started || cancelled) return
        started = true
        Thread(::run, "Edendale.BufferedByteSource").apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------------
    // Worker thread
    // ------------------------------------------------------------------

    private fun run() {
        while (true) {
            var target = -1L
            var keepAlive = false
            val stop = lock.withLock {
                while (!cancelled) {
                    target = nextChunk()
                    if (target >= 0) break
                    var waitMillis = 3_600_000L
                    if (file != null && keepAliveMillis > 0) {
                        val idle = nowMillis() - lastActivity
                        if (idle >= keepAliveMillis) {
                            keepAlive = true
                            break
                        }
                        waitMillis = keepAliveMillis - idle
                    }
                    try {
                        changed.await(waitMillis, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Only close() stops the worker.
                    }
                }
                cancelled
            }
            if (stop) break
            if (keepAlive) {
                // A dead connection reopens at the next fetch.
                if (!runCatching { file?.keepAlive() == true }.getOrDefault(false)) setFile(null)
                lastActivity = nowMillis()
            } else {
                fetchChunk(target)
            }
        }
        // Closes the connection on the thread that used it.
        setFile(null)
    }

    /** The chunk a read waits for, then the first one missing ahead of the read position. */
    private fun nextChunk(): Long {
        if (fatal != null) return -1
        if (wanted >= 0 && wanted != failedChunk && !chunks.containsKey(wanted)) return wanted
        if (prefetchHalted || !opened) return -1
        val end = minOf(lastChunk, readChunk + aheadChunks)
        var index = readChunk
        while (index <= end) {
            if (index != failedChunk && !chunks.containsKey(index)) return index
            index++
        }
        return -1
    }

    private fun isCancelled(): Boolean = lock.withLock { cancelled }

    /** Sets the worker's file, keeping close()'s reference in step and closing the old one. */
    private fun setFile(newFile: BufferedFile?) {
        val old = file
        file = newFile
        lock.withLock { liveFile = if (cancelled) null else newFile }
        if (old != null && old !== newFile) runCatching { old.close() }
    }

    private fun fetchChunk(index: Long) {
        var error: IOException? = null
        var data: ByteArray? = null
        var attempt = 0
        while (true) {
            if (file == null) {
                error = null
                val opened = try {
                    opener.open()
                } catch (e: IOException) {
                    error = e
                    null
                } catch (e: RuntimeException) {
                    error = IOException(e.message, e)
                    null
                }
                setFile(opened)
                if (opened != null) {
                    everOpened = true
                    val fileSize = opened.size
                    lock.withLock {
                        this.opened = true
                        size = fileSize
                        if (fileSize >= 0) lastChunk = if (fileSize == 0L) -1 else (fileSize - 1) / chunkSize
                        changed.signalAll()
                    }
                }
            }
            if (file != null) {
                try {
                    data = readChunk(index)
                } catch (e: IOException) {
                    error = e
                } catch (e: RuntimeException) {
                    error = IOException(e.message, e)
                }
                lastActivity = nowMillis()
                if (data != null) break
                // Drop the connection: a timed-out one rarely recovers.
                setFile(null)
            }
            // A login or path that never worked won't work on a retry either.
            if (!everOpened || attempt >= retryDelays.size || isCancelled()) break
            retryWait(retryDelays[attempt])
            attempt++
        }

        lock.withLock {
            if (data != null) {
                chunks[index] = data
                if (data.size < chunkSize) lastChunk = minOf(lastChunk, index)
                evict()
            } else if (!cancelled) {
                if (!everOpened) {
                    fatal = RemoteOpenException(host, error)
                } else {
                    failure = RemoteConnectionLostException(host, error?.message, error)
                    if (wanted == index) failedChunk = index
                    // Retrying again at once would only fail again; wait for a read.
                    prefetchHalted = true
                }
            }
            changed.signalAll()
        }
    }

    /** Reads one whole chunk, which may take several calls; null when cancelled. */
    private fun readChunk(index: Long): ByteArray? {
        val current = file ?: return null
        val offset = index * chunkSize
        var want = chunkSize.toLong()
        val fileSize = current.size
        if (fileSize >= 0) want = (fileSize - offset).coerceIn(0, want)
        val bytes = ByteArray(want.toInt())
        var filled = 0
        while (filled < want) {
            if (isCancelled()) return null
            val count = current.read(offset + filled, bytes, filled, (want - filled).toInt())
            if (count <= 0) break
            filled += count
        }
        return if (filled == bytes.size) bytes else bytes.copyOf(filled)
    }

    /** Drops the chunks farthest from the read position, those behind it first. */
    private fun evict() {
        while (chunks.size > cacheChunks) {
            val victim = chunks.keys.maxBy { index ->
                if (index < readChunk) (readChunk - index) * 4 else index - readChunk
            }
            chunks.remove(victim)
        }
    }

    /** The default retry delay: a timed wait that close() cuts short. */
    private fun awaitCancellable(delayMillis: Long) {
        val deadline = nowMillis() + delayMillis
        lock.withLock {
            while (!cancelled) {
                val remaining = deadline - nowMillis()
                if (remaining <= 0) break
                try {
                    changed.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }
}
