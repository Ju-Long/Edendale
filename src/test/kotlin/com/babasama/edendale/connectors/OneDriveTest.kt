package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudAccountVault
import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.oauth.OAuthConfiguration
import com.babasama.edendale.oauth.OAuthTokens
import com.babasama.edendale.oauth.SecretStore
import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.OkHttpRemoteHttp
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteHttp
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.RemoteSourceException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A transport that sends a provider's API host to the local test server. */
internal class RewritingHttp(private val host: String, private val port: Int) : RemoteHttp {
    private val local = OkHttpRemoteHttp()

    override fun newCall(request: RemoteRequest, bodyLimit: Int) = local.newCall(
        RemoteRequest(
            request.url.replace("https://$host", "http://127.0.0.1:$port"),
            request.headers,
            request.method,
            request.body,
            request.contentType,
        ),
        bodyLimit,
    )
}

internal class MemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
    override fun keys(): Set<String> = values.keys.toSet()
}

/** A token provider that already holds the token "token" for [account], refreshing at [tokenEndpoint]. */
internal fun seededTokens(account: CloudAccount, http: RemoteHttp, tokenEndpoint: String = "http://127.0.0.1:1/token"): CloudTokenProvider {
    val vault = CloudAccountVault(MemorySecretStore()).apply { save(account) }
    val configuration = OAuthConfiguration(account.kind, "client", "x", tokenEndpoint, null, "x://y", "x", emptyList())
    return CloudTokenProvider(vault, http, { configuration }).apply { store(OAuthTokens("token", 3600), account) }
}

/**
 * H.7.T1 (Apple's CloudListingTests OneDrive cases): recorded Graph
 * responses for personal and work or school accounts (paging, folders,
 * shared folders, OneNote packages, the video facet's duration), the
 * pre-authenticated download link, and ProviderHttp's refresh and failures.
 */
class OneDriveTest {

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) =
        LocalHttpServer(handler).also { servers += it }

    private fun json(body: String, status: Int = 200) =
        LocalHttpServer.Response(status, mapOf("Content-Type" to "application/json"), body.toByteArray())

    private fun account(driveId: String? = "d1") = CloudAccount(
        kind = MediaSourceKind.ONE_DRIVE,
        subject = "subject-onedrive",
        email = "me@example.com",
        displayName = null,
        refreshToken = "refresh",
        scopes = emptyList(),
        driveId = driveId,
    )

    private fun provider(server: LocalHttpServer, account: CloudAccount = account()): ProviderHttp {
        val http = RewritingHttp("graph.microsoft.com", server.port)
        return ProviderHttp(MediaSourceKind.ONE_DRIVE, account.key, seededTokens(account, http), http, listOf(10, 10, 10))
    }

    @Test
    fun `lists children across pages and shared folders`() = runBlocking {
        val server = server { request ->
            if (request.query?.contains("skiptoken") == true) {
                json("""{"value":[{"id":"a1","name":"Alien.1979.mp4","size":20,"file":{"mimeType":"video/mp4"}}]}""")
            } else {
                json(
                    """{"value":[
                      {"id":"m1","name":"Movies","folder":{"childCount":3}},
                      {"id":"h1","name":"Heat.1995.mkv","size":1000,"file":{"mimeType":"video/x-matroska"},
                       "video":{"duration":6000000},"lastModifiedDateTime":"2024-01-02T03:04:05Z"},
                      {"id":"n1","name":"Notebook","package":{"type":"oneNote"}},
                      {"id":"x1","name":"Shared","remoteItem":{"id":"r1","folder":{"childCount":1},"parentReference":{"driveId":"d2"}}}
                    ],
                    "@odata.nextLink":"https://graph.microsoft.com/v1.0/drives/d1/root/children?${'$'}skiptoken=abc"}""",
                )
            }
        }
        val account = account()
        val connector = OneDriveConnector.create(account, provider(server))!!

        assertEquals("onedrive://${account.key}/d1/root/OneDrive", connector.root)
        val entries = connector.list(connector.root)
        assertEquals(listOf("Movies", "Shared", "Alien.1979.mp4", "Heat.1995.mkv"), entries.map { it.name })
        val heat = entries.first { it.name == "Heat.1995.mkv" }
        assertEquals(6000.0, heat.durationSeconds)
        assertEquals(1000L, heat.size)
        assertEquals(1_704_164_645_000L, heat.modifiedEpochMillis)
        assertEquals(listOf("d1", "h1"), SourceUrl.parseAccountItem(heat.url)?.ids)
        val shared = entries.first { it.name == "Shared" }
        assertTrue(shared.isDirectory)
        assertEquals(listOf("d2", "r1"), SourceUrl.parseAccountItem(shared.url)?.ids)

        val requests = server.requests
        assertEquals(2, requests.size)
        assertEquals("/v1.0/drives/d1/root/children", requests[0].path)
        assertTrue(requests[0].queryValue("\$select")!!.contains("video"))
        assertTrue(requests[1].query!!.contains("\$skiptoken=abc"))
        assertTrue(requests.all { it.header("Authorization") == "Bearer token" })

        // An account from before the drive was known can't browse.
        assertNull(OneDriveConnector.create(account(driveId = null), provider(server)))
    }

    @Test
    fun `lists a work or school folder`() = runBlocking {
        // SharePoint-backed drives: b! drive ids, hashes, and a folder below the root.
        val server = server {
            json(
                """{"@odata.context":"https://graph.microsoft.com/v1.0/${'$'}metadata#Collection(driveItem)",
                "value":[
                  {"id":"01ABC","name":"Season 1","folder":{"childCount":10},"parentReference":{"driveType":"business","driveId":"b!xYz"}},
                  {"id":"01DEF","name":"Show.S01E01.mkv","size":734003200,
                   "file":{"mimeType":"video/x-matroska","hashes":{"quickXorHash":"abc="}},
                   "video":{"duration":2580000,"height":1080,"width":1920},
                   "parentReference":{"driveType":"business","driveId":"b!xYz"},
                   "lastModifiedDateTime":"2025-03-04T05:06:07.123Z"}
                ]}""",
            )
        }
        val account = account(driveId = "b!xYz")
        val connector = OneDriveConnector.create(account, provider(server, account))!!
        val folder = SourceUrl.accountItem(MediaSourceKind.ONE_DRIVE, account.key, listOf("b!xYz", "01PARENT"), "Show")
        val entries = connector.list(folder)
        assertEquals(listOf("Season 1", "Show.S01E01.mkv"), entries.map { it.name })
        assertEquals(2580.0, entries[1].durationSeconds)
        assertEquals(734_003_200L, entries[1].size)
        assertEquals(listOf("b!xYz", "01DEF"), SourceUrl.parseAccountItem(entries[1].url)?.ids)
        assertEquals("/v1.0/drives/b!xYz/items/01PARENT/children", server.requests.single().path)
    }

    @Test
    fun `streams from its pre-authenticated download URL`() {
        val lookups = AtomicInteger()
        val server = server { json("""{"id":"h1","@microsoft.graph.downloadUrl":"https://download.example/link-${lookups.incrementAndGet()}"}""") }
        val resolver = OneDriveContentResolver("d1", "h1", provider(server))
        assertTrue(resolver.usesPreauthorizedLinks)
        val first = resolver.contentRequest(refresh = false)
        assertEquals("https://download.example/link-1", first.url)
        assertNull(first.headers["Authorization"])
        // Reused until it fails, then resolved again.
        assertEquals(first.url, resolver.contentRequest(refresh = false).url)
        assertEquals("https://download.example/link-2", resolver.contentRequest(refresh = true).url)
        assertEquals("/v1.0/drives/d1/items/h1", server.requests.first().path)
    }

    @Test
    fun `a rejected token is refreshed once, then sign-in is needed`() {
        val refreshes = AtomicInteger()
        val server = server { request ->
            when {
                request.path == "/token" -> json("""{"access_token":"token-${refreshes.incrementAndGet()}","expires_in":3600}""")
                request.header("Authorization") == "Bearer token" -> json("""{"error":{"code":"InvalidAuthenticationToken"}}""", 401)
                else -> json("""{"error":{"code":"InvalidAuthenticationToken"}}""", 401)
            }
        }
        val account = account()
        val http = RewritingHttp("graph.microsoft.com", server.port)
        val tokens = seededTokens(account, http, tokenEndpoint = "http://127.0.0.1:${server.port}/token")
        val provider = ProviderHttp(MediaSourceKind.ONE_DRIVE, account.key, tokens, http, listOf(10, 10, 10))
        val error = assertFailsWith<ConnectorException> {
            runBlocking { provider.json { RemoteRequest(OneDriveConnector.itemUrl("d1", "h1")) } }
        }
        assertEquals(ConnectorFailure.SignInRequired(MediaSourceKind.ONE_DRIVE), error.failure)
        assertEquals(1, refreshes.get())
        // The second try carried the refreshed token.
        assertEquals("Bearer token-1", server.requests.last().header("Authorization"))
    }

    @Test
    fun `rate limits back off and a missing item says so`() = runBlocking {
        val attempts = AtomicInteger()
        val server = server { request ->
            when {
                request.path.endsWith("/items/gone") -> json("""{"error":{"code":"itemNotFound"}}""", 404)
                attempts.incrementAndGet() == 1 -> json("{}", 429).let { LocalHttpServer.Response(429, mapOf("Retry-After" to "0"), it.body) }
                else -> json("""{"id":"h1"}""")
            }
        }
        val provider = provider(server)
        assertEquals("h1", provider.json { RemoteRequest(OneDriveConnector.itemUrl("d1", "h1")) }.text("id"))
        assertEquals(2, attempts.get())
        val missing = assertFailsWith<RemoteSourceException> {
            runBlocking { provider.json { RemoteRequest(OneDriveConnector.itemUrl("d1", "gone")) } }
        }
        assertEquals(RemoteFailure.NotFound, missing.failure)
    }
}
