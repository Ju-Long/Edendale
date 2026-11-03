package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudAccountVault
import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.oauth.OAuthConfiguration
import com.babasama.edendale.oauth.OAuthTokens
import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.RemoteByteSource
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException
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
 * H.9.T1 and H.9.T2 (Apple's CloudListingTests Drive cases): under
 * `drive.file` each account has one virtual folder, "Videos you chose",
 * listed with `files.list` across pages, with folders, folder shortcuts, and
 * Google formats skipped and shortcuts to files followed; nothing else lists;
 * and streaming with the Bearer token, refreshed once after a 401.
 */
class GoogleDriveTest {

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) =
        LocalHttpServer(handler).also { servers += it }

    private fun json(body: String, status: Int = 200) =
        LocalHttpServer.Response(status, mapOf("Content-Type" to "application/json"), body.toByteArray())

    private val account = CloudAccount(
        kind = MediaSourceKind.GOOGLE_DRIVE,
        subject = "01234567890123456789",
        email = "me@example.com",
        displayName = "Me",
        refreshToken = "refresh",
        scopes = listOf("https://www.googleapis.com/auth/drive.file"),
    )

    /** The provider's HTTP, with the token endpoint on the same local server (`/token`) for the refresh cases. */
    private fun provider(server: LocalHttpServer): ProviderHttp {
        val http = RewritingHttp("www.googleapis.com", server.port)
        val tokens = seededTokens(account, http, tokenEndpoint = "http://127.0.0.1:${server.port}/token")
        return ProviderHttp(MediaSourceKind.GOOGLE_DRIVE, account.key, tokens, http, listOf(10, 10, 10))
    }

    private fun connector(server: LocalHttpServer) = GoogleDriveConnector(account, provider(server))

    @Test
    fun `the root is the account's Videos you chose, the only folder that lists or links`() {
        val server = server { json("{}") }
        val connector = connector(server)
        // The name in the URL is fixed, so a source's stored URL is the same in every language.
        assertEquals("gdrive://${account.key}/~chosen/Google%20Drive", connector.root)
        assertEquals(connector.root, GoogleDriveConnector.chosenFolderUrl(account.key))
        assertTrue(GoogleDriveConnector.isChosenFolder(connector.root))
        assertTrue(connector.canIndex(connector.root))

        // An older build's folder sources, the old virtual roots, and other providers aren't "Videos you chose".
        val older = listOf(
            "gdrive://${account.key}/m0/Films",
            "gdrive://${account.key}/root/My%20Drive",
            "gdrive://${account.key}/~roots/Google%20Drive",
            "gdrive://${account.key}/d2/Team%20Films?drive=d2",
            "onedrive://${account.key}/drive/~chosen/Google%20Drive",
        )
        older.forEach { url ->
            assertFalse(GoogleDriveConnector.isChosenFolder(url), url)
            assertFalse(connector.canIndex(url), url)
        }
        // None of them lists, and neither does another account's.
        for (url in older + GoogleDriveConnector.chosenFolderUrl("another-account")) {
            val error = assertFailsWith<ConnectorException>(url) { runBlocking { connector.list(url) } }
            assertEquals(ConnectorFailure.InvalidAddress, error.failure)
        }
        // Nothing was asked of the server.
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `lists every picked file across pages, following shortcuts and skipping folders and Google formats`() = runBlocking {
        val server = server { request ->
            assertEquals("/drive/v3/files", request.path)
            if (request.queryValue("pageToken") == "page2") {
                json("""{"files":[{"id":"f9","name":"Zulu.mkv","mimeType":"application/octet-stream","size":"42"}]}""")
            } else {
                json(
                    """{"nextPageToken":"page2","files":[
                      {"id":"m1","name":"Movies","mimeType":"application/vnd.google-apps.folder","modifiedTime":"2024-01-02T03:04:05.000Z"},
                      {"id":"h1","name":"Heat (1995).mkv","mimeType":"video/x-matroska","size":"1000",
                       "videoMediaMetadata":{"durationMillis":"6000000"},"modifiedTime":"2024-01-02T03:04:05Z"},
                      {"id":"s1","name":"Alien.1979.mp4","mimeType":"application/vnd.google-apps.shortcut",
                       "shortcutDetails":{"targetId":"t1","targetMimeType":"video/mp4"}},
                      {"id":"s2","name":"Season 1","mimeType":"application/vnd.google-apps.shortcut",
                       "shortcutDetails":{"targetId":"t2","targetMimeType":"application/vnd.google-apps.folder"}},
                      {"id":"s3","name":"Broken","mimeType":"application/vnd.google-apps.shortcut","shortcutDetails":{}},
                      {"id":"d1","name":"Notes","mimeType":"application/vnd.google-apps.document"},
                      {"id":"n1","name":"notes.txt","mimeType":"video/mp4","size":"5"},
                      {"id":"p1","name":"still.mkv","mimeType":"video/x-matroska","videoMediaMetadata":{}}
                    ]}""",
                )
            }
        }
        val connector = connector(server)
        val entries = connector.list(connector.root)

        // A picked folder never grants what's inside it, so folders and folder shortcuts are gone, as are the
        // broken shortcut and the Doc; files are sorted by name.
        assertEquals(listOf("Alien.1979.mp4", "Heat (1995).mkv", "notes.txt", "still.mkv", "Zulu.mkv"), entries.map { it.name })
        assertTrue(entries.none { it.isDirectory })
        // A shortcut's entry carries its target's ID.
        assertEquals("gdrive://${account.key}/t1/Alien.1979.mp4", entries[0].url)
        val heat = entries[1]
        assertEquals(1000L, heat.size)
        assertEquals(6000.0, heat.durationSeconds)
        assertEquals(1_704_164_645_000L, heat.modifiedEpochMillis)
        assertEquals("gdrive://${account.key}/h1/Heat%20(1995).mkv", heat.url)
        assertEquals("h1", GoogleDriveConnector.fileId(heat.url))
        // Videos are recognized by extension: the .txt with a video MIME type isn't one, the .mkv with none is.
        assertFalse(entries[2].isVideo)
        assertTrue(entries[4].isVideo)
        assertNull(entries[3].durationSeconds)

        // drive.file limits files.list to the picked files, wherever they live.
        val first = server.requests.first()
        assertEquals("trashed = false", first.queryValue("q")?.let(SourceUrl::decode))
        assertEquals("true", first.queryValue("supportsAllDrives"))
        assertEquals("true", first.queryValue("includeItemsFromAllDrives"))
        assertEquals("1000", first.queryValue("pageSize"))
        assertNull(first.queryValue("corpora"))
        assertNull(first.queryValue("driveId"))
        assertTrue(first.queryValue("fields")!!.let(SourceUrl::decode).contains("shortcutDetails(targetId,targetMimeType)"))
        assertEquals("Bearer token", first.header("Authorization"))
        assertEquals(2, server.requests.size)
        assertEquals("page2", server.requests[1].queryValue("pageToken"))
    }

    @Test
    fun `a scan lists the picked files once and validate asks about`() = runBlocking {
        val server = server { request ->
            when (request.path) {
                "/drive/v3/about" -> json("""{"user":{"emailAddress":"me@example.com"}}""")
                else -> json("""{"files":[{"id":"w1","name":"From Ana","mimeType":"application/vnd.google-apps.folder"},
                    {"id":"w2","name":"Severance S01E01.mkv","mimeType":"video/x-matroska"}]}""")
            }
        }
        val connector = connector(server)
        connector.validate()
        assertEquals("/drive/v3/about", server.requests.first().path)
        assertEquals("user(emailAddress)", server.requests.first().queryValue("fields")?.let(SourceUrl::decode))

        val enumeration = connector.enumerateVideos(connector.root)
        assertTrue(enumeration.complete)
        assertEquals(listOf("Severance S01E01.mkv"), enumeration.videos.map { it.name })
        // The picked folder isn't walked: its contents were never granted.
        assertEquals(1, server.requests.count { it.path == "/drive/v3/files" })
    }

    @Test
    fun `a listing failure says why, and a rejected refresh asks for sign-in`() {
        // Every token is refused, and the refresh token itself has been revoked.
        val server = server { request ->
            if (request.path == "/token") {
                json("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", 400)
            } else {
                json("""{"error":{"code":401,"message":"Invalid Credentials"}}""", 401)
            }
        }
        val connector = connector(server)
        val error = assertFailsWith<ConnectorException> { runBlocking { connector.list(connector.root) } }
        assertEquals(ConnectorFailure.SignInRequired(MediaSourceKind.GOOGLE_DRIVE), error.failure)
        assertTrue(server.requests.any { it.path == "/token" })

        val unavailable = server { json("""{"error":{"code":404,"message":"File not found"}}""", 404) }
        val missing = assertFailsWith<RemoteSourceException> { runBlocking { connector(unavailable).list(connector.root) } }
        assertEquals(RemoteFailure.NotFound, missing.failure)
    }

    @Test
    fun `streams with the Bearer token and refreshes it once after a 401`() {
        val data = ByteArray(5000) { (it * 3).toByte() }
        var tokenRequests = 0
        val server = server { request ->
            when {
                request.path == "/token" -> {
                    tokenRequests += 1
                    json("""{"access_token":"token-$tokenRequests","expires_in":3600}""")
                }
                request.header("Authorization") != "Bearer token-${tokenRequests.coerceAtLeast(1)}" ->
                    json("""{"error":{"code":401}}""", 401)
                else -> {
                    assertEquals("/drive/v3/files/f%201", request.path)
                    assertEquals("media", request.queryValue("alt"))
                    assertEquals("true", request.queryValue("supportsAllDrives"))
                    val range = request.header("Range")!!.removePrefix("bytes=").split('-')
                    val from = range[0].toInt()
                    val until = minOf(range[1].toInt() + 1, data.size)
                    LocalHttpServer.Response(
                        206,
                        mapOf("Content-Range" to "bytes $from-${until - 1}/${data.size}", "Content-Type" to "video/x-matroska"),
                        data.copyOfRange(from, until),
                    )
                }
            }
        }
        val http = RewritingHttp("www.googleapis.com", server.port)
        val vault = CloudAccountVault(MemorySecretStore()).apply { save(account) }
        val configuration = OAuthConfiguration(account.kind, "client", "x", "http://127.0.0.1:${server.port}/token", null, "x:/y", "x", emptyList())
        val tokens = CloudTokenProvider(vault, http, { configuration }).apply { store(OAuthTokens("stale", 3600), account) }
        // A stale token in the cache: the first read gets a 401, refreshes once, and reads on.
        val resolver = GoogleDriveContentResolver("f 1", account.key, tokens)
        RemoteByteSource(resolver, http, config = RemoteByteSource.Config(chunkSize = 2048, backoffDelaysMillis = listOf(10, 10))).use { source ->
            val buffer = ByteArray(data.size)
            var position = 0L
            while (position < data.size) {
                val read = source.read(position, buffer, position.toInt(), data.size - position.toInt())
                if (read <= 0) break
                position += read
            }
            assertContentEquals(data, buffer)
            assertEquals(data.size.toLong(), source.length)
        }
        assertEquals(1, tokenRequests)
        // The refresh token traveled to the token endpoint; no listing request was made for a stream.
        assertTrue(server.requests.any { it.path == "/token" && String(it.body).contains("refresh_token=refresh") })
        assertTrue(server.requests.none { it.path == "/drive/v3/files" })
    }
}
