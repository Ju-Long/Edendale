package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.Genre
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.PersonDetail
import com.babasama.edendale.domain.PersonItem
import com.babasama.edendale.domain.SearchQuery
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

data class HeroScene(
    val detail: MediaDetail,
    val progress: WatchProgress? = null,
) {
    val isContinueWatching: Boolean get() = progress != null

    val remainingText: String?
        get() {
            val runtime = detail.runtimeMinutes ?: return null
            val currentProgress = progress ?: return null
            if (runtime <= 0) return null
            val seconds = (runtime * 60 * (1 - currentProgress.normalizedPosition)).toInt()
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val remainder = seconds % 60
            return "${hours.twoDigits()}:${minutes.twoDigits()}:${remainder.twoDigits()} left"
        }
}

data class HomeCatalog(
    val heroScenes: List<HeroScene>,
    val trending: List<MediaItem>,
    val popularMovies: List<MediaItem>,
    val popularShows: List<MediaItem>,
    val topRated: List<MediaItem>,
    val genres: List<Genre>,
)

/**
 * The result of one scoped search. [titles] and [people] are both populated
 * when the scope wants both; the UI decides which section leads from [scope]
 * (people first under a people prefix, titles first otherwise).
 */
data class ScopedSearchResult(
    val scope: SearchScope,
    val term: String,
    val titles: List<MediaItem> = emptyList(),
    val people: List<PersonItem> = emptyList(),
) {
    val isEmpty: Boolean get() = titles.isEmpty() && people.isEmpty()

    /** True when people should render above titles. */
    val leadsWithPeople: Boolean get() = scope == SearchScope.PEOPLE
}

sealed interface CollectionFilter {
    val title: String

    data object All : CollectionFilter { override val title = "All Archives" }
    data object Movies : CollectionFilter { override val title = "Feature Films" }
    data object Shows : CollectionFilter { override val title = "Series" }
    data class ByGenre(val genre: Genre) : CollectionFilter { override val title = genre.name }
}

/** Cross-platform browse use cases and orchestration consumed directly by Compose. */
class BrowseRepository(private val api: TmdbApi) {
    val isConfigured: Boolean get() = api.isConfigured

    suspend fun loadHome(progress: List<WatchProgress> = emptyList()): HomeCatalog = coroutineScope {
        val trendingRequest = async { api.trending() }
        val moviesRequest = async { api.popular(MediaType.MOVIE) }
        val showsRequest = async { api.popular(MediaType.TV) }
        val ratedRequest = async { api.topRated(MediaType.MOVIE) }
        val genresRequest = async { api.movieGenres() }

        val trending = trendingRequest.await()
        val popularMovies = moviesRequest.await()
        val popularShows = showsRequest.await()
        val topRated = ratedRequest.await()
        val genres = genresRequest.await()

        val continueRef = progress
            .asSequence()
            .filterNot(WatchProgress::isCompleted)
            .sortedByDescending(WatchProgress::lastWatchedEpochMillis)
            .mapNotNull { item ->
                when (item.mediaType) {
                    WatchMediaType.MOVIE -> MediaRef(item.tmdbId, MediaType.MOVIE)
                    WatchMediaType.EPISODE -> item.showTmdbId?.let { MediaRef(it, MediaType.TV) }
                }
            }
            .firstOrNull()

        val continueScene = continueRef?.let { ref ->
            runCatching { api.mediaDetail(ref) }.getOrNull()?.let { detail ->
                HeroScene(detail, progress.firstOrNull { item ->
                    item.tmdbId == ref.id || item.showTmdbId == ref.id
                })
            }
        }
        val spotlightDetails = trending
            .asSequence()
            .map(MediaItem::ref)
            .filter { it != continueRef }
            .take(8)
            .map { ref -> async { runCatching { api.mediaDetail(ref) }.getOrNull() } }
            .toList()
            .awaitAll()
            .filterNotNull()

        HomeCatalog(
            heroScenes = listOfNotNull(continueScene) + spotlightDetails.map(::HeroScene),
            trending = trending,
            popularMovies = popularMovies,
            popularShows = popularShows,
            topRated = topRated,
            genres = genres,
        )
    }

    /** Today's trending titles — the search tab's idle grid. */
    suspend fun trending(window: String = "day"): List<MediaItem> = api.trending(window)

    suspend fun loadCollection(filter: CollectionFilter): List<MediaItem> = when (filter) {
        CollectionFilter.All -> api.trending(window = "week")
        CollectionFilter.Movies -> api.discover(MediaType.MOVIE)
        CollectionFilter.Shows -> api.discover(MediaType.TV)
        is CollectionFilter.ByGenre -> api.discover(MediaType.MOVIE, filter.genre.id)
    }

    suspend fun search(query: String): List<MediaItem> =
        if (query.isBlank()) emptyList() else api.search(query.trim())

    suspend fun searchPeople(query: String): List<PersonItem> =
        if (query.isBlank()) emptyList() else api.searchPeople(query.trim())

    /**
     * One search field value — including any `actors:` / `movies:` / `shows:`
     * prefix — resolved to the titles and people its scope asks for. Every
     * platform calls this instead of dispatching endpoints itself, so section
     * ordering and the "additive lookup never blanks the primary one" rule
     * are decided in one place.
     */
    suspend fun searchScoped(raw: String): ScopedSearchResult = coroutineScope {
        val query = SearchQuery.parse(raw)
        val term = query.term
        if (term.isBlank()) return@coroutineScope ScopedSearchResult(query.scope, term)

        when (query.scope) {
            SearchScope.PEOPLE -> {
                // People are the point of this scope, so they propagate
                // failures; titles are the additive section.
                val peopleRequest = async { api.searchPeople(term) }
                val titlesRequest = async { optional(emptyList()) { api.search(term) } }
                ScopedSearchResult(query.scope, term, titlesRequest.await(), peopleRequest.await())
            }

            SearchScope.MOVIES -> ScopedSearchResult(query.scope, term, api.searchMovies(term))
            SearchScope.SHOWS -> ScopedSearchResult(query.scope, term, api.searchShows(term))

            SearchScope.ALL -> {
                val titlesRequest = async { api.search(term) }
                val peopleRequest = async { optional(emptyList()) { api.searchPeople(term) } }
                ScopedSearchResult(query.scope, term, titlesRequest.await(), peopleRequest.await())
            }
        }
    }

    /**
     * Runs an additive lookup whose failure must never blank the primary
     * section. Cancellation still propagates — swallowing it would detach the
     * request from the enclosing scope.
     */
    private suspend fun <T> optional(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            fallback
        }

    suspend fun personDetail(personId: Int): PersonDetail = api.personDetail(personId)

    /**
     * Movies released inside [`from`, `to`] ("yyyy-MM-dd", inclusive) across
     * up to three discover pages (Apple's result page limit), de-duplicated.
     */
    suspend fun moviesReleased(from: String, to: String): List<MediaItem> {
        val seen = mutableSetOf<Int>()
        val results = mutableListOf<MediaItem>()
        for (page in 1..3) {
            val items = api.moviesReleased(from, to, page)
            if (items.isEmpty()) break
            for (item in items) {
                if (seen.add(item.id)) results.add(item)
            }
        }
        return results
    }

    /** Per-day release counts for a given year, sampling up to 10 discover pages. */
    suspend fun releaseCounts(year: Int): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val from = "$year-01-01"
        val to = "$year-12-31"
        val seen = mutableSetOf<Int>()
        for (page in 1..10) {
            val items = api.moviesReleased(from, to, page)
            if (items.isEmpty()) break
            for (item in items) {
                if (seen.add(item.id)) {
                    val date = item.releaseDate ?: continue
                    if (date.startsWith(year.toString())) {
                        counts[date] = (counts[date] ?: 0) + 1
                    }
                }
            }
        }
        return counts
    }

    fun selectionSummary(from: String, to: String): String =
        com.babasama.edendale.domain.ReleaseCalendar.formatSummary(from, to)


    suspend fun detail(ref: MediaRef): MediaDetail = api.mediaDetail(ref)

    suspend fun episodeDetail(
        showId: Int,
        season: Int,
        episode: Int,
    ): TmdbEpisodeDetail = api.episodeDetail(showId, season, episode)

    suspend fun season(showId: Int, seasonNumber: Int): TmdbSeasonDetail =
        api.season(showId, seasonNumber)

    suspend fun filmography(personId: Int): List<MediaItem> = api.filmography(personId)

    suspend fun bestTrailer(ref: MediaRef): TmdbVideo? = api.bestTrailer(ref)
}

fun HomeCatalog.collectionFilters(): List<CollectionFilter> =
    listOf(CollectionFilter.All, CollectionFilter.Movies, CollectionFilter.Shows) +
        genres.take(6).map(CollectionFilter::ByGenre)

private fun Int.twoDigits(): String = toString().padStart(2, '0')
