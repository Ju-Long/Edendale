package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.remote.RemoteContentResolver
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.RemoteSourceException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Dropbox through API v2 with the scoped `files.metadata.read`,
 * `files.content.read`, and `account_info.read` (H.8, Apple's
 * `DropboxConnector`). Item URLs are `dropbox://<account>/<fileId>/<name>`
 * with the percent-encoded `id:…`; the root is the virtual ID `root` (the
 * API's empty path). An import lists the whole folder in one recursive
 * `list_folder` (plus `continue` pages) instead of walking it.
 */
class DropboxConnector(
    val account: CloudAccount,
    private val provider: ProviderHttp,
) : MediaConnector {
    override val kind = MediaSourceKind.DROPBOX
    override val root: String = SourceUrl.accountItem(kind, account.key, listOf(ROOT_FOLDER), "Dropbox")
    override val accountLabel: String = account.label

    override suspend fun list(directory: String): List<ConnectorEntry> =
        listFolder(directory, recursive = false).filterNot { it.isHidden }.sortedWith(WebDavConnector.FOLDERS_FIRST)

    /** One recursive listing names every descendant; anything inside a hidden folder is skipped, as the walk would. */
    override suspend fun enumerateVideos(folder: String): Enumeration =
        Enumeration(listFolder(folder, recursive = true).filter { it.isVideo && !it.isHidden }, complete = true)

    private suspend fun listFolder(folder: String, recursive: Boolean): List<ConnectorEntry> {
        val item = SourceUrl.parseAccountItem(folder)?.takeIf { it.kind == kind }
            ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
        val id = item.ids.first()
        val body = buildJsonObject {
            put("path", JsonPrimitive(if (id == ROOT_FOLDER) "" else id))
            put("recursive", JsonPrimitive(recursive))
            put("include_deleted", JsonPrimitive(false))
            put("include_non_downloadable_files", JsonPrimitive(false))
            put("limit", JsonPrimitive(2000))
        }
        var page = provider.json { ProviderHttp.jsonPost("${API_BASE}files/list_folder", body) }
        val entries = page.array("entries").mapNotNull { entry(it, hiddenAncestors = recursive) }.toMutableList()
        while (page["has_more"]?.let { (it as? JsonPrimitive)?.content == "true" } == true) {
            val cursor = page.text("cursor") ?: break
            page = provider.json {
                ProviderHttp.jsonPost("${API_BASE}files/list_folder/continue", buildJsonObject { put("cursor", JsonPrimitive(cursor)) })
            }
            entries += page.array("entries").mapNotNull { entry(it, hiddenAncestors = recursive) }
        }
        return entries
    }

    /**
     * Files and folders become entries; deleted entries and files that can't
     * be downloaded (Google Docs and the like in Dropbox) don't. With
     * [hiddenAncestors], an item below a dot-folder is left out.
     */
    internal fun entry(metadata: JsonObject, hiddenAncestors: Boolean = false): ConnectorEntry? {
        val tag = metadata.text(".tag")
        val id = metadata.text("id") ?: return null
        val name = metadata.text("name") ?: return null
        if (tag != "file" && tag != "folder") return null
        if (tag == "file" && metadata.text("is_downloadable") == "false") return null
        if (hiddenAncestors) {
            val ancestors = metadata.text("path_lower")?.split('/')?.dropLast(1).orEmpty()
            if (ancestors.any { it.startsWith(".") }) return null
        }
        val isFolder = tag == "folder"
        return ConnectorEntry(
            name = name,
            url = SourceUrl.accountItem(kind, account.key, listOf(id), name),
            isDirectory = isFolder,
            size = if (isFolder) null else metadata.number("size"),
            modifiedEpochMillis = parseInstant(metadata.text("server_modified")),
        )
    }

    companion object {
        const val API_BASE = "https://api.dropboxapi.com/2/"
        const val ROOT_FOLDER = "root"
    }
}

/**
 * Streams from `get_temporary_link` (H.8), which lasts four hours and then
 * answers 410 Gone; the byte source then asks for a new one.
 */
class DropboxContentResolver(
    private val fileId: String,
    private val provider: ProviderHttp,
) : RemoteContentResolver {
    override val kind = MediaSourceKind.DROPBOX
    override val usesPreauthorizedLinks = true
    private var link: String? = null

    @Synchronized
    override fun contentRequest(refresh: Boolean): RemoteRequest {
        if (!refresh) link?.let { return RemoteRequest(it) }
        val result = runBlocking {
            provider.json {
                ProviderHttp.jsonPost("${DropboxConnector.API_BASE}files/get_temporary_link", buildJsonObject { put("path", JsonPrimitive(fileId)) })
            }
        }
        val fresh = result.text("link") ?: throw RemoteSourceException(kind, RemoteFailure.AccessDenied)
        link = fresh
        return RemoteRequest(fresh)
    }
}
