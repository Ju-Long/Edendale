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
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.PersonDetail
import com.babasama.edendale.domain.PersonItem
import com.babasama.edendale.domain.SearchQuery
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.UserMediaRecord
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.data.LocalCopy
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.parental.awaitReady
import com.babasama.edendale.android.parental.parentalControls
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.parental.ParentalGateRules
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
    /** The open page; null while none is. */
    val source: DetailSource? = null,
    /**
     * The TMDB title behind the page: a TMDB page's own, a library movie's
     * match, or null for a file enrichment hasn't matched.
     */
    val ref: MediaRef? = null,
    val isLoading: Boolean = false,
    val detail: MediaDetail? = null,
    val userMedia: UserMediaRecord? = null,
    /**
     * True once [userMedia] has been read for [ref]. A null [userMedia] is
     * also what a title without marks reads as, and the favourite and
     * Watchlist toggles flip what the page shows, so the page shows the
     * user's marks only from then on.
     */
    val userMediaLoaded: Boolean = false,
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
    /**
     * The library movie's title and poster, which a Watchlist save records
     * when TMDB has no record for the page (no key, offline, or no match).
     */
    val fallbackTitle: String? = null,
    val fallbackPosterPath: String? = null,
) {
    val isOpen: Boolean get() = source != null
}

class BrowseViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val repository: BrowseRepository = AndroidEdendaleCore.browseRepository()
    private val strings = AppStrings(application)
    private val dataStore = LocalDataStore((application as EdendaleApplication).database)
    private val libraryRepo = (application as EdendaleApplication).libraryRepository
    private val parental = parentalControls(application)

    var state by mutableStateOf(BrowseUiState())
        private set

    var detailState by mutableStateOf(DetailUiState())
        private set

    private var detailJob: Job? = null
    private var collectionJob: Job? = null
    private var seasonJob: Job? = null

    /**
     * The latest watch rows. A deletion can land while [load] waits on TMDB
     * (a launch sync applying another device's clear), before there is a
     * catalog to strip, so [load] applies them to the catalog it assigns.
     */
    private var watchRows: List<WatchProgress>? = null

    init {
        load()
        // The hero's progress is a snapshot: once its record is deleted, it stops offering to resume.
        viewModelScope.launch {
            dataStore.observeWatchProgress().collect { rows ->
                watchRows = rows
                val catalog = state.catalog ?: return@collect
                val kept = catalog.keepingProgressIn(rows)
                if (kept !== catalog) state = state.copy(catalog = kept)
            }
        }
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
                val catalog = repository.loadHome(progress).let { loaded -> watchRows?.let(loaded::keepingProgressIn) ?: loaded }
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
        val source = DetailSource.Tmdb(ref)
        detailState = DetailUiState(source = source, ref = ref, isLoading = true)
        detailJob = viewModelScope.launch {
            try {
                // The user's own marks are local, so they come first: a page
                // the library draws meanwhile (or after TMDB fails) shows them
                // right, and a toggle flips what the page shows.
                val userMedia = dataStore.getUserMedia(ref)
                detailState = detailState.copy(userMedia = userMedia, userMediaLoaded = true)
                val detail = repository.detail(ref)
                // Copied rather than rebuilt, so the marks above and a library
                // match's title and poster (syncLibraryMovie) outlive the load.
                detailState = detailState.copy(
                    isLoading = false,
                    detail = detail,
                    // Shows open on their first season; the episode list itself
                    // is fetched lazily by selectSeason below.
                    selectedSeason = detail.seasons.firstOrNull()?.seasonNumber,
                    watchedEpisodeIds = watchedEpisodeIds(),
                )
                detail.seasons.firstOrNull()?.let { selectSeason(it.seasonNumber) }

                // O-D10: with Block trailers and links on, /videos is never requested.
                parental.awaitReady()
                val trailer = if (ParentalGateRules.allowsTrailers(parental.policy.value)) repository.bestTrailer(ref) else null
                if (detailState.ref == ref) {
                    detailState = detailState.copy(trailer = trailer)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Copied, so the user's marks and the library match stay: a
                // page the library holds keeps drawing from its copy, and a
                // trailer that failed after the record landed leaves it be.
                if (detailState.source == source) {
                    detailState = detailState.copy(
                        isLoading = false,
                        errorMessage = error.message ?: strings.detailsNotLoaded,
                    )
                }
            }
        }
    }

    /**
     * Opens a library movie's page (Apple's `MediaDetailSource.localMovie`).
     * The page shows the row at once; its TMDB match, when it has one, adds
     * the user's marks and, when TMDB answers, the full record ([loadMatch]).
     */
    fun openLocalMovie(movie: LibraryMovieEntity) {
        closeFilmography()
        detailJob?.cancel()
        seasonJob?.cancel()
        detailState = DetailUiState(source = DetailSource.LocalMovie(movie.uri))
        syncLibraryMovie(movie)
    }

    /**
     * Opens a library show's page (Apple's `MediaDetailSource.localShow`):
     * its episodes from the library at once, and, once the show is matched,
     * the user's marks and TMDB's record ([syncLibraryShow]).
     */
    fun openLocalShow(show: LibraryShowEntity) {
        closeFilmography()
        detailJob?.cancel()
        seasonJob?.cancel()
        detailState = DetailUiState(source = DetailSource.LocalShow(show.key))
        syncLibraryShow(show)
    }

    /**
     * [syncLibraryMovie] for a show: keeps an open page in step with the
     * library show behind it — the page's own row, or a TMDB page's library
     * match — and attaches a match enrichment finds while a library page is
     * open (or the row of a page restored after process death).
     */
    fun syncLibraryShow(show: LibraryShowEntity) {
        val state = detailState
        val ref = show.tmdbId?.let { MediaRef(it, MediaType.TV) }
        val ownRow = state.source == DetailSource.LocalShow(show.key)
        val match = state.source is DetailSource.Tmdb && ref != null && state.ref == ref
        if (!ownRow && !match) return
        if (state.fallbackTitle != show.name || state.fallbackPosterPath != show.posterPath) {
            detailState = state.copy(fallbackTitle = show.name, fallbackPosterPath = show.posterPath)
        }
        if (!ownRow || ref == null || ref == detailState.ref) return
        detailJob?.cancel()
        detailState = detailState.copy(ref = ref, detail = null, userMedia = null, userMediaLoaded = false, trailer = null)
        detailJob = viewModelScope.launch { loadMatch(ref) }
    }

    /**
     * Keeps an open page in step with the library movie behind it — the
     * page's own row, or a TMDB page's library match. Its title and poster
     * are what a Watchlist save records without a TMDB record. On a library
     * page, a match enrichment finds while the page is open (or the row of a
     * page restored after process death, which starts from its uri) attaches
     * the TMDB title and loads its half of the page.
     */
    fun syncLibraryMovie(movie: LibraryMovieEntity) {
        val state = detailState
        val ref = movie.tmdbRef
        val ownRow = state.source == DetailSource.LocalMovie(movie.uri)
        val match = state.source is DetailSource.Tmdb && ref != null && state.ref == ref
        if (!ownRow && !match) return
        if (state.fallbackTitle != movie.title || state.fallbackPosterPath != movie.posterPath) {
            detailState = state.copy(fallbackTitle = movie.title, fallbackPosterPath = movie.posterPath)
        }
        if (!ownRow || ref == null || ref == detailState.ref) return
        detailJob?.cancel()
        detailState = detailState.copy(ref = ref, detail = null, userMedia = null, userMediaLoaded = false, trailer = null)
        detailJob = viewModelScope.launch { loadMatch(ref) }
    }

    /**
     * A library page's TMDB half for its match [ref]: the user's own marks
     * always, TMDB's record and trailer when TMDB answers. A failed fetch
     * (offline, or no TMDB key) leaves the page on the row's own header and
     * never turns it into an error page.
     */
    private suspend fun loadMatch(ref: MediaRef) {
        val userMedia = dataStore.getUserMedia(ref)
        if (detailState.ref != ref) return
        detailState = detailState.copy(userMedia = userMedia, userMediaLoaded = true)
        if (!repository.isConfigured) return
        val detail = try {
            repository.detail(ref)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        if (detailState.ref != ref) return
        detailState = detailState.copy(detail = detail)

        // O-D10: with Block trailers and links on, /videos is never requested.
        parental.awaitReady()
        if (!ParentalGateRules.allowsTrailers(parental.policy.value)) return
        val trailer = try {
            repository.bestTrailer(ref)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (detailState.ref == ref) detailState = detailState.copy(trailer = trailer)
    }

    /**
     * Reopens the page a process death closed: a TMDB page loads again, and
     * a library page starts from its uri or key until the screen hands it
     * the row ([syncLibraryMovie], [syncLibraryShow]). A page that is already
     * open stays as it is.
     */
    fun restoreDetail(source: DetailSource) {
        if (detailState.isOpen) return
        when (source) {
            is DetailSource.Tmdb -> openDetail(source.ref)
            is DetailSource.LocalMovie, is DetailSource.LocalShow -> detailState = DetailUiState(source = source)
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
            refreshUserMedia(ref)
        }
    }

    /** Reads [ref]'s marks back into the page, unless another title opened meanwhile. */
    private suspend fun refreshUserMedia(ref: MediaRef) {
        val userMedia = dataStore.getUserMedia(ref)
        if (detailState.ref == ref) detailState = detailState.copy(userMedia = userMedia, userMediaLoaded = true)
    }

    fun toggleWatchlist(ref: MediaRef) {
        viewModelScope.launch {
            val inWatchlist = detailState.userMedia?.watchlist == true
            // Snapshot the title and poster so the Watchlist tab can render the
            // card without a per-title fetch, and it stays useful offline. A
            // library page without a TMDB record snapshots its row's.
            dataStore.setWatchlist(
                ref,
                !inWatchlist,
                title = detailState.detail?.title ?: detailState.fallbackTitle,
                posterPath = detailState.detail?.posterPath ?: detailState.fallbackPosterPath,
            )
            refreshUserMedia(ref)
        }
    }

    /**
     * Mark Watched for movies: writes a completed [WatchProgress], or
     * clears it again. Shows are marked per episode, so this is movie-only.
     * [isWatched] is the page's live watch state: the player may have
     * finished the title since the page opened. The page observes the
     * record, so nothing is copied back here.
     */
    fun toggleWatched(ref: MediaRef, isWatched: Boolean) {
        viewModelScope.launch {
            val key = "movie:${ref.id}"
            if (isWatched) {
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
        }
    }

    fun setRating(ref: MediaRef, rating: Double?) {
        viewModelScope.launch {
            dataStore.setRating(ref, rating)
            refreshUserMedia(ref)
        }
    }

    suspend fun getLocalCopies(tmdbId: Int): List<LocalCopy> = libraryRepo.localCopiesFor(tmdbId)

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

// The repository is built in the body rather than taken as a defaulted
// constructor parameter: AndroidViewModelFactory looks up <init>(Application) by
// reflection, and a default argument makes Kotlin emit only the two-arg and
// synthetic overloads, so viewModel() dies with NoSuchMethodException on launch.
class SearchViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val repository: BrowseRepository = AndroidEdendaleCore.browseRepository()
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
