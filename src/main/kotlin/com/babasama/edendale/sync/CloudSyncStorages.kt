package com.babasama.edendale.sync

import com.babasama.edendale.connectors.DropboxConnector
import com.babasama.edendale.connectors.GoogleDriveConnector
import com.babasama.edendale.connectors.OneDriveConnector
import com.babasama.edendale.connectors.ProviderHttp
import com.babasama.edendale.connectors.S3Signer
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteHttp
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.RemoteSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
private fun JsonObject.array(name: String): List<JsonObject> = (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/**
 * Google Drive as a sync location (SYNC-PROTOCOL.md §4) under `drive.file`,
 * which sees the folders and files Edendale itself created: `Edendale/` in
 * the Drive root, the space folder inside it, `files.create` for a new
 * device file and `files.update` with `uploadType=media` to replace it, and
 * `files.list` on the space folder with `modifiedTime` as the stamp. A space
 * folder ID handed over from another ecosystem (§9.8) is used as is.
 */
class GoogleDriveSyncStorage(
    private val provider: ProviderHttp,
    /** The space folder's Drive ID when another device already made it (the handoff's `googleFolderId`). */
    knownSpaceFolderId: String? = null,
) : SyncStorage {
    private var rootFolderId: String? = null
    private val spaceFolders = mutableMapOf<String, String>()
    private val fileIds = mutableMapOf<String, String>()
    private val knownSpaceFolder = knownSpaceFolderId?.takeIf { it.isNotBlank() }

    /** The space folder's Drive ID once [ensureSpace] ran: the handoff sends it across ecosystems. */
    fun spaceFolderId(space: String): String? = spaceFolders[space] ?: knownSpaceFolder

    override suspend fun ensureSpace(space: String) {
        spaceFolders[space]?.let { return }
        knownSpaceFolder?.let { id ->
            // Confirm it still exists and isn't trashed; otherwise fall back to creating one by name.
            val item = provider.json({ it == 404 }) { RemoteRequest(GoogleDriveConnector.url("files/${S3Signer.uriEncode(id)}", listOf("fields" to "id,trashed", "supportsAllDrives" to "true"))) }
            if (item.text("id") == id && item.text("trashed") != "true") {
                spaceFolders[space] = id
                return
            }
        }
        val root = rootFolderId ?: findOrCreateFolder(SyncStorage.ROOT_FOLDER, "root").also { rootFolderId = it }
        spaceFolders[space] = findOrCreateFolder(space, root)
    }

    private suspend fun findOrCreateFolder(name: String, parent: String): String {
        val query = "name = '${escape(name)}' and mimeType = '$FOLDER_MIME' and '${escape(parent)}' in parents and trashed = false"
        val found = provider.json { RemoteRequest(GoogleDriveConnector.url("files", listOf("q" to query, "fields" to "files(id,name)", "pageSize" to "10"))) }
        found.array("files").firstOrNull { it.text("name") == name }?.text("id")?.let { return it }
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(FOLDER_MIME))
            put("parents", JsonArray(listOf(JsonPrimitive(parent))))
        }
        val created = provider.json { ProviderHttp.jsonPost(GoogleDriveConnector.url("files", listOf("fields" to "id")), metadata) }
        return created.text("id") ?: throw RemoteSourceException(provider.kind, RemoteFailure.ServerError(200))
    }

    override suspend fun list(space: String): List<RemoteSyncFile> {
        ensureSpace(space)
        val folder = spaceFolders.getValue(space)
        val files = mutableListOf<RemoteSyncFile>()
        var pageToken: String? = null
        do {
            val parameters = buildList {
                add("q" to "'${escape(folder)}' in parents and trashed = false")
                add("fields" to "nextPageToken,files(id,name,modifiedTime,size)")
                add("pageSize" to "1000")
                add("supportsAllDrives" to "true")
                add("includeItemsFromAllDrives" to "true")
                pageToken?.let { add("pageToken" to it) }
            }
            val page = provider.json { RemoteRequest(GoogleDriveConnector.url("files", parameters)) }
            page.array("files").forEach { file ->
                val id = file.text("id") ?: return@forEach
                val name = file.text("name") ?: return@forEach
                fileIds[fileKey(space, name)] = id
                files += RemoteSyncFile(name, file.text("modifiedTime").orEmpty(), file.number("size"))
            }
            pageToken = page.text("nextPageToken")
        } while (pageToken != null)
        return files
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray {
        val id = fileId(space, name) ?: throw RemoteSourceException(provider.kind, RemoteFailure.NotFound)
        val response = provider.send { RemoteRequest(GoogleDriveConnector.url("files/${S3Signer.uriEncode(id)}", listOf("alt" to "media", "supportsAllDrives" to "true"))) }
        return if (response.body.size > limit) response.body.copyOf(limit) else response.body
    }

    override suspend fun write(space: String, name: String, bytes: ByteArray) {
        ensureSpace(space)
        val existing = fileId(space, name)
        if (existing != null) {
            provider.send {
                RemoteRequest(
                    url = "$UPLOAD_BASE/files/${S3Signer.uriEncode(existing)}?uploadType=media&supportsAllDrives=true",
                    method = "PATCH",
                    body = bytes,
                    contentType = CONTENT_TYPE,
                )
            }
            return
        }
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(CONTENT_TYPE))
            put("parents", JsonArray(listOf(JsonPrimitive(spaceFolders.getValue(space)))))
        }
        val body = multipart(metadata.toString(), bytes)
        val created = provider.json {
            RemoteRequest(
                url = "$UPLOAD_BASE/files?uploadType=multipart&supportsAllDrives=true&fields=id",
                method = "POST",
                body = body,
                contentType = "multipart/related; boundary=$BOUNDARY",
            )
        }
        created.text("id")?.let { fileIds[fileKey(space, name)] = it }
    }

    override suspend fun delete(space: String, name: String) {
        val id = fileId(space, name) ?: return
        provider.send({ it == 404 }) { RemoteRequest(GoogleDriveConnector.url("files/${S3Signer.uriEncode(id)}", listOf("supportsAllDrives" to "true")), method = "DELETE") }
        fileIds.remove(fileKey(space, name))
    }

    private suspend fun fileId(space: String, name: String): String? {
        fileIds[fileKey(space, name)]?.let { return it }
        list(space)
        return fileIds[fileKey(space, name)]
    }

    private fun fileKey(space: String, name: String) = "$space/$name"

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        const val CONTENT_TYPE = "application/json"
        internal const val BOUNDARY = "edendale-sync-boundary"

        /** A Drive query string literal: backslashes and quotes escaped. */
        fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

        /** The `multipart/related` body of a `files.create` upload: the metadata part, then the media part. */
        fun multipart(metadata: String, media: ByteArray): ByteArray {
            val head = "--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$BOUNDARY\r\nContent-Type: $CONTENT_TYPE\r\n\r\n"
            val tail = "\r\n--$BOUNDARY--"
            return head.toByteArray(Charsets.UTF_8) + media + tail.toByteArray(Charsets.UTF_8)
        }
    }
}

/**
 * OneDrive as a sync location (§4; FEATURES-27.1.md §10 decision 1): the app
 * folder, `Apps/Edendale/`, reached as `/me/drive/special/approot` with
 * `Files.ReadWrite.AppFolder`, so `Edendale/` itself is the app folder and
 * the space folder sits directly inside it. `PUT …:/content` replaces a
 * device file (under 4 MiB; a heavy history is well under 1 MiB) and
 * `eTag` is the stamp.
 */
class OneDriveSyncStorage(private val provider: ProviderHttp, private val http: RemoteHttp) : SyncStorage {
    private fun itemUrl(path: String) = "${OneDriveConnector.API_BASE}me/drive/special/approot:/${encodePath(path)}"

    override suspend fun ensureSpace(space: String) {
        val existing = provider.json({ it == 404 }) { RemoteRequest(itemUrl(space) + "?\$select=id,folder") }
        if (existing.text("id") != null) return
        val body = buildJsonObject {
            put("name", JsonPrimitive(space))
            put("folder", buildJsonObject {})
            put("@microsoft.graph.conflictBehavior", JsonPrimitive("fail"))
        }
        provider.send({ it == 409 }) { ProviderHttp.jsonPost("${OneDriveConnector.API_BASE}me/drive/special/approot/children", body) }
    }

    override suspend fun list(space: String): List<RemoteSyncFile> {
        val files = mutableListOf<RemoteSyncFile>()
        var next: String? = itemUrl(space) + ":/children?\$select=id,name,eTag,lastModifiedDateTime,size,file&\$top=200"
        while (next != null) {
            val url = next
            val page = provider.json({ it == 404 }) { RemoteRequest(url) }
            if (page.text("id") == null && page["value"] == null) return emptyList()
            page.array("value").forEach { item ->
                if (item["file"] == null) return@forEach
                val name = item.text("name") ?: return@forEach
                files += RemoteSyncFile(name, item.text("eTag") ?: item.text("lastModifiedDateTime").orEmpty(), item.number("size"))
            }
            next = page.text("@odata.nextLink")
        }
        return files
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray {
        val item = provider.json { RemoteRequest(itemUrl("$space/$name")) }
        val link = item.text("@microsoft.graph.downloadUrl") ?: throw RemoteSourceException(provider.kind, RemoteFailure.AccessDenied)
        return withContext(Dispatchers.IO) {
            val response = http.newCall(RemoteRequest(link), bodyLimit = limit).execute()
            if (response.status !in 200..299) throw RemoteSourceException(provider.kind, RemoteFailure.ServerError(response.status))
            response.body
        }
    }

    override suspend fun write(space: String, name: String, bytes: ByteArray) {
        if (bytes.size > SIMPLE_UPLOAD_LIMIT) throw IOException("Device file too large for a simple upload")
        ensureSpace(space)
        provider.send { RemoteRequest(itemUrl("$space/$name") + ":/content", method = "PUT", body = bytes, contentType = "application/json") }
    }

    override suspend fun delete(space: String, name: String) {
        provider.send({ it == 404 }) { RemoteRequest(itemUrl("$space/$name"), method = "DELETE") }
    }

    companion object {
        /** Graph's limit for `PUT …:/content`; above it an upload session is needed (not implemented: device files stay far below). */
        const val SIMPLE_UPLOAD_LIMIT = 4 shl 20

        internal fun encodePath(path: String) = path.split('/').joinToString("/") { S3Signer.uriEncode(it) }
    }
}

/**
 * Dropbox as a sync location (§4): `/Edendale/<space>/` in the account,
 * `files/upload` with `mode: overwrite`, `files/download`, and
 * `files/list_folder` with `rev` as the stamp. The content endpoints take
 * their arguments in the ASCII-only `Dropbox-API-Arg` header.
 */
class DropboxSyncStorage(private val provider: ProviderHttp) : SyncStorage {
    private fun spacePath(space: String) = "/${SyncStorage.ROOT_FOLDER}/$space"

    override suspend fun ensureSpace(space: String) {
        val body = buildJsonObject {
            put("path", JsonPrimitive(spacePath(space)))
            put("autorename", JsonPrimitive(false))
        }
        // 409 is "already there" (path/conflict/folder): fine.
        provider.send({ it == 409 }) { ProviderHttp.jsonPost("${DropboxConnector.API_BASE}files/create_folder_v2", body) }
    }

    override suspend fun list(space: String): List<RemoteSyncFile> {
        val body = buildJsonObject {
            put("path", JsonPrimitive(spacePath(space)))
            put("recursive", JsonPrimitive(false))
            put("include_deleted", JsonPrimitive(false))
            put("limit", JsonPrimitive(2000))
        }
        val files = mutableListOf<RemoteSyncFile>()
        var page = provider.json({ it == 409 }) { ProviderHttp.jsonPost("${DropboxConnector.API_BASE}files/list_folder", body) }
        if (page["entries"] == null) return emptyList()
        fun collect(page: JsonObject) = page.array("entries").forEach { entry ->
            if (entry.text(".tag") != "file") return@forEach
            val name = entry.text("name") ?: return@forEach
            files += RemoteSyncFile(name, entry.text("rev") ?: entry.text("server_modified").orEmpty(), entry.number("size"))
        }
        collect(page)
        while (page.text("has_more") == "true") {
            val cursor = page.text("cursor") ?: break
            page = provider.json { ProviderHttp.jsonPost("${DropboxConnector.API_BASE}files/list_folder/continue", buildJsonObject { put("cursor", JsonPrimitive(cursor)) }) }
            collect(page)
        }
        return files
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray {
        val argument = buildJsonObject { put("path", JsonPrimitive("${spacePath(space)}/$name")) }
        val response = provider.send {
            RemoteRequest("${CONTENT_BASE}files/download", headers = mapOf("Dropbox-API-Arg" to apiArgument(argument)), method = "POST", body = ByteArray(0))
        }
        return if (response.body.size > limit) response.body.copyOf(limit) else response.body
    }

    override suspend fun write(space: String, name: String, bytes: ByteArray) {
        ensureSpace(space)
        val argument = buildJsonObject {
            put("path", JsonPrimitive("${spacePath(space)}/$name"))
            put("mode", JsonPrimitive("overwrite"))
            put("mute", JsonPrimitive(true))
        }
        provider.send {
            RemoteRequest(
                "${CONTENT_BASE}files/upload",
                headers = mapOf("Dropbox-API-Arg" to apiArgument(argument)),
                method = "POST",
                body = bytes,
                contentType = "application/octet-stream",
            )
        }
    }

    override suspend fun delete(space: String, name: String) {
        val body = buildJsonObject { put("path", JsonPrimitive("${spacePath(space)}/$name")) }
        provider.send({ it == 409 }) { ProviderHttp.jsonPost("${DropboxConnector.API_BASE}files/delete_v2", body) }
    }

    companion object {
        const val CONTENT_BASE = "https://content.dropboxapi.com/2/"

        /** The header form of a Dropbox argument: JSON with every non-ASCII character as `\uXXXX`, as HTTP headers require. */
        fun apiArgument(argument: JsonObject): String = buildString {
            argument.toString().forEach { char ->
                if (char.code < 0x7F) append(char) else append("\\u%04x".format(char.code))
            }
        }

        @Suppress("unused")
        private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    }
}
