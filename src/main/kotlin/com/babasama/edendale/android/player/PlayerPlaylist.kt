package com.babasama.edendale.android.player

import android.net.Uri
import android.provider.DocumentsContract
import com.babasama.edendale.android.data.LibraryDao
import com.babasama.edendale.android.data.LibraryRepository
import com.babasama.edendale.connectors.MediaSourceKind

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
    val stillPath: String? = null,
    /** The show's backdrop for an episode, the movie's own for a movie. */
    val backdropPath: String? = null,
    val runtimeMinutes: Int? = null,
) {
    /** The 16:9 artwork the playlist row shows: the episode still, else the backdrop. */
    val artworkPath: String? get() = stillPath ?: backdropPath
}

internal data class PlayerPlaylist(
    val isEpisodeList: Boolean,
    val entries: List<PlaylistEntry>,
    /** The show's name for an episode list, when the library has the show. */
    val showName: String? = null,
)

/**
 * Whether a playlist row shows 16:9 artwork with its title and play time (B.5):
 * identified episodes in a show's list, and the playing file when TMDB knows
 * it. Unknown sibling files keep the plain file-name row.
 */
internal fun playlistShowsArtwork(entry: PlaylistEntry, isEpisodeList: Boolean, isCurrent: Boolean): Boolean =
    entry.tmdbId != null && (isEpisodeList || isCurrent)

/** One line of the playlist panel: a season heading or an entry. */
internal sealed interface PlaylistItem {
    data class Season(val number: Int) : PlaylistItem
    data class Entry(val entry: PlaylistEntry) : PlaylistItem
}

/** The panel's lines in order: an episode list is grouped under season headings. */
internal fun playlistItems(playlist: PlayerPlaylist?): List<PlaylistItem> {
    val entries = playlist?.entries.orEmpty()
    if (playlist?.isEpisodeList != true) return entries.map { PlaylistItem.Entry(it) }
    return entries.groupBy { it.season ?: 0 }.flatMap { (season, seasonEntries) ->
        listOf(PlaylistItem.Season(season)) + seasonEntries.map { PlaylistItem.Entry(it) }
    }
}

/** The line the panel scrolls to when it opens, or -1. */
internal fun List<PlaylistItem>.indexOfEntry(uri: String): Int =
    indexOfFirst { it is PlaylistItem.Entry && it.entry.uri == uri }

/**
 * The directory component of a stored library URI, used to narrow folder
 * siblings to the playing file's actual directory: `folderUri` on library
 * rows is the imported source's *root*, and scans recurse, so the root alone
 * would list every video in the source. Exact for smb:// URLs and for
 * path-shaped SAF document ids (`primary:Movies/file.mkv`); null when the id
 * is opaque, in which case callers fall back to grouping by source root.
 */
internal fun playlistParentKey(uri: String): String? = when {
    // Server URLs are path-shaped (smb, nfs, sftp, WebDAV).
    MediaSourceKind.forSourceUri(uri)?.let { it.isRemote && !it.isCloudAccount && it != MediaSourceKind.S3 } == true ->
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
        val show = playingEpisode?.let { dao.showByKey(it.showKey) }
            ?: showTmdbIdExtra?.let { dao.showByTmdbId(it) }
        val backdropPath = show?.backdropPath
        val showTmdbId = showTmdbIdExtra ?: show?.tmdbId
        return PlayerPlaylist(
            isEpisodeList = true,
            showName = show?.name,
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
                    stillPath = episode.stillPath,
                    backdropPath = backdropPath,
                    runtimeMinutes = episode.runtimeMinutes,
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
                backdropPath = movie.backdropPath,
                runtimeMinutes = movie.runtimeMinutes,
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
                stillPath = episode.stillPath,
                runtimeMinutes = episode.runtimeMinutes,
            )
        }
        )
        .filter { parent == null || playlistParentKey(it.uri) == parent }
        .sortedWith { a, b -> PlayerLogic.naturalCompare(a.title, b.title) }
    if (siblings.size < 2) return null
    return PlayerPlaylist(isEpisodeList = false, entries = siblings)
}
