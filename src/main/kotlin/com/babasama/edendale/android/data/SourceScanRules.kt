package com.babasama.edendale.android.data

import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.Enumeration
import com.babasama.edendale.connectors.GoogleDriveConnector
import com.babasama.edendale.connectors.GoogleDriveHiddenItems
import com.babasama.edendale.connectors.GoogleDriveMigration
import com.babasama.edendale.connectors.MediaConnector
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceEndpoint
import com.babasama.edendale.connectors.SourceStatus
import com.babasama.edendale.domain.MediaParser
import com.babasama.edendale.domain.ParsedMedia
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException
import jcifs.smb.NtStatus
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException

/**
 * When the library rescans a source on its own, what a scan changes, and how
 * a failed scan is recorded on that source (D.3). Pure apart from jcifs-ng's
 * exception types, so the JVM suite covers it.
 */
internal object SourceScanRules {

    /** Remote sources scanned more recently than this are skipped by the automatic sweep. */
    const val AUTOMATIC_RESCAN_INTERVAL_MILLIS = 15 * 60 * 1000L

    /**
     * Values bound per statement when deleting rows by URI, under the 999
     * SQLite allowed before 3.32 (Android 8 to 11).
     */
    const val MAX_URIS_PER_STATEMENT = 500

    fun kindOf(folder: LibraryFolderEntity): MediaSourceKind? =
        MediaSourceKind.fromRaw(folder.kind) ?: MediaSourceKind.forSourceUri(folder.treeUri)

    /**
     * Whether the sweep on each Downloaded visit rescans [folder]. Local
     * folders always do; a remote one only once 15 minutes have passed since
     * its last successful scan, unless a scan of it began and never
     * committed (the app was closed mid-scan): its rows may then be
     * incomplete however recent that last scan is. A manual Rescan ignores
     * this.
     */
    fun shouldAutoRescan(folder: LibraryFolderEntity, nowMillis: Long): Boolean {
        if (isInterrupted(folder)) return true
        if (kindOf(folder)?.isRemote != true) return true
        val last = folder.lastScannedAt ?: return true
        return nowMillis - last >= AUTOMATIC_RESCAN_INTERVAL_MILLIS
    }

    /**
     * Whether a scan of [folder] began and never ended, so the launch resume
     * scans it again. Its commit or a recorded failure ends it, including one
     * by another scan that got to the source while the resume worked through
     * the sources before it.
     */
    fun isInterrupted(folder: LibraryFolderEntity): Boolean = folder.scanStartedAt != null

    /** [uris] in batches small enough to bind in one statement. */
    fun statementBatches(uris: List<String>): List<List<String>> = uris.chunked(MAX_URIS_PER_STATEMENT)

    /**
     * What storing one listing changes, worked out before anything is
     * written so it can commit as one transaction. [files] are the listing's
     * URIs and file names; [folderUri] is the source, or null for files
     * added on their own. Stale rows are the source's own rows
     * ([storedMovieUris], [storedEpisodeUris]) the listing no longer has,
     * and only a [complete] listing names any: a walk that broke off partway
     * doesn't mean every file it missed was deleted. A file is new when no
     * source holds its URI yet ([knownUris]) and is classified on the device
     * by MediaParser, before any network call: a blank title falls back to
     * the file name, and an episode of a show the library lacks
     * ([knownShowKeys]) brings one row for that show. A URI listed twice is
     * planned once.
     */
    fun plan(
        files: List<Pair<String, String>>,
        complete: Boolean,
        folderUri: String?,
        storedMovieUris: Collection<String>,
        storedEpisodeUris: Collection<String>,
        knownUris: Set<String>,
        knownShowKeys: Set<String>,
        now: Long,
    ): ScanPlan {
        val found = files.mapTo(HashSet()) { it.first }
        val planned = HashSet<String>()
        val newShowKeys = HashSet<String>()
        val movies = mutableListOf<LibraryMovieEntity>()
        val shows = mutableListOf<LibraryShowEntity>()
        val episodes = mutableListOf<LibraryEpisodeEntity>()
        files.forEach { (uri, fileName) ->
            if (uri in knownUris || !planned.add(uri)) return@forEach
            when (val parsed = MediaParser.parse(fileName)) {
                is ParsedMedia.Movie -> movies += LibraryMovieEntity(
                    uri = uri,
                    folderUri = folderUri,
                    fileName = fileName,
                    title = parsed.title.ifBlank { fileName.substringBeforeLast('.') },
                    year = parsed.year,
                    tmdbId = null,
                    posterPath = null,
                    backdropPath = null,
                    overview = null,
                    runtimeMinutes = null,
                    addedAtEpochMillis = now,
                )
                is ParsedMedia.Episode -> {
                    val showName = parsed.showName.ifBlank { fileName.substringBeforeLast('.') }
                    val key = showName.lowercase()
                    if (key !in knownShowKeys && newShowKeys.add(key)) {
                        shows += LibraryShowEntity(
                            key = key,
                            name = showName,
                            tmdbId = null,
                            posterPath = null,
                            backdropPath = null,
                            overview = null,
                            firstAirYear = null,
                        )
                    }
                    episodes += LibraryEpisodeEntity(
                        uri = uri,
                        folderUri = folderUri,
                        showKey = key,
                        fileName = fileName,
                        season = parsed.season,
                        episode = parsed.episode,
                        tmdbId = null,
                        title = null,
                        stillPath = null,
                        runtimeMinutes = null,
                        addedAtEpochMillis = now,
                    )
                }
            }
        }
        return ScanPlan(
            staleMovieUris = if (complete) storedMovieUris.filter { it !in found } else emptyList(),
            staleEpisodeUris = if (complete) storedEpisodeUris.filter { it !in found } else emptyList(),
            newMovies = movies,
            newShows = shows,
            newEpisodes = episodes,
        )
    }

    /**
     * Every video in the linked [folder], after checking the source still
     * answers. The walk starts at the folder itself, not the connector's
     * root: a cloud account's or S3 bucket's root is the whole account, so
     * starting there imported everything the account holds. Google Drive
     * files removed from the library on this device ([hidden], D21) stay out.
     */
    suspend fun enumerate(connector: MediaConnector, folder: LibraryFolderEntity, hidden: Set<String> = emptySet()): Enumeration {
        connector.validate()
        val enumeration = connector.enumerateVideos(folder.treeUri)
        return enumeration.copy(videos = GoogleDriveHiddenItems.visible(enumeration.videos, hidden))
    }

    /**
     * D20 (H.9.4): the Google Drive sources among [folders] from before
     * `drive.file`, which go at launch: Drive folders rather than "Videos
     * you chose", and anything read through an account in
     * [outdatedAccountKeys].
     */
    fun outdatedGoogleDriveSources(folders: List<LibraryFolderEntity>, outdatedAccountKeys: Set<String>): List<LibraryFolderEntity> =
        folders.filter { GoogleDriveMigration.isOutdatedSource(kindOf(it), it.treeUri, it.accountKey, outdatedAccountKeys) }

    /**
     * What a source's `endpoint` should hold (v5), which Play From names a
     * copy by when its URL can't (27.1). For a cloud account, its email or
     * name from this device's vault; for S3, the saved endpoint URL — either
     * [fromStore]. Without one (signed out since, or a login that isn't on
     * this device) the [stored] value stays; a cloud source linked before v5
     * falls back to the account its [displayPath] starts with. Null for every
     * other kind, whose URL names its host.
     */
    fun endpointToRecord(kind: MediaSourceKind?, stored: String?, fromStore: String?, displayPath: String?): String? {
        if (kind == null || !(kind.isCloudAccount || kind == MediaSourceKind.S3)) return null
        val known = fromStore?.trim()?.takeIf { it.isNotEmpty() } ?: stored?.takeIf { it.isNotBlank() }
        return when {
            known != null -> known
            kind.isCloudAccount -> SourceEndpoint.accountFromDisplayPath(kind, displayPath)
            // An S3 path starts with the bucket, which the URL already names.
            else -> null
        }
    }

    /**
     * Whether removing a video from the library lasts, so Remove from
     * Library is offered for it: a file added on its own is never rescanned,
     * and a Google Drive source's scans skip the IDs removed on this device
     * (D21). Any other source's next scan would bring the file straight back.
     */
    fun removalLasts(folderUri: String?): Boolean = folderUri == null || GoogleDriveConnector.isChosenFolder(folderUri)

    /** NT status codes that mean the server refused the login rather than the network failing. */
    private val authenticationStatuses = setOf(
        NtStatus.NT_STATUS_LOGON_FAILURE,
        NtStatus.NT_STATUS_WRONG_PASSWORD,
        NtStatus.NT_STATUS_ACCESS_DENIED,
        NtStatus.NT_STATUS_ACCOUNT_DISABLED,
        NtStatus.NT_STATUS_ACCOUNT_LOCKED_OUT,
        NtStatus.NT_STATUS_ACCOUNT_RESTRICTION,
        NtStatus.NT_STATUS_PASSWORD_EXPIRED,
        NtStatus.NT_STATUS_PASSWORD_MUST_CHANGE,
        NtStatus.NT_STATUS_LOGON_TYPE_NOT_GRANTED,
        NtStatus.NT_STATUS_NO_SUCH_USER,
        NtStatus.NT_STATUS_TRUSTED_DOMAIN_FAILURE,
    )

    /**
     * `needsSignIn` when the server refused the login (jcifs's
     * [SmbAuthException], an authentication NT status, or a connector's
     * refused login or missing account anywhere in the cause chain);
     * `offline` for everything else — unreachable hosts,
     * timeouts, missing shares, an unmounted folder.
     */
    fun classifyFailure(error: Throwable): SourceStatus {
        var cause: Throwable? = error
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is SmbAuthException) return SourceStatus.NEEDS_SIGN_IN
            if (cause is ConnectorException && cause.failure.needsUserAction) return SourceStatus.NEEDS_SIGN_IN
            if (cause is RemoteSourceException && cause.failure == RemoteFailure.SignInRequired) return SourceStatus.NEEDS_SIGN_IN
            if (cause is SmbException && cause.ntStatus in authenticationStatuses) return SourceStatus.NEEDS_SIGN_IN
            cause = cause.cause
        }
        return SourceStatus.OFFLINE
    }
}

/**
 * One scan's changes to the library index ([SourceScanRules.plan]), which
 * [LibraryDao.applyScan] commits together with the source's scan time.
 */
data class ScanPlan(
    val staleMovieUris: List<String>,
    val staleEpisodeUris: List<String>,
    val newMovies: List<LibraryMovieEntity>,
    val newShows: List<LibraryShowEntity>,
    val newEpisodes: List<LibraryEpisodeEntity>,
)
