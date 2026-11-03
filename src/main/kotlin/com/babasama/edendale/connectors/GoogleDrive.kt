package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudProviders
import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.remote.RemoteContentResolver
import com.babasama.edendale.remote.RemoteRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * Google Drive through the Drive v3 REST API with `drive.file` (H.9.4,
 * Apple's `GoogleDriveConnector`): Edendale sees only the files the viewer
 * picked in Google's picker, which opens during sign-in and from Choose More
 * Videos. So there is nothing to browse. Each account has one virtual
 * folder, "Videos you chose", listing every file picked so far on any device
 * or platform, because Google grants picks per account and Cloud project,
 * not per token. Item URLs are `gdrive://<account>/<fileId>/<Name.ext>`.
 *
 * Folders are skipped (a picked folder never grants what's inside it),
 * shortcuts are followed to their targets, other Google formats (Docs,
 * Sheets) are skipped, and videos are recognized by file extension rather
 * than MIME type. Files stream from `files/<id>?alt=media` with a Bearer
 * token and `Range`. `acknowledgeAbuse` is never sent: a file Google flags
 * as abusive fails with a clear message instead.
 */
class GoogleDriveConnector(
    val account: CloudAccount,
    private val provider: ProviderHttp,
) : MediaConnector {
    override val kind = MediaSourceKind.GOOGLE_DRIVE
    override val root: String = chosenFolderUrl(account.key)
    override val accountLabel: String = account.label

    /** Only "Videos you chose" can become a source; nothing else lists. */
    override fun canIndex(directory: String): Boolean = isChosenFolder(directory)

    /** `about` with the account's own email: the lightest call that proves the token works. */
    override suspend fun validate() {
        provider.json { RemoteRequest(url("about", listOf("fields" to "user(emailAddress)"))) }
    }

    /** Only "Videos you chose" lists: it holds every file the viewer picked, and no folder's contents are ever granted. */
    override suspend fun list(directory: String): List<ConnectorEntry> {
        if (!isChosenFolder(directory) || SourceUrl.parseAccountItem(directory)?.account != account.key) {
            throw ConnectorException(ConnectorFailure.InvalidAddress)
        }
        return listChosenFiles()
    }

    // MARK: - Listing

    /**
     * Every file the viewer picked, across pages. `drive.file` limits
     * `files.list` to those, whether they're in My Drive, shared with the
     * viewer, or in a shared drive.
     */
    private suspend fun listChosenFiles(): List<ConnectorEntry> {
        val entries = mutableListOf<ConnectorEntry>()
        var pageToken: String? = null
        do {
            val parameters = buildList {
                add("q" to "trashed = false")
                add("fields" to FILE_FIELDS)
                add("pageSize" to "1000")
                add("supportsAllDrives" to "true")
                add("includeItemsFromAllDrives" to "true")
                pageToken?.let { add("pageToken" to it) }
            }
            val page = provider.json { RemoteRequest(url("files", parameters)) }
            entries += page.array("files").mapNotNull(::entry)
            pageToken = page.text("nextPageToken")
        } while (pageToken != null)
        return entries.sortedWith(WebDavConnector.FOLDERS_FIRST)
    }

    /** Maps a picked Drive file to an entry. Folders, folder shortcuts, and other Google formats are skipped. */
    internal fun entry(file: JsonObject): ConnectorEntry? {
        var id = file.text("id") ?: return null
        val name = file.text("name") ?: return null
        var mimeType = file.text("mimeType").orEmpty()
        if (mimeType == SHORTCUT_MIME_TYPE) {
            val details = file.obj("shortcutDetails")
            id = details?.text("targetId") ?: return null
            mimeType = details.text("targetMimeType").orEmpty()
        }
        // Folders are Google formats too.
        if (mimeType.startsWith(GOOGLE_APPS_PREFIX)) return null
        return ConnectorEntry(
            name = name,
            url = SourceUrl.accountItem(kind, account.key, listOf(id), name),
            isDirectory = false,
            // Drive encodes 64-bit numbers as strings.
            size = file.number("size"),
            // Missing until Drive finishes processing a video.
            durationSeconds = file.obj("videoMediaMetadata")?.number("durationMillis")?.let { it / 1_000.0 },
            modifiedEpochMillis = parseInstant(file.text("modifiedTime")),
        )
    }

    companion object {
        const val API_BASE = "https://www.googleapis.com/drive/v3/"
        const val SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut"
        private const val GOOGLE_APPS_PREFIX = "application/vnd.google-apps."
        private const val FILE_FIELDS =
            "nextPageToken,files(id,name,mimeType,size,modifiedTime,videoMediaMetadata(durationMillis),shortcutDetails(targetId,targetMimeType))"

        /** The ID of the "Videos you chose" folder; real Drive IDs never start with `~`. */
        const val CHOSEN_FOLDER_ID = "~chosen"

        /** The name in its URL, fixed so a source's stored URL is the same in every language. */
        const val CHOSEN_FOLDER_URL_NAME = "Google Drive"

        /** The account's Google Drive source URL. */
        fun chosenFolderUrl(accountKey: String): String =
            SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, accountKey, listOf(CHOSEN_FOLDER_ID), CHOSEN_FOLDER_URL_NAME)

        /** Whether [url] is a Google Drive source from a `drive.file` build: older builds linked Drive folders, which `drive.file` can't list. */
        fun isChosenFolder(url: String): Boolean {
            val item = SourceUrl.parseAccountItem(url) ?: return false
            return item.kind == MediaSourceKind.GOOGLE_DRIVE && item.ids == listOf(CHOSEN_FOLDER_ID)
        }

        /** The Drive file ID in an item URL; null for other kinds and for "Videos you chose" itself. */
        fun fileId(itemUrl: String): String? {
            val item = SourceUrl.parseAccountItem(itemUrl)?.takeIf { it.kind == MediaSourceKind.GOOGLE_DRIVE } ?: return null
            return item.ids.single().takeIf { it != CHOSEN_FOLDER_ID }
        }

        /** `API_BASE/path?query`, with each value encoded the way Google expects (spaces and quotes escaped). */
        fun url(path: String, query: List<Pair<String, String>>): String =
            API_BASE + path + "?" + query.joinToString("&") { "${S3Signer.uriEncode(it.first)}=${S3Signer.uriEncode(it.second)}" }
    }
}

/**
 * The move to `drive.file` (H.9.4, D20: a clean break). An older build's
 * Google accounts hold `drive.readonly` tokens, under which "Videos you
 * chose" would list every file in Drive, and its sources are Drive folders,
 * which `drive.file` can't list. Both go at launch, and the viewer is told
 * once to link Google Drive again. Old tokens aren't revoked: Google would
 * end the whole grant, including a newer sign-in on another device.
 */
object GoogleDriveMigration {
    /** Google accounts from before `drive.file`. */
    fun outdatedAccounts(accounts: List<CloudAccount>): List<CloudAccount> =
        accounts.filter { it.kind == MediaSourceKind.GOOGLE_DRIVE && !CloudProviders.hasDriveFileAccess(it.scopes) }

    /**
     * Whether a source must go: a Google Drive source that isn't "Videos you
     * chose", or one read through an account in [outdatedKeys].
     */
    fun isOutdatedSource(kind: MediaSourceKind?, url: String, accountKey: String?, outdatedKeys: Set<String>): Boolean =
        kind == MediaSourceKind.GOOGLE_DRIVE && (!GoogleDriveConnector.isChosenFolder(url) || accountKey in outdatedKeys)
}

/**
 * Removing a picked Drive video from the library (H.9.4, D21). Google can't
 * unshare one file with an app, so the source keeps the file IDs removed on
 * this device and its scans skip them. Picking a removed video again brings
 * it back.
 */
object GoogleDriveHiddenItems {
    /** [entries] without the files in [hidden]; entries of other kinds pass. */
    fun visible(entries: List<ConnectorEntry>, hidden: Set<String>): List<ConnectorEntry> {
        if (hidden.isEmpty()) return entries
        return entries.filter { entry -> GoogleDriveConnector.fileId(entry.url)?.let { it !in hidden } ?: true }
    }

    /** [hidden] with the IDs of the removed [itemUrls] added. */
    fun hiding(hidden: Set<String>, itemUrls: List<String>): Set<String> = hidden + itemUrls.mapNotNull(GoogleDriveConnector::fileId)

    /** [hidden] without the files just picked again. */
    fun unhiding(hidden: Set<String>, picked: List<String>): Set<String> = hidden - picked.toSet()
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
