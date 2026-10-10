package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.remote.RemoteContentResolver
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.RemoteSourceException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * OneDrive (personal, work, and school) through Microsoft Graph with the
 * least-privileged `Files.Read` (H.7, Apple's `OneDriveConnector`). Item URLs
 * are `onedrive://<account>/<driveId>/<itemId>/<Name.ext>`; the picker's root
 * is the user's own drive. A folder shared into it (`remoteItem`) is followed
 * into the drive it lives in. Enumeration is the breadth-first walk, as on
 * Apple: folder-level `delta` works only for personal accounts.
 */
class OneDriveConnector private constructor(
    val account: CloudAccount,
    val driveId: String,
    private val provider: ProviderHttp,
) : MediaConnector {
    override val kind = MediaSourceKind.ONE_DRIVE
    override val root: String = SourceUrl.accountItem(kind, account.key, listOf(driveId, ROOT_ITEM), "OneDrive")
    override val accountLabel: String = account.label

    override suspend fun list(directory: String): List<ConnectorEntry> {
        val item = SourceUrl.parseAccountItem(directory)?.takeIf { it.kind == kind && it.ids.size == 2 }
            ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
        val entries = mutableListOf<ConnectorEntry>()
        var next: String? = childrenUrl(item.ids[0], item.ids[1])
        while (next != null) {
            val url = next
            val page = provider.json { RemoteRequest(url) }
            entries += page.array("value").mapNotNull(::entry)
            next = page.text("@odata.nextLink")
        }
        return entries.sortedWith(WebDavConnector.FOLDERS_FIRST)
    }

    /** Folders and files become entries; OneNote packages are skipped. */
    internal fun entry(item: JsonObject): ConnectorEntry? {
        if (item.obj("package") != null) return null
        val name = item.text("name") ?: return null
        var drive = item.obj("parentReference")?.text("driveId") ?: driveId
        var id = item.text("id") ?: return null
        var isFolder = item.obj("folder") != null
        var isFile = item.obj("file") != null
        var size = item.number("size")
        var video = item.obj("video")
        item.obj("remoteItem")?.let { remote ->
            // A folder shared into this drive lives in another one.
            drive = remote.obj("parentReference")?.text("driveId") ?: drive
            id = remote.text("id") ?: id
            isFolder = remote.obj("folder") != null
            isFile = remote.obj("file") != null
            size = remote.number("size") ?: size
            video = remote.obj("video") ?: video
        }
        if (!isFolder && !isFile) return null
        return ConnectorEntry(
            name = name,
            url = SourceUrl.accountItem(kind, account.key, listOf(drive, id), name),
            isDirectory = isFolder,
            size = if (isFolder) null else size,
            // Graph reports milliseconds.
            durationSeconds = video?.number("duration")?.let { it / 1_000.0 },
            modifiedEpochMillis = parseInstant(item.text("lastModifiedDateTime")),
        )
    }

    companion object {
        const val API_BASE = "https://graph.microsoft.com/v1.0/"
        const val ROOT_ITEM = "root"

        /** Null for an account from before its drive was known: sign in again. */
        fun create(account: CloudAccount, provider: ProviderHttp): OneDriveConnector? =
            account.driveId?.takeIf { it.isNotEmpty() }?.let { OneDriveConnector(account, it, provider) }

        fun childrenUrl(driveId: String, itemId: String): String {
            val path = if (itemId == ROOT_ITEM) "drives/$driveId/root/children" else "drives/$driveId/items/$itemId/children"
            val select = "id,name,size,folder,file,package,video,lastModifiedDateTime,parentReference,remoteItem"
            return "$API_BASE$path?\$select=$select&\$top=200"
        }

        fun itemUrl(driveId: String, itemId: String) = "${API_BASE}drives/$driveId/items/$itemId"
    }
}

/**
 * Streams from the item's pre-authenticated `@microsoft.graph.downloadUrl`
 * (H.7), which needs no Authorization header, may expire within minutes,
 * and takes `Range` itself. It may ignore `Range` and answer 200, which the
 * byte source accepts at offset 0. A refresh fetches a new link.
 */
class OneDriveContentResolver(
    private val driveId: String,
    private val itemId: String,
    private val provider: ProviderHttp,
) : RemoteContentResolver {
    override val kind = MediaSourceKind.ONE_DRIVE
    override val usesPreauthorizedLinks = true
    private var link: String? = null

    @Synchronized
    override fun contentRequest(refresh: Boolean): RemoteRequest {
        if (!refresh) link?.let { return RemoteRequest(it) }
        val item = runBlocking { provider.json { RemoteRequest(OneDriveConnector.itemUrl(driveId, itemId)) } }
        val fresh = item.text("@microsoft.graph.downloadUrl") ?: throw RemoteSourceException(kind, RemoteFailure.AccessDenied)
        link = fresh
        return RemoteRequest(fresh)
    }
}
