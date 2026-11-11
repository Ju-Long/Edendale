package com.babasama.edendale.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.android.CopySource
import com.babasama.edendale.android.PlaybackSources
import com.babasama.edendale.android.copySource
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.connectors.ConnectorEntry
import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaConnector
import com.babasama.edendale.connectors.DropboxConnector
import com.babasama.edendale.connectors.GoogleDriveConnector
import com.babasama.edendale.connectors.GoogleDriveHiddenItems
import com.babasama.edendale.connectors.GoogleDriveMigration
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.OneDriveConnector
import com.babasama.edendale.connectors.ProviderHttp
import com.babasama.edendale.connectors.S3Configuration
import com.babasama.edendale.connectors.S3Connector
import com.babasama.edendale.connectors.Sftp
import com.babasama.edendale.connectors.SftpConnector
import com.babasama.edendale.connectors.SourceEndpoint
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.connectors.SshHostKey
import com.babasama.edendale.connectors.VideoFiles
import com.babasama.edendale.connectors.WebDav
import com.babasama.edendale.connectors.WebDavConnector
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.oauth.CloudSignIn
import com.babasama.edendale.remote.OkHttpRemoteHttp
import com.babasama.edendale.remote.ServerLogin
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.babasama.edendale.android.AppStrings
import com.babasama.edendale.android.connectorFailureMessage

/** What the Downloaded tab shows while imports and enrichment run. */
data class LibraryActivity(
    /**
     * The sources being scanned, `treeUri` to display name, in the order
     * their scans began; a source stays here until its results are stored.
     * Rows are marked by `treeUri`, as two sources can share a name (one
     * share through its LAN host and a Tailscale address).
     */
    val scanningSources: Map<String, String> = emptyMap(),
    val isEnriching: Boolean = false,
    val errorMessage: String? = null,
) {
    /** The source the Downloaded tab names while scanning: the latest scan to begin. */
    val scanningFolder: String? get() = scanningSources.values.lastOrNull()
    val isBusy: Boolean get() = scanningSources.isNotEmpty() || isEnriching
}

/**
 * The persisted local library: SAF folders and files survive restarts because
 * import takes persistable URI permissions and the index lives in Room.
 * Classification runs on-device through MediaParser before any
 * network call; TMDB enrichment is best-effort and never blocks import.
 */
class LibraryRepository(
    private val context: Context,
    private val database: EdendaleDatabase,
) {
    private val strings = AppStrings(context)
    private val dao get() = database.libraryDao()
    private val browse = AndroidEdendaleCore.browseRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** TMDB matching: one pass at a time, and a request during a pass gets one more. */
    private val enrichment = CoalescingRunner(scope) { enrichPass() }

    /** Whether this process has resumed the last one's unfinished work. */
    private val resumed = AtomicBoolean(false)
    private val smbCredentialsStore = SmbCredentialsStore(context)
    private val serverLogins = ServerLoginStore(context)
    val sshHostKeys = SshHostKeyStore(context)
    private val http = OkHttpRemoteHttp()
    private val cloud by lazy { (context.applicationContext as EdendaleApplication).cloudAccounts }
    private val tlsPins by lazy { (context.applicationContext as EdendaleApplication).tlsPins }
    private val googleDrive = GoogleDriveStore(context)

    /**
     * Also where a scan claims its source ([claimScan]), so every write is
     * an atomic `update`: a plain read-and-set could drop a concurrent claim.
     */
    private val _activity = MutableStateFlow(LibraryActivity())
    val activity: StateFlow<LibraryActivity> = _activity.asStateFlow()

    /**
     * What the last Choose More Videos did to a Google Drive source, by
     * `treeUri`, shown on its row until the source's next scan (H.9.6).
     * Unlike a scan status, never a problem.
     */
    private val _sourceNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    val sourceNotes: StateFlow<Map<String, String>> = _sourceNotes.asStateFlow()

    /** Whether the one-time "Link Google Drive Again" notice is due (D20). */
    private val _needsGoogleDriveRelinkNotice = MutableStateFlow(false)
    val needsGoogleDriveRelinkNotice: StateFlow<Boolean> = _needsGoogleDriveRelinkNotice.asStateFlow()

    /** The source each show and movie last played from on this device (27.1). */
    val sourceMemory = PlaybackSourceMemory(context, scope)

    /** D20 (H.9.4), once per process before anything scans. */
    private val googleDriveMigration = scope.async { migrateGoogleDriveToFileAccess() }

    init {
        // Once per process, after D20 has removed what it removes: Play From's labels (27.1).
        scope.launch {
            googleDriveMigration.await()
            runCatching { refreshSourceEndpoints() }
        }
    }

    val folders: Flow<List<LibraryFolderEntity>> = dao.observeFolders()
    val movies: Flow<List<LibraryMovieEntity>> = dao.observeMovies()
    val shows: Flow<List<LibraryShowEntity>> = dao.observeShows()
    val episodes: Flow<List<LibraryEpisodeEntity>> = dao.observeEpisodes()

    /** Watch state, from the same database, so the Downloaded screen can combine
     * it with the library flows to surface Continue Watching and watched ticks. */
    val watchProgress: Flow<List<WatchProgress>> =
        database.watchProgressDao().observeAll().map { list -> list.map { it.toDomain() } }

    // MARK: - Import

    fun importFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        scope.launch {
            val document = DocumentFile.fromTreeUri(context, treeUri)
            if (document == null || !document.isDirectory) {
                _activity.update { it.copy(errorMessage = strings.folderNotOpened) }
                return@launch
            }
            val folder = LibraryFolderEntity(
                treeUri = treeUri.toString(),
                displayName = document.name ?: strings.defaultFolderName,
                addedAtEpochMillis = System.currentTimeMillis(),
                kind = MediaSourceKind.LOCAL.raw,
            )
            dao.upsertFolder(folder)
            scan(folder)
        }
    }

    /**
     * [input] is raw text from the add-source dialog, so it is normalised here
     * rather than assumed to be a URL. An address we cannot read is reported
     * instead of silently dropped.
     */
    fun importSmbFolder(input: String, user: String, pass: String) {
        val url = SmbClient.normalizeUrl(input)
        val host = url?.let { Uri.parse(it).host }
        if (url == null || host.isNullOrBlank()) {
            _activity.update { it.copy(errorMessage = strings.notShareAddress(input.trim())) }
            return
        }
        scope.launch {
            smbCredentialsStore.saveCredentials(host, user, pass)
            val share = Uri.parse(url).pathSegments.firstOrNull().orEmpty()
            val folder = LibraryFolderEntity(
                treeUri = url,
                displayName = share.ifBlank { host },
                addedAtEpochMillis = System.currentTimeMillis(),
                kind = MediaSourceKind.SMB.raw,
                displayPath = url,
                accountKey = host,
            )
            dao.upsertFolder(folder)
            scan(folder)
        }
    }

    /**
     * Folders directly under [url], for the add-source browser. Credentials are
     * the ones being typed into that dialog, so they are passed in rather than
     * read from the store — nothing is saved until an import actually happens.
     */
    suspend fun listSmbDirectories(
        url: String,
        user: String,
        pass: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            SmbClient.listDirectories(url, if (user.isBlank()) null else user to pass)
        }
    }

    /**
     * The login already saved for the server [address] names (H.11), so
     * linking another folder there doesn't mean typing it again.
     */
    suspend fun savedLogin(kind: MediaSourceKind, address: String): ServerLogin? = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                MediaSourceKind.SMB -> SmbClient.normalizeUrl(address)?.let { Uri.parse(it).host }
                    ?.let(smbCredentialsStore::getCredentials)
                    ?.let { (user, pass) -> ServerLogin(user, pass) }
                MediaSourceKind.WEBDAV -> WebDav.canonicalRoot(address)?.let { serverLogins.forUrl(kind, it) }
                MediaSourceKind.SFTP -> sftpAddress(address)?.let { (host, port, _) -> serverLogins.get(kind, host, port) }
                else -> null
            }
        }.getOrNull()
    }

    /** The key an SSH server presents, for the viewer to approve before linking (H.4). */
    suspend fun fetchSftpHostKey(host: String, port: Int): Result<SshHostKey> =
        runCatching { withContext(Dispatchers.IO) { Sftp.fetchHostKey(host, port) } }

    /**
     * The folders under [folderUrl] on an SSH server (the login directory when
     * null), for the Link Source browser (H.4): the folder listed and its
     * subfolders. The login is the one being typed, not yet saved.
     */
    suspend fun listSftpFolders(host: String, port: Int, login: ServerLogin, folderUrl: String?): Result<Pair<String, List<ConnectorEntry>>> =
        runCatching {
            val start = SftpConnector(host, port, login, sshHostKeys)
            val folder = folderUrl ?: Sftp.url(host, port, start.homeDirectory(), isDirectory = true)
                ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
            folder to start.list(folder).filter { it.isDirectory }
        }

    /** Links a folder on an SSH server (H.4): saves the login under its host and port, then scans. */
    fun importSftpFolder(folderUrl: String, login: ServerLogin) {
        val host = SourceUrl.credentialHost(folderUrl) ?: return
        val port = SourceUrl.port(folderUrl) ?: Sftp.DEFAULT_PORT
        scope.launch {
            serverLogins.save(MediaSourceKind.SFTP, host, port, login)
            val segments = SourceUrl.pathSegments(folderUrl)
            val address = if (port != Sftp.DEFAULT_PORT) "$host:$port" else host
            val folder = LibraryFolderEntity(
                treeUri = folderUrl,
                displayName = segments.lastOrNull() ?: address,
                addedAtEpochMillis = System.currentTimeMillis(),
                kind = MediaSourceKind.SFTP.raw,
                displayPath = (listOf(address) + segments).joinToString(" › "),
                accountKey = address,
            )
            dao.upsertFolder(folder)
            scan(folder)
        }
    }

    /**
     * The folders under a WebDAV [folderUrl] (`davs://…/`), for the Link
     * Source browser (H.3). The login is the one being typed, not yet saved.
     */
    suspend fun listWebDavFolders(folderUrl: String, user: String, pass: String): Result<List<ConnectorEntry>> =
        runCatching {
            WebDavConnector(folderUrl, ServerLogin(user, pass), http).list(folderUrl).filter { it.isDirectory }
        }

    /** Links a WebDAV folder (H.3): saves the login under its host and port, then scans. */
    fun importWebDavFolder(folderUrl: String, user: String, pass: String) {
        val host = SourceUrl.credentialHost(folderUrl) ?: return
        val port = SourceUrl.port(folderUrl)
        scope.launch {
            serverLogins.save(MediaSourceKind.WEBDAV, host, port, ServerLogin(user.trim(), pass))
            val segments = SourceUrl.pathSegments(folderUrl)
            val address = if (port != null) "$host:$port" else host
            val folder = LibraryFolderEntity(
                treeUri = WebDav.directoryUrl(folderUrl),
                displayName = segments.lastOrNull() ?: address,
                addedAtEpochMillis = System.currentTimeMillis(),
                kind = MediaSourceKind.WEBDAV.raw,
                displayPath = (listOf(address) + segments).joinToString(" › "),
                accountKey = address,
            )
            dao.upsertFolder(folder)
            scan(folder)
        }
    }

    /** The folders under an S3 [prefixUrl] (`s3://<account>/<bucket>/<prefix>/`), for the Link Source browser (H.5). */
    suspend fun listS3Folders(configuration: S3Configuration, login: ServerLogin, prefixUrl: String?): Result<Pair<String, List<ConnectorEntry>>> =
        runCatching {
            val connector = S3Connector(configuration, login, http)
            val folder = prefixUrl ?: connector.root
            folder to connector.list(folder).filter { it.isDirectory }
        }

    /** Links an S3 bucket or prefix (H.5): saves the key pair and location under the account key, then scans. */
    fun importS3Folder(configuration: S3Configuration, login: ServerLogin, folderUrl: String) {
        val item = SourceUrl.parseS3(folderUrl) ?: return
        scope.launch {
            serverLogins.saveS3(item.account, login, configuration)
            val segments = item.key.split('/').filter { it.isNotEmpty() }
            val folder = LibraryFolderEntity(
                treeUri = folderUrl,
                displayName = segments.lastOrNull() ?: configuration.bucket,
                addedAtEpochMillis = System.currentTimeMillis(),
                kind = MediaSourceKind.S3.raw,
                displayPath = (listOf(configuration.bucket) + segments).joinToString(" › "),
                accountKey = item.account,
                // The URL's host is the account key; Play From names the server.
                endpoint = configuration.endpoint,
            )
            dao.upsertFolder(folder)
            scan(folder)
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val files = uris.map { uri ->
                uri.toString() to (displayName(uri) ?: uri.lastPathSegment ?: strings.defaultVideoName)
            }
            try {
                // One plan and one transaction for the whole pick, so a kill
                // can't store half of it: these files are never rescanned.
                database.withTransaction {
                    dao.insertPlanned(
                        SourceScanRules.plan(
                            files = files,
                            complete = false,
                            folderUri = null,
                            storedMovieUris = emptyList(),
                            storedEpisodeUris = emptyList(),
                            knownUris = dao.libraryUris().toHashSet(),
                            knownShowKeys = dao.showKeys().toHashSet(),
                            now = now,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "picked files weren't stored: ${error.javaClass.simpleName}")
                return@launch
            }
            enrich()
        }
    }

    fun rescanFolder(treeUri: String) {
        scope.launch {
            val folder = dao.folders().firstOrNull { it.treeUri == treeUri } ?: return@launch
            scan(folder)
        }
    }

    /**
     * The sweep on each Downloaded visit skips remote sources scanned in the
     * last 15 minutes (D.3), unless their last scan never finished; [force]
     * (a manual Rescan) scans every source. The rule is checked again as
     * each source's turn comes, as the sources before it can take minutes:
     * the launch resume, or a sweep fired again, may have scanned it since.
     */
    fun rescanAll(force: Boolean = false) {
        scope.launch {
            val due = { folder: LibraryFolderEntity ->
                force || SourceScanRules.shouldAutoRescan(folder, System.currentTimeMillis())
            }
            dao.folders().filter(due).forEach { scan(it, due = due) }
        }
    }

    /**
     * Finishes what the last process didn't, once per process: every source
     * whose scan began and never committed (the app was closed or killed
     * mid-scan) is scanned again, whenever it was last scanned, and then
     * titles still waiting for TMDB are matched. Called as the app's screens
     * start rather than with the process, which background sync also starts,
     * and not from the player, so a title opened from the TV's Watch Next row
     * doesn't share the network with a scan. A source the Downloaded sweep
     * finished, or one removed, while earlier ones were scanned is skipped.
     * Not a library edit, so the parental PIN isn't asked (O.5).
     */
    fun resumeInterruptedWork() {
        if (!resumed.compareAndSet(false, true)) return
        scope.launch {
            googleDriveMigration.await()
            dao.interruptedFolders().forEach {
                scan(it, enrichAfterwards = false, due = SourceScanRules::isInterrupted)
            }
            // One pass for these scans' new titles and any a killed pass left unmatched.
            if (dao.pendingEnrichmentCount() > 0) enrich()
        }
    }

    /**
     * Unlinks a source: the folder and everything scanned from it go, and the
     * SAF grant is handed back. A network source's saved login is kept — it
     * lives in Settings → Accounts until removed there (D.4). Titles that last
     * played from it forget it (27.1).
     */
    fun removeFolder(treeUri: String) {
        scope.launch {
            dao.removeFolderTree(treeUri)
            googleDrive.clear(treeUri)
            sourceMemory.forgetSource(treeUri)
            _sourceNotes.update { it - treeUri }
            if (treeUri.startsWith("content://")) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }

    /** A removed Google Drive video stays hidden from later scans on this device (D21). */
    fun removeMovie(uri: String) {
        scope.launch {
            dao.movieByUri(uri)?.folderUri?.let { hideGoogleDriveItems(it, listOf(uri)) }
            dao.deleteMovie(uri)
        }
    }

    fun removeEpisode(uri: String) {
        scope.launch {
            dao.episodeByUri(uri)?.folderUri?.let { hideGoogleDriveItems(it, listOf(uri)) }
            dao.deleteEpisode(uri)
            dao.pruneOrphanShows()
        }
    }

    private fun hideGoogleDriveItems(folderUri: String, uris: List<String>) {
        if (!GoogleDriveConnector.isChosenFolder(folderUri)) return
        googleDrive.updateHidden(folderUri) { GoogleDriveHiddenItems.hiding(it, uris) }
    }

    fun clearError() {
        _activity.update { it.copy(errorMessage = null) }
    }

    /** A library-wide message, such as a failed Choose More Videos (H.9.6). */
    fun showError(message: String) {
        _activity.update { it.copy(errorMessage = message) }
    }

    /**
     * The show drill-in's watch tick. Progress is keyed by TMDB id, so an
     * episode that enrichment has not matched yet has nothing to key on and the
     * call is a no-op (the row hides its tick in that case).
     */
    fun setEpisodeWatched(
        episode: LibraryEpisodeEntity,
        showTmdbId: Int?,
        watched: Boolean,
    ) {
        val tmdbId = episode.tmdbId ?: return
        scope.launch {
            val dao = database.watchProgressDao()
            val record = WatchProgress(
                tmdbId = tmdbId,
                mediaType = WatchMediaType.EPISODE,
                position = 1.0,
                lastWatchedEpochMillis = System.currentTimeMillis(),
                isCompleted = true,
                showTmdbId = showTmdbId,
                seasonNumber = episode.season,
                episodeNumber = episode.episode,
            )
            if (watched) {
                dao.upsert(WatchProgressEntity.fromDomain(record))
                LocalDataStore.syncRecorder?.progressWritten(record)
            } else {
                val previous = dao.get(record.storageKey)?.toDomain()
                dao.delete(record.storageKey)
                LocalDataStore.syncRecorder?.progressCleared(previous ?: record)
            }
        }
    }

    /**
     * Continue Watching's Clear Progress: clears one title's progress. For
     * 27.1 sync the clear is a write (a tombstone), so other devices drop it
     * too. Runs in the repository's scope, because the card that asked can
     * leave the screen as soon as its row is gone, before the tombstone is
     * recorded.
     */
    fun clearWatchProgress(storageKey: String) {
        scope.launch { LocalDataStore(database).deleteWatchProgress(storageKey) }
    }

    /**
     * Every imported copy of the movie or episode with [tmdbId], in Play From
     * order (D.5): local folders first, then by source name — except that
     * copies on the source the movie or the episode's show last played from
     * (27.1) come first and are marked [LocalCopy.lastPlayed]. Empty when the
     * library holds none. A show's own id matches nothing here.
     */
    suspend fun localCopiesFor(tmdbId: Int): List<LocalCopy> {
        val folders = dao.folders().associateBy { it.treeUri }
        val movies = dao.moviesByTmdbId(tmdbId)
        val episodes = if (movies.isEmpty()) dao.episodesByTmdbId(tmdbId) else emptyList()
        val copies = movies.map { LocalCopy(it.uri, it.folderUri, isEpisode = false) }
            .ifEmpty { episodes.map { LocalCopy(it.uri, it.folderUri, isEpisode = true) } }
        val showKeys = episodes.map { it.showKey }.distinct()
        val showTmdbId = showKeys.firstNotNullOfOrNull { dao.showByKey(it)?.tmdbId }
        val sources = withContext(Dispatchers.IO) { sourceMemory.sources() }
        val remembered = if (movies.isNotEmpty()) {
            PlaybackSources.movieMemoryKey(tmdbId)?.let(sources::get)
        } else {
            PlaybackSources.rememberedShowSource(sources, showTmdbId, showKeys)
        }
        val ordered = PlaybackSources.order(null, copies, { folders.copySource(it.folderUri) }, { it.uri })
        return PlaybackSources.rememberedFirst(ordered, { PlaybackSources.sourceKey(it.folderUri) }, remembered)
            .map { copy ->
                copy.copy(
                    source = folders.copySource(copy.folderUri),
                    lastPlayed = remembered != null && PlaybackSources.sourceKey(copy.folderUri) == remembered,
                )
            }
    }

    // MARK: - Scan (classify-before-network: only MediaParser runs here)

    /**
     * The outcome of walking one source.
     *
     * [complete] is the important half: a listing may only delete rows when it
     * actually finished. An unreachable share, rejected credentials, or a
     * revoked folder grant all produce zero files, and treating that as "every
     * file was deleted" is what emptied the library moments after a source was
     * linked.
     */
    private data class Listing(
        val files: List<Pair<String, String>>, // uri to fileName
        val complete: Boolean,
    )

    /**
     * Scans [folder]: lists it, then plans and commits the changes in one
     * transaction (D.3). The source's `scanStartedAt` is set first and
     * cleared only by that commit or by a recorded failure, so a scan the
     * process didn't live to finish is found and done again at the next
     * launch ([resumeInterruptedWork]); its last scan time is written with
     * the results, never before them. Returns false, doing nothing, while
     * another scan of the same source runs. Once claimed, the source's row
     * as it is now must pass [due]: a caller working through sources it read
     * earlier passes the rule that chose them, so one another scan finished
     * in the meantime isn't listed again, and a removed one never is. A
     * stored scan asks for a TMDB pass unless [enrichAfterwards] is false.
     */
    private suspend fun scan(
        folder: LibraryFolderEntity,
        enrichAfterwards: Boolean = true,
        due: (LibraryFolderEntity) -> Boolean = { true },
    ): Boolean {
        googleDriveMigration.await()
        if (!claimScan(folder)) return false
        var stored = false
        try {
            // Read under the claim, so an earlier scan of the source has committed.
            val current = dao.folder(folder.treeUri)
            if (current == null || !due(current)) return true
            // Only a scan that goes ahead clears the last one's notice.
            _activity.update { it.copy(errorMessage = null) }
            _sourceNotes.update { it - folder.treeUri }
            val listing = try {
                dao.markScanStarted(folder.treeUri, System.currentTimeMillis())
                connectorFor(folder)?.let { listConnector(it, folder) } ?: listDocuments(folder)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Recorded on the source's own row (D.3), not as a library-wide
                // error on every visit; the next successful scan clears it.
                recordScanFailure(folder.treeUri, error)
                return true
            }
            stored = try {
                store(folder.treeUri, listing)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // The database refused the results: the source's row says the
                // scan failed, and the app carries on. A crash here would
                // come back at every launch, as the scan is resumed there.
                Log.w(TAG, "a scan's results weren't stored: ${error.javaClass.simpleName}")
                recordScanFailure(folder.treeUri, error)
                return true
            }
            if (stored && !listing.complete) {
                _activity.update { it.copy(errorMessage = strings.sourcePartialError(folder.displayName)) }
            }
        } finally {
            _activity.update { it.copy(scanningSources = it.scanningSources - folder.treeUri) }
        }
        if (stored && enrichAfterwards) enrich()
        return true
    }

    /**
     * Claims [folder] for one scan, or returns false while another scan of
     * it runs: the Downloaded sweep fires again on rotation and on every
     * section change, and the launch resume may still be scanning when it
     * does.
     */
    private fun claimScan(folder: LibraryFolderEntity): Boolean {
        var claimed = false
        _activity.update { current ->
            claimed = folder.treeUri !in current.scanningSources
            if (!claimed) {
                current
            } else {
                current.copy(scanningSources = current.scanningSources + (folder.treeUri to folder.displayName))
            }
        }
        return claimed
    }

    /**
     * Plans [listing] against what the library holds and commits it, in one
     * transaction so nothing changes in between (another source's commit
     * can't prune a show this plan counts on). False when the source was
     * removed while it was being listed.
     */
    private suspend fun store(treeUri: String, listing: Listing): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val plan = SourceScanRules.plan(
                files = listing.files,
                complete = listing.complete,
                folderUri = treeUri,
                storedMovieUris = dao.movieUrisInFolder(treeUri),
                storedEpisodeUris = dao.episodeUrisInFolder(treeUri),
                knownUris = dao.libraryUris().toHashSet(),
                knownShowKeys = dao.showKeys().toHashSet(),
                now = now,
            )
            dao.applyScan(treeUri, plan, now)
        }
    }

    /**
     * Records why a scan failed on the source's row, which also ends the
     * scan. If the database can't take even that, the scan stays marked as
     * started and the next launch tries the source again.
     */
    private suspend fun recordScanFailure(treeUri: String, error: Throwable) {
        try {
            dao.markFolderFailed(treeUri, SourceScanRules.classifyFailure(error).raw)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unrecorded: Exception) {
            Log.w(TAG, "a failed scan wasn't recorded: ${unrecorded.javaClass.simpleName}")
        }
    }

    private fun listDocuments(folder: LibraryFolderEntity): Listing {
        val treeUri = Uri.parse(folder.treeUri)
        val granted = context.contentResolver.persistedUriPermissions
            .any { it.uri == treeUri && it.isReadPermission }
        if (!granted) error(strings.permissionRevoked)
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.isDirectory) error(strings.folderUnopenable)
        val files = mutableListOf<Pair<String, String>>()
        collectVideos(root, files)
        return Listing(files, complete = true)
    }

    /** The connector a remote source scans through (H.1); null for a local folder. */
    private fun connectorFor(folder: LibraryFolderEntity): MediaConnector? =
        when (val kind = MediaSourceKind.fromRaw(folder.kind) ?: MediaSourceKind.forSourceUri(folder.treeUri)) {
            MediaSourceKind.SMB -> SmbConnector(
                root = folder.treeUri,
                credentials = smbCredentialsStore.getCredentials(Uri.parse(folder.treeUri).host.orEmpty()),
                strings = strings,
            )
            MediaSourceKind.S3 -> SourceUrl.credentialHost(folder.treeUri)
                ?.let(serverLogins::getS3)
                ?.let { (login, configuration) -> S3Connector(configuration, login, http) }
                // No key pair: the scan records that the source needs signing in again.
                ?: throw ConnectorException(ConnectorFailure.SignInRequired(MediaSourceKind.S3))
            MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX, MediaSourceKind.GOOGLE_DRIVE ->
                cloudConnector(kind, folder.accountKey ?: SourceUrl.credentialHost(folder.treeUri).orEmpty())
            MediaSourceKind.SFTP -> {
                val host = SourceUrl.credentialHost(folder.treeUri).orEmpty()
                val port = SourceUrl.port(folder.treeUri) ?: Sftp.DEFAULT_PORT
                // SFTP needs its login; without one the source has to sign in again.
                val login = serverLogins.get(kind, host, port) ?: throw ConnectorException(ConnectorFailure.SignInRequired(kind))
                SftpConnector(host, port, login, sshHostKeys, Sftp.path(folder.treeUri))
            }
            MediaSourceKind.WEBDAV -> WebDavConnector(
                root = folder.treeUri,
                login = serverLogins.forUrl(MediaSourceKind.WEBDAV, folder.treeUri),
                http = http,
            )
            else -> null
        }

    /**
     * The connector for a linked cloud account (H.7 on); a missing account
     * means the source has to sign in again.
     */
    private fun cloudConnector(kind: MediaSourceKind, accountKey: String): MediaConnector {
        val account = cloud.vault.account(kind, accountKey) ?: throw ConnectorException(ConnectorFailure.SignInRequired(kind))
        val provider = ProviderHttp(kind, account.key, cloud.tokens, cloud.http)
        val connector = when (kind) {
            MediaSourceKind.ONE_DRIVE -> OneDriveConnector.create(account, provider)
            MediaSourceKind.DROPBOX -> DropboxConnector(account, provider)
            MediaSourceKind.GOOGLE_DRIVE -> GoogleDriveConnector(account, provider)
            else -> null
        }
        return connector ?: throw ConnectorException(ConnectorFailure.SignInRequired(kind))
    }

    /** One step of the Link Source browser through a cloud account (H.11). */
    class AccountFolderListing(
        val folder: String,
        /** The subfolders, every one of them browsable. */
        val entries: List<ConnectorEntry>,
        /** Whether [folder] itself can be linked. */
        val canIndex: Boolean,
    )

    /**
     * The folders under [folderUrl] (the account's root when null) of a linked
     * cloud account, for the Link Source browser (H.11): the folder listed, its
     * subfolders, and whether it can be linked.
     */
    suspend fun listAccountFolders(kind: MediaSourceKind, accountKey: String, folderUrl: String?): Result<AccountFolderListing> =
        runCatching {
            withContext(Dispatchers.IO) {
                val connector = cloudConnector(kind, accountKey)
                val folder = folderUrl ?: connector.root
                AccountFolderListing(folder, connector.list(folder).filter { it.isDirectory }, connector.canIndex(folder))
            }
        }

    /** Links a folder of a linked cloud account (H.11), then scans it. Google Drive links through [addChosenVideos] instead. */
    fun importAccountFolder(kind: MediaSourceKind, accountKey: String, folderUrl: String, trail: List<String>) {
        scope.launch {
            val folder = LibraryFolderEntity(
                treeUri = folderUrl,
                displayName = trail.lastOrNull() ?: SourceUrl.fileName(folderUrl).orEmpty(),
                addedAtEpochMillis = System.currentTimeMillis(),
                kind = kind.raw,
                displayPath = trail.joinToString(" › "),
                accountKey = accountKey,
                // The URL's host is a hashed account key; Play From names the account.
                endpoint = accountIdentity(kind, accountKey),
            )
            dao.upsertFolder(folder)
            scan(folder)
        }
    }

    // MARK: - Google Drive (H.9.4)

    /**
     * Links an account's Google Drive source, "Videos you chose", or
     * rescans it when this device already has it: one source per account
     * (R6). [signIn] carries the files Google's picker just returned (none
     * when an account already linked was chosen, or one a phone handed to
     * a TV); picking a removed video again brings it back. When something
     * was picked, the source's row then says how many of those videos are
     * new to the library.
     */
    fun addChosenVideos(signIn: CloudSignIn) {
        scope.launch {
            googleDriveMigration.await()
            val account = signIn.account
            val treeUri = GoogleDriveConnector.chosenFolderUrl(account.key)
            val picked = signIn.pickedFileIds
            googleDrive.updateHidden(treeUri) { GoogleDriveHiddenItems.unhiding(it, picked) }
            val existing = dao.folders().firstOrNull { it.treeUri == treeUri }
            val before = if (existing == null) emptySet() else googleDriveFileIds(treeUri)
            // Play From names the source by its account (27.1); a new sign-in can bring a new email.
            val identity = SourceEndpoint.accountIdentity(account.email, account.displayName)
            if (existing != null && identity != null && existing.endpoint != identity) dao.setFolderEndpoint(treeUri, identity)
            val folder = existing ?: LibraryFolderEntity(
                treeUri = treeUri,
                displayName = strings.googleDriveChosenFolder,
                addedAtEpochMillis = System.currentTimeMillis(),
                kind = MediaSourceKind.GOOGLE_DRIVE.raw,
                // The account, as other cloud sources' paths start with it.
                displayPath = "${account.label} › ${strings.googleDriveChosenFolder}",
                accountKey = account.key,
                endpoint = identity,
            ).also { dao.upsertFolder(it) }
            // A scan of this source already under way may have listed it
            // before the pick: let it finish, then scan again.
            while (!scan(folder)) _activity.first { treeUri !in it.scanningSources }
            if (picked.isEmpty() || dao.folders().firstOrNull { it.treeUri == treeUri }?.status != null) return@launch
            val added = picked.toSet().intersect(googleDriveFileIds(treeUri) - before).size
            _sourceNotes.update { it + (treeUri to strings.googleDriveAdded(added)) }
        }
    }

    /** The Drive file IDs the library holds from the source at [treeUri]. */
    private suspend fun googleDriveFileIds(treeUri: String): Set<String> =
        (dao.moviesInFolder(treeUri).map { it.uri } + dao.episodesInFolder(treeUri).map { it.uri })
            .mapNotNullTo(HashSet(), GoogleDriveConnector::fileId)

    /**
     * D20, the move to `drive.file`: removes Google accounts from before it
     * (their `drive.readonly` tokens would list all of Drive) and every
     * Google Drive source that isn't "Videos you chose" or that one of them
     * read, without revoking anything. Runs at each launch, so an account a
     * restore brings back goes too; whenever something went, the viewer is
     * told once. Returns whether anything was removed.
     */
    private suspend fun migrateGoogleDriveToFileAccess(): Boolean {
        // An unreadable vault still lets the old sources go.
        val outdated = runCatching {
            GoogleDriveMigration.outdatedAccounts(cloud.vault.accounts(MediaSourceKind.GOOGLE_DRIVE))
        }.getOrDefault(emptyList())
        outdated.forEach { runCatching { cloud.signOut(it, revoke = false) } }
        val keys = outdated.mapTo(HashSet()) { it.key }
        val sources = SourceScanRules.outdatedGoogleDriveSources(dao.folders(), keys)
        sources.forEach {
            dao.removeFolderTree(it.treeUri)
            googleDrive.clear(it.treeUri)
        }
        val removed = outdated.isNotEmpty() || sources.isNotEmpty()
        if (removed) googleDrive.needsRelinkNotice = true
        _needsGoogleDriveRelinkNotice.value = googleDrive.needsRelinkNotice
        return removed
    }

    // MARK: - Where sources are reached from (27.1)

    /** A linked cloud account's email, else its name, from this device's vault. */
    private fun accountIdentity(kind: MediaSourceKind, accountKey: String): String? =
        runCatching { cloud.vault.account(kind, accountKey) }.getOrNull()
            ?.let { SourceEndpoint.accountIdentity(it.email, it.displayName) }

    /**
     * Gives each cloud and S3 source the endpoint Play From names it by, from
     * what this device already holds — the account vault and the saved S3
     * locations — so sources linked before v5 get one too and a signed-out
     * account keeps its last one. Reads nothing from the network; writes only
     * the rows whose value changes.
     */
    private suspend fun refreshSourceEndpoints() {
        dao.folders().forEach { folder ->
            val kind = SourceScanRules.kindOf(folder) ?: return@forEach
            if (!kind.isCloudAccount && kind != MediaSourceKind.S3 && folder.endpoint == null) return@forEach
            val accountKey = folder.accountKey ?: SourceUrl.credentialHost(folder.treeUri)
            val fromStore = accountKey?.let { key ->
                when {
                    kind.isCloudAccount -> accountIdentity(kind, key)
                    kind == MediaSourceKind.S3 -> runCatching { serverLogins.getS3(key) }.getOrNull()?.second?.endpoint
                    else -> null
                }
            }
            val value = SourceScanRules.endpointToRecord(kind, folder.endpoint, fromStore, folder.displayPath)
            if (value != folder.endpoint) dao.setFolderEndpoint(folder.treeUri, value)
        }
    }

    /** The viewer saw the notice. */
    fun dismissGoogleDriveRelinkNotice() {
        _needsGoogleDriveRelinkNotice.value = false
        scope.launch { googleDrive.needsRelinkNotice = false }
    }

    // MARK: - Phone-to-TV handoff (I.2)

    /**
     * The saved logins of [kind] on this device, as a phone hands them to a
     * TV: with the server's pinned SSH host key or certificate so the TV
     * trusts it the same way, an S3 bucket's location, and the sources
     * linked on each so the TV can start browsing from one.
     */
    suspend fun handedOffLogins(kind: MediaSourceKind): List<AccountHandoff.Login> = withContext(Dispatchers.IO) {
        val folders = dao.folders()
        when (kind) {
            MediaSourceKind.SMB -> smbCredentialsStore.savedLogins().mapNotNull { saved ->
                val (user, password) = smbCredentialsStore.getCredentials(saved.host) ?: return@mapNotNull null
                val addresses = folders.filter { SmbClient.hostOf(it.treeUri).equals(saved.host, ignoreCase = true) }.map { it.treeUri }
                AccountHandoff.Login(kind, saved.host, null, user, password, addresses = addresses)
            }
            MediaSourceKind.SFTP, MediaSourceKind.WEBDAV, MediaSourceKind.S3 -> serverLogins.all().filter { it.kind == kind }.mapNotNull { saved ->
                val addresses = folders.filter { folder ->
                    SourceScanRules.kindOf(folder) == kind &&
                        SourceUrl.credentialHost(folder.treeUri) == saved.host.lowercase() &&
                        SourceUrl.port(folder.treeUri) == saved.port
                }.map { it.treeUri }
                when (kind) {
                    MediaSourceKind.S3 -> serverLogins.getS3(saved.host)?.let { (login, configuration) ->
                        val (host, port) = endpointHostAndPort(configuration.endpoint)
                        AccountHandoff.Login(
                            kind, saved.host, null, login.user, login.password,
                            certificateFingerprint = tlsPins.pinned(host, port), s3 = configuration, addresses = addresses,
                        )
                    }
                    MediaSourceKind.SFTP -> serverLogins.get(kind, saved.host, saved.port)?.let { login ->
                        val port = saved.port ?: Sftp.DEFAULT_PORT
                        AccountHandoff.Login(
                            kind, saved.host, port, login.user, login.password,
                            hostKeyFingerprint = sshHostKeys.pinned(saved.host, port), addresses = addresses,
                        )
                    }
                    else -> serverLogins.get(kind, saved.host, saved.port)?.let { login ->
                        AccountHandoff.Login(
                            kind, saved.host, saved.port, login.user, login.password,
                            certificateFingerprint = tlsPins.pinned(saved.host, saved.port ?: 443), addresses = addresses,
                        )
                    }
                }
            }
            else -> emptyList()
        }
    }

    /**
     * The TV side: tests a login a phone handed over where a quick test
     * exists (SMB lists the shares, SFTP its home folder, S3 the bucket,
     * WebDAV the first linked folder), then stores it, pinning the server's
     * host key or certificate first so the test and later connections trust
     * it as the phone did. A failed test stores nothing and says why.
     */
    suspend fun adoptHandedOffLogin(login: AccountHandoff.Login): AccountHandoff.Result = withContext(Dispatchers.IO) {
        runCatching {
            when (login.kind) {
                MediaSourceKind.SMB -> {
                    SmbClient.listDirectories("smb://${login.host}/", login.user.takeIf { it.isNotBlank() }?.let { it to login.password })
                    smbCredentialsStore.saveCredentials(login.host, login.user, login.password)
                }
                MediaSourceKind.SFTP -> {
                    val port = login.port ?: Sftp.DEFAULT_PORT
                    login.hostKeyFingerprint?.let { sshHostKeys.pin(login.host, port, it) }
                    // Without the phone's host key the TV's Link Source shows the key for approval instead.
                    if (login.hostKeyFingerprint != null) SftpConnector(login.host, port, login.serverLogin, sshHostKeys).homeDirectory()
                    serverLogins.save(login.kind, login.host, port, login.serverLogin)
                }
                MediaSourceKind.WEBDAV -> {
                    login.certificateFingerprint?.let { tlsPins.pin(login.host, login.port ?: 443, it) }
                    login.addresses.firstOrNull()?.let { WebDavConnector(it, login.serverLogin, http).list(it) }
                    serverLogins.save(login.kind, login.host, login.port, login.serverLogin)
                }
                MediaSourceKind.S3 -> {
                    val configuration = login.s3 ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
                    login.certificateFingerprint?.let { fingerprint ->
                        val (host, port) = endpointHostAndPort(configuration.endpoint)
                        tlsPins.pin(host, port, fingerprint)
                    }
                    val connector = S3Connector(configuration, login.serverLogin, http)
                    connector.list(connector.root)
                    serverLogins.saveS3(login.host, login.serverLogin, configuration)
                }
                else -> throw ConnectorException(ConnectorFailure.InvalidAddress)
            }
        }.fold(
            { AccountHandoff.Result(stored = true) },
            { error -> AccountHandoff.Result(stored = false, message = connectorFailureMessage(context, error) ?: error.message) },
        )
    }

    /** The host and port an endpoint URL connects to, with the scheme's default port. */
    private fun endpointHostAndPort(endpoint: String): Pair<String, Int> {
        val scheme = endpoint.substringBefore("://", "https").lowercase()
        val authority = endpoint.substringAfter("://").substringBefore('/').substringAfterLast('@')
        val port = authority.substringAfterLast(':', "").toIntOrNull()
        val host = if (port != null) authority.substringBeforeLast(':') else authority
        return host.lowercase() to (port ?: if (scheme == "http") 80 else 443)
    }

    /** Validates the source, then walks it; a partial walk deletes nothing. Hidden Drive videos stay out (D21). */
    private suspend fun listConnector(connector: MediaConnector, folder: LibraryFolderEntity): Listing {
        val enumeration = SourceScanRules.enumerate(connector, folder, googleDrive.hidden(folder.treeUri))
        return Listing(enumeration.videos.map { it.url to it.name }, enumeration.complete)
    }

    private fun collectVideos(directory: DocumentFile, into: MutableList<Pair<String, String>>) {
        directory.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectVideos(file, into)
                file.isFile -> {
                    val name = file.name ?: return@forEach
                    val looksLikeVideo = file.type?.startsWith("video/") == true || VideoFiles.isVideoName(name)
                    if (looksLikeVideo) into += file.uri.toString() to name
                }
            }
        }
    }

    // MARK: - Enrichment (best-effort, off the import fast path)

    /**
     * Asks for a TMDB pass over every unmatched title. One already running
     * is followed by exactly one more, so titles stored during it don't wait
     * for the next scan.
     */
    fun enrich() {
        if (!AndroidEdendaleCore.hasTmdbCredentials()) return
        enrichment.request()
    }

    /** One pass, library-wide: movies, shows, and the episodes of matched shows. */
    private suspend fun enrichPass() {
        _activity.update { it.copy(isEnriching = true) }
        try {
            dao.movies().filter { it.tmdbId == null }.forEach { enrichMovie(it) }
            dao.shows().forEach { show ->
                val enriched = if (show.tmdbId == null) enrichShow(show) else show
                val showTmdbId = enriched?.tmdbId ?: return@forEach
                dao.episodesForShow(show.key)
                    .filter { it.tmdbId == null }
                    .forEach { enrichEpisode(showTmdbId, it) }
            }
        } finally {
            _activity.update { it.copy(isEnriching = false) }
        }
    }

    private suspend fun enrichMovie(movie: LibraryMovieEntity) {
        runCatching {
            val match = browse.search(movie.title)
                .filter { it.mediaType == MediaType.MOVIE }
                .sortedByDescending { movie.year != null && it.year == movie.year }
                .firstOrNull() ?: return
            val detail = runCatching { browse.detail(match.ref) }.getOrNull()
            dao.updateMovieMatch(
                uri = movie.uri,
                tmdbId = match.id,
                posterPath = match.posterPath,
                backdropPath = match.backdropPath,
                overview = detail?.overview ?: match.overview,
                runtimeMinutes = detail?.runtimeMinutes,
                year = movie.year ?: match.year,
            )
        }
    }

    private suspend fun enrichShow(show: LibraryShowEntity): LibraryShowEntity? =
        runCatching {
            val match = browse.search(show.name)
                .firstOrNull { it.mediaType == MediaType.TV } ?: return null
            val enriched = show.copy(
                tmdbId = match.id,
                posterPath = match.posterPath,
                backdropPath = match.backdropPath,
                overview = match.overview,
                firstAirYear = match.year,
            )
            dao.updateShowMatch(
                key = show.key,
                tmdbId = match.id,
                posterPath = match.posterPath,
                backdropPath = match.backdropPath,
                overview = match.overview,
                firstAirYear = match.year,
            )
            enriched
        }.getOrNull()

    private suspend fun enrichEpisode(showTmdbId: Int, episode: LibraryEpisodeEntity) {
        runCatching {
            val detail = browse.episodeDetail(showTmdbId, episode.season, episode.episode)
            if (detail.id == 0) return
            dao.updateEpisodeMatch(
                uri = episode.uri,
                tmdbId = detail.id,
                title = detail.name,
                stillPath = detail.stillPath,
                runtimeMinutes = detail.runtimeMinutes,
            )
        }
    }

    // MARK: - Helpers

    private fun displayName(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private companion object {
        const val TAG = "LibraryRepository"
    }
}

/** One imported copy of a title and the source it came from (D.5). */
data class LocalCopy(
    val uri: String,
    val folderUri: String?,
    val isEpisode: Boolean,
    val source: CopySource = CopySource("", null, isUnavailable = false),
    /** On the source the title last played from on this device (27.1). */
    val lastPlayed: Boolean = false,
)

/**
 * What a viewer types for an SSH server — `nas.local`, `nas.local:2222`,
 * `sftp://nas.local/home/me` — as its host, port, and starting path.
 */
internal fun sftpAddress(input: String): Triple<String, Int, String?>? {
    val text = input.trim().removePrefix("sftp://").removePrefix("ssh://").trimEnd('/')
    if (text.isEmpty()) return null
    val authority = text.substringBefore('/').substringAfterLast('@')
    val path = text.substringAfter('/', "").takeIf { it.isNotEmpty() }?.let { "/$it" }
    val port = authority.substringAfterLast(':', "").toIntOrNull()
    val host = (if (port != null) authority.substringBeforeLast(':') else authority).ifEmpty { return null }
    return Triple(host, port ?: Sftp.DEFAULT_PORT, path)
}
