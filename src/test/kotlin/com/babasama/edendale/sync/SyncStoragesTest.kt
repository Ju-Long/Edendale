package com.babasama.edendale.sync

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.MemorySecretStore
import com.babasama.edendale.connectors.ProviderHttp
import com.babasama.edendale.connectors.RewritingHttp
import com.babasama.edendale.connectors.Sftp
import com.babasama.edendale.connectors.WebDavConnector
import com.babasama.edendale.connectors.seededTokens
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.remote.HttpAuth
import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.OkHttpRemoteHttp
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FEATURES-27.1.md §11 "Connectors": writes against local servers (a tiny
 * WebDAV server, an in-process SFTP server) and the API providers against
 * recorded request shapes. Each storage creates the folders, lists with a
 * stamp, writes, reads back, and deletes.
 */
class SyncStoragesTest {
    private val servers = mutableListOf<LocalHttpServer>()
    private val device = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
    private val fileName = DeviceFile.fileName(device)
    private val payload = """{"format":1,"space":"Family"}""".toByteArray()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
        if (::ssh.isInitialized) ssh.stop(true)
    }

    private fun server(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) = LocalHttpServer(handler).also { servers += it }

    private fun roundTrip(storage: SyncStorage) = runBlocking {
        storage.ensureSpace("Family")
        assertTrue(storage.list("Family").none { it.name == fileName })
        storage.write("Family", fileName, payload)
        val listed = storage.list("Family")
        val file = listed.single { it.name == fileName }
        assertTrue(file.stamp.isNotEmpty())
        assertContentEquals(payload, storage.read("Family", fileName))
        // Replacing changes the stamp.
        storage.write("Family", fileName, payload + "x".toByteArray())
        val replaced = storage.list("Family").single { it.name == fileName }
        assertTrue(replaced.stamp != file.stamp || replaced.size != file.size)
        storage.delete("Family", fileName)
        assertTrue(storage.list("Family").none { it.name == fileName })
        // Deleting again is not an error.
        storage.delete("Family", fileName)
    }

    // MARK: - WebDAV

    /** A minimal WebDAV server: collections and files in memory, Basic login, ETags from a counter. */
    private class MemoryDav {
        val collections = ConcurrentHashMap.newKeySet<String>().apply { add("/dav") }
        val files = ConcurrentHashMap<String, Pair<ByteArray, Int>>()

        fun handle(request: LocalHttpServer.Request): LocalHttpServer.Response {
            if (request.header("Authorization") != HttpAuth.basic(ServerLogin("me", "secret"))) {
                return LocalHttpServer.Response(401, mapOf("WWW-Authenticate" to "Basic realm=\"dav\""))
            }
            val path = URLDecoder.decode(request.path, "UTF-8").trimEnd('/')
            return when (request.method) {
                "MKCOL" -> if (path in collections) LocalHttpServer.Response(405) else {
                    if (path.substringBeforeLast('/') !in collections) LocalHttpServer.Response(409) else { collections += path; LocalHttpServer.Response(201) }
                }
                "PUT" -> if (path.substringBeforeLast('/') !in collections) LocalHttpServer.Response(409) else {
                    files[path] = request.body to ((files[path]?.second ?: 0) + 1)
                    LocalHttpServer.Response(201)
                }
                "GET" -> files[path]?.let { LocalHttpServer.Response(200, emptyMap(), it.first) } ?: LocalHttpServer.Response(404)
                "DELETE" -> if (files.remove(path) != null) LocalHttpServer.Response(204) else LocalHttpServer.Response(404)
                "PROPFIND" -> if (path !in collections) LocalHttpServer.Response(404) else {
                    val children = files.filterKeys { it.substringBeforeLast('/') == path }
                    val body = buildString {
                        append("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>$path/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                        children.forEach { (name, file) ->
                            append("""<d:response><d:href>${name.replace(" ", "%20")}</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${file.first.size}</d:getcontentlength><d:getetag>"etag-${file.second}"</d:getetag><d:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                        }
                        append("</d:multistatus>")
                    }
                    LocalHttpServer.Response(207, mapOf("Content-Type" to "application/xml"), body.toByteArray())
                }
                else -> LocalHttpServer.Response(405)
            }
        }
    }

    @Test
    fun `WebDAV creates the folders with MKCOL, writes with PUT, and stamps with the ETag`() {
        val dav = MemoryDav()
        val server = server(dav::handle)
        val base = "dav://127.0.0.1:${server.port}/dav/"
        val connector = WebDavConnector(base, ServerLogin("me", "secret"), OkHttpRemoteHttp())
        roundTrip(WebDavSyncStorage(connector, base))
        assertTrue("/dav/Edendale" in dav.collections)
        assertTrue("/dav/Edendale/Family" in dav.collections)
        val methods = server.requests.map { it.method }
        assertTrue("MKCOL" in methods && "PUT" in methods && "PROPFIND" in methods && "DELETE" in methods)
        assertTrue(server.requests.filter { it.method == "PROPFIND" }.all { it.header("Depth") == "1" && "getetag" in String(it.body) })
        // A space with a space in its name is percent-encoded on the wire and decoded by the server.
        runBlocking { WebDavSyncStorage(connector, base).ensureSpace("Living Room") }
        assertTrue("/dav/Edendale/Living Room" in dav.collections)
    }

    // MARK: - SFTP

    private lateinit var ssh: SshServer
    private val hostKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun `SFTP writes a temporary file and renames it over the target`() {
        val root: Path = Files.createTempDirectory("edendale-sync-sftp")
        Files.createDirectories(root.resolve("home"))
        ssh = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "me" && password == "secret" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        ssh.start()
        val pins = Sftp.HostKeyPins { _, _ -> Sftp.fingerprint(hostKeyPair.public) }
        val storage = SftpSyncStorage("127.0.0.1", ssh.port, ServerLogin("me", "secret"), pins, "/home")
        roundTrip(storage)
        assertTrue(Files.isDirectory(root.resolve("home/Edendale/Family")))
        // No temporary file is left behind after a write.
        runBlocking { storage.write("Family", fileName, payload) }
        assertEquals(listOf(fileName), Files.list(root.resolve("home/Edendale/Family")).map { it.fileName.toString() }.toList())
        root.toFile().deleteRecursively()
    }

    // MARK: - Google Drive

    private fun account(kind: MediaSourceKind) = CloudAccount(kind, "subject", "me@example.com", "Me", "refresh", emptyList(), driveId = "drive1")

    private fun provider(kind: MediaSourceKind, host: String, server: LocalHttpServer): Pair<ProviderHttp, RewritingHttp> {
        val http = RewritingHttp(host, server.port)
        val account = account(kind)
        return ProviderHttp(kind, account.key, seededTokens(account, http), http) to http
    }

    @Test
    fun `Google Drive finds or creates the folders and uploads with files create and update`() {
        // A fake Drive: folders by (name, parent), files by id, modifiedTime from a counter.
        val folders = ConcurrentHashMap<String, String>() // id -> "name|parent"
        val files = ConcurrentHashMap<String, Triple<String, String, ByteArray>>() // id -> (name, parent, bytes)
        var counter = 0
        val server = server { request ->
            val query = request.query?.split('&')?.associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.orEmpty()
            when {
                request.method == "GET" && request.path == "/drive/v3/files" -> {
                    val q = query.getValue("q")
                    val parent = Regex("'([^']+)' in parents").find(q)!!.groupValues[1]
                    val items = if ("mimeType = '${GoogleDriveSyncStorage.FOLDER_MIME}'" in q) {
                        val name = Regex("name = '([^']+)'").find(q)!!.groupValues[1]
                        folders.filterValues { it == "$name|$parent" }.map { (id, _) -> """{"id":"$id","name":"$name"}""" }
                    } else {
                        files.filterValues { it.second == parent }.map { (id, file) -> """{"id":"$id","name":"${file.first}","modifiedTime":"2026-11-02T00:00:0${counter % 10}Z","size":"${file.third.size}"}""" }
                    }
                    LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"files":[${items.joinToString(",")}]}""".toByteArray())
                }
                request.method == "POST" && request.path == "/drive/v3/files" -> {
                    val body = Json.parseToJsonElement(String(request.body)).jsonObject
                    val id = "f${++counter}"
                    folders[id] = body["name"]!!.jsonPrimitive.content + "|" + body["parents"]!!.jsonArrayFirst()
                    LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"id":"$id"}""".toByteArray())
                }
                request.method == "POST" && request.path == "/upload/drive/v3/files" -> {
                    val text = String(request.body, Charsets.ISO_8859_1)
                    assertTrue(request.header("Content-Type")!!.startsWith("multipart/related; boundary="))
                    val metadata = Json.parseToJsonElement(text.substringAfter("\r\n\r\n").substringBefore("\r\n--")).jsonObject
                    val media = text.substringAfter("Content-Type: application/json\r\n\r\n").substringBefore("\r\n--${GoogleDriveSyncStorage.BOUNDARY}--").toByteArray(Charsets.ISO_8859_1)
                    val id = "f${++counter}"
                    files[id] = Triple(metadata["name"]!!.jsonPrimitive.content, metadata["parents"]!!.jsonArrayFirst(), media)
                    LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"id":"$id"}""".toByteArray())
                }
                request.method == "PATCH" && request.path.startsWith("/upload/drive/v3/files/") -> {
                    val id = request.path.substringAfterLast('/')
                    val current = files[id] ?: return@server LocalHttpServer.Response(404)
                    files[id] = current.copy(third = request.body)
                    counter++
                    LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"id":"$id"}""".toByteArray())
                }
                request.method == "GET" && request.path.startsWith("/drive/v3/files/") && query["alt"] == "media" ->
                    files[request.path.substringAfterLast('/')]?.let { LocalHttpServer.Response(200, emptyMap(), it.third) } ?: LocalHttpServer.Response(404)
                request.method == "GET" && request.path.startsWith("/drive/v3/files/") -> {
                    val id = request.path.substringAfterLast('/')
                    if (folders.containsKey(id)) LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"id":"$id","trashed":false}""".toByteArray()) else LocalHttpServer.Response(404, emptyMap(), "{}".toByteArray())
                }
                request.method == "DELETE" -> if (files.remove(request.path.substringAfterLast('/')) != null) LocalHttpServer.Response(204) else LocalHttpServer.Response(404)
                else -> LocalHttpServer.Response(500)
            }
        }
        val (provider, _) = provider(MediaSourceKind.GOOGLE_DRIVE, "www.googleapis.com", server)
        val storage = GoogleDriveSyncStorage(provider)
        roundTrip(storage)
        assertEquals(setOf("Edendale|root", "Family|f1"), folders.values.toSet())
        val spaceFolder = storage.spaceFolderId("Family")
        assertEquals("f2", spaceFolder)
        // A second storage given the folder ID (the handoff's googleFolderId) uses it without searching by name.
        val handed = GoogleDriveSyncStorage(provider, knownSpaceFolderId = spaceFolder)
        runBlocking { handed.ensureSpace("Family") }
        assertEquals("f2", handed.spaceFolderId("Family"))
        assertTrue(server.requests.none { it.header("Authorization") == null }, "every request carries the token")
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArrayFirst() = (this as kotlinx.serialization.json.JsonArray).first().jsonPrimitive.content

    // MARK: - OneDrive

    @Test
    fun `OneDrive uses the app folder, PUT content, and eTags`() {
        val items = ConcurrentHashMap<String, Pair<ByteArray, Int>>() // "Family/<name>" -> bytes, version
        val folderExists = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var server: LocalHttpServer
        server = server { request ->
            val path = URLDecoder.decode(request.path, "UTF-8")
            val approot = "/v1.0/me/drive/special/approot"
            when {
                request.method == "POST" && path == "$approot/children" -> {
                    folderExists.set(true)
                    LocalHttpServer.Response(201, mapOf("Content-Type" to "application/json"), """{"id":"folder1","name":"Family"}""".toByteArray())
                }
                request.method == "GET" && path == "$approot:/Family" -> if (folderExists.get()) LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"id":"folder1","folder":{}}""".toByteArray()) else LocalHttpServer.Response(404, mapOf("Content-Type" to "application/json"), """{"error":{"code":"itemNotFound"}}""".toByteArray())
                request.method == "GET" && path == "$approot:/Family:/children" -> {
                    val value = items.map { (key, file) -> """{"id":"${key.hashCode()}","name":"${key.substringAfter('/')}","eTag":"\"${file.second}\"","size":${file.first.size},"file":{}}""" }
                    LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"value":[${value.joinToString(",")}]}""".toByteArray())
                }
                request.method == "PUT" && path.endsWith(":/content") -> {
                    val key = path.removePrefix("$approot:/").removeSuffix(":/content")
                    items[key] = request.body to ((items[key]?.second ?: 0) + 1)
                    LocalHttpServer.Response(201, mapOf("Content-Type" to "application/json"), """{"id":"x","eTag":"\"${items[key]!!.second}\""}""".toByteArray())
                }
                request.method == "GET" && path.startsWith("$approot:/Family/") && !path.endsWith(":/content") -> {
                    val key = path.removePrefix("$approot:/")
                    if (items.containsKey(key)) LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"id":"x","@microsoft.graph.downloadUrl":"http://127.0.0.1:${server.port}/download/$key"}""".toByteArray()) else LocalHttpServer.Response(404)
                }
                request.method == "GET" && path.startsWith("/download/") -> items[path.removePrefix("/download/")]?.let { LocalHttpServer.Response(200, emptyMap(), it.first) } ?: LocalHttpServer.Response(404)
                request.method == "DELETE" -> if (items.remove(path.removePrefix("$approot:/")) != null) LocalHttpServer.Response(204) else LocalHttpServer.Response(404)
                else -> LocalHttpServer.Response(500, emptyMap(), path.toByteArray())
            }
        }
        val (provider, http) = provider(MediaSourceKind.ONE_DRIVE, "graph.microsoft.com", server)
        roundTrip(OneDriveSyncStorage(provider, http))
        // The download link is pre-authenticated: no token travels to it.
        assertTrue(server.requests.filter { it.path.startsWith("/download/") }.all { it.header("Authorization") == null })
        assertTrue(server.requests.any { it.method == "POST" && it.path.endsWith("/approot/children") && "conflictBehavior" in String(it.body) })
    }

    // MARK: - Dropbox

    @Test
    fun `Dropbox uploads with overwrite through the content endpoint and stamps with rev`() {
        val files = ConcurrentHashMap<String, Pair<ByteArray, Int>>()
        val folders = ConcurrentHashMap.newKeySet<String>()
        val server = server { request ->
            fun arg(): kotlinx.serialization.json.JsonObject = Json.parseToJsonElement(request.header("Dropbox-API-Arg") ?: String(request.body)).jsonObject
            fun ok(json: String) = LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), json.toByteArray())
            when (request.path) {
                "/2/files/create_folder_v2" -> {
                    val path = arg()["path"]!!.jsonPrimitive.content
                    if (!folders.add(path)) LocalHttpServer.Response(409, mapOf("Content-Type" to "application/json"), """{"error_summary":"path/conflict/folder/..."}""".toByteArray()) else ok("""{"metadata":{"name":"x"}}""")
                }
                "/2/files/list_folder" -> {
                    val path = arg()["path"]!!.jsonPrimitive.content
                    if (path !in folders) return@server LocalHttpServer.Response(409, mapOf("Content-Type" to "application/json"), """{"error_summary":"path/not_found/..."}""".toByteArray())
                    val entries = files.filterKeys { it.substringBeforeLast('/') == path }.map { (key, file) -> """{".tag":"file","name":"${key.substringAfterLast('/')}","id":"id:${key.hashCode()}","rev":"rev${file.second}","size":${file.first.size}}""" }
                    ok("""{"entries":[${entries.joinToString(",")}],"cursor":"c","has_more":false}""")
                }
                "/2/files/upload" -> {
                    val argument = arg()
                    assertEquals("overwrite", argument["mode"]!!.jsonPrimitive.content)
                    assertEquals("application/octet-stream", request.header("Content-Type"))
                    val path = argument["path"]!!.jsonPrimitive.content
                    files[path] = request.body to ((files[path]?.second ?: 0) + 1)
                    ok("""{"name":"x","rev":"rev${files[path]!!.second}"}""")
                }
                "/2/files/download" -> files[arg()["path"]!!.jsonPrimitive.content]?.let { LocalHttpServer.Response(200, emptyMap(), it.first) } ?: LocalHttpServer.Response(409)
                "/2/files/delete_v2" -> if (files.remove(arg()["path"]!!.jsonPrimitive.content) != null) ok("""{"metadata":{}}""") else LocalHttpServer.Response(409, mapOf("Content-Type" to "application/json"), """{"error_summary":"path_lookup/not_found/"}""".toByteArray())
                else -> LocalHttpServer.Response(500)
            }
        }
        // Both API hosts go to the one local server.
        val account = account(MediaSourceKind.DROPBOX)
        val http = object : com.babasama.edendale.remote.RemoteHttp {
            val local = OkHttpRemoteHttp()
            override fun newCall(request: com.babasama.edendale.remote.RemoteRequest, bodyLimit: Int) = local.newCall(
                com.babasama.edendale.remote.RemoteRequest(
                    request.url.replace("https://api.dropboxapi.com", "http://127.0.0.1:${server.port}").replace("https://content.dropboxapi.com", "http://127.0.0.1:${server.port}"),
                    request.headers, request.method, request.body, request.contentType,
                ),
                bodyLimit,
            )
        }
        val provider = ProviderHttp(MediaSourceKind.DROPBOX, account.key, seededTokens(account, http), http)
        roundTrip(DropboxSyncStorage(provider))
        assertTrue("/Edendale/Family" in folders)
        // Non-ASCII in the header argument is escaped.
        val argument = DropboxSyncStorage.apiArgument(kotlinx.serialization.json.buildJsonObject { put("path", kotlinx.serialization.json.JsonPrimitive("/Edendale/Família")) })
        assertEquals("{\"path\":\"/Edendale/Fam\\u00edlia\"}", argument)
        assertTrue(argument.all { it.code < 0x7F })
    }

    @Suppress("unused")
    private val memoryStore = MemorySecretStore()
}
