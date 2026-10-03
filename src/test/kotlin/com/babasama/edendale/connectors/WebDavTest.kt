package com.babasama.edendale.connectors

import com.babasama.edendale.remote.HttpAuth
import com.babasama.edendale.remote.HttpAuthSession
import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.OkHttpRemoteHttp
import com.babasama.edendale.remote.RemoteByteSource
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H.3.T1: recorded `PROPFIND` responses from Nextcloud, Synology, and Apache
 * `mod_dav` (Apple's CloudListingTests WebDAV cases), WebDAV addresses, Basic
 * and Digest logins (RFC 7617 and RFC 7616 vectors), and listing and
 * streaming against a local server.
 */
class WebDavTest {

    // MARK: - Recorded listings

    private val nextcloud = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/Heat%20(1995).mkv</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength>
              <d:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</d:getlastmodified></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>https://HOST/remote.php/dav/files/me/Movies/Season%201/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/.DS_Store</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `parses Nextcloud's multistatus`() {
        val entries = WebDav.parseMultistatus(
            nextcloud.replace("HOST", "cloud.example.com").toByteArray(),
            requestUrl = "https://cloud.example.com/remote.php/dav/files/me/Movies/",
            canonicalDirectory = "davs://cloud.example.com/remote.php/dav/files/me/Movies/",
        )!!
        assertEquals(listOf("Heat (1995).mkv", "Season 1", ".DS_Store"), entries.map { it.name })
        val heat = entries[0]
        assertEquals("davs://cloud.example.com/remote.php/dav/files/me/Movies/Heat%20(1995).mkv", heat.url)
        assertEquals(1234L, heat.size)
        assertEquals(784_903_526_000L, heat.modifiedEpochMillis)
        assertFalse(heat.isDirectory)
        assertTrue(entries[1].isDirectory)
        assertTrue(entries[1].url.endsWith("/Season%201/"))
        // A 404 propstat's empty size doesn't count.
        assertNull(entries[1].size)
    }

    @Test
    fun `parses Apache mod_dav's prefixes`() {
        // Uppercase prefix, and properties in another prefix bound to DAV:.
        val apache = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"><D:response xmlns:lp1="DAV:">
            <D:href>/dav/Alien.1979.mp4</D:href>
            <D:propstat><D:prop><lp1:resourcetype/><lp1:getcontentlength>5</lp1:getcontentlength></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>
        """.trimIndent()
        val entries = WebDav.parseMultistatus(apache.toByteArray(), "http://nas.local/dav/", "dav://nas.local/dav/")!!
        assertEquals(listOf("Alien.1979.mp4"), entries.map { it.name })
        assertEquals(5L, entries.first().size)
        assertEquals("dav://nas.local/dav/Alien.1979.mp4", entries.first().url)
    }

    @Test
    fun `parses Synology's absolute hrefs with raw spaces and a port`() {
        val synology = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
            <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
            <D:href>https://nas.local:5006/video/</D:href>
            <D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response>
            <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
            <D:href>https://nas.local:5006/video/My Movies/</D:href>
            <D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype>
            <lp1:getlastmodified>Sat, 03 Oct 2026 10:00:00 GMT</lp1:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response>
            <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
            <D:href>https://nas.local:5006/video/Am%C3%A9lie.2001.mkv</D:href>
            <D:propstat><D:prop><lp1:resourcetype/><lp1:getcontentlength>4294967296</lp1:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = WebDav.parseMultistatus(synology.toByteArray(), "https://nas.local:5006/video/", "davs://nas.local:5006/video/")!!
        assertEquals(listOf("My Movies", "Amélie.2001.mkv"), entries.map { it.name })
        assertEquals("davs://nas.local:5006/video/My%20Movies/", entries[0].url)
        assertTrue(entries[0].isDirectory)
        assertEquals("davs://nas.local:5006/video/Am%C3%A9lie.2001.mkv", entries[1].url)
        // Larger than 4 GiB.
        assertEquals(4_294_967_296L, entries[1].size)
    }

    @Test
    fun `a body that isn't a multistatus is no listing`() {
        assertNull(WebDav.parseMultistatus("<html><body>Login</body></html>".toByteArray(), "https://a/", "davs://a/"))
        assertNull(WebDav.parseMultistatus("not xml".toByteArray(), "https://a/", "davs://a/"))
        // No entity expansion from a server's DTD.
        val hostile = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x "x">]><d:multistatus xmlns:d="DAV:"/>"""
        assertNull(WebDav.parseMultistatus(hostile.toByteArray(), "https://a/", "davs://a/"))
    }

    // MARK: - Addresses

    @Test
    fun `normalizes WebDAV addresses`() {
        assertEquals("davs://cloud.example.com/remote.php/dav/files/me/", WebDav.canonicalRoot("https://cloud.example.com/remote.php/dav/files/me"))
        assertEquals("dav://nas.local:5005/", WebDav.canonicalRoot("http://nas.local:5005"))
        assertEquals("davs://nas.local/dav/", WebDav.canonicalRoot("nas.local/dav"))
        assertEquals("davs://nas.local/", WebDav.canonicalRoot("https://me:pw@nas.local/"))
        assertEquals("davs://nas.local/a/", WebDav.canonicalRoot("https://nas.local/a?x=1#y"))
        assertNull(WebDav.canonicalRoot("ftp://nas.local/"))
        assertNull(WebDav.canonicalRoot("  "))
        assertEquals("https://nas.local:5006/a%20b/", WebDav.httpUrl("davs://nas.local:5006/a%20b/"))
        assertEquals("http://nas.local/x.mkv", WebDav.httpUrl("dav://nas.local/x.mkv"))
        assertNull(WebDav.httpUrl("smb://nas/x.mkv"))
        assertEquals(784_903_526_000L, WebDav.parseHttpDate("Tue, 15 Nov 1994 12:45:26 GMT"))
        assertNull(WebDav.parseHttpDate("yesterday"))
    }

    // MARK: - Logins

    @Test
    fun `Basic matches RFC 7617`() {
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", HttpAuth.basic(ServerLogin("Aladdin", "open sesame")))
        assertTrue("open sesame" !in ServerLogin("Aladdin", "open sesame").toString())
    }

    @Test
    fun `Digest matches RFC 7616's examples`() {
        val login = ServerLogin("Mufasa", "Circle of Life")
        val cnonce = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"
        fun challenge(algorithm: String) = HttpAuth.parseChallenges(
            """Digest realm="http-auth@example.org", qop="auth, auth-int", algorithm=$algorithm, """ +
                """nonce="7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", opaque="FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS"""",
        ).single()
        val md5 = HttpAuth.digest(challenge("MD5"), login, "GET", "/dir/index.html", 1, cnonce)!!
        assertTrue("""response="8ca523f5e9506fed4657c9700eebdbec"""" in md5, md5)
        assertTrue("nc=00000001" in md5 && "qop=auth" in md5 && "opaque=\"FQhe" in md5, md5)
        val sha = HttpAuth.digest(challenge("SHA-256"), login, "GET", "/dir/index.html", 1, cnonce)!!
        assertTrue("""response="753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1"""" in sha, sha)
        // auth-int alone can't be answered.
        val intOnly = HttpAuth.parseChallenges("""Digest realm="r", nonce="n", qop="auth-int"""").single()
        assertNull(HttpAuth.digest(intOnly, login, "GET", "/", 1))
    }

    @Test
    fun `parses several challenges with quoted commas`() {
        val challenges = HttpAuth.parseChallenges(
            """Digest realm="a, b", nonce="n", qop="auth", algorithm=SHA-256, Basic realm="files", Negotiate""",
        )
        assertEquals(listOf("Digest", "Basic", "Negotiate"), challenges.map { it.scheme })
        assertEquals("a, b", challenges[0].param("realm"))
        assertEquals("SHA-256", challenges[0].param("algorithm"))
        assertEquals("files", challenges[1].param("realm"))
        assertTrue(HttpAuth.parseChallenges(null).isEmpty())
        assertEquals("/a%20b/c?x=1", HttpAuth.requestTarget("https://h:1/a%20b/c?x=1"))
        assertEquals("/", HttpAuth.requestTarget("https://h"))
    }

    @Test
    fun `a session prefers Digest and never sends a login unasked`() {
        val session = HttpAuthSession(ServerLogin("me", "pw"))
        val request = com.babasama.edendale.remote.RemoteRequest("https://h/a.mkv")
        assertNull(session.authorize(request).headers["Authorization"])
        assertTrue(session.learn("""Basic realm="x", Digest realm="x", nonce="n", qop="auth""""))
        val first = session.authorize(request).headers["Authorization"]!!
        val second = session.authorize(request).headers["Authorization"]!!
        assertTrue(first.startsWith("Digest ") && "nc=00000001" in first, first)
        assertTrue("nc=00000002" in second, second)
        // A guest has nothing to answer with.
        assertFalse(HttpAuthSession(ServerLogin("", "")).learn("""Basic realm="x""""))
        assertFalse(HttpAuthSession(null).learn("""Basic realm="x""""))
    }

    // MARK: - Against a local server

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) =
        LocalHttpServer(handler).also { servers += it }

    private val listing = nextcloud.replace("https://HOST", "")

    @Test
    fun `lists a folder with PROPFIND and a Basic login`() = runBlocking {
        val server = server { request ->
            if (request.header("Authorization") != HttpAuth.basic(ServerLogin("me", "secret"))) {
                LocalHttpServer.Response(401, mapOf("WWW-Authenticate" to "Basic realm=\"dav\""))
            } else {
                LocalHttpServer.Response(207, mapOf("Content-Type" to "application/xml"), listing.toByteArray())
            }
        }
        val root = WebDav.canonicalRoot("http://127.0.0.1:${server.port}/remote.php/dav/files/me/Movies")!!
        val connector = WebDavConnector(root, ServerLogin("me", "secret"), OkHttpRemoteHttp(), allowPlainHttp = true)
        assertEquals("dav://127.0.0.1:${server.port}/remote.php/dav/files/me/Movies/", connector.root)

        val entries = connector.list(connector.root)
        // Hidden files are left out; folders come first.
        assertEquals(listOf("Season 1", "Heat (1995).mkv"), entries.map { it.name })
        val propfind = server.requests.last()
        assertEquals("PROPFIND", propfind.method)
        assertEquals("1", propfind.header("Depth"))
        assertEquals("/remote.php/dav/files/me/Movies/", propfind.path)
        assertTrue("getcontentlength" in String(propfind.body))
        // The first request went without the password; only the challenge brought it.
        assertNull(server.requests.first().header("Authorization"))
        assertEquals("me", connector.accountLabel)
    }

    @Test
    fun `answers a Digest challenge`() = runBlocking {
        val login = ServerLogin("me", "secret")
        val challenge = """Digest realm="dav", nonce="abc123", qop="auth", algorithm=MD5"""
        val server = server { request ->
            val authorization = request.header("Authorization")
            val expected = authorization?.let { header ->
                val cnonce = Regex("cnonce=\"([^\"]+)\"").find(header)?.groupValues?.get(1) ?: return@let null
                val nc = Regex("nc=([0-9a-f]+)").find(header)?.groupValues?.get(1)?.toInt(16) ?: return@let null
                HttpAuth.digest(HttpAuth.parseChallenges(challenge).single(), login, request.method, request.path, nc, cnonce)
            }
            if (authorization == null || authorization != expected) {
                LocalHttpServer.Response(401, mapOf("WWW-Authenticate" to challenge))
            } else {
                LocalHttpServer.Response(207, emptyMap(), listing.toByteArray())
            }
        }
        val connector = WebDavConnector("dav://127.0.0.1:${server.port}/remote.php/dav/files/me/Movies/", login, OkHttpRemoteHttp(), allowPlainHttp = true)
        assertEquals(2, connector.list(connector.root).size)
    }

    @Test
    fun `a refused login says so`() {
        val server = server { LocalHttpServer.Response(401, mapOf("WWW-Authenticate" to "Basic realm=\"x\"")) }
        val connector = WebDavConnector("dav://127.0.0.1:${server.port}/dav/", null, OkHttpRemoteHttp(), allowPlainHttp = true)
        val error = assertFailsWith<ConnectorException> { runBlocking { connector.validate() } }
        assertEquals(ConnectorFailure.AuthenticationFailed("127.0.0.1"), error.failure)
        assertTrue(error.failure.needsUserAction)
    }

    @Test
    fun `an address that isn't a WebDAV folder fails to list`() {
        val server = server { LocalHttpServer.Response(200, emptyMap(), "<html/>".toByteArray()) }
        val connector = WebDavConnector("dav://127.0.0.1:${server.port}/dav/", null, OkHttpRemoteHttp(), allowPlainHttp = true)
        val error = assertFailsWith<ConnectorException> { runBlocking { connector.list(connector.root) } }
        assertEquals(ConnectorFailure.ListingFailed("/dav"), error.failure)
    }

    @Test
    fun `plain HTTP waits for D10`() {
        val connector = WebDavConnector("dav://nas.local/dav/", null, OkHttpRemoteHttp())
        val error = assertFailsWith<ConnectorException> { runBlocking { connector.list(connector.root) } }
        assertEquals(ConnectorFailure.InsecureConnection, error.failure)
    }

    @Test
    fun `streams a file through the saved login`() {
        val data = ByteArray(3000) { (it * 7).toByte() }
        val login = ServerLogin("me", "secret")
        val server = server { request ->
            if (request.header("Authorization") != HttpAuth.basic(login)) {
                LocalHttpServer.Response(401, mapOf("WWW-Authenticate" to "Basic realm=\"dav\""))
            } else {
                val (first, last) = request.header("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
                val end = minOf(last, data.size - 1)
                LocalHttpServer.Response(206, mapOf("Content-Range" to "bytes $first-$end/${data.size}"), data.copyOfRange(first, end + 1))
            }
        }
        val http = OkHttpRemoteHttp()
        val resolver = WebDavContentResolver("dav://127.0.0.1:${server.port}/dav/Heat.1995.mkv", HttpAuthSession(login), http)
        val source = RemoteByteSource(resolver, http, config = RemoteByteSource.Config(chunkSize = 1024))
        try {
            val buffer = ByteArray(200)
            assertEquals(200, source.read(1500, buffer, 0, 200))
            assertContentEquals(data.copyOfRange(1500, 1700), buffer)
        } finally {
            source.close()
        }
    }
}
