package com.babasama.edendale.android.player

import android.net.Uri
import android.provider.DocumentsContract
import com.babasama.edendale.android.data.LibraryDao
import com.babasama.edendale.android.data.LibraryRepository

/**
 * One row of the player's playlist side panel: the show's episodes when the
 * playing item is a known episode, otherwise the other videos in the same
 * imported folder. Everything comes from the library database — the scan
 * already recorded every playable file, so no SAF or SMB re-walk is needed
 * (and none is possible for "Open with" files, which simply get no list).
 */
internal data class PlaylistEntry(
    val uri: String,
    val title: String,
    val detail: String?,
    val tmdbId: Int?,
    val isEpisode: Boolean,
    val showTmdbId: Int?,
    val season: Int?,
    val episode: Int?,
)

internal data class PlayerPlaylist(
    val isEpisodeList: Boolean,
    val entries: List<PlaylistEntry>,
)

/**
 * The directory component of a stored library URI, used to narrow folder
 * siblings to the playing file's actual directory: `folderUri` on library
 * rows is the imported source's *root*, and scans recurse, so the root alone
 * would list every video in the source. Exact for smb:// URLs and for
 * path-shaped SAF document ids (`primary:Movies/file.mkv`); null when the id
 * is opaque, in which case callers fall back to grouping by source root.
 */
internal fun playlistParentKey(uri: String): String? = when {
    uri.startsWith("smb://") ->
        uri.trimEnd('/').substringBeforeLast('/', "").ifBlank { null }
    uri.startsWith("content://") -> runCatching {
        DocumentsContract.getDocumentId(Uri.parse(uri))
    }.getOrNull()?.takeIf { it.contains('/') }?.substringBeforeLast('/')
    else -> null
}

/**
 * Loads the panel's contents for the playing [uriString]. Suspends on Room's
 * own executor; safe to call from any dispatcher.
 */
internal suspend fun loadPlayerPlaylist(
    dao: LibraryDao,
    repository: LibraryRepository,
    uriString: String,
    showTmdbIdExtra: Int?,
): PlayerPlaylist? {
    // Episode branch: resolve by URI first — showKey is assigned by the local
    // filename parse, so this works even before TMDB enrichment names the
    // show. The intent's showTmdbId covers files launched by TMDB id whose
    // URI never entered the library (for instance a re-imported path).
    val playingEpisode = dao.episodeByUri(uriString)
    val episodes = when {
        playingEpisode != null -> dao.episodesForShow(playingEpisode.showKey)
        showTmdbIdExtra != null -> repository.episodesForShowTmdbId(showTmdbIdExtra)
        else -> emptyList()
    }
    if (episodes.isNotEmpty()) {
        val showTmdbId = showTmdbIdExtra
            ?: playingEpisode?.let { dao.showByKey(it.showKey)?.tmdbId }
        return PlayerPlaylist(
            isEpisodeList = true,
            entries = episodes.map { episode ->
                PlaylistEntry(
                    uri = episode.uri,
                    title = episode.title ?: episode.fileName,
                    detail = "S%02dE%02d".format(episode.season, episode.episode),
                    tmdbId = episode.tmdbId,
                    isEpisode = true,
                    showTmdbId = showTmdbId,
                    season = episode.season,
                    episode = episode.episode,
                )
            },
        )
    }

    // Folder branch: siblings of a known movie, narrowed to its directory.
    val playingMovie = dao.movieByUri(uriString) ?: return null
    val root = playingMovie.folderUri ?: return null
    val parent = playlistParentKey(uriString)
    val siblings = (
        dao.moviesInFolder(root).map { movie ->
            PlaylistEntry(
                uri = movie.uri,
                title = movie.title,
                detail = movie.year?.toString(),
                tmdbId = movie.tmdbId,
                isEpisode = false,
                showTmdbId = null,
                season = null,
                episode = null,
            )
        } + dao.episodesInFolder(root).map { episode ->
            PlaylistEntry(
                uri = episode.uri,
                title = episode.title ?: episode.fileName,
                detail = "S%02dE%02d".format(episode.season, episode.episode),
                tmdbId = episode.tmdbId,
                isEpisode = true,
                showTmdbId = null,
                season = episode.season,
                episode = episode.episode,
            )
        }
        )
        .filter { parent == null || playlistParentKey(it.uri) == parent }
        .sortedWith { a, b -> PlayerLogic.naturalCompare(a.title, b.title) }
    if (siblings.size < 2) return null
    return PlayerPlaylist(isEpisodeList = false, entries = siblings)
}
