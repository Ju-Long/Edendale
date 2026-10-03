package com.babasama.edendale.remote

import com.babasama.edendale.connectors.MediaSourceKind
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * H.2.T1, Apple's `RemoteByteSourceTests`: [RemoteByteSource] over OkHttp
 * against a local server with `Range` support (A.5.5). Chunking, prefetch,
 * cached backward seeks, one refresh for a 401 (shared by concurrent
 * readers), a new link for a 410, an ignored `Range`, backoff, the 404
 * message, and cancellation. Small chunks keep the fixtures tiny.
 */
private typealias Recorded = LocalHttpServer.Request
private typealias Reply = LocalHttpServer.Response

class RemoteByteSourceTest {

    // MARK: - Local server

    private class StubServer(handler: (Recorded) -> Reply) : Closeable {
        private val server = LocalHttpServer(handler)
        val requests: List<Recorded> get() = server.requests
        val url: String get() = "http://127.0.0.1:${server.port}/file.mkv"
        val ranges: List<String> get() = requests.mapNotNull { it.header("Range") }

        override fun close() = server.close()
    }

    /** Serves [data] with `Range` support: 206 for a range, 416 past the end, 200 without one. */
    private fun file(data: ByteArray, request: Recorded): Reply {
        val range = request.header("Range") ?: return Reply(200, mapOf("Content-Length" to "${data.size}"), data)
        val (first, last) = range.removePrefix("bytes=").split('-').map { it.toLong() }
        if (first >= data.size) return Reply(416, mapOf("Content-Range" to "bytes */${data.size}"))
        val end = minOf(last, data.size - 1L)
        return Reply(206, mapOf("Content-Range" to "bytes $first-$end/${data.size}"), data.copyOfRange(first.toInt(), end.toInt() + 1))
    }

    private fun text(body: String, status: Int, headers: Map<String, String> = emptyMap()) =
        Reply(status, headers, body.toByteArray())

    /** Hands out requests to the stub server, counting refreshes; a refresh bumps the token or link. */
    private class StubResolver(
        private val url: String,
        override val kind: MediaSourceKind = MediaSourceKind.GOOGLE_DRIVE,
        override val usesPreauthorizedLinks: Boolean = false,
    ) : RemoteContentResolver {
        val refreshes = AtomicInteger()
        private val version = AtomicInteger()

        override fun contentRequest(refresh: Boolean): RemoteRequest {
            if (refresh) {
                refreshes.incrementAndGet()
                version.incrementAndGet()
            }
            val current = version.get()
            val headers = if (usesPreauthorizedLinks) emptyMap() else mapOf("Authorization" to "Bearer token$current")
            return RemoteRequest("$url?link=$current", headers)
        }
    }

    private val servers = mutableListOf<StubServer>()
    private val sources = mutableListOf<RemoteByteSource>()

    @AfterTest
    fun tearDown() {
        sources.forEach { it.close() }
        servers.forEach { it.close() }
    }

    private fun server(handler: (Recorded) -> Reply) = StubServer(handler).also { servers += it }

    private fun source(resolver: StubResolver, cachedChunks: Int = 4) = RemoteByteSource(
        resolver = resolver,
        http = OkHttpRemoteHttp(),
        config = RemoteByteSource.Config(
            chunkSize = CHUNK,
            cachedChunks = cachedChunks,
            backoffDelaysMillis = listOf(10, 10, 10),
            backoffJitter = 0.0,
        ),
    ).also { sources += it }

    private fun testData(count: Int) = ByteArray(count) { ((it * 31) xor (it shr 7)).toByte() }

    private fun RemoteByteSource.readBytes(position: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        val count = read(position, buffer, 0, length)
        return buffer.copyOf(count)
    }

    /** Reads on another thread, as the player's loader does; the result or the failure. */
    private fun readAsync(source: RemoteByteSource, position: Long, length: Int): Pair<Thread, AtomicReference<Result<ByteArray>?>> {
        val result = AtomicReference<Result<ByteArray>?>(null)
        val thread = Thread { result.set(runCatching { source.readBytes(position, length) }) }
        thread.start()
        return thread to result
    }

    // MARK: - Reading

    @Test
    fun `reads the whole file in range chunks`() {
        val data = testData(5000)
        val server = server { file(data, it) }
        val source = source(StubResolver(server.url))

        assertEquals(-1, source.length)
        val collected = java.io.ByteArrayOutputStream()
        while (collected.size() < data.size) {
            val bytes = source.readBytes(collected.size().toLong(), 700)
            assertTrue(bytes.isNotEmpty())
            collected.write(bytes)
        }
        assertContentEquals(data, collected.toByteArray())
        // The first Content-Range told it the size.
        assertEquals(5000, source.length)
        assertEquals(0, source.readBytes(5000, 10).size)
        // Every request asked for a whole, chunk-aligned range.
        server.ranges.forEach { range ->
            assertEquals(0L, range.removePrefix("bytes=").substringBefore('-').toLong() % CHUNK, range)
        }
    }

    @Test
    fun `prefetches the next chunk during sequential reads`() {
        val data = testData(8 * 1024)
        val server = server { file(data, it) }
        val source = source(StubResolver(server.url))

        source.readBytes(0, 512)
        source.readBytes(512, 512)
        // The second read was sequential: chunk 1 loads before anyone asks.
        val deadline = System.currentTimeMillis() + 3_000
        while ("bytes=1024-2047" !in server.ranges && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("bytes=1024-2047" in server.ranges, server.ranges.toString())
    }

    @Test
    fun `serves backward seeks from the cache`() {
        val data = testData(4096)
        val server = server { file(data, it) }
        val source = source(StubResolver(server.url))

        source.readBytes(3000, 100)
        source.readBytes(10, 100)
        val before = server.requests.size
        assertContentEquals(data.copyOfRange(3050, 3070), source.readBytes(3050, 20))
        assertEquals(before, server.requests.size)
    }

    @Test
    fun `a range past the end is the end of the file`() {
        val data = testData(2048)
        val server = server { file(data, it) }
        // Exactly two chunks, size unknown until the server says.
        val source = source(StubResolver(server.url))

        assertEquals(0, source.readBytes(2048, 64).size)
        assertEquals(2048, source.length)
    }

    // MARK: - Tokens and links

    @Test
    fun `refreshes the token once after a 401`() {
        val data = testData(2048)
        val server = server { request ->
            if (request.header("Authorization") == "Bearer token0") text("expired", 401) else file(data, request)
        }
        val resolver = StubResolver(server.url)
        val source = source(resolver)

        assertContentEquals(data.copyOf(100), source.readBytes(0, 100))
        assertEquals(1, resolver.refreshes.get())
    }

    @Test
    fun `concurrent readers share one refresh`() {
        val data = testData(8 * 1024)
        val bothRejected = CountDownLatch(2)
        val server = server { request ->
            if (request.header("Authorization") == "Bearer token0") {
                // Hold both rejections until both loads have asked.
                bothRejected.countDown()
                bothRejected.await(2, TimeUnit.SECONDS)
                text("expired", 401)
            } else {
                file(data, request)
            }
        }
        val resolver = StubResolver(server.url)
        val source = source(resolver)

        val (first, firstResult) = readAsync(source, 0, 100)
        val (second, secondResult) = readAsync(source, 5000, 100)
        first.join(5_000)
        second.join(5_000)
        assertContentEquals(data.copyOf(100), firstResult.get()!!.getOrThrow())
        assertContentEquals(data.copyOfRange(5000, 5100), secondResult.get()!!.getOrThrow())
        assertEquals(1, resolver.refreshes.get())
    }

    @Test
    fun `a rejected refreshed token asks for sign-in`() {
        val server = server { text("no", 401) }
        val resolver = StubResolver(server.url)
        val source = source(resolver)

        val error = assertFailsWith<RemoteSourceException> { source.readBytes(0, 100) }
        assertEquals(RemoteFailure.SignInRequired, error.failure)
        assertEquals(1, resolver.refreshes.get())
    }

    @Test
    fun `resolves a new link after a 410`() {
        val data = testData(3000)
        val server = server { request -> if (request.queryValue("link") == "0") text("gone", 410) else file(data, request) }
        val resolver = StubResolver(server.url, MediaSourceKind.DROPBOX, usesPreauthorizedLinks = true)
        val source = source(resolver)

        assertContentEquals(data.copyOfRange(1500, 1700), source.readBytes(1500, 200))
        assertEquals(1, resolver.refreshes.get())
        // A pre-authorized link never carries a token.
        assertTrue(server.requests.all { it.header("Authorization") == null })
    }

    // MARK: - Ignored ranges

    @Test
    fun `accepts a server that ignores Range at the start`() {
        val data = testData(5000)
        val server = server { Reply(200, mapOf("Content-Length" to "5000"), data) }
        val source = source(StubResolver(server.url))

        assertContentEquals(data.copyOf(300), source.readBytes(0, 300))
        assertEquals(5000, source.length)
    }

    @Test
    fun `an ignored Range later in the file fails after one retry`() {
        val data = testData(5000)
        val server = server { Reply(200, mapOf("Content-Length" to "5000"), data) }
        val source = source(StubResolver(server.url))

        val error = assertFailsWith<RemoteSourceException> { source.readBytes(3000, 100) }
        assertEquals(RemoteFailure.RangeUnsupported, error.failure)
        assertEquals(2, server.ranges.count { it == "bytes=2048-3071" })
    }

    // MARK: - Backoff and failures

    @Test
    fun `backs off when rate limited`() {
        val data = testData(2000)
        val attempts = AtomicInteger()
        val server = server { request ->
            if (attempts.incrementAndGet() <= 2) text("slow down", 429, mapOf("Retry-After" to "0")) else file(data, request)
        }
        val source = source(StubResolver(server.url))

        assertEquals(64, source.readBytes(0, 64).size)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `persistent server errors fail after the backoffs`() {
        val server = server { text("down", 503) }
        val source = source(StubResolver(server.url))

        val error = assertFailsWith<RemoteSourceException> { source.readBytes(0, 64) }
        assertEquals(RemoteFailure.RateLimited, error.failure)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun `a missing file says it's gone`() {
        val server = server { text("missing", 404) }
        val source = source(StubResolver(server.url, MediaSourceKind.ONE_DRIVE, usesPreauthorizedLinks = true))

        val error = assertFailsWith<RemoteSourceException> { source.readBytes(0, 64) }
        assertEquals(RemoteFailure.NotFound, error.failure)
        assertEquals(MediaSourceKind.ONE_DRIVE, error.kind)
        // The message never carries the URL.
        assertTrue("127.0.0.1" !in error.message.orEmpty())
    }

    @Test
    fun `Drive rate limits in a 403 are retried`() {
        val data = testData(2000)
        val attempts = AtomicInteger()
        val rateLimit = """{"error":{"errors":[{"domain":"usageLimits","reason":"userRateLimitExceeded"}],"code":403}}"""
        val server = server { request -> if (attempts.incrementAndGet() == 1) text(rateLimit, 403) else file(data, request) }
        val source = source(StubResolver(server.url))

        assertEquals(64, source.readBytes(0, 64).size)
    }

    @Test
    fun `an unreachable server fails after the backoffs`() {
        val deadUrl = StubServer { Reply(200) }.let { stub ->
            stub.url.also { stub.close() }
        }
        val source = source(StubResolver(deadUrl))

        val error = assertFailsWith<RemoteSourceException> { source.readBytes(0, 64) }
        assertEquals(RemoteFailure.Unreachable, error.failure)
    }

    // MARK: - Cancellation

    @Test
    fun `close fails a blocked read at once`() {
        val data = testData(2000)
        val server = server { request ->
            Thread.sleep(2_000)
            file(data, request)
        }
        val source = source(StubResolver(server.url))

        val started = System.currentTimeMillis()
        val (thread, result) = readAsync(source, 0, 64)
        Thread.sleep(150)
        source.close()
        thread.join(3_000)
        assertTrue(result.get()!!.isFailure)
        assertTrue(System.currentTimeMillis() - started < 1_500, "took ${System.currentTimeMillis() - started} ms")
        assertFailsWith<IOException> { source.readBytes(0, 64) }
    }

    @Test
    fun `an interrupt fails only the blocked read`() {
        val data = testData(2000)
        val server = server { request ->
            Thread.sleep(500)
            file(data, request)
        }
        val source = source(StubResolver(server.url))

        val started = System.currentTimeMillis()
        val (thread, result) = readAsync(source, 0, 64)
        Thread.sleep(100)
        thread.interrupt()
        thread.join(3_000)
        assertTrue(result.get()!!.exceptionOrNull() is InterruptedIOException, result.get().toString())
        assertTrue(System.currentTimeMillis() - started < 450, "took ${System.currentTimeMillis() - started} ms")
        // The player reads on after a seek.
        assertContentEquals(data.copyOf(64), source.readBytes(0, 64))
    }

    // MARK: - Parsing and classification

    @Test
    fun `parses Content-Range headers`() {
        assertEquals(RemoteByteSource.ContentRange(0, 499, 1234), RemoteByteSource.parseContentRange("bytes 0-499/1234"))
        assertEquals(RemoteByteSource.ContentRange(500, 999, null), RemoteByteSource.parseContentRange("bytes 500-999/*"))
        assertEquals(RemoteByteSource.ContentRange(0, null, 1234), RemoteByteSource.parseContentRange("bytes */1234"))
        assertEquals(null, RemoteByteSource.parseContentRange("items 0-1/2"))
        assertEquals(null, RemoteByteSource.parseContentRange(null))
    }

    @Test
    fun `classifies provider responses`() {
        fun action(status: Int, body: String = "", preauthorized: Boolean = false, headers: Map<String, String> = emptyMap()) =
            ProviderResponse.action(status, body.toByteArray(), { headers[it] }, preauthorized)
        assertEquals(ProviderResponse.Action.Refresh, action(401))
        assertEquals(ProviderResponse.Action.Refresh, action(410))
        assertEquals(ProviderResponse.Action.Backoff(null), action(403, """{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}"""))
        assertEquals(ProviderResponse.Action.Fail(RemoteFailure.AbusiveFile), action(403, """{"error":{"errors":[{"reason":"cannotDownloadAbusiveFile"}]}}"""))
        assertEquals(
            ProviderResponse.Action.Refresh,
            action(403, "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>", preauthorized = true),
        )
        assertEquals(ProviderResponse.Action.Fail(RemoteFailure.AccessDenied), action(403, "forbidden"))
        assertEquals(ProviderResponse.Action.Fail(RemoteFailure.NotFound), action(404))
        assertEquals(ProviderResponse.Action.Fail(RemoteFailure.NotFound), action(409, """{"error_summary":"path/not_found/"}"""))
        assertEquals(ProviderResponse.Action.Backoff(null), action(429))
        assertEquals(ProviderResponse.Action.Backoff(null), action(503))
        assertEquals(ProviderResponse.Action.Backoff(3.0), action(429, headers = mapOf("Retry-After" to "3")))
        assertEquals(ProviderResponse.Action.Backoff(10.0), action(429, headers = mapOf("Retry-After" to "600")))
        assertEquals(ProviderResponse.Action.Fail(RemoteFailure.ServerError(418)), action(418))
    }

    @Test
    fun `requests never print their URL or headers`() {
        val request = RemoteRequest("https://example.com/file?sig=secret", mapOf("Authorization" to "Bearer secret"))
        assertTrue("secret" !in request.toString())
        assertTrue("secret" !in request.with("Range", "bytes=0-1").toString())
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
