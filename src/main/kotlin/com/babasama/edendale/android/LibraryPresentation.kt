package com.babasama.edendale.android

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.data.SourceScanRules
import com.babasama.edendale.android.player.CompletedProgressEntry
import com.babasama.edendale.android.player.EpisodeCandidate
import com.babasama.edendale.android.player.EpisodeProgression
import com.babasama.edendale.android.player.ShowCandidate
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl

/**
 * The screens' pure logic — every join, format, and layout calculation the
 * Compose files need but none of the Compose itself. Keeping it here means the
 * unit tests in `src/test` can reach it without an emulator, and that a change
 * to how (say) Continue Watching is ordered is a change to one testable
 * function rather than to a screen.
 *
 * The one Compose import is [Dp], a plain value class, so this file still
 * compiles and runs on a bare JVM.
 */

// MARK: - Watch progress lookup

/**
 * Watch records keyed the way the domain model stores them, so a library row
 * only has to know its own TMDB id to find its progress.
 */
internal fun List<WatchProgress>.byStorageKey(): Map<String, WatchProgress> =
    associateBy { it.storageKey }

internal fun Map<String, WatchProgress>.forMovie(tmdbId: Int?): WatchProgress? =
    tmdbId?.let { this["movie:$it"] }

internal fun Map<String, WatchProgress>.forEpisode(tmdbId: Int?): WatchProgress? =
    tmdbId?.let { this["episode:$it"] }

/** The gold hairline only reads as progress while a title is part-watched. */
internal fun WatchProgress?.partialFraction(): Float? =
    this?.takeIf { !it.isCompleted && it.normalizedPosition > 0.01 }
        ?.normalizedPosition
        ?.toFloat()

// MARK: - Formatting

/**
 * Runtime text such as "134 min", or null when unknown.
 *
 * [format] carries the localized `runtime_minutes` template. It defaults to the
 * English form so this file stays free of Android framework types and the
 * hermetic tests in `src/test` can call it without a Context; screens pass
 * `rememberRuntimeFormat()`.
 */
internal fun formatRuntime(
    minutes: Int?,
    format: (Int) -> String = { "$it min" },
): String? = minutes?.takeIf { it > 0 }?.let(format)

/** "1999 · 134 min" — whichever halves are known. */
internal fun mediaSubtitle(
    year: Int?,
    runtimeMinutes: Int?,
    runtimeFormat: (Int) -> String = { "$it min" },
): String? {
    val parts = listOfNotNull(year?.toString(), formatRuntime(runtimeMinutes, runtimeFormat))
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * SAF tree URIs are opaque; the document id ("primary:Movies/Archive") is the
 * closest readable thing without resolving the provider.
 */
internal fun sourceDisplayPath(treeUri: String): String {
    if (treeUri.startsWith("smb://")) return treeUri
    val decoded = java.net.URLDecoder.decode(treeUri, "UTF-8")
    return decoded.substringAfterLast("/tree/", decoded)
}

private fun Int.pad(): String = toString().padStart(2, '0')

// MARK: - Grid layout

/**
 * Fits as many preferred-width posters as the content area can hold without
 * squeezing in a partial extra column. The row centers the unused width.
 */
internal fun libraryGridMetrics(
    availableWidth: Dp,
    edgeMargin: Dp,
    spacing: Dp,
    preferredWidth: Dp,
): Pair<Int, Dp> {
    val content = (availableWidth - edgeMargin * 2).coerceAtLeast(0.dp)
    val columns = ((content + spacing) / (preferredWidth + spacing)).toInt().coerceAtLeast(1)
    val cell = preferredWidth.coerceAtMost(content)
    return columns to cell
}

// MARK: - Continue Watching

/**
 * One resumable title, already joined to the local file that plays it. A
 * next-up entry is the stored episode after a show's furthest completed one: it
 * has no watch record of its own, so [fraction] is 0 and it draws no progress.
 */
internal data class ContinueEntry(
    val uri: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val fraction: Float,
    val tmdbId: Int?,
    val isEpisode: Boolean,
    val showTmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isNextUp: Boolean = false,
)

/** How many resumable titles the shelf shows before it stops being a shelf. */
internal const val CONTINUE_WATCHING_LIMIT = 12

/** A part-watched record: the shelf's and the next-up rule's shared test. */
private fun WatchProgress.isResumable(): Boolean = !isCompleted && normalizedPosition > 0.01

// MARK: - Copies of one title (D.5)

/** The source a copy came from; an individually picked file counts as local. */
internal fun Map<String, LibraryFolderEntity>.copySource(folderUri: String?): CopySource {
    val folder = folderUri?.let { this[it] } ?: return CopySource("", MediaSourceKind.LOCAL, isUnavailable = false)
    return CopySource(folder.displayName, SourceScanRules.kindOf(folder), isUnavailable = folder.status != null)
}

/**
 * The copy Play starts for each TMDB id when the same title was imported more
 * than once: the first available one in Play From order (D.5), so the choice
 * never depends on scan order.
 */
private fun <T> List<T>.preferredCopyByTmdbId(
    tmdbId: (T) -> Int?,
    folderUri: (T) -> String?,
    uri: (T) -> String,
    folders: Map<String, LibraryFolderEntity>,
): Map<Int, T> =
    filter { tmdbId(it) != null }
        .groupBy { tmdbId(it)!! }
        .mapValues { (_, copies) -> preferredCopy(null, copies, folderUri, uri, folders)!! }

/**
 * The copy to play among [copies]: [primary] (the one the viewer picked) first,
 * then local folders, then by source name, skipping sources whose last scan
 * failed unless every one did (D.5).
 */
internal fun <T> preferredCopy(
    primary: T?,
    copies: List<T>,
    folderUri: (T) -> String?,
    uri: (T) -> String,
    folders: Map<String, LibraryFolderEntity>,
): T? {
    val ordered = PlaybackSources.order(primary, copies, { folders.copySource(folderUri(it)) }, uri)
    return PlaybackSources.preferred(ordered) { folders.copySource(folderUri(it)).isUnavailable }
}

/** A movie's copies in Play From order, [primary] (the page's own) first. */
internal fun movieCopies(
    primary: LibraryMovieEntity,
    movies: List<LibraryMovieEntity>,
    folders: Map<String, LibraryFolderEntity>,
): List<LibraryMovieEntity> {
    val others = primary.tmdbId?.let { id -> movies.filter { it.tmdbId == id && it.uri != primary.uri } }.orEmpty()
    return PlaybackSources.order(primary, others, { folders.copySource(it.folderUri) }, { it.uri })
}

/** The movie copy a tap on [tapped] plays: itself unless its source is unavailable and another isn't. */
internal fun preferredMovie(
    tapped: LibraryMovieEntity,
    movies: List<LibraryMovieEntity>,
    folders: Map<String, LibraryFolderEntity>,
): LibraryMovieEntity =
    PlaybackSources.preferred(movieCopies(tapped, movies, folders)) { folders.copySource(it.folderUri).isUnavailable }
        ?: tapped

/**
 * Continue Watching, newest first, joined to the local files that play it:
 * every part-watched title, plus — for each show with nothing in progress — the
 * stored episode after its furthest completed one (Apple's next-up card). The
 * next-up entry is a suggestion only; nothing here writes watch progress, and
 * it still appears after the completed episode's file was deleted. Records with
 * no matching import are dropped — they belong to TMDB titles the library does
 * not hold.
 *
 * [nextUpFormat] carries the localized "Up Next · %1$s" template; the English
 * default keeps this file free of Android types for the JVM tests.
 * [limit] caps the shelf; null returns every entry.
 */
internal fun continueWatching(
    progress: List<WatchProgress>,
    movies: List<LibraryMovieEntity>,
    episodes: List<LibraryEpisodeEntity>,
    shows: List<LibraryShowEntity>,
    nextUpFormat: (String) -> String = { "Up Next · $it" },
    limit: Int? = CONTINUE_WATCHING_LIMIT,
    folders: List<LibraryFolderEntity> = emptyList(),
): List<ContinueEntry> {
    if (progress.isEmpty()) return emptyList()
    val foldersByUri = folders.associateBy { it.treeUri }
    val moviesByTmdbId = movies.preferredCopyByTmdbId({ it.tmdbId }, { it.folderUri }, { it.uri }, foldersByUri)
    val episodesByTmdbId = episodes.preferredCopyByTmdbId({ it.tmdbId }, { it.folderUri }, { it.uri }, foldersByUri)
    val showsByKey = shows.associateBy { it.key }

    val resumable = progress.filter { it.isResumable() }
    val inProgress = resumable.mapNotNull { record ->
        val fraction = record.normalizedPosition.toFloat()
        val entry = when (record.mediaType) {
            WatchMediaType.MOVIE -> {
                val movie = moviesByTmdbId[record.tmdbId] ?: return@mapNotNull null
                ContinueEntry(
                    uri = movie.uri,
                    title = movie.title,
                    subtitle = mediaSubtitle(movie.year, movie.runtimeMinutes),
                    posterUrl = tmdbImageUrl(movie.posterPath, TmdbImageSize.POSTER),
                    fraction = fraction,
                    tmdbId = movie.tmdbId,
                    isEpisode = false,
                )
            }
            WatchMediaType.EPISODE -> {
                val episode = episodesByTmdbId[record.tmdbId] ?: return@mapNotNull null
                val show = showsByKey[episode.showKey]
                ContinueEntry(
                    uri = episode.uri,
                    title = show?.name ?: episode.fileName,
                    subtitle = episodeCode(episode.season, episode.episode) +
                        (episode.title?.let { " · $it" } ?: ""),
                    posterUrl = tmdbImageUrl(show?.posterPath, TmdbImageSize.POSTER),
                    fraction = fraction,
                    tmdbId = episode.tmdbId,
                    isEpisode = true,
                    showTmdbId = show?.tmdbId,
                    season = episode.season,
                    episode = episode.episode,
                )
            }
        }
        entry to record.lastWatchedEpochMillis
    }

    val nextUp = nextUpEntries(progress, resumable, episodes, shows, episodesByTmdbId, nextUpFormat, foldersByUri)

    val sorted = (inProgress + nextUp)
        .sortedByDescending { it.second }
        .map { it.first }
    return if (limit == null) sorted else sorted.take(limit)
}

/** "S01E02". */
internal fun episodeCode(season: Int, episode: Int): String = "S${season.pad()}E${episode.pad()}"

/**
 * Next-up entries paired with the completed episode's last-watched time, which
 * is where they sort. Copies of one show (the same TMDB id imported from
 * several folders) merge into one candidate list, so they give one card.
 */
private fun nextUpEntries(
    progress: List<WatchProgress>,
    resumable: List<WatchProgress>,
    episodes: List<LibraryEpisodeEntity>,
    shows: List<LibraryShowEntity>,
    episodesByTmdbId: Map<Int, LibraryEpisodeEntity>,
    nextUpFormat: (String) -> String,
    folders: Map<String, LibraryFolderEntity>,
): List<Pair<ContinueEntry, Long>> {
    val showsByKey = shows.associateBy { it.key }
    val inProgressShowIds = resumable
        .filter { it.mediaType == WatchMediaType.EPISODE }
        .mapNotNull { record ->
            record.showTmdbId
                ?: episodesByTmdbId[record.tmdbId]?.let { showsByKey[it.showKey]?.tmdbId }
        }
        .toSet()
    val episodesByShowKey = episodes.groupBy { it.showKey }
    val candidates = shows
        .filter { it.tmdbId != null }
        .groupBy { it.tmdbId!! }
        .map { (showId, copies) ->
            ShowCandidate(
                tmdbId = showId,
                name = copies.first().name,
                episodes = copies
                    .flatMap { episodesByShowKey[it.key].orEmpty() }
                    .sortedWith(PlaybackSources.comparator({ folders.copySource(it.folderUri) }, { it.uri }))
                    .map { EpisodeCandidate(id = it.uri, season = it.season, episode = it.episode, title = it.title) },
            )
        }
    val completed = progress.map {
        CompletedProgressEntry(
            tmdbId = it.tmdbId,
            isEpisode = it.mediaType == WatchMediaType.EPISODE,
            isCompleted = it.isCompleted,
            showTmdbId = it.showTmdbId,
            seasonNumber = it.seasonNumber,
            episodeNumber = it.episodeNumber,
            lastWatchedEpochMillis = it.lastWatchedEpochMillis,
        )
    }
    val episodesByUri = episodes.associateBy { it.uri }
    return EpisodeProgression.nextUpEpisodes(completed, inProgressShowIds, candidates)
        .mapNotNull { candidate ->
            val found = episodesByUri[candidate.episode.id] ?: return@mapNotNull null
            val showKeys = shows.filter { it.tmdbId == candidate.show.tmdbId }.mapTo(HashSet()) { it.key }
            val copies = episodes.filter {
                it.showKey in showKeys && it.season == found.season && it.episode == found.episode
            }
            val episode = preferredCopy(null, copies, { it.folderUri }, { it.uri }, folders) ?: found
            val show = showsByKey[episode.showKey]
            ContinueEntry(
                uri = episode.uri,
                title = show?.name ?: candidate.show.name,
                subtitle = nextUpFormat(episodeCode(episode.season, episode.episode)),
                posterUrl = tmdbImageUrl(show?.posterPath, TmdbImageSize.POSTER),
                fraction = 0f,
                tmdbId = episode.tmdbId,
                isEpisode = true,
                showTmdbId = candidate.show.tmdbId,
                season = episode.season,
                episode = episode.episode,
                isNextUp = true,
            ) to candidate.lastWatchedEpochMillis
        }
}

// MARK: - Critical Consensus

/** One cell of the scorecard. */
internal data class ConsensusTile(
    val source: String,
    val value: String,
    val suffix: String? = null,
)

/**
 * TMDB score and vote count, plus a show's season/episode catalogue — the same
 * cells the Apple detail view's Critical Consensus card carries. Third-party
 * critic scores stay off-limits (TMDB is the only permitted source).
 */
internal fun consensusTiles(detail: MediaDetail): List<ConsensusTile> = buildList {
    detail.score?.takeIf { it > 0 }?.let {
        add(ConsensusTile("TMDB", ((it * 10).toInt() / 10.0).toString(), "/10"))
    }
    detail.voteCount?.takeIf { it > 0 }?.let {
        add(ConsensusTile("Votes", it.toString()))
    }
    detail.seasonCount?.takeIf { it > 0 }?.let {
        add(ConsensusTile("Seasons", it.toString()))
    }
    detail.episodeCount?.takeIf { it > 0 }?.let {
        add(ConsensusTile("Episodes", it.toString()))
    }
}

// MARK: - Star rating

/**
 * The full → half → removed cycle for one star; any other state fills it first.
 * Mirrors Apple's `StarRatingControl`: for star N, full = N*2 and half = full-1,
 * and removing a star falls back to the full stars before it.
 */
internal fun nextStarRating(index: Int, current: Double): Double? {
    val full = index * 2.0
    val half = full - 1.0
    return when (current) {
        full -> half
        half -> ((index - 1) * 2.0).takeIf { it > 0 }
        else -> full
    }
}

// MARK: - Search: local library matches

/** Imported titles whose name contains the query — matched entirely on device. */
internal data class LocalMatches(
    val movies: List<LibraryMovieEntity> = emptyList(),
    val shows: List<LibraryShowEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && shows.isEmpty()
}

/**
 * The "From Your Library" section's contents. Matching is a plain on-device
 * `contains`, so results land before the first TMDB response; the keyword scope
 * narrows which half is searched, and a person filter suppresses the section
 * entirely because filmography is a TMDB-only view.
 */
internal fun localMatches(
    term: String,
    scope: SearchScope,
    hasActivePerson: Boolean,
    movies: List<LibraryMovieEntity>,
    shows: List<LibraryShowEntity>,
): LocalMatches {
    if (term.isBlank() || hasActivePerson || scope == SearchScope.PEOPLE) return LocalMatches()
    return LocalMatches(
        movies = if (scope == SearchScope.SHOWS) {
            emptyList()
        } else {
            movies.filter { it.title.contains(term, ignoreCase = true) }
        },
        shows = if (scope == SearchScope.MOVIES) {
            emptyList()
        } else {
            shows.filter { it.name.contains(term, ignoreCase = true) }
        },
    )
}
