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
import com.babasama.edendale.connectors.SourceEndpoint
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl
import com.babasama.edendale.parental.ParentalGateRules

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
    // Remote source URLs are credential-free and readable as they are.
    if (MediaSourceKind.forSourceUri(treeUri)?.isRemote == true) return treeUri
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
    /** The episode's own title, apart from its code. */
    val episodeTitle: String? = null,
    /** 16:9 artwork: the episode still, else the show backdrop; a movie's backdrop. */
    val backdropUrl: String? = null,
    /** Where playback stopped; null for a next-up entry. */
    val positionMillis: Long? = null,
    /** The file's length, from the watch record or else the TMDB runtime. */
    val durationMillis: Long? = null,
    /** When it was last watched (for next-up, the completed episode): the shelf's order. */
    val lastWatchedEpochMillis: Long = 0L,
    /**
     * Whether playing this copy may make its source the one the title
     * remembers (27.1): false when the card fell back from the remembered
     * source because its last scan failed (`PlaybackSources.remembersSource`).
     */
    val remembersSource: Boolean = true,
)

/**
 * The watch record a part-watched entry resumes from, in the domain's
 * storage-key form, which the card's Clear Progress clears. Null for a
 * next-up entry: it is a suggestion with no record of its own.
 */
internal val ContinueEntry.progressKey: String?
    get() = if (isNextUp) null else tmdbId?.let { if (isEpisode) "episode:$it" else "movie:$it" }

/** How many resumable titles the shelf shows before it stops being a shelf. */
internal const val CONTINUE_WATCHING_LIMIT = 12

/** A part-watched record: the shelf's and the next-up rule's shared test. */
private fun WatchProgress.isResumable(): Boolean = !isCompleted && normalizedPosition > 0.01

/**
 * Where a part-watched record stopped and how long the file is, in
 * milliseconds. The player stores the position in seconds beside the
 * fraction, which gives the length; a record without seconds (an import)
 * falls back to the TMDB runtime.
 */
private fun WatchProgress.playbackMillis(runtimeMinutes: Int?): Pair<Long?, Long?> {
    val fraction = normalizedPosition
    val position = (watchedSeconds * 1000).toLong()
    if (position > 0 && fraction > 0) return position to (position / fraction).toLong()
    val duration = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L } ?: return null to null
    return (duration * fraction).toLong() to duration
}

// MARK: - Copies of one title (D.5)

/**
 * The source a copy came from, with where it's reached from (27.1); an
 * individually picked file counts as local, on this device.
 */
internal fun Map<String, LibraryFolderEntity>.copySource(folderUri: String?): CopySource {
    val folder = folderUri?.let { this[it] } ?: return CopySource("", MediaSourceKind.LOCAL, isUnavailable = false)
    val kind = SourceScanRules.kindOf(folder)
    return CopySource(
        name = folder.displayName,
        kind = kind,
        isUnavailable = folder.status != null,
        endpoint = SourceEndpoint.of(kind, folder.treeUri, folder.endpoint),
    )
}

/** The copy a card starts, and whether playing it may become the title's remembered source (27.1). */
private class CardStart<T>(val file: T, val remembersSource: Boolean)

/**
 * The copy Play starts for each TMDB id when the same title was imported more
 * than once: one on the source the title last played from ([remembered]),
 * else the first available one in Play From order (D.5), so the choice never
 * depends on scan order.
 */
private fun <T> List<T>.preferredCopyByTmdbId(
    tmdbId: (T) -> Int?,
    folderUri: (T) -> String?,
    uri: (T) -> String,
    folders: Map<String, LibraryFolderEntity>,
    remembered: (T) -> String? = { null },
): Map<Int, CardStart<T>> =
    filter { tmdbId(it) != null }
        .groupBy { tmdbId(it)!! }
        .mapValues { (_, copies) ->
            val source = copies.firstNotNullOfOrNull(remembered)
            val file = preferredCopy(null, copies, folderUri, uri, folders, source)!!
            CardStart(file, PlaybackSources.remembersSource(copies, file, { PlaybackSources.sourceKey(folderUri(it)) }, source))
        }

/**
 * The copy to play among [copies]: [primary] (the one the viewer picked) first,
 * then local folders, then by source name, skipping sources whose last scan
 * failed unless every one did (D.5). Copies on the [remembered] source — the
 * one the title last played from (27.1) — go first; it still falls back when
 * its last scan failed.
 */
internal fun <T> preferredCopy(
    primary: T?,
    copies: List<T>,
    folderUri: (T) -> String?,
    uri: (T) -> String,
    folders: Map<String, LibraryFolderEntity>,
    remembered: String? = null,
): T? {
    val ordered = PlaybackSources.order(primary, copies, { folders.copySource(folderUri(it)) }, uri)
    return PlaybackSources.startingCopy(ordered, { PlaybackSources.sourceKey(folderUri(it)) }, remembered) {
        folders.copySource(folderUri(it)).isUnavailable
    }
}

// MARK: - Detail page

/**
 * What a detail page shows (Apple's `MediaDetailSource`): a TMDB title, or a
 * library row opened from Downloaded or Search. A library row may have no
 * TMDB match at all, so its page is built from the row itself.
 */
sealed interface DetailSource {
    data class Tmdb(val ref: MediaRef) : DetailSource

    /** A movie file, by its library uri. */
    data class LocalMovie(val uri: String) : DetailSource

    /** A library show, by its key: its episodes merged across every copy ([pageShow]). */
    data class LocalShow(val key: String) : DetailSource

    companion object {
        /**
         * The page as text the activity's saved state can hold, so an open
         * page outlives process death the way an open show does.
         */
        fun encode(source: DetailSource): String = when (source) {
            is Tmdb -> "tmdb:${source.ref.mediaType.pathSegment}:${source.ref.id}"
            is LocalMovie -> "movie:${source.uri}"
            is LocalShow -> "show:${source.key}"
        }

        /** The page [encode] wrote, or null for text that isn't one. */
        fun decode(saved: String): DetailSource? {
            val value = saved.substringAfter(':', "").ifEmpty { return null }
            return when (saved.substringBefore(':')) {
                "tmdb" -> {
                    val type = MediaType.entries.firstOrNull { it.pathSegment == value.substringBefore(':') } ?: return null
                    val id = value.substringAfter(':', "").toIntOrNull() ?: return null
                    Tmdb(MediaRef(id, type))
                }
                // A uri carries colons of its own, so everything after the first one is the value.
                "movie" -> LocalMovie(value)
                "show" -> LocalShow(value)
                else -> null
            }
        }
    }
}

/** The TMDB title a library movie was matched to; null until enrichment matches the file. */
internal val LibraryMovieEntity.tmdbRef: MediaRef?
    get() = tmdbId?.let { MediaRef(it, MediaType.MOVIE) }

/**
 * A detail page's header: TMDB's record field by field where there is one,
 * else the library row's own, so an unmatched file, a device without a TMDB
 * key, or one that is offline still gets a page.
 */
internal data class DetailHeader(
    val mediaType: MediaType,
    val title: String,
    val tagline: String?,
    val overview: String?,
    val backdropPath: String?,
    val posterPath: String?,
    val year: Int?,
    val runtimeMinutes: Int?,
    /** TMDB's score; a library row has none. */
    val score: Double?,
)

/** The header for [detail], falling back to [movie] or [show]; null while there is nothing to show. */
internal fun detailHeader(
    detail: MediaDetail?,
    movie: LibraryMovieEntity?,
    show: LibraryShowEntity?,
): DetailHeader? {
    val title = detail?.title ?: movie?.title ?: show?.name ?: return null
    return DetailHeader(
        mediaType = detail?.ref?.mediaType ?: if (movie != null) MediaType.MOVIE else MediaType.TV,
        title = title,
        tagline = detail?.tagline?.takeIf { it.isNotBlank() },
        overview = listOfNotNull(detail?.overview, movie?.overview, show?.overview).firstOrNull { it.isNotBlank() },
        backdropPath = detail?.backdropPath ?: movie?.backdropPath ?: show?.backdropPath,
        posterPath = detail?.posterPath ?: movie?.posterPath ?: show?.posterPath,
        year = detail?.year ?: movie?.year ?: show?.firstAirYear,
        runtimeMinutes = listOfNotNull(detail?.runtimeMinutes, movie?.runtimeMinutes).firstOrNull { it > 0 },
        score = detail?.score,
    )
}

/**
 * The library movie a page plays: the row a library page was opened from,
 * or for a TMDB movie page the first of its imported copies in Play From
 * order (D.5), so a title found in Movies & Shows plays what the library
 * holds. Null for a show and for a title the library doesn't hold.
 */
internal fun pageMovie(
    source: DetailSource?,
    movies: List<LibraryMovieEntity>,
    folders: Map<String, LibraryFolderEntity>,
): LibraryMovieEntity? = when (source) {
    is DetailSource.LocalMovie -> movies.firstOrNull { it.uri == source.uri }
    is DetailSource.Tmdb -> source.ref.takeIf { it.mediaType == MediaType.MOVIE }?.let { ref ->
        PlaybackSources.order(null, movies.filter { it.tmdbId == ref.id }, { folders.copySource(it.folderUri) }, { it.uri })
            .firstOrNull()
    }
    is DetailSource.LocalShow, null -> null
}

/**
 * Whether the parental policy blocks a detail page: while a title rule is
 * in force only a title verified as allowed opens, so a library file without
 * a TMDB match fails closed (O-D8), as it does in the player.
 */
internal fun detailRestricted(policyActive: Boolean, ref: MediaRef?, allows: (MediaRef) -> Boolean): Boolean =
    !ParentalGateRules.permitsPlayback(policyActive, ref, allows)

/**
 * The title the parental policy judges a page by: the page's TMDB title,
 * else its library movie's or show's match the moment the row has one. Null
 * for a library file or show enrichment hasn't matched, which
 * [detailRestricted] blocks while a rule is on.
 */
internal fun pageGateRef(ref: MediaRef?, movie: LibraryMovieEntity?, show: LibraryShowEntity?): MediaRef? =
    ref ?: movie?.tmdbRef ?: show?.tmdbId?.let { MediaRef(it, MediaType.TV) }

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
 * [lastSources] is the playback memory (27.1): a title resumes, and a show's
 * next episode starts, on the source it last played from while that source
 * has the file and its last scan didn't fail. A card that falls back from it
 * keeps the memory as it is ([ContinueEntry.remembersSource]).
 */
internal fun continueWatching(
    progress: List<WatchProgress>,
    movies: List<LibraryMovieEntity>,
    episodes: List<LibraryEpisodeEntity>,
    shows: List<LibraryShowEntity>,
    nextUpFormat: (String) -> String = { "Up Next · $it" },
    limit: Int? = CONTINUE_WATCHING_LIMIT,
    folders: List<LibraryFolderEntity> = emptyList(),
    lastSources: Map<String, String> = emptyMap(),
): List<ContinueEntry> {
    if (progress.isEmpty()) return emptyList()
    val foldersByUri = folders.associateBy { it.treeUri }
    val showsByKey = shows.associateBy { it.key }
    // A movie's copies are its title's: the same TMDB id, or the same file
    // through another link whose match is still pending (27.1), so the card
    // and the movie's page start the same copy.
    val moviesByTmdbId = movieTitles(movies, foldersByUri)
        .filter { it.tmdbId != null }
        .associate { title ->
            val remembered = PlaybackSources.movieMemoryKey(title.tmdbId)?.let(lastSources::get)
            val start = moviePlayStart(title.copies, foldersByUri, remembered)!!
            title.tmdbId!! to (title to CardStart(start.copy, start.remembersSource))
        }
    val episodesByTmdbId = episodes.preferredCopyByTmdbId({ it.tmdbId }, { it.folderUri }, { it.uri }, foldersByUri) {
        PlaybackSources.rememberedShowSource(lastSources, showsByKey[it.showKey]?.tmdbId, listOf(it.showKey))
    }

    val resumable = progress.filter { it.isResumable() }
    val inProgress = resumable.mapNotNull { record ->
        val fraction = record.normalizedPosition.toFloat()
        val entry = when (record.mediaType) {
            WatchMediaType.MOVIE -> {
                val (title, start) = moviesByTmdbId[record.tmdbId] ?: return@mapNotNull null
                // The card names the title by its matched copy, and plays the
                // copy Play would start — which may be an alias whose match is
                // still pending, so the record's id goes with it.
                val movie = title.display
                val (position, duration) = record.playbackMillis(movie.runtimeMinutes)
                ContinueEntry(
                    uri = start.file.uri,
                    title = movie.title,
                    subtitle = mediaSubtitle(movie.year, movie.runtimeMinutes),
                    posterUrl = tmdbImageUrl(movie.posterPath, TmdbImageSize.POSTER),
                    fraction = fraction,
                    tmdbId = record.tmdbId,
                    isEpisode = false,
                    backdropUrl = tmdbImageUrl(movie.backdropPath, TmdbImageSize.BACKDROP),
                    positionMillis = position,
                    durationMillis = duration,
                    lastWatchedEpochMillis = record.lastWatchedEpochMillis,
                    remembersSource = start.remembersSource,
                )
            }
            WatchMediaType.EPISODE -> {
                val start = episodesByTmdbId[record.tmdbId] ?: return@mapNotNull null
                val episode = start.file
                val show = showsByKey[episode.showKey]
                val (position, duration) = record.playbackMillis(episode.runtimeMinutes)
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
                    episodeTitle = episode.title,
                    backdropUrl = tmdbImageUrl(episode.stillPath ?: show?.backdropPath, TmdbImageSize.BACKDROP),
                    positionMillis = position,
                    durationMillis = duration,
                    lastWatchedEpochMillis = record.lastWatchedEpochMillis,
                    remembersSource = start.remembersSource,
                )
            }
        }
        entry to record.lastWatchedEpochMillis
    }

    val nextUp = nextUpEntries(progress, resumable, episodes, shows, episodesByTmdbId, nextUpFormat, foldersByUri, lastSources)

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
 * several folders) merge into one candidate list, so they give one card, which
 * starts on the source the show last played from ([lastSources]) when it can.
 */
private fun nextUpEntries(
    progress: List<WatchProgress>,
    resumable: List<WatchProgress>,
    episodes: List<LibraryEpisodeEntity>,
    shows: List<LibraryShowEntity>,
    episodesByTmdbId: Map<Int, CardStart<LibraryEpisodeEntity>>,
    nextUpFormat: (String) -> String,
    folders: Map<String, LibraryFolderEntity>,
    lastSources: Map<String, String>,
): List<Pair<ContinueEntry, Long>> {
    val showsByKey = shows.associateBy { it.key }
    val inProgressShowIds = resumable
        .filter { it.mediaType == WatchMediaType.EPISODE }
        .mapNotNull { record ->
            record.showTmdbId
                ?: episodesByTmdbId[record.tmdbId]?.let { showsByKey[it.file.showKey]?.tmdbId }
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
            val remembered = PlaybackSources.rememberedShowSource(lastSources, candidate.show.tmdbId, showKeys.sorted())
            val episode = preferredCopy(null, copies, { it.folderUri }, { it.uri }, folders, remembered) ?: found
            // A copy whose match is still pending plays as the matched one.
            val named = copies.firstOrNull { it.tmdbId != null } ?: episode
            val show = showsByKey[episode.showKey]
            ContinueEntry(
                uri = episode.uri,
                title = show?.name ?: candidate.show.name,
                subtitle = nextUpFormat(episodeCode(episode.season, episode.episode)),
                posterUrl = tmdbImageUrl(show?.posterPath, TmdbImageSize.POSTER),
                fraction = 0f,
                tmdbId = named.tmdbId,
                isEpisode = true,
                showTmdbId = candidate.show.tmdbId,
                season = episode.season,
                episode = episode.episode,
                isNextUp = true,
                episodeTitle = named.title,
                backdropUrl = tmdbImageUrl(named.stillPath ?: show?.backdropPath, TmdbImageSize.BACKDROP),
                durationMillis = named.runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L },
                lastWatchedEpochMillis = candidate.lastWatchedEpochMillis,
                remembersSource = PlaybackSources.remembersSource(copies, episode, { PlaybackSources.sourceKey(it.folderUri) }, remembered),
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

/**
 * Imported titles whose name contains the query — matched entirely on
 * device. One entry per title, as Downloaded shows them (27.1).
 */
internal data class LocalMatches(
    val movies: List<LibraryTitle<LibraryMovieEntity>> = emptyList(),
    val shows: List<ShowTitle> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && shows.isEmpty()
}

/**
 * The "From Your Library" section's contents. Matching is a plain on-device
 * `contains`, so results land before the first TMDB response; the keyword scope
 * narrows which half is searched, and a person filter suppresses the section
 * entirely because filmography is a TMDB-only view. Titles are grouped
 * first ([movieTitles], [showTitles]) and then matched, so a title matches
 * when any of its copies' names does and shows once however many links
 * reach it.
 */
internal fun localMatches(
    term: String,
    scope: SearchScope,
    hasActivePerson: Boolean,
    movies: List<LibraryTitle<LibraryMovieEntity>>,
    shows: List<ShowTitle>,
): LocalMatches {
    if (term.isBlank() || hasActivePerson || scope == SearchScope.PEOPLE) return LocalMatches()
    return LocalMatches(
        movies = if (scope == SearchScope.SHOWS) {
            emptyList()
        } else {
            movies.filter { title -> title.copies.any { it.title.contains(term, ignoreCase = true) } }
        },
        shows = if (scope == SearchScope.MOVIES) {
            emptyList()
        } else {
            shows.filter { title -> title.shows.any { it.name.contains(term, ignoreCase = true) } }
        },
    )
}
