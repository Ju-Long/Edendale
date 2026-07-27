package com.babasama.edendale.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.PersonDetail
import com.babasama.edendale.domain.PersonItem
import com.babasama.edendale.domain.SearchQuery
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.UserMediaRecord
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.domain.WatchProgress
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.babasama.edendale.tmdb.BrowseRepository
import com.babasama.edendale.tmdb.CollectionFilter
import com.babasama.edendale.tmdb.HomeCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class LoadPhase { IDLE, LOADING, LOADED, MISSING_CREDENTIAL, FAILED }

data class BrowseUiState(
    val phase: LoadPhase = LoadPhase.IDLE,
    val catalog: HomeCatalog? = null,
    val selectedCollection: CollectionFilter = CollectionFilter.All,
    val collectionItems: List<MediaItem> = emptyList(),
    val isLoadingCollection: Boolean = false,
    val errorMessage: String? = null,
)

data class DetailUiState(
    val ref: MediaRef? = null,
    val isLoading: Boolean = false,
    val detail: MediaDetail? = null,
    val userMedia: UserMediaRecord? = null,
    val watchProgress: WatchProgress? = null,
    val trailer: com.babasama.edendale.tmdb.TmdbVideo? = null,
    val errorMessage: String? = null,
    /** Season the episode browser is showing; null until a show detail lands. */
    val selectedSeason: Int? = null,
    /** Episode lists already fetched, keyed by season number. */
    val episodesBySeason: Map<Int, List<com.babasama.edendale.tmdb.TmdbEpisodeDetail>> = emptyMap(),
    val loadingSeason: Int? = null,
    val seasonErrorMessage: String? = null,
    /** Watched episode TMDB ids, so the browser can tick them without a per-card query. */
    val watchedEpisodeIds: Set<Int> = emptySet(),
)

class BrowseViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val repository: BrowseRepository = AndroidEdendaleCore.browseRepository()
    private val strings = AppStrings(application)
    private val dataStore = LocalDataStore((application as EdendaleApplication).database)
    private val libraryRepo = (application as EdendaleApplication).libraryRepository

    var state by mutableStateOf(BrowseUiState())
        private set

    var detailState by mutableStateOf(DetailUiState())
        private set

    private var detailJob: Job? = null
    private var collectionJob: Job? = null
    private var seasonJob: Job? = null

    init {
        load()
    }

    fun load() {
        if (!repository.isConfigured) {
            state = BrowseUiState(phase = LoadPhase.MISSING_CREDENTIAL)
            return
        }
        viewModelScope.launch {
            state = state.copy(phase = LoadPhase.LOADING, errorMessage = null)
            try {
                val progress = dataStore.getAllWatchProgress()
                val catalog = repository.loadHome(progress)
                state = state.copy(phase = LoadPhase.LOADED, catalog = catalog)
                selectCollection(CollectionFilter.All)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = state.copy(
                    phase = LoadPhase.FAILED,
                    errorMessage = error.message ?: strings.archiveNotLoaded,
                )
            }
        }
    }

    fun selectCollection(filter: CollectionFilter) {
        collectionJob?.cancel()
        state = state.copy(selectedCollection = filter, isLoadingCollection = true)
        collectionJob = viewModelScope.launch {
            try {
                val items = repository.loadCollection(filter)
                state = state.copy(collectionItems = items, isLoadingCollection = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = state.copy(
                    isLoadingCollection = false,
                    errorMessage = error.message,
                )
            }
        }
    }

    fun openDetail(ref: MediaRef) {
        closeFilmography()
        detailJob?.cancel()
        detailState = DetailUiState(ref = ref, isLoading = true)
        detailJob = viewModelScope.launch {
            try {
                val detail = repository.detail(ref)
                val userMedia = dataStore.getUserMedia(ref)
                val watchProgress = dataStore.getWatchProgress("${ref.mediaType.pathSegment}:${ref.id}")
                detailState = DetailUiState(
                    ref = ref,
                    detail = detail,
                    userMedia = userMedia,
                    watchProgress = watchProgress,
                    // Shows open on their first season; the episode list itself
                    // is fetched lazily by selectSeason below.
                    selectedSeason = detail.seasons.firstOrNull()?.seasonNumber,
                    watchedEpisodeIds = watchedEpisodeIds(),
                )
                detail.seasons.firstOrNull()?.let { selectSeason(it.seasonNumber) }

                val trailer = repository.bestTrailer(ref)
                if (detailState.ref == ref) {
                    detailState = detailState.copy(trailer = trailer)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                detailState = DetailUiState(
                    ref = ref,
                    errorMessage = error.message ?: strings.detailsNotLoaded,
                )
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        seasonJob?.cancel()
        detailState = DetailUiState()
    }

    /**
     * Shows the given season, fetching its episode list once and caching it —
     * parity with Apple's `TMDBSeasonBrowser`, which also never pays for
     * seasons nobody opens.
     */
    fun selectSeason(seasonNumber: Int) {
        val ref = detailState.ref ?: return
        detailState = detailState.copy(selectedSeason = seasonNumber, seasonErrorMessage = null)
        if (detailState.episodesBySeason.containsKey(seasonNumber)) return

        seasonJob?.cancel()
        detailState = detailState.copy(loadingSeason = seasonNumber)
        seasonJob = viewModelScope.launch {
            try {
                val season = repository.season(ref.id, seasonNumber)
                // The user may have moved on while this was in flight.
                if (detailState.ref != ref) return@launch
                detailState = detailState.copy(
                    episodesBySeason = detailState.episodesBySeason + (seasonNumber to season.episodes),
                    loadingSeason = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (detailState.ref != ref) return@launch
                detailState = detailState.copy(
                    loadingSeason = null,
                    seasonErrorMessage = error.message ?: strings.seasonNotLoaded,
                )
            }
        }
    }

    /**
     * TMDB episodes have no local file behind them, so the browser toggles
     * watch state instead of playing — matching Apple.
     */
    fun toggleEpisodeWatched(episode: com.babasama.edendale.tmdb.TmdbEpisodeDetail) {
        val showId = detailState.ref?.id
        viewModelScope.launch {
            val progress = WatchProgress(
                tmdbId = episode.id,
                mediaType = com.babasama.edendale.domain.WatchMediaType.EPISODE,
                position = 1.0,
                lastWatchedEpochMillis = System.currentTimeMillis(),
                isCompleted = true,
                showTmdbId = showId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
            )
            if (episode.id in detailState.watchedEpisodeIds) {
                dataStore.deleteWatchProgress(progress.storageKey)
            } else {
                dataStore.updateWatchProgress(progress)
            }
            detailState = detailState.copy(watchedEpisodeIds = watchedEpisodeIds())
        }
    }

    private suspend fun watchedEpisodeIds(): Set<Int> =
        dataStore.getAllWatchProgress()
            .filter { it.mediaType == com.babasama.edendale.domain.WatchMediaType.EPISODE && it.isCompleted }
            .map { it.tmdbId }
            .toSet()

    fun toggleFavourite(ref: MediaRef) {
        viewModelScope.launch {
            val isFavourite = detailState.userMedia?.favourite == true
            dataStore.setFavourite(ref, !isFavourite)
            detailState = detailState.copy(userMedia = dataStore.getUserMedia(ref))
        }
    }

    fun toggleWatchlist(ref: MediaRef) {
        viewModelScope.launch {
            val inWatchlist = detailState.userMedia?.watchlist == true
            dataStore.setWatchlist(ref, !inWatchlist)
            detailState = detailState.copy(userMedia = dataStore.getUserMedia(ref))
        }
    }

    /**
     * Mark Watched for movies: writes a completed [WatchProgress], or
     * clears it again. Shows are marked per episode, so this is movie-only.
     */
    fun toggleWatched(ref: MediaRef) {
        viewModelScope.launch {
            val key = "movie:${ref.id}"
            if (detailState.watchProgress?.isCompleted == true) {
                dataStore.deleteWatchProgress(key)
            } else {
                dataStore.updateWatchProgress(
                    WatchProgress(
                        tmdbId = ref.id,
                        mediaType = com.babasama.edendale.domain.WatchMediaType.MOVIE,
                        position = 1.0,
                        lastWatchedEpochMillis = System.currentTimeMillis(),
                        isCompleted = true,
                    ),
                )
            }
            detailState = detailState.copy(watchProgress = dataStore.getWatchProgress(key))
        }
    }

    fun setRating(ref: MediaRef, rating: Double?) {
        viewModelScope.launch {
            dataStore.setRating(ref, rating)
            detailState = detailState.copy(userMedia = dataStore.getUserMedia(ref))
        }
    }

    suspend fun getLocalUri(tmdbId: Int): String? {
        return libraryRepo.localUriFor(tmdbId)
    }

    var filmographyState by mutableStateOf(FilmographyUiState())
        private set
    private var filmographyJob: Job? = null

    /**
     * Opens the person page. Biography and filmography are separate TMDB
     * endpoints, so they run concurrently and whichever lands first is
     * published immediately (same incremental pattern as [openDetail]).
     */
    fun openFilmography(personId: Int, personName: String) {
        filmographyJob?.cancel()
        filmographyState = FilmographyUiState(personId = personId, personName = personName, isLoading = true)
        filmographyJob = viewModelScope.launch {
            val detailRequest = async {
                runCatching { repository.personDetail(personId) }.getOrNull()
            }
            try {
                val items = repository.filmography(personId)
                if (filmographyState.personId != personId) return@launch
                filmographyState = filmographyState.copy(items = items, isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                filmographyState = filmographyState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: strings.filmographyFailed,
                )
            }

            val detail = detailRequest.await()
            if (detail != null && filmographyState.personId == personId) {
                // A biography arriving after a failed filmography still gives
                // the page something to show.
                filmographyState = filmographyState.copy(
                    detail = detail,
                    personName = detail.name,
                    errorMessage = filmographyState.errorMessage.takeIf { filmographyState.items.isEmpty() },
                )
            }
        }
    }

    fun closeFilmography() {
        filmographyJob?.cancel()
        filmographyState = FilmographyUiState()
    }
}

data class FilmographyUiState(
    val personId: Int? = null,
    val personName: String? = null,
    /** Biography header; null until `/person/{id}` lands (or if it fails). */
    val detail: PersonDetail? = null,
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    /** People matching the query — a real result section, not just suggestions. */
    val people: List<PersonItem> = emptyList(),
    /** What the query's keyword prefix scopes the search to. */
    val scope: SearchScope = SearchScope.ALL,
    val errorMessage: String? = null,
    val activePerson: PersonItem? = null,
    val selectedRange: Pair<String, String>? = null,
    val suggestionPeople: List<PersonItem> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val heatmapCache: Map<Int, Map<String, Int>> = emptyMap(),
    /** Idle grid: today's trending titles, fetched once per session. */
    val trending: List<MediaItem> = emptyList(),
    val isLoadingTrending: Boolean = false,
) {
    /** True when nothing is being searched for and the trending grid leads. */
    val isIdle: Boolean
        get() = activePerson == null && query.isBlank() && selectedRange == null

    /** A prefix typed with nothing after it yet ("actors:"). */
    val isAwaitingTerm: Boolean
        get() = scope != SearchScope.ALL && SearchQuery.parse(query).term.isEmpty()
}

class SearchViewModel(
    application: Application,
    private val repository: BrowseRepository = AndroidEdendaleCore.browseRepository(),
) : AndroidViewModel(application) {
    private val strings = AppStrings(application)

    var state by mutableStateOf(SearchUiState())
        private set

    val isConfigured: Boolean get() = repository.isConfigured

    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    fun updateQuery(query: String) {
        state = state.copy(
            query = query,
            activePerson = null,
            scope = SearchQuery.parse(query).scope,
        )
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            executeSearch()
        }
    }

    /** Strips a keyword prefix, keeping whatever was typed after it. */
    fun clearScope() {
        val parsed = SearchQuery.parse(state.query)
        if (parsed.scope == SearchScope.ALL) return
        updateQuery(parsed.term)
    }

    /** Fills the idle trending grid, once per session. */
    fun loadTrendingIfNeeded() {
        if (state.trending.isNotEmpty() || state.isLoadingTrending || !isConfigured) return
        state = state.copy(isLoadingTrending = true)
        viewModelScope.launch {
            try {
                state = state.copy(trending = repository.trending())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The idle grid is a nicety; a failure just falls back to the
                // prompt state rather than shouting at the user.
            } finally {
                state = state.copy(isLoadingTrending = false)
            }
        }
    }

    fun clearRange() {
        state = state.copy(selectedRange = null)
        executeSearch()
    }

    fun setRange(from: String, to: String) {
        state = state.copy(selectedRange = from to to)
        executeSearch()
    }

    fun clearPerson() {
        state = state.copy(activePerson = null)
        executeSearch()
    }

    fun selectPerson(person: com.babasama.edendale.domain.PersonItem) {
        val newRecent = (listOf(person.name) + state.recentSearches).distinct().take(5)
        state = state.copy(activePerson = person, recentSearches = newRecent, suggestionPeople = emptyList())
        executeSearch()
    }

    fun submitSearch(query: String) {
        if (query.isNotBlank()) {
            val newRecent = (listOf(query) + state.recentSearches).distinct().take(5)
            state = state.copy(recentSearches = newRecent)
        }
        executeSearch()
    }

    fun loadHeatmap(year: Int) {
        if (state.heatmapCache.containsKey(year)) return
        viewModelScope.launch {
            try {
                val counts = repository.releaseCounts(year)
                state = state.copy(heatmapCache = state.heatmapCache + (year to counts))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { /* ignore */ }
        }
    }

    private fun executeSearch() {
        if (!isConfigured) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val parsed = SearchQuery.parse(state.query)

            // A prefix with nothing after it shows that scope's prompt rather
            // than firing a request for an empty term.
            if (parsed.isAwaitingTerm && state.selectedRange == null && state.activePerson == null) {
                state = state.copy(
                    isSearching = false,
                    hasSearched = false,
                    results = emptyList(),
                    people = emptyList(),
                    suggestionPeople = emptyList(),
                )
                return@launch
            }

            state = state.copy(isSearching = true, errorMessage = null)
            try {
                val results: List<MediaItem>
                var people: List<PersonItem> = emptyList()

                if (state.activePerson != null) {
                    results = repository.filmography(state.activePerson!!.id)
                } else if (parsed.term.isNotBlank()) {
                    val scoped = repository.searchScoped(state.query)
                    results = scoped.titles
                    people = scoped.people
                } else if (state.selectedRange != null) {
                    val (from, to) = state.selectedRange!!
                    results = repository.moviesReleased(from, to)
                } else {
                    state = state.copy(
                        isSearching = false,
                        hasSearched = false,
                        results = emptyList(),
                        people = emptyList(),
                        suggestionPeople = emptyList(),
                    )
                    return@launch
                }

                val finalResults = if (state.selectedRange != null && (state.activePerson != null || parsed.term.isNotBlank())) {
                    val (from, to) = state.selectedRange!!
                    results.filter {
                        val rDate = it.releaseDate
                        rDate != null && rDate >= from && rDate <= to
                    }
                } else {
                    results
                }

                state = state.copy(
                    isSearching = false,
                    hasSearched = true,
                    results = finalResults,
                    people = people,
                    // The expanded bar's shortcut list mirrors the people
                    // results, so one lookup feeds both.
                    suggestionPeople = people,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = state.copy(
                    isSearching = false,
                    hasSearched = true,
                    errorMessage = error.message ?: strings.searchFailed,
                )
            }
        }
    }

    fun search() = executeSearch()
}
