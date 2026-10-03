package com.babasama.edendale.remote

import com.babasama.edendale.connectors.MediaSourceKind
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLException
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * One HTTP GET a byte source or a listing makes (H.2). The URL and headers
 * may carry a token or a signed link, so they never reach a log or a message.
 */
class RemoteRequest(val url: String, val headers: Map<String, String> = emptyMap()) {
    fun with(name: String, value: String) = RemoteRequest(url, headers + (name to value))

    override fun toString() = "RemoteRequest(<redacted>)"
}

class RemoteResponse(val status: Int, headers: Map<String, String>, val body: ByteArray) {
    private val headers = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = headers[name.lowercase()]
}

/** The transport: OkHttp in the app ([OkHttpRemoteHttp]), a local server in tests. */
fun interface RemoteHttp {
    /**
     * A GET that keeps at most [bodyLimit] bytes of a 2xx body (a server that
     * ignores `Range` would otherwise send the whole file) and 64 KiB of an
     * error body.
     */
    fun newCall(request: RemoteRequest, bodyLimit: Int): RemoteCall
}

interface RemoteCall {
    /** Blocks until the response; throws on a transport failure or after [cancel]. */
    @Throws(IOException::class)
    fun execute(): RemoteResponse

    fun cancel()
}

/**
 * How a byte source asks for a remote file's bytes (Apple's
 * `RemoteContentResolver`): an authorized request to the file (Drive,
 * WebDAV), or a short-lived pre-authorized link (OneDrive, Dropbox, S3).
 */
interface RemoteContentResolver {
    val kind: MediaSourceKind

    /** Requests are pre-authorized links, so a refresh gets a new link rather than a new token. */
    val usesPreauthorizedLinks: Boolean get() = false

    /**
     * The request for the file's bytes; [refresh] forces a new token or link
     * after a 401, an expired link, or a 410. Blocking: called on a loader
     * thread.
     */
    @Throws(IOException::class)
    fun contentRequest(refresh: Boolean): RemoteRequest
}

/** Why a remote read or listing failed, for the message the viewer sees. */
sealed interface RemoteFailure {
    data object SignInRequired : RemoteFailure
    data object AccessDenied : RemoteFailure
    data object NotFound : RemoteFailure
    data object RateLimited : RemoteFailure
    data object RangeUnsupported : RemoteFailure
    data object AbusiveFile : RemoteFailure
    data object Unreachable : RemoteFailure
    data object UntrustedCertificate : RemoteFailure
    data class ServerError(val status: Int) : RemoteFailure
}

/** A provider read failed; [failure] says why. The message never carries a URL or token. */
class RemoteSourceException(
    val kind: MediaSourceKind,
    val failure: RemoteFailure,
    cause: Throwable? = null,
) : IOException("${kind.raw}: $failure", cause)

/** Turns the status of a failed provider request into what to do next (Apple's `ProviderResponse`). */
object ProviderResponse {
    sealed interface Action {
        /** Get a new access token or link, once, then retry. */
        data object Refresh : Action

        /** Wait (for `Retry-After` when given, in seconds), then retry. */
        data class Backoff(val retryAfterSeconds: Double?) : Action

        data class Fail(val failure: RemoteFailure) : Action
    }

    fun action(status: Int, body: ByteArray, header: (String) -> String?, preauthorizedLink: Boolean): Action =
        when (status) {
            401 -> Action.Refresh
            403 -> {
                val text = body.decodePrefix().lowercase()
                when {
                    "ratelimitexceeded" in text || "rate_limit_exceeded" in text ||
                        "slowdown" in text || "too_many_requests" in text -> Action.Backoff(retryAfter(header))
                    "cannotdownloadabusivefile" in text -> Action.Fail(RemoteFailure.AbusiveFile)
                    "downloadquotaexceeded" in text -> Action.Fail(RemoteFailure.RateLimited)
                    // An expired signed link (S3's "Request has expired") or a
                    // OneDrive download URL past its lifetime: resolve a new one.
                    preauthorizedLink -> Action.Refresh
                    else -> Action.Fail(RemoteFailure.AccessDenied)
                }
            }
            404 -> Action.Fail(RemoteFailure.NotFound)
            // Dropbox reports endpoint errors as 409 with a summary.
            409 -> Action.Fail(if ("not_found" in body.decodePrefix()) RemoteFailure.NotFound else RemoteFailure.ServerError(status))
            // Dropbox temporary links expire after four hours.
            410 -> Action.Refresh
            408, 429, 500, 502, 503, 504 -> Action.Backoff(retryAfter(header))
            else -> Action.Fail(RemoteFailure.ServerError(status))
        }

    /** `Retry-After` in seconds, capped so a hostile value can't stall playback for minutes. */
    fun retryAfter(header: (String) -> String?): Double? =
        header("Retry-After")?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.coerceAtMost(10.0)

    private fun ByteArray.decodePrefix(): String = String(copyOf(minOf(size, 16_384)), Charsets.UTF_8)
}

/**
 * Random-access bytes of a remote file over HTTP (H.2, Apple's
 * `RemoteByteSource`), for the player's data source.
 *
 * The file is read in [Config.chunkSize] `Range` chunks. While reads are
 * sequential the next chunk is prefetched, and up to [Config.cachedChunks]
 * stay cached, so the MKV cues or MP4 `moov` at the end of a file stay cached
 * through the open. Reads block until their chunk arrives; interrupting the
 * reading thread fails that read only, and [close] fails every read at once.
 *
 *     206                        serve the range
 *     200 at offset 0            accept (the server ignored Range; Graph does)
 *     200 at any other offset    retry once, then fail the read
 *     401                        refresh the token (or link) once, then retry
 *     403 rate limit, 429, 5xx   back off 0.5, 1, 2 s with jitter, then fail
 *     403 expired link, 410      resolve a new link once, then retry
 *     404                        fail: "This file is no longer in <provider>"
 *
 * A 401 seen by several loads at once triggers one refresh, which they share.
 * Pure Kotlin: the JVM suite drives it against a local HTTP server.
 */
class RemoteByteSource(
    private val resolver: RemoteContentResolver,
    private val http: RemoteHttp,
    /** The file size when the listing reported it; otherwise the first `Content-Range` gives it. */
    length: Long? = null,
    private val config: Config = Config(),
) : Closeable {

    data class Config(
        val chunkSize: Long = 4L shl 20,
        val cachedChunks: Int = 8,
        /** Delays before each retry of a rate-limited or failed request; their count is the number of retries. */
        val backoffDelaysMillis: List<Long> = listOf(500, 1_000, 2_000),
        /** Jitter on each backoff delay, as a fraction. */
        val backoffJitter: Double = 0.2,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val loader: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "RemoteByteSource").apply { isDaemon = true }
    }

    // Guarded by lock.
    private var knownLength = length ?: -1L
    private val chunks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private val loads = HashSet<Long>()
    private val failures = HashMap<Long, IOException>()
    private val waiters = HashMap<Long, Int>()
    private val calls = HashSet<RemoteCall>()
    private var lastReadEnd = -1L
    private var cancelled = false

    // The request every load uses, and how many refreshes produced it (guarded by requestLock).
    private val requestLock = Any()
    private var request: RemoteRequest? = null
    private var generation = 0

    /** The file size, or -1 until a listing or response stated it. */
    val length: Long get() = lock.withLock { knownLength }

    /**
     * Reads up to [length] bytes at [position] into [buffer]; returns the
     * count, or 0 at the end of the file. Blocks until the chunk arrives.
     */
    @Throws(IOException::class)
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || position < 0) return 0
        val index = position / config.chunkSize
        lock.withLock {
            while (true) {
                if (cancelled) throw IOException("Closed")
                if (knownLength >= 0 && position >= knownLength) return 0

                chunks[index]?.let { data ->
                    val start = (position - index * config.chunkSize).toInt()
                    // A chunk shorter than the offset ends the file.
                    if (start >= data.size) return 0
                    val count = minOf(length, data.size - start)
                    System.arraycopy(data, start, buffer, offset, count)
                    val sequential = position == lastReadEnd
                    lastReadEnd = position + count
                    if (sequential || start + count == data.size) prefetch(index + 1)
                    return count
                }

                failures.remove(index)?.let { throw it }
                if (index !in loads) startLoading(index)
                waiters[index] = (waiters[index] ?: 0) + 1
                try {
                    // Wakes at least every 50 ms to notice an interrupt.
                    changed.await(50, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Read interrupted")
                } finally {
                    val left = (waiters[index] ?: 1) - 1
                    if (left == 0) waiters.remove(index) else waiters[index] = left
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Read interrupted")
            }
        }
    }

    /** Fails every blocked and future read, and cancels requests in flight. */
    override fun close() {
        val running = lock.withLock {
            cancelled = true
            chunks.clear()
            changed.signalAll()
            calls.toList().also { calls.clear() }
        }
        running.forEach { it.cancel() }
        loader.shutdownNow()
    }

    // MARK: - Chunk loading (called with lock held)

    private fun prefetch(index: Long) {
        if (index in chunks || index in loads || index in failures) return
        if (knownLength >= 0 && index * config.chunkSize >= knownLength) return
        // One chunk ahead hides a request's latency; more would spend
        // bandwidth on data a seek may throw away.
        if (loads.size >= 2) return
        startLoading(index)
    }

    private fun startLoading(index: Long) {
        loads += index
        try {
            loader.execute { load(index) }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            loads -= index
        }
    }

    private fun load(index: Long) {
        val start = index * config.chunkSize
        val result = runCatching { fillChunk(start) }
        lock.withLock {
            loads -= index
            if (cancelled) return
            result.onSuccess { data ->
                chunks[index] = data
                if (data.size < config.chunkSize && knownLength < 0) {
                    // A short chunk without a stated total is the end of the file.
                    knownLength = start + data.size
                }
                val excess = chunks.size - config.cachedChunks
                if (excess > 0) {
                    chunks.keys.filter { it != index }.take(excess).forEach { chunks.remove(it) }
                }
            }.onFailure { error ->
                // A failed prefetch nobody waited for is dropped, so the read that reaches it tries again.
                if (index in waiters) failures[index] = error.asIOException()
            }
            changed.signalAll()
        }
    }

    /** One chunk, continuing where a server returned less of a range than asked for. */
    private fun fillChunk(start: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var position = start
        val chunkEnd = start + config.chunkSize
        while (position < chunkEnd) {
            val known = length
            val end = if (known >= 0) minOf(chunkEnd, known) else chunkEnd
            if (position >= end) break
            val (piece, total) = fetch(position, end)
            if (total != null) lock.withLock { if (knownLength < 0) knownLength = total }
            out.write(piece)
            position += piece.size
            // An empty or short piece without a larger stated total ends the file.
            if (piece.isEmpty()) break
            if (position < end && (total == null || position >= total)) break
        }
        return out.toByteArray()
    }

    // MARK: - HTTP

    /** Bytes [from, until), following the response table above. */
    private fun fetch(from: Long, until: Long): Pair<ByteArray, Long?> {
        val kind = resolver.kind
        var refreshAfter: Int? = null
        var refreshed = false
        var retried200 = false
        var backoffs = 0

        while (true) {
            if (lock.withLock { cancelled }) throw IOException("Closed")
            val (base, requestGeneration) = currentRequest(refreshAfter)
            refreshAfter = null
            val ranged = base
                .with("Range", "bytes=$from-${until - 1}")
                // Byte offsets must refer to the file itself, never to a compressed rendition.
                .with("Accept-Encoding", "identity")
            val call = http.newCall(ranged, bodyLimit = (until - from).toInt())
            val response = try {
                lock.withLock {
                    if (cancelled) throw IOException("Closed")
                    calls += call
                }
                call.execute()
            } catch (error: IOException) {
                if (lock.withLock { cancelled }) throw error
                if (error is SSLException) throw RemoteSourceException(kind, RemoteFailure.UntrustedCertificate, error)
                if (backoffs >= config.backoffDelaysMillis.size) {
                    throw RemoteSourceException(kind, RemoteFailure.Unreachable, error)
                }
                backoff(backoffs++, null)
                continue
            } finally {
                lock.withLock { calls -= call }
            }

            when (response.status) {
                206 -> {
                    val range = parseContentRange(response.header("Content-Range"))
                    if (range != null && range.start != from) throw RemoteSourceException(kind, RemoteFailure.RangeUnsupported)
                    return response.body to range?.total
                }
                200 -> {
                    if (from == 0L) {
                        return response.body to response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                    }
                    if (retried200) throw RemoteSourceException(kind, RemoteFailure.RangeUnsupported)
                    retried200 = true
                }
                // The range starts at or past the end of the file.
                416 -> return ByteArray(0) to (parseContentRange(response.header("Content-Range"))?.total ?: from)
                else -> when (val action = ProviderResponse.action(response.status, response.body, response::header, resolver.usesPreauthorizedLinks)) {
                    ProviderResponse.Action.Refresh -> {
                        if (refreshed) {
                            throw RemoteSourceException(
                                kind,
                                if (resolver.usesPreauthorizedLinks) RemoteFailure.AccessDenied else RemoteFailure.SignInRequired,
                            )
                        }
                        refreshed = true
                        refreshAfter = requestGeneration
                    }
                    is ProviderResponse.Action.Backoff -> {
                        if (backoffs >= config.backoffDelaysMillis.size) throw RemoteSourceException(kind, RemoteFailure.RateLimited)
                        backoff(backoffs++, action.retryAfterSeconds)
                    }
                    is ProviderResponse.Action.Fail -> throw RemoteSourceException(kind, action.failure)
                }
            }
        }
    }

    /**
     * The request to use. After a 401 or an expired link, [refreshAfter] is
     * the generation that failed: the first load to report it refreshes, and
     * the others take the result (single flight).
     */
    private fun currentRequest(refreshAfter: Int?): Pair<RemoteRequest, Int> = synchronized(requestLock) {
        val current = request
        if (refreshAfter != null && refreshAfter == generation) {
            request = resolver.contentRequest(refresh = true)
            generation += 1
        } else if (current == null) {
            request = resolver.contentRequest(refresh = false)
        }
        request!! to generation
    }

    private fun backoff(attempt: Int, retryAfterSeconds: Double?) {
        val base = retryAfterSeconds?.let { (it * 1_000).toLong() } ?: config.backoffDelaysMillis[attempt]
        val jitter = config.backoffJitter
        val delay = if (jitter > 0) (base * Random.nextDouble(1 - jitter, 1 + jitter)).toLong() else base
        try {
            Thread.sleep(delay)
        } catch (interrupted: InterruptedException) {
            throw InterruptedIOException("Closed")
        }
    }

    private fun Throwable.asIOException(): IOException = this as? IOException ?: IOException(message, this)

    data class ContentRange(val start: Long, val end: Long?, val total: Long?)

    companion object {
        /** Parses `bytes 0-499/1234`, `bytes 0-499/*`, and `bytes */1234`. */
        fun parseContentRange(header: String?): ContentRange? {
            val value = header?.trim() ?: return null
            if (!value.lowercase().startsWith("bytes ")) return null
            val spec = value.substring("bytes ".length)
            val parts = spec.split('/', limit = 2)
            if (parts.size != 2) return null
            val total = parts[1].toLongOrNull()
            if (parts[0] == "*") return ContentRange(0, null, total)
            val bounds = parts[0].split('-', limit = 2)
            if (bounds.size != 2) return null
            val start = bounds[0].toLongOrNull() ?: return null
            val end = bounds[1].toLongOrNull() ?: return null
            return ContentRange(start, end, total)
        }
    }
}
