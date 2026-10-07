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
 * H.9.T1 (Apple's CloudListingTests Drive cases): recorded Drive v3
 * responses for paging, shortcuts, shared drives, and filtering; the picker's
 * virtual folders; and streaming with the Bearer token, refreshed once after
 * a 401.
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
        subject = "110169484474386276334",
        email = "me@example.com",
        displayName = "Me",
        refreshToken = "refresh",
        scopes = emptyList(),
    )

    /** The provider's HTTP, with the token endpoint on the same local server (`/token`) for the refresh cases. */
    private fun provider(server: LocalHttpServer): ProviderHttp {
        val http = RewritingHttp("www.googleapis.com", server.port)
        val tokens = seededTokens(account, http, tokenEndpoint = "http://127.0.0.1:${server.port}/token")
        return ProviderHttp(MediaSourceKind.GOOGLE_DRIVE, account.key, tokens, http, listOf(10, 10, 10))
    }

    private fun connector(server: LocalHttpServer) = GoogleDriveConnector(account, provider(server))

    @Test
    fun `the root gathers My Drive, Shared with me, and Shared drives, and can't be linked itself`() = runBlocking {
        val server = server { json("{}") }
        val labels = GoogleDriveLabels(myDrive = "Meine Ablage", sharedWithMe = "Für mich freigegeben", sharedDrives = "Geteilte Ablagen")
        val connector = GoogleDriveConnector(account, provider(server), labels)
        assertEquals("gdrive://${account.key}/~roots/Google%20Drive", connector.root)

        val roots = connector.list(connector.root)
        assertEquals(listOf("Meine Ablage", "Für mich freigegeben", "Geteilte Ablagen"), roots.map { it.name })
        assertTrue(roots.all { it.isDirectory })
        assertEquals("gdrive://${account.key}/root/Meine%20Ablage", roots[0].url)
        assertEquals("gdrive://${account.key}/~shared/F%C3%BCr%20mich%20freigegeben", roots[1].url)
        assertEquals("gdrive://${account.key}/~drives/Geteilte%20Ablagen", roots[2].url)
        // Nothing was asked of the server for the virtual root.
        assertTrue(server.requests.isEmpty())

        assertFalse(connector.canIndex(connector.root))
        assertTrue(connector.canIndex(roots[0].url))
        assertTrue(connector.canIndex(roots[1].url))
        assertFalse(connector.canIndex(roots[2].url))
        assertFalse(connector.canIndex("dropbox://x/root/Dropbox"))
    }

    @Test
    fun `lists a folder across pages, following shortcuts and skipping Google formats`() = runBlocking {
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
        val folder = connector.folderUrl("m0", "Films")
        val entries = connector.list(folder)

        // Folders first (a folder shortcut counts), then files by name; the broken shortcut and the Doc are gone.
        assertEquals(listOf("Movies", "Season 1", "Alien.1979.mp4", "Heat (1995).mkv", "notes.txt", "still.mkv", "Zulu.mkv"), entries.map { it.name })
        val movies = entries[0]
        assertTrue(movies.isDirectory)
        assertEquals("gdrive://${account.key}/m1/Movies", movies.url)
        assertEquals(1_704_164_645_000L, movies.modifiedEpochMillis)
        // A shortcut's entry carries its target's ID.
        assertEquals("gdrive://${account.key}/t2/Season%201", entries[1].url)
        assertEquals("gdrive://${account.key}/t1/Alien.1979.mp4", entries[2].url)
        val heat = entries[3]
        assertEquals(1000L, heat.size)
        assertEquals(6000.0, heat.durationSeconds)
        assertEquals("gdrive://${account.key}/h1/Heat%20(1995).mkv", heat.url)
        // Videos are recognized by extension: the .txt with a video MIME type isn't one, the .mkv with none is.
        assertFalse(entries[4].isVideo)
        assertTrue(entries[6].isVideo)
        assertNull(entries[5].durationSeconds)

        val first = server.requests.first()
        assertEquals("'m0' in parents and trashed = false", first.queryValue("q")?.let(SourceUrl::decode))
        assertEquals("true", first.queryValue("supportsAllDrives"))
        assertEquals("true", first.queryValue("includeItemsFromAllDrives"))
        assertEquals("1000", first.queryValue("pageSize"))
        assertNull(first.queryValue("corpora"))
        assertTrue(first.queryValue("fields")!!.let(SourceUrl::decode).contains("shortcutDetails(targetId,targetMimeType)"))
        assertEquals("Bearer token", first.header("Authorization"))
        assertEquals(2, server.requests.size)
        assertEquals("page2", server.requests[1].queryValue("pageToken"))
    }

    @Test
    fun `shared drives list by name and their folders name the drive`() = runBlocking {
        val server = server { request ->
            when (request.path) {
                "/drive/v3/drives" -> if (request.queryValue("pageToken") == null) {
                    json("""{"nextPageToken":"more","drives":[{"id":"d2","name":"Team Films"}]}""")
                } else {
                    json("""{"drives":[{"id":"d1","name":"Archive"}]}""")
                }
                "/drive/v3/files" -> json("""{"files":[{"id":"x1","name":"Docs","mimeType":"application/vnd.google-apps.folder"},
                    {"id":"x2","name":"Heat.mkv","mimeType":"video/x-matroska","size":"7"}]}""")
                else -> LocalHttpServer.Response(404)
            }
        }
        val connector = connector(server)
        val drives = connector.list(connector.folderUrl(GoogleDriveConnector.VirtualFolder.SHARED_DRIVES, "Shared drives"))
        assertEquals(listOf("Archive", "Team Films"), drives.map { it.name })
        // A shared drive's root folder has the drive's ID, and remembers the drive for its listings.
        assertEquals("gdrive://${account.key}/d2/Team%20Films?drive=d2", drives[1].url)

        val inside = connector.list(drives[1].url)
        assertEquals(listOf("Docs", "Heat.mkv"), inside.map { it.name })
        assertEquals("gdrive://${account.key}/x1/Docs?drive=d2", inside[0].url)
        // Files don't carry the drive: their ID is enough to stream.
        assertEquals("gdrive://${account.key}/x2/Heat.mkv", inside[1].url)
        val listing = server.requests.last()
        assertEquals("drive", listing.queryValue("corpora"))
        assertEquals("d2", listing.queryValue("driveId"))
        assertEquals("'d2' in parents and trashed = false", listing.queryValue("q")?.let(SourceUrl::decode))
    }

    @Test
    fun `Shared with me queries sharedWithMe and validate asks about`() = runBlocking {
        val server = server { request ->
            when (request.path) {
                "/drive/v3/about" -> json("""{"user":{"emailAddress":"me@example.com"}}""")
                else -> json("""{"files":[{"id":"w1","name":"From Ana","mimeType":"application/vnd.google-apps.folder"}]}""")
            }
        }
        val connector = connector(server)
        connector.validate()
        assertEquals("/drive/v3/about", server.requests.first().path)
        assertEquals("user(emailAddress)", server.requests.first().queryValue("fields")?.let(SourceUrl::decode))

        val shared = connector.list(connector.folderUrl(GoogleDriveConnector.VirtualFolder.SHARED_WITH_ME, "Shared with me"))
        assertEquals(listOf("From Ana"), shared.map { it.name })
        assertEquals("sharedWithMe = true and trashed = false", server.requests.last().queryValue("q")?.let(SourceUrl::decode))
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
        val error = assertFailsWith<ConnectorException> { runBlocking { connector.list(connector.folderUrl("m0", "Films")) } }
        assertEquals(ConnectorFailure.SignInRequired(MediaSourceKind.GOOGLE_DRIVE), error.failure)
        assertTrue(server.requests.any { it.path == "/token" })

        val notFound = server { json("""{"error":{"code":404,"message":"File not found"}}""", 404) }
        val missing = assertFailsWith<RemoteSourceException> { runBlocking { connector(notFound).list(connector.folderUrl("gone", "Gone")) } }
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
