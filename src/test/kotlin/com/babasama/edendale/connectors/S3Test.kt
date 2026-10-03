package com.babasama.edendale.connectors

import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.OkHttpRemoteHttp
import com.babasama.edendale.remote.RemoteByteSource
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * H.5.T1 (Apple's CloudListingTests S3 cases): SigV4 against AWS's published
 * examples, path- and host-style addressing, `ListObjectsV2` pages (AWS and
 * MinIO shapes), errors, and streaming through pre-signed URLs.
 */
class S3Test {

    private val signer = S3Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1")

    /** 2013-05-24T00:00:00Z, the date AWS's examples use. */
    private val exampleDate = 1_369_353_600_000L

    // MARK: - Signing

    @Test
    fun `signs list requests like AWS's example`() {
        // "GET Bucket (List Objects)" from the AWS Signature Version 4 documentation.
        val signed = signer.sign(RemoteRequest("https://examplebucket.s3.amazonaws.com/?max-keys=2&prefix=J"), exampleDate)
        assertEquals("20130524T000000Z", signed.headers["x-amz-date"])
        assertEquals(S3Signer.EMPTY_PAYLOAD_HASH, signed.headers["x-amz-content-sha256"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            signed.headers["Authorization"],
        )
    }

    @Test
    fun `presigns URLs like AWS's example`() {
        // The query-string authentication example from the AWS documentation.
        val url = signer.presign("https://examplebucket.s3.amazonaws.com/test.txt", exampleDate, expiresSeconds = 86_400)!!
        val query = url.substringAfter('?').split('&').associate { it.substringBefore('=') to SourceUrl.decode(it.substringAfter('=')) }
        assertEquals("aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", query["X-Amz-Signature"])
        assertEquals("AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request", query["X-Amz-Credential"])
        assertTrue(url.startsWith("https://examplebucket.s3.amazonaws.com/test.txt?"))
    }

    @Test
    fun `encodes and orders like AWS`() {
        assertEquals("a%20b%2Fc~-._", S3Signer.uriEncode("a b/c~-._"))
        assertEquals("%C3%A9", S3Signer.uriEncode("é"))
        assertEquals("a=1&a=2&b=", S3Signer.canonicalQuery(listOf("b" to "", "a" to "2", "a" to "1")))
        assertEquals("minio.local:9000", S3Signer.hostHeader("http://minio.local:9000/films/"))
        assertEquals("s3.example.com", S3Signer.hostHeader("https://s3.example.com:443/films/"))
    }

    // MARK: - Addressing

    @Test
    fun `addresses buckets by path or host`() {
        val pathStyle = S3Configuration("http://minio.local:9000", "us-east-1", "films", usesPathStyle = true)
        assertEquals("http://minio.local:9000/films/Heat%20%281995%29/Heat.mkv", S3.bucketUrl(pathStyle, "Heat (1995)/Heat.mkv"))
        val aws = S3Configuration("https://s3.us-east-1.amazonaws.com", "us-east-1", "films", usesPathStyle = false)
        assertEquals("https://films.s3.us-east-1.amazonaws.com/a%20b.mkv?list-type=2", S3.bucketUrl(aws, "a b.mkv", listOf("list-type" to "2")))
        assertEquals(false, S3.defaultUsesPathStyle(aws.endpoint, "films"))
        assertEquals(true, S3.defaultUsesPathStyle(aws.endpoint, "my.films"))
        assertEquals(true, S3.defaultUsesPathStyle(pathStyle.endpoint, "films"))
    }

    // MARK: - Listing

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) =
        LocalHttpServer(handler).also { servers += it }

    private fun xml(body: String, status: Int = 200) = LocalHttpServer.Response(status, mapOf("Content-Type" to "application/xml"), body.toByteArray())

    @Test
    fun `lists a prefix across pages`() = runBlocking {
        val pages = AtomicInteger()
        val server = server {
            if (pages.incrementAndGet() == 1) {
                xml(
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <Name>films</Name><Prefix>Movies/</Prefix><IsTruncated>true</IsTruncated>
                      <NextContinuationToken>next-1</NextContinuationToken>
                      <Contents><Key>Movies/</Key><Size>0</Size></Contents>
                      <Contents><Key>Movies/Heat.1995.mkv</Key><Size>1000</Size><LastModified>2024-01-02T03:04:05.000Z</LastModified></Contents>
                      <CommonPrefixes><Prefix>Movies/Classics/</Prefix></CommonPrefixes>
                    </ListBucketResult>""",
                )
            } else {
                // MinIO's shape: no namespace prefix differences, a nested Owner.
                xml(
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <IsTruncated>false</IsTruncated>
                      <Contents><Key>Movies/Alien.1979.mp4</Key><Size>5</Size><Owner><ID>x</ID></Owner></Contents>
                      <Contents><Key>Movies/.hidden.mkv</Key><Size>5</Size></Contents>
                    </ListBucketResult>""",
                )
            }
        }
        val configuration = S3Configuration("http://127.0.0.1:${server.port}", "us-east-1", "films", usesPathStyle = true)
        val connector = S3Connector(configuration, ServerLogin("AKID", "secret"), OkHttpRemoteHttp()) { exampleDate }

        val folder = SourceUrl.s3(connector.accountKey, "films", "Movies/")
        val entries = connector.list(folder)
        assertEquals(listOf("Classics", "Alien.1979.mp4", "Heat.1995.mkv"), entries.map { it.name })
        assertTrue(entries.first().isDirectory)
        assertEquals("Movies/Classics/", SourceUrl.parseS3(entries.first().url)?.key)
        assertEquals(1000L, entries.last().size)
        assertEquals(1_704_164_645_000L, entries.last().modifiedEpochMillis)
        assertEquals("films", connector.accountLabel)

        val requests = server.requests
        assertEquals(2, requests.size)
        assertEquals("/films/", requests[0].path)
        assertEquals("2", requests[0].queryValue("list-type"))
        assertEquals("%2F", requests[0].queryValue("delimiter"))
        assertEquals("Movies%2F", requests[0].queryValue("prefix"))
        assertEquals("next-1", requests[1].queryValue("continuation-token"))
        assertTrue(requests.all { it.header("Authorization")?.startsWith("AWS4-HMAC-SHA256 Credential=AKID/") == true })
    }

    @Test
    fun `explains login and region errors`() {
        val signature = "<Error><Code>SignatureDoesNotMatch</Code></Error>".toByteArray()
        assertEquals(ConnectorFailure.AuthenticationFailed("s3.example.com"), S3.failure(403, signature, "s3.example.com"))
        val region = "<Error><Code>AuthorizationHeaderMalformed</Code><Region>eu-west-1</Region></Error>".toByteArray()
        assertEquals(ConnectorFailure.BucketInAnotherRegion("eu-west-1"), S3.failure(400, region, "s3.example.com"))
        assertEquals(ConnectorFailure.ListingFailed("s3.example.com"), S3.failure(404, "<Error><Code>NoSuchBucket</Code></Error>".toByteArray(), "s3.example.com"))
        assertEquals(ConnectorFailure.ServerError(MediaSourceKind.S3, 500), S3.failure(500, ByteArray(0), "s3.example.com"))
    }

    @Test
    fun `a refused key pair fails the listing`() {
        val server = server { xml("<Error><Code>InvalidAccessKeyId</Code></Error>", status = 403) }
        val configuration = S3Configuration("http://127.0.0.1:${server.port}", "us-east-1", "films", usesPathStyle = true)
        val connector = S3Connector(configuration, ServerLogin("AKID", "wrong"), OkHttpRemoteHttp())
        val error = assertFailsWith<ConnectorException> { runBlocking { connector.validate() } }
        assertEquals(ConnectorFailure.AuthenticationFailed("127.0.0.1"), error.failure)
    }

    // MARK: - Streaming

    @Test
    fun `streams through freshly signed URLs`() {
        val configuration = S3Configuration("https://s3.example.com", "auto", "films", usesPathStyle = true)
        val resolver = S3ContentResolver(configuration, ServerLogin("AKID", "secret"), "Movies/Heat 1995.mkv")
        assertTrue(resolver.usesPreauthorizedLinks)
        val url = resolver.contentRequest(refresh = false).url
        assertTrue(url.startsWith("https://s3.example.com/films/Movies/Heat%201995.mkv?"), url)
        assertTrue("X-Amz-Signature=" in url)
        assertTrue("%2Fauto%2Fs3%2Faws4_request" in url, url)
        // A pre-signed link carries no Authorization header.
        assertTrue(resolver.contentRequest(refresh = true).headers.isEmpty())
    }

    @Test
    fun `an expired link is signed again`() {
        val data = ByteArray(2048) { it.toByte() }
        val attempts = AtomicInteger()
        val server = server { request ->
            if (attempts.incrementAndGet() == 1) {
                xml("<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>", status = 403)
            } else {
                val (first, last) = request.header("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
                val end = minOf(last, data.size - 1)
                LocalHttpServer.Response(206, mapOf("Content-Range" to "bytes $first-$end/${data.size}"), data.copyOfRange(first, end + 1))
            }
        }
        val configuration = S3Configuration("http://127.0.0.1:${server.port}", "us-east-1", "films", usesPathStyle = true)
        val http = OkHttpRemoteHttp()
        val source = RemoteByteSource(
            S3ContentResolver(configuration, ServerLogin("AKID", "secret"), "Heat.1995.mkv"),
            http,
            config = RemoteByteSource.Config(chunkSize = 1024),
        )
        try {
            val buffer = ByteArray(100)
            assertEquals(100, source.read(0, buffer, 0, 100))
            assertContentEquals(data.copyOf(100), buffer)
            assertTrue(server.requests.all { it.path == "/films/Heat.1995.mkv" && it.queryValue("X-Amz-Signature") != null })
        } finally {
            source.close()
        }
    }
}
