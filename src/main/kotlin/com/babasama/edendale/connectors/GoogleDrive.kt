package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.remote.RemoteContentResolver
import com.babasama.edendale.remote.RemoteRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/** The names of Drive's virtual folders, which the app localizes; the connector itself has no resources. */
data class GoogleDriveLabels(
    val root: String = "Google Drive",
    val myDrive: String = "My Drive",
    val sharedWithMe: String = "Shared with me",
    val sharedDrives: String = "Shared drives",
)

/**
 * Google Drive through the Drive v3 REST API with `drive.readonly` (H.9.2,
 * Apple's `GoogleDriveConnector`). Item URLs are
 * `gdrive://<account>/<fileId>/<Name.ext>`; folders use the folder's ID the
 * same way, plus `?drive=<driveId>` inside a shared drive, whose listings
 * must name it. The picker's root holds My Drive, Shared with me, and Shared
 * drives.
 *
 * Shortcuts are followed to their targets, other Google formats (Docs,
 * Sheets) are skipped, and videos are recognized by file extension rather
 * than MIME type. Files stream from `files/<id>?alt=media` with a Bearer
 * token and `Range`. `acknowledgeAbuse` is never sent: a file Google flags
 * as abusive fails with a clear message instead.
 */
class GoogleDriveConnector(
    val account: CloudAccount,
    private val provider: ProviderHttp,
    private val labels: GoogleDriveLabels = GoogleDriveLabels(),
) : MediaConnector {
    override val kind = MediaSourceKind.GOOGLE_DRIVE
    override val root: String = folderUrl(VirtualFolder.ROOTS, labels.root)
    override val accountLabel: String = account.label

    /** IDs of the picker's virtual folders; real Drive IDs never start with `~`. */
    object VirtualFolder {
        const val ROOTS = "~roots"
        const val SHARED_WITH_ME = "~shared"
        const val SHARED_DRIVES = "~drives"
        const val MY_DRIVE = "root"
    }

    /** The picker's root and the list of shared drives only gather other folders; linking either would scan all of Drive. */
    override fun canIndex(directory: String): Boolean {
        val id = SourceUrl.parseAccountItem(directory)?.takeIf { it.kind == kind }?.ids?.firstOrNull() ?: return false
        return id != VirtualFolder.ROOTS && id != VirtualFolder.SHARED_DRIVES
    }

    /** `about` with the account's own email: the lightest call that proves the token works. */
    override suspend fun validate() {
        provider.json { RemoteRequest(url("about", listOf("fields" to "user(emailAddress)"))) }
    }

    override suspend fun list(directory: String): List<ConnectorEntry> {
        val item = SourceUrl.parseAccountItem(directory)?.takeIf { it.kind == kind }
            ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
        return when (val id = item.ids.first()) {
            VirtualFolder.ROOTS -> listOf(
                ConnectorEntry(labels.myDrive, folderUrl(VirtualFolder.MY_DRIVE, labels.myDrive), isDirectory = true),
                ConnectorEntry(labels.sharedWithMe, folderUrl(VirtualFolder.SHARED_WITH_ME, labels.sharedWithMe), isDirectory = true),
                ConnectorEntry(labels.sharedDrives, folderUrl(VirtualFolder.SHARED_DRIVES, labels.sharedDrives), isDirectory = true),
            )
            VirtualFolder.SHARED_DRIVES -> listSharedDrives()
            VirtualFolder.SHARED_WITH_ME -> listFiles("sharedWithMe = true and trashed = false", driveId = null)
            else -> listFiles("'$id' in parents and trashed = false", driveId = item.queryValue("drive"))
        }
    }

    // MARK: - Listing

    private suspend fun listFiles(query: String, driveId: String?): List<ConnectorEntry> {
        val entries = mutableListOf<ConnectorEntry>()
        var pageToken: String? = null
        do {
            val parameters = buildList {
                add("q" to query)
                add("fields" to FILE_FIELDS)
                add("pageSize" to "1000")
                add("supportsAllDrives" to "true")
                add("includeItemsFromAllDrives" to "true")
                if (driveId != null) {
                    add("corpora" to "drive")
                    add("driveId" to driveId)
                }
                pageToken?.let { add("pageToken" to it) }
            }
            val page = provider.json { RemoteRequest(url("files", parameters)) }
            entries += page.array("files").mapNotNull { entry(it, driveId) }
            pageToken = page.text("nextPageToken")
        } while (pageToken != null)
        return entries.sortedWith(WebDavConnector.FOLDERS_FIRST)
    }

    private suspend fun listSharedDrives(): List<ConnectorEntry> {
        val entries = mutableListOf<ConnectorEntry>()
        var pageToken: String? = null
        do {
            val parameters = buildList {
                add("pageSize" to "100")
                add("fields" to "nextPageToken,drives(id,name)")
                pageToken?.let { add("pageToken" to it) }
            }
            val page = provider.json { RemoteRequest(url("drives", parameters)) }
            entries += page.array("drives").mapNotNull { drive ->
                val id = drive.text("id") ?: return@mapNotNull null
                val name = drive.text("name") ?: return@mapNotNull null
                // A shared drive's root folder has the drive's ID.
                ConnectorEntry(name, folderUrl(id, name, driveId = id), isDirectory = true)
            }
            pageToken = page.text("nextPageToken")
        } while (pageToken != null)
        return entries.sortedWith(WebDavConnector.FOLDERS_FIRST)
    }

    /** Maps a Drive file to an entry: folders and folder shortcuts are directories; other Google formats are skipped. */
    internal fun entry(file: JsonObject, driveId: String?): ConnectorEntry? {
        var id = file.text("id") ?: return null
        val name = file.text("name") ?: return null
        var mimeType = file.text("mimeType").orEmpty()
        if (mimeType == SHORTCUT_MIME_TYPE) {
            val details = file.obj("shortcutDetails")
            id = details?.text("targetId") ?: return null
            mimeType = details.text("targetMimeType").orEmpty()
        }
        val modified = parseInstant(file.text("modifiedTime"))
        if (mimeType == FOLDER_MIME_TYPE) {
            return ConnectorEntry(name, folderUrl(id, name, driveId), isDirectory = true, modifiedEpochMillis = modified)
        }
        if (mimeType.startsWith(GOOGLE_APPS_PREFIX)) return null
        return ConnectorEntry(
            name = name,
            url = SourceUrl.accountItem(kind, account.key, listOf(id), name),
            isDirectory = false,
            // Drive encodes 64-bit numbers as strings.
            size = file.number("size"),
            // Missing until Drive finishes processing a video.
            durationSeconds = file.obj("videoMediaMetadata")?.number("durationMillis")?.let { it / 1_000.0 },
            modifiedEpochMillis = modified,
        )
    }

    // MARK: - URLs

    fun folderUrl(id: String, name: String, driveId: String? = null): String = SourceUrl.accountItem(
        kind,
        account.key,
        listOf(id),
        name,
        query = driveId?.let { listOf("drive" to it) }.orEmpty(),
    )

    companion object {
        const val API_BASE = "https://www.googleapis.com/drive/v3/"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut"
        private const val GOOGLE_APPS_PREFIX = "application/vnd.google-apps."
        private const val FILE_FIELDS =
            "nextPageToken,files(id,name,mimeType,size,modifiedTime,videoMediaMetadata(durationMillis),shortcutDetails(targetId,targetMimeType))"

        /** `API_BASE/path?query`, with each value encoded the way Google expects (spaces and quotes escaped). */
        fun url(path: String, query: List<Pair<String, String>>): String =
            API_BASE + path + "?" + query.joinToString("&") { "${S3Signer.uriEncode(it.first)}=${S3Signer.uriEncode(it.second)}" }
    }
}

/**
 * Streams a Drive file with a Bearer token (H.9.2); a refresh asks the token
 * provider for one newer than the token the server just refused.
 */
class GoogleDriveContentResolver(
    private val fileId: String,
    private val accountKey: String,
    private val tokens: CloudTokenProvider,
) : RemoteContentResolver {
    override val kind = MediaSourceKind.GOOGLE_DRIVE
    private var lastToken: String? = null

    @Synchronized
    override fun contentRequest(refresh: Boolean): RemoteRequest {
        val token = runBlocking { tokens.accessToken(kind, accountKey, rejecting = if (refresh) lastToken else null) }
        lastToken = token
        val url = GoogleDriveConnector.url("files/${S3Signer.uriEncode(fileId)}", listOf("alt" to "media", "supportsAllDrives" to "true"))
        return RemoteRequest(url, mapOf("Authorization" to "Bearer $token"))
    }
}
