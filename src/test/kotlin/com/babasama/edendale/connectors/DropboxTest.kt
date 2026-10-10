package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.OkHttpRemoteHttp
import com.babasama.edendale.remote.RemoteByteSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H.8.T1 (Apple's CloudListingTests Dropbox cases): recorded listing pages,
 * the recursive enumeration, temporary links, and a 410 through H.2's byte
 * source resolving a new link.
 */
class DropboxTest {

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) =
        LocalHttpServer(handler).also { servers += it }

    private fun json(body: String, status: Int = 200) =
        LocalHttpServer.Response(status, mapOf("Content-Type" to "application/json"), body.toByteArray())

    private fun LocalHttpServer.Request.json() = Json.parseToJsonElement(String(body)).jsonObject

    private val account = CloudAccount(MediaSourceKind.DROPBOX, "subject-dropbox", "me@example.com", null, "refresh", emptyList())

    private fun provider(server: LocalHttpServer): ProviderHttp {
        val http = RewritingHttp("api.dropboxapi.com", server.port)
        return ProviderHttp(MediaSourceKind.DROPBOX, account.key, seededTokens(account, http), http, listOf(10, 10, 10))
    }

    @Test
    fun `lists a folder across pages`() = runBlocking {
        val server = server { request ->
            if (request.path.endsWith("/continue")) {
                json("""{"entries":[{".tag":"file","name":"Alien.1979.mp4","id":"id:a","size":5}],"cursor":"c2","has_more":false}""")
            } else {
                json(
                    """{"entries":[
                      {".tag":"folder","name":"Movies","id":"id:m"},
                      {".tag":"file","name":"Heat.1995.mkv","id":"id:h","size":10,"server_modified":"2015-05-12T15:50:38Z","is_downloadable":true},
                      {".tag":"file","name":".hidden.mkv","id":"id:x"},
                      {".tag":"file","name":"Doc.paper","id":"id:p","is_downloadable":false},
                      {".tag":"deleted","name":"Gone.mkv"}
                    ],"cursor":"c1","has_more":true}""",
                )
            }
        }
        val connector = DropboxConnector(account, provider(server))
        val entries = connector.list(connector.root)
        assertEquals(listOf("Movies", "Alien.1979.mp4", "Heat.1995.mkv"), entries.map { it.name })
        val heat = entries.first { it.name == "Heat.1995.mkv" }
        assertEquals("dropbox://${account.key}/id%3Ah/Heat.1995.mkv", heat.url)
        assertEquals(listOf("id:h"), SourceUrl.parseAccountItem(heat.url)?.ids)
        assertEquals(1_431_445_838_000L, heat.modifiedEpochMillis)

        val requests = server.requests
        assertEquals("/2/files/list_folder", requests[0].path)
        assertEquals("", requests[0].json()["path"]!!.jsonPrimitive.content)
        assertEquals("false", requests[0].json()["recursive"]!!.jsonPrimitive.content)
        assertEquals("application/json", requests[0].header("Content-Type"))
        assertEquals("c1", requests[1].json()["cursor"]!!.jsonPrimitive.content)
        assertTrue(requests.all { it.header("Authorization") == "Bearer token" })
    }

    @Test
    fun `enumerates recursively in one listing`() = runBlocking {
        val server = server {
            json(
                """{"entries":[
                  {".tag":"folder","name":"Movies","id":"id:m","path_lower":"/movies"},
                  {".tag":"file","name":"Heat.1995.mkv","id":"id:h","path_lower":"/movies/heat.1995.mkv"},
                  {".tag":"file","name":"Old.2001.mkv","id":"id:o","path_lower":"/.trash/old.2001.mkv"},
                  {".tag":"file","name":"readme.txt","id":"id:r","path_lower":"/movies/readme.txt"}
                ],"cursor":"c","has_more":false}""",
            )
        }
        val connector = DropboxConnector(account, provider(server))
        val folder = SourceUrl.accountItem(MediaSourceKind.DROPBOX, account.key, listOf("id:films"), "Films")
        val enumeration = connector.enumerateVideos(folder)
        assertEquals(listOf("Heat.1995.mkv"), enumeration.videos.map { it.name })
        assertTrue(enumeration.complete)
        val request = server.requests.single()
        assertEquals("id:films", request.json()["path"]!!.jsonPrimitive.content)
        assertEquals("true", request.json()["recursive"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streams from a temporary link`() {
        val links = AtomicInteger()
        val server = server { json("""{"link":"https://dl.dropboxusercontent.com/link-${links.incrementAndGet()}","metadata":{}}""") }
        val resolver = DropboxContentResolver("id:h", provider(server))
        assertEquals("https://dl.dropboxusercontent.com/link-1", resolver.contentRequest(refresh = false).url)
        assertEquals("https://dl.dropboxusercontent.com/link-1", resolver.contentRequest(refresh = false).url)
        assertEquals("https://dl.dropboxusercontent.com/link-2", resolver.contentRequest(refresh = true).url)
        val request = server.requests.first()
        assertEquals("/2/files/get_temporary_link", request.path)
        assertEquals("id:h", request.json()["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an expired link answers 410 and a new one is resolved`() {
        val data = ByteArray(3000) { (it * 5).toByte() }
        val links = AtomicInteger()
        val server = server { request ->
            when {
                request.path == "/2/files/get_temporary_link" ->
                    json("""{"link":"https://api.dropboxapi.com/content/${links.incrementAndGet()}"}""")
                // The first link has expired.
                request.path == "/content/1" -> json("""{"error":"gone"}""", 410)
                else -> {
                    val (first, last) = request.header("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
                    val end = minOf(last, data.size - 1)
                    LocalHttpServer.Response(206, mapOf("Content-Range" to "bytes $first-$end/${data.size}"), data.copyOfRange(first, end + 1))
                }
            }
        }
        val http = RewritingHttp("api.dropboxapi.com", server.port)
        val source = RemoteByteSource(DropboxContentResolver("id:h", provider(server)), http, config = RemoteByteSource.Config(chunkSize = 1024))
        try {
            val buffer = ByteArray(200)
            assertEquals(200, source.read(1500, buffer, 0, 200))
            assertContentEquals(data.copyOfRange(1500, 1700), buffer)
            assertEquals(2, links.get())
            // A temporary link carries no token.
            assertTrue(server.requests.filter { it.path.startsWith("/content/") }.all { it.header("Authorization") == null })
        } finally {
            source.close()
        }
    }
}
