package com.babasama.edendale.android.player

import android.net.Uri
import android.provider.DocumentsContract
import com.babasama.edendale.android.CopySource
import com.babasama.edendale.android.PlaybackSources
import com.babasama.edendale.android.copySource
import com.babasama.edendale.android.data.LibraryDao
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.parental.ParentalGateRules

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
    /** The source this copy plays from, for an episode row's Play From menu (27.1). */
    val source: CopySource? = null,
    /** `PlaybackSources.sourceKey` of that source. */
    val sourceKey: String? = null,
    /**
     * Every copy of this episode, this one included, in Play From order, when
     * the library holds more than one; each is a whole entry of its own (its
     * own file, title, and source). Empty otherwise.
     */
    val copies: List<PlaylistEntry> = emptyList(),
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

/** The title the parental policy judges a row by (O.5): an episode by its show, a movie by itself, an id-less file by nothing. */
internal fun PlaylistEntry.audienceRef(): MediaRef? = ParentalGateRules.playbackRef(tmdbId, isEpisode, showTmdbId)

/** Every title a playlist's rows are judged by, for one verification before the rows are filtered. */
internal fun playlistAudienceRefs(playlist: PlayerPlaylist?): List<MediaRef> =
    playlist?.entries.orEmpty().mapNotNull { it.audienceRef() }.distinct()

/**
 * The rows a viewer may reach while a title rule is active (O.5), filtered
 * the way the library's `visibleLibrary` filters: episodes follow their show,
 * and a file without a TMDB id is restricted (O-D8). The panel, Up Next,
 * auto-advance, and the media session's next and previous all read this
 * list, so none of them can land on a restricted title. A folder list that
 * shrinks below two siblings is no list at all, as the loader decides; an
 * episode list stands with one.
 */
internal fun permittedPlaylist(
    playlist: PlayerPlaylist?,
    policyActive: Boolean,
    allows: (MediaRef) -> Boolean,
): PlayerPlaylist? {
    if (playlist == null || !policyActive) return playlist
    val entries = playlist.entries.filter { ParentalGateRules.permitsPlayback(true, it.audienceRef(), allows) }
    if (entries.isEmpty() || (!playlist.isEpisodeList && entries.size < 2)) return null
    return playlist.copy(entries = entries)
}

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
 * The episode list's rows (C.2 with D.5): each episode's chosen copy, which Up
 * Next and auto-advance play, carrying every copy of the episode as an entry
 * of its own when there's more than one, for the row's Play From menu (27.1).
 * A copy TMDB hasn't matched yet — a link added after the others — plays
 * under a matched copy's id and name, so its progress and resume are the
 * episode's; an episode no copy of which is matched keeps its file name.
 */
internal fun episodeEntries(
    rows: List<PlaybackSources.PlayerEpisode<LibraryEpisodeEntity>>,
    showTmdbId: Int?,
    backdropPath: String?,
    sourceOf: (LibraryEpisodeEntity) -> CopySource,
): List<PlaylistEntry> {
    fun entry(episode: LibraryEpisodeEntity, named: LibraryEpisodeEntity?) = PlaylistEntry(
        uri = episode.uri,
        title = episode.title ?: named?.title ?: episode.fileName,
        detail = "S%02dE%02d".format(episode.season, episode.episode),
        tmdbId = episode.tmdbId ?: named?.tmdbId,
        isEpisode = true,
        showTmdbId = showTmdbId,
        season = episode.season,
        episode = episode.episode,
        stillPath = episode.stillPath ?: named?.stillPath,
        backdropPath = backdropPath,
        runtimeMinutes = episode.runtimeMinutes ?: named?.runtimeMinutes,
        source = sourceOf(episode),
        sourceKey = PlaybackSources.sourceKey(episode.folderUri),
    )
    return rows.map { row ->
        val named = row.copies.firstOrNull { it.tmdbId != null }
        val chosen = entry(row.chosen, named)
        if (row.copies.size < 2) chosen else chosen.copy(copies = row.copies.map { if (it == row.chosen) chosen else entry(it, named) })
    }
}

/**
 * The episode list for [episodes], every copy of the show, while [playing]
 * plays ([PlaybackSources.playerEpisodeRows], then [episodeEntries]). The
 * playing copy's source answers whatever its last scan said, for the copy
 * each row plays and for each row's Play From alike
 * ([PlaybackSources.whilePlaying]): a stale flag mustn't mark the copies of
 * the source playing now "Unavailable". [remembered], the source the show
 * last played from, anchors the list only when [playing] isn't a library row.
 */
internal fun playerEpisodeEntries(
    episodes: List<LibraryEpisodeEntity>,
    playing: LibraryEpisodeEntity?,
    showTmdbId: Int?,
    backdropPath: String?,
    remembered: String? = null,
    sourceOf: (LibraryEpisodeEntity) -> CopySource,
): List<PlaylistEntry> {
    val sourceKey: (LibraryEpisodeEntity) -> String = { PlaybackSources.sourceKey(it.folderUri) }
    val source = PlaybackSources.whilePlaying(playing, sourceKey, sourceOf)
    val rows = PlaybackSources.playerEpisodeRows(
        episodes = episodes,
        playing = playing,
        season = { it.season },
        number = { it.episode },
        sourceKey = sourceKey,
        source = source,
        path = { it.uri },
        remembered = remembered.takeIf { playing == null },
    )
    return episodeEntries(rows, showTmdbId, backdropPath, source)
}

/**
 * The list with [pick], one of an episode's copies, in place of that
 * episode's row, until a reload settles the rows after it: the panel shows
 * the pick as playing straight away.
 */
internal fun PlayerPlaylist.choosing(pick: PlaylistEntry): PlayerPlaylist = this.copy(
    entries = entries.map { entry ->
        if (entry.uri != pick.uri && entry.copies.any { it.uri == pick.uri }) pick.copy(copies = entry.copies) else entry
    },
)

/**
 * Loads the panel's contents for the playing [uriString]. Suspends on Room's
 * own executor; safe to call from any dispatcher. [remembered], the source the
 * show last played from (27.1), anchors the episode list only when the
 * playing URI isn't a library row; otherwise the playing copy's source does.
 */
internal suspend fun loadPlayerPlaylist(
    dao: LibraryDao,
    uriString: String,
    showTmdbIdExtra: Int?,
    remembered: String? = null,
): PlayerPlaylist? {
    // Episode branch: resolve by URI first — showKey is assigned by the local
    // filename parse, so this works even before TMDB enrichment names the
    // show. The intent's showTmdbId covers files launched by TMDB id whose
    // URI never entered the library (for instance a re-imported path).
    val playingEpisode = dao.episodeByUri(uriString)
    val show = playingEpisode?.let { dao.showByKey(it.showKey) }
        ?: showTmdbIdExtra?.let { dao.showByTmdbId(it) }
    val showTmdbId = showTmdbIdExtra ?: show?.tmdbId
    // Every copy of the show, merged the way its page merges them (D.5).
    val showKeys = buildSet {
        playingEpisode?.let { add(it.showKey) }
        show?.let { add(it.key) }
        if (showTmdbId != null) dao.shows().filter { it.tmdbId == showTmdbId }.forEach { add(it.key) }
    }
    val folders = dao.folders().associateBy { it.treeUri }
    val entries = playerEpisodeEntries(
        episodes = showKeys.flatMap { dao.episodesForShow(it) },
        playing = playingEpisode,
        showTmdbId = showTmdbId,
        backdropPath = show?.backdropPath,
        remembered = remembered,
    ) { folders.copySource(it.folderUri) }
    if (entries.isNotEmpty()) {
        return PlayerPlaylist(isEpisodeList = true, showName = show?.name, entries = entries)
    }

    // Folder branch: siblings of a known movie, narrowed to its directory.
    val playingMovie = dao.movieByUri(uriString) ?: return null
    val root = playingMovie.folderUri ?: return null
    val parent = playlistParentKey(uriString)
    val folderEpisodes = dao.episodesInFolder(root)
    // A loose episode is judged by its show (O.5), so the row carries the show's id when the library knows it.
    val showIdsByKey = folderEpisodes.map { it.showKey }.distinct().associateWith { key -> dao.showByKey(key)?.tmdbId }
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
        } + folderEpisodes.map { episode ->
            PlaylistEntry(
                uri = episode.uri,
                title = episode.title ?: episode.fileName,
                detail = "S%02dE%02d".format(episode.season, episode.episode),
                tmdbId = episode.tmdbId,
                isEpisode = true,
                showTmdbId = showIdsByKey[episode.showKey],
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
