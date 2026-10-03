package com.babasama.edendale.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import jcifs.smb.SmbFile
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.android.CopySource
import com.babasama.edendale.android.PlaybackSources
import com.babasama.edendale.android.copySource
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.domain.MediaParser
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.ParsedMedia
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.babasama.edendale.android.AppStrings

/** What the Downloaded tab shows while imports and enrichment run. */
data class LibraryActivity(
    val scanningFolder: String? = null,
    val isEnriching: Boolean = false,
    val errorMessage: String? = null,
) {
    val isBusy: Boolean get() = scanningFolder != null || isEnriching
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
    private val enrichmentLock = Mutex()
    private val smbCredentialsStore = SmbCredentialsStore(context)

    private val _activity = MutableStateFlow(LibraryActivity())
    val activity: StateFlow<LibraryActivity> = _activity.asStateFlow()

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
                _activity.value = _activity.value.copy(
                    errorMessage = strings.folderNotOpened,
                )
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
            _activity.value = _activity.value.copy(
                errorMessage = strings.notShareAddress(input.trim()),
            )
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
            uris.forEach { uri ->
                val name = displayName(uri) ?: uri.lastPathSegment ?: strings.defaultVideoName
                classifyAndStore(uri.toString(), folderUri = null, fileName = name, now = now)
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
     * last 15 minutes (D.3); [force] (a manual Rescan) scans every source.
     */
    fun rescanAll(force: Boolean = false) {
        scope.launch {
            val now = System.currentTimeMillis()
            dao.folders()
                .filter { force || SourceScanRules.shouldAutoRescan(it, now) }
                .forEach { scan(it) }
        }
    }

    /**
     * Unlinks a source: the folder and everything scanned from it go, and the
     * SAF grant is handed back. A network source's saved login is kept — it
     * lives in Settings → Accounts until removed there (D.4).
     */
    fun removeFolder(treeUri: String) {
        scope.launch {
            dao.removeFolderTree(treeUri)
            if (SmbClient.hostOf(treeUri) == null) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }

    fun removeMovie(uri: String) {
        scope.launch { dao.deleteMovie(uri) }
    }

    fun removeEpisode(uri: String) {
        scope.launch {
            dao.deleteEpisode(uri)
            dao.pruneOrphanShows()
        }
    }

    fun clearError() {
        _activity.value = _activity.value.copy(errorMessage = null)
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
            } else {
                dao.delete(record.storageKey)
            }
        }
    }

    /** Imported episodes belonging to an enriched show, ordered by season/episode. */
    suspend fun episodesForShowTmdbId(tmdbId: Int): List<LibraryEpisodeEntity> =
        dao.showByTmdbId(tmdbId)?.key?.let { dao.episodesForShow(it) }.orEmpty()

    /**
     * Every imported copy of the movie or episode with [tmdbId], in Play From
     * order (D.5): local folders first, then by source name. Empty when the
     * library holds none. A show's own id matches nothing here.
     */
    suspend fun localCopiesFor(tmdbId: Int): List<LocalCopy> {
        val folders = dao.folders().associateBy { it.treeUri }
        val movies = dao.moviesByTmdbId(tmdbId)
        val copies = movies.map { LocalCopy(it.uri, it.folderUri, isEpisode = false) }
            .ifEmpty {
                dao.episodesByTmdbId(tmdbId).map { LocalCopy(it.uri, it.folderUri, isEpisode = true) }
            }
        return PlaybackSources.order(null, copies, { folders.copySource(it.folderUri) }, { it.uri })
            .map { it.copy(source = folders.copySource(it.folderUri)) }
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

    private suspend fun scan(folder: LibraryFolderEntity) {
        _activity.value = _activity.value.copy(
            scanningFolder = folder.displayName,
            errorMessage = null,
        )
        val listing = try {
            if (folder.treeUri.startsWith("smb://")) listSmb(folder) else listDocuments(folder)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Recorded on the source's own row (D.3), not as a library-wide
            // error on every visit; the next successful scan clears it.
            dao.markFolderFailed(folder.treeUri, SourceScanRules.classifyFailure(error).raw)
            null
        } finally {
            _activity.value = _activity.value.copy(scanningFolder = null)
        }
        if (listing == null) return
        dao.markFolderScanned(folder.treeUri, System.currentTimeMillis())

        if (listing.complete) {
            // Drop records whose file disappeared since the last scan.
            val foundUris = listing.files.mapTo(HashSet()) { it.first }
            val staleMovies = dao.movies()
                .filter { it.folderUri == folder.treeUri && it.uri !in foundUris }
                .map { it.uri }
            val staleEpisodes = dao.episodes()
                .filter { it.folderUri == folder.treeUri && it.uri !in foundUris }
                .map { it.uri }
            if (staleMovies.isNotEmpty()) dao.deleteMovies(staleMovies)
            if (staleEpisodes.isNotEmpty()) dao.deleteEpisodes(staleEpisodes)
            dao.pruneOrphanShows()
        } else {
            _activity.value = _activity.value.copy(
                errorMessage = strings.sourcePartialError(folder.displayName),
            )
        }

        val known = (dao.movies().map { it.uri } + dao.episodes().map { it.uri }).toHashSet()
        val now = System.currentTimeMillis()
        listing.files.forEach { (uri, fileName) ->
            if (uri !in known) classifyAndStore(uri, folder.treeUri, fileName, now)
        }
        enrich()
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

    private fun listSmb(folder: LibraryFolderEntity): Listing {
        val host = Uri.parse(folder.treeUri).host.orEmpty()
        val cifsContext = SmbClient.context(smbCredentialsStore.getCredentials(host))
        val root = SmbFile(folder.treeUri, cifsContext)
        if (!root.exists()) error(strings.shareUnreachable)
        if (!root.isDirectory) error(strings.addressIsFile)
        val files = mutableListOf<Pair<String, String>>()
        val complete = collectVideosSmb(root, files)
        return Listing(files, complete)
    }

    private fun collectVideos(directory: DocumentFile, into: MutableList<Pair<String, String>>) {
        directory.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectVideos(file, into)
                file.isFile -> {
                    val name = file.name ?: return@forEach
                    val looksLikeVideo = file.type?.startsWith("video/") == true ||
                        name.substringAfterLast('.', "").lowercase() in videoExtensions
                    if (looksLikeVideo) into += file.uri.toString() to name
                }
            }
        }
    }

    /**
     * Returns false when any part of the subtree could not be read. One
     * unreadable directory should not condemn the whole source, but it does
     * make the listing untrustworthy for deletions.
     */
    private fun collectVideosSmb(
        directory: SmbFile,
        into: MutableList<Pair<String, String>>,
    ): Boolean {
        val children = try {
            directory.listFiles()
        } catch (error: Exception) {
            return false
        } ?: return false

        var complete = true
        children.forEach { file ->
            try {
                when {
                    file.isDirectory -> if (!collectVideosSmb(file, into)) complete = false
                    file.isFile -> {
                        val name = file.name ?: return@forEach
                        val extension = name.substringAfterLast('.', "").lowercase()
                        if (extension in videoExtensions) into += file.url.toString() to name
                    }
                }
            } catch (error: Exception) {
                complete = false
            }
        }
        return complete
    }

    private suspend fun classifyAndStore(
        uri: String,
        folderUri: String?,
        fileName: String,
        now: Long,
    ) {
        when (val parsed = MediaParser.parse(fileName)) {
            is ParsedMedia.Movie -> dao.upsertMovie(
                LibraryMovieEntity(
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
                ),
            )
            is ParsedMedia.Episode -> {
                val showName = parsed.showName.ifBlank { fileName.substringBeforeLast('.') }
                val key = showName.lowercase()
                if (dao.shows().none { it.key == key }) {
                    dao.upsertShow(
                        LibraryShowEntity(
                            key = key,
                            name = showName,
                            tmdbId = null,
                            posterPath = null,
                            backdropPath = null,
                            overview = null,
                            firstAirYear = null,
                        ),
                    )
                }
                dao.upsertEpisode(
                    LibraryEpisodeEntity(
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
                    ),
                )
            }
        }
    }

    // MARK: - Enrichment (best-effort, off the import fast path)

    fun enrich() {
        if (!AndroidEdendaleCore.hasTmdbCredentials()) return
        scope.launch {
            if (!enrichmentLock.tryLock()) return@launch
            _activity.value = _activity.value.copy(isEnriching = true)
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
                _activity.value = _activity.value.copy(isEnriching = false)
                enrichmentLock.unlock()
            }
        }
    }

    private suspend fun enrichMovie(movie: LibraryMovieEntity) {
        runCatching {
            val match = browse.search(movie.title)
                .filter { it.mediaType == MediaType.MOVIE }
                .sortedByDescending { movie.year != null && it.year == movie.year }
                .firstOrNull() ?: return
            val detail = runCatching { browse.detail(match.ref) }.getOrNull()
            dao.upsertMovie(
                movie.copy(
                    tmdbId = match.id,
                    posterPath = match.posterPath,
                    backdropPath = match.backdropPath,
                    overview = detail?.overview ?: match.overview,
                    runtimeMinutes = detail?.runtimeMinutes,
                    year = movie.year ?: match.year,
                ),
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
            dao.upsertShow(enriched)
            enriched
        }.getOrNull()

    private suspend fun enrichEpisode(showTmdbId: Int, episode: LibraryEpisodeEntity) {
        runCatching {
            val detail = browse.episodeDetail(showTmdbId, episode.season, episode.episode)
            if (detail.id == 0) return
            dao.upsertEpisode(
                episode.copy(
                    tmdbId = detail.id,
                    title = detail.name,
                    stillPath = detail.stillPath,
                    runtimeMinutes = detail.runtimeMinutes,
                ),
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
        val videoExtensions = setOf(
            "mkv", "mp4", "m4v", "mov", "avi", "wmv", "flv", "webm",
            "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "ogv", "vob",
        )
    }
}

/** One imported copy of a title and the source it came from (D.5). */
data class LocalCopy(
    val uri: String,
    val folderUri: String?,
    val isEpisode: Boolean,
    val source: CopySource = CopySource("", null, isUnavailable = false),
)
