package com.babasama.edendale.android

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.PersonItem
import com.babasama.edendale.domain.SearchQuery
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    audienceFilter: YoungAudienceFilter,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
    onOpenPerson: (Int, String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit,
    onOpenShow: (String) -> Unit = {},
) {
    val state = viewModel.state
    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp

    var expanded by rememberSaveable { mutableStateOf(false) }
    var showHeatmap by rememberSaveable { mutableStateOf(false) }
    // TV only: the field stays an inert focus target until the remote's select
    // button opens it. A live text field takes IME focus the moment D-pad Down
    // lands on it and then swallows every further Down, which walled the results
    // off from the remote entirely.
    var tvSearchActive by rememberSaveable { mutableStateOf(false) }

    // Local matches are on-device title contains, so they land before the first
    // TMDB response. Person filmography and the release heatmap stay TMDB-only.
    val library = rememberLibrary()
    val localMovies by library.movies.collectAsState(initial = emptyList())
    val localShows by library.shows.collectAsState(initial = emptyList())
    val localProgress by library.watchProgress.collectAsState(initial = emptyList())
    val localTerm = SearchQuery.parse(state.query).term.trim()
    val localMatches = remember(localTerm, state.scope, state.activePerson, localMovies, localShows) {
        localMatches(
            term = localTerm,
            scope = state.scope,
            hasActivePerson = state.activePerson != null,
            movies = localMovies,
            shows = localShows,
        )
    }
    val localProgressByKey = remember(localProgress) { localProgress.byStorageKey() }

    // Young Audience filter: hide anything not verified as PG / PG-13. Local
    // matches without a TMDB id cannot be verified, so they fail closed too.
    val audienceRefs = remember(state.results, state.trending, localMatches) {
        (state.results.map { it.ref } +
            state.trending.map { it.ref } +
            localMatches.movies.mapNotNull { m -> m.tmdbId?.let { MediaRef(it, MediaType.MOVIE) } } +
            localMatches.shows.mapNotNull { s -> s.tmdbId?.let { MediaRef(it, MediaType.TV) } })
            .distinct()
    }
    LaunchedEffect(audienceRefs, audienceFilter.isEnabled, audienceFilter.contextIdentifier) {
        audienceFilter.verify(audienceRefs)
    }
    val visibleResults = audienceFilter.visible(state.results)
    val visibleTrending = audienceFilter.visible(state.trending)
    val visibleLocal = if (!audienceFilter.isEnabled) {
        localMatches
    } else {
        LocalMatches(
            movies = localMatches.movies.filter {
                it.tmdbId?.let { id -> audienceFilter.allows(MediaRef(id, MediaType.MOVIE)) } == true
            },
            shows = localMatches.shows.filter {
                it.tmdbId?.let { id -> audienceFilter.allows(MediaRef(id, MediaType.TV)) } == true
            },
        )
    }
    val audienceVerifying = audienceFilter.isVerifying(audienceRefs)

    LaunchedEffect(Unit) { viewModel.loadTrendingIfNeeded() }

    if (showHeatmap) {
        ReleaseHeatmapSheet(
            heatmapCache = state.heatmapCache,
            onLoadHeatmap = { viewModel.loadHeatmap(it) },
            selectedRange = state.selectedRange,
            onRangeSelected = { start, end ->
                viewModel.setRange(start, end)
                showHeatmap = false
            },
            onDismiss = { showHeatmap = false },
            isTelevision = isTelevision,
        )
    }

    if (!viewModel.isConfigured) {
        ArchiveEmptyState(
            icon = {
                Icon(
                    painterResource(id = R.drawable.ic_circle_xmark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            },
            title = stringResource(R.string.search_offline_title),
            message = stringResource(R.string.search_offline_message),
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        val collapsedFieldFocus = remember { FocusRequester() }
        val inputFieldFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // Set as editing ends so focus returns to the collapsed field, rather
        // than being dropped and leaving the remote with nothing selected.
        var restoreCollapsedFocus by remember { mutableStateOf(false) }
        val closeTvSearch: () -> Unit = {
            expanded = false
            tvSearchActive = false
            restoreCollapsedFocus = true
            keyboard?.hide()
        }

        if (isTelevision && tvSearchActive) {
            // Opening is the one moment the keyboard is wanted, so it is raised
            // explicitly instead of relying on focus to summon it.
            LaunchedEffect(Unit) {
                inputFieldFocus.requestFocus()
                keyboard?.show()
            }
            BackHandler(onBack = closeTvSearch)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = contentPadding.calculateTopPadding(), start = edgeMargin, end = edgeMargin)
        ) {
            if (isTelevision && !tvSearchActive) {
                LaunchedEffect(restoreCollapsedFocus) {
                    if (restoreCollapsedFocus) {
                        restoreCollapsedFocus = false
                        collapsedFieldFocus.requestFocus()
                    }
                }
                TvCollapsedSearchField(
                    query = state.query,
                    modifier = Modifier.focusRequester(collapsedFieldFocus),
                    onActivate = { tvSearchActive = true },
                )
            } else {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = state.query,
                            onQueryChange = { viewModel.updateQuery(it) },
                            onSearch = {
                                expanded = false
                                viewModel.submitSearch(it)
                                if (isTelevision) closeTvSearch()
                            },
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = if (isTelevision) Modifier.focusRequester(inputFieldFocus) else Modifier,
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.query.isNotEmpty()) {
                                        ArchiveIconButton(
                                            onClick = { viewModel.updateQuery("") },
                                            isTelevision = isTelevision,
                                        ) { _ ->
                                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear_query))
                                        }
                                    }
                                    if (!isTelevision && !expanded) {
                                        IconButton(onClick = onOpenSettings) {
                                            Icon(painterResource(id = R.drawable.ic_gear_complex), contentDescription = stringResource(R.string.action_settings))
                                        }
                                    }
                                }
                            }
                        )
                    },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.suggestionPeople.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(state.suggestionPeople.size) { index ->
                                val person = state.suggestionPeople[index]
                                SuggestionRow(
                                    label = person.name,
                                    icon = Icons.Default.Person,
                                    onClick = {
                                        expanded = false
                                        if (isTelevision) closeTvSearch()
                                        viewModel.selectPerson(person)
                                    },
                                )
                            }
                        }
                    } else if (state.recentSearches.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(state.recentSearches.size) { index ->
                                val recent = state.recentSearches[index]
                                SuggestionRow(
                                    label = recent,
                                    icon = Icons.Default.History,
                                    onClick = {
                                        expanded = false
                                        if (isTelevision) closeTvSearch()
                                        viewModel.updateQuery(recent)
                                        viewModel.submitSearch(recent)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeMargin, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ArchiveFilterChip(
                selected = state.selectedRange != null,
                onClick = { showHeatmap = true },
                label = {
                    if (state.selectedRange != null) {
                        Text(stringResource(R.string.search_date_range, state.selectedRange.first, state.selectedRange.second))
                    } else {
                        Text(stringResource(R.string.search_any_date))
                    }
                },
                isTelevision = isTelevision,
                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                trailingIcon = {
                    if (state.selectedRange != null) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.search_clear_date),
                            modifier = Modifier.clickable { viewModel.clearRange() }
                        )
                    }
                }
            )

            if (state.scope != SearchScope.ALL) {
                ArchiveFilterChip(
                    selected = true,
                    onClick = { viewModel.clearScope() },
                    label = { Text(stringResource(state.scope.labelRes())) },
                    isTelevision = isTelevision,
                    trailingIcon = { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear_scope)) }
                )
            }

            if (state.activePerson != null) {
                // The label opens that person's page, so the filter chip is
                // not a dead end; the trailing ✕ clears the filter.
                ArchiveFilterChip(
                    selected = true,
                    onClick = { onOpenPerson(state.activePerson.id, state.activePerson.name) },
                    label = { Text(state.activePerson.name) },
                    isTelevision = isTelevision,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.search_clear_person),
                            modifier = Modifier.clickable { viewModel.clearPerson() },
                        )
                    }
                )
            }
        }

        // Resolved out here: the LazyListScope builders below are not composable,
        // so they cannot reach stringResource themselves.
        val tmdbHeader = stringResource(
            if (visibleLocal.isEmpty) R.string.search_section_results else R.string.search_section_tmdb,
        )
        val alsoInTitlesHeader = stringResource(R.string.search_section_also_titles)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 56.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            state.errorMessage?.let { message ->
                item("error") {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = edgeMargin),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Section builders, so a people-scoped query can lead with people
            // and still show titles underneath.
            fun LazyListScope.librarySection() {
                if (visibleLocal.isEmpty) return
                item("library-header") {
                    SectionHeader(
                        title = stringResource(R.string.search_section_library),
                        modifier = Modifier.padding(horizontal = edgeMargin),
                        large = isTelevision,
                    )
                }
                item("library") {
                    LibraryResultsGrid(
                        matches = visibleLocal,
                        progressByKey = localProgressByKey,
                        edgeMargin = edgeMargin,
                        isTelevision = isTelevision,
                        onOpenShow = onOpenShow,
                    )
                }
            }

            fun LazyListScope.titleSection(header: String) {
                if (visibleResults.isEmpty()) return
                item("titles-header") {
                    SectionHeader(
                        title = header,
                        modifier = Modifier.padding(horizontal = edgeMargin),
                        large = isTelevision,
                    )
                }
                item("titles") {
                    SearchResultsGrid(
                        items = visibleResults,
                        edgeMargin = edgeMargin,
                        isTelevision = isTelevision,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }

            fun LazyListScope.peopleSection() {
                if (state.people.isEmpty()) return
                item("people-header") {
                    SectionHeader(
                        title = stringResource(R.string.search_section_people),
                        modifier = Modifier.padding(horizontal = edgeMargin),
                        large = isTelevision,
                    )
                }
                item("people") {
                    PeopleGrid(
                        people = state.people,
                        edgeMargin = edgeMargin,
                        isTelevision = isTelevision,
                        onOpenPerson = onOpenPerson,
                    )
                }
            }

            // Local hits render as soon as the query changes, whatever TMDB is
            // doing; the network section labels itself once both are present.
            librarySection()

            when {
                state.isAwaitingTerm -> item("scope-prompt") {
                    ArchiveEmptyState(
                        icon = {
                            Icon(
                                painterResource(id = scopePromptIcon(state.scope)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        },
                        title = stringResource(scopePromptTitle(state.scope)),
                        message = stringResource(scopePromptMessage(state.scope)),
                        modifier = Modifier.height(if (isTelevision) 360.dp else 300.dp),
                    )
                }
                (state.isSearching || audienceVerifying) &&
                    visibleResults.isEmpty() &&
                    state.people.isEmpty() &&
                    visibleLocal.isEmpty -> item("loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                state.hasSearched &&
                    !audienceVerifying &&
                    visibleResults.isEmpty() &&
                    state.people.isEmpty() &&
                    visibleLocal.isEmpty -> item("empty") {
                    ArchiveEmptyState(
                        icon = { Icon(painterResource(id = R.drawable.ic_magnifying_glass_play), contentDescription = null) },
                        title = stringResource(R.string.search_empty_title),
                        message = stringResource(R.string.search_empty_message),
                        modifier = Modifier.height(320.dp),
                    )
                }
                visibleResults.isNotEmpty() || state.people.isNotEmpty() -> {
                    if (state.scope == SearchScope.PEOPLE) {
                        // A people prefix reorders — people lead, titles follow.
                        peopleSection()
                        titleSection(alsoInTitlesHeader)
                    } else {
                        titleSection(tmdbHeader)
                        peopleSection()
                    }
                }
                // Local hits already fill the screen while TMDB is still working
                // or came back with nothing.
                !visibleLocal.isEmpty -> Unit
                // Nothing searched for yet: browse today's trending titles
                // rather than staring at a placeholder.
                visibleTrending.isNotEmpty() -> {
                    item("trending-header") {
                        SectionHeader(
                            title = stringResource(R.string.search_section_trending_today),
                            modifier = Modifier.padding(horizontal = edgeMargin),
                            large = isTelevision,
                        )
                    }
                    item("trending") {
                        SearchResultsGrid(
                            items = visibleTrending,
                            edgeMargin = edgeMargin,
                            isTelevision = isTelevision,
                            onOpenDetail = onOpenDetail,
                        )
                    }
                }
                state.isLoadingTrending -> item("trending-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                else -> item("prompt") {
                    ArchiveEmptyState(
                        icon = {
                            Icon(
                                painterResource(id = R.drawable.ic_magnifying_glass_play),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        },
                        title = stringResource(R.string.search_prompt_title),
                        message = stringResource(R.string.search_prompt_message),
                        modifier = Modifier.height(if (isTelevision) 360.dp else 300.dp),
                    )
                }
            }
        }
    }
}

/**
 * One suggestion under the search field. A stack of near-identical rows is
 * where a focus ring is least legible, so the focused row takes the fill.
 */
@Composable
private fun SuggestionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        ),
        colors = ListItemDefaults.colors(
            containerColor = if (focused) EdendaleColors.Gold else Color.Transparent,
            headlineColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
            leadingIconColor = if (focused) EdendaleColors.OnGold
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * The resting state of the TV search field: it looks like the real input bar but
 * is an ordinary focus target, so Down carries on into the results. Select swaps
 * in the live field and raises the keyboard.
 */
@Composable
private fun TvCollapsedSearchField(
    query: String,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onActivate,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .tvFocusLift(true),
        shape = RoundedCornerShape(28.dp),
        color = if (focused) EdendaleColors.Gold else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Text(
                text = query.ifEmpty { stringResource(R.string.search_placeholder) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * "From Your Library": movies play straight away, shows open the local drill-in.
 * Neither needs a TMDB id, so unmatched imports still appear here.
 */
@Composable
private fun LibraryResultsGrid(
    matches: LocalMatches,
    progressByKey: Map<String, WatchProgress>,
    edgeMargin: Dp,
    isTelevision: Boolean,
    onOpenShow: (String) -> Unit,
) {
    val context = LocalContext.current
    val runtimeFormat = rememberRuntimeFormat()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = edgeMargin)) {
        val spacing = if (isTelevision) 24.dp else 16.dp
        val minimumWidth = if (isTelevision) 180.dp else 145.dp
        val columnCount = ((maxWidth + spacing) / (minimumWidth + spacing)).toInt().coerceAtLeast(2)
        val cardWidth = (maxWidth - spacing * (columnCount - 1)) / columnCount
        val cells: List<Any> = matches.movies + matches.shows

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            cells.chunked(columnCount).forEach { rowCells ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowCells.forEach { cell ->
                        when (cell) {
                            is LibraryMovieEntity -> {
                                val progress = progressByKey.forMovie(cell.tmdbId)
                                LibraryPosterCard(
                                    title = cell.title,
                                    subtitle = mediaSubtitle(cell.year, cell.runtimeMinutes, runtimeFormat),
                                    posterUrl = tmdbImageUrl(cell.posterPath, TmdbImageSize.POSTER),
                                    width = cardWidth,
                                    isTelevision = isTelevision,
                                    isWatched = progress?.isCompleted == true,
                                    progress = progress.partialFraction(),
                                    mediaType = MediaType.MOVIE,
                                    onClick = {
                                        PlayerActivity.play(
                                            context = context,
                                            uri = cell.uri,
                                            title = cell.title,
                                            tmdbId = cell.tmdbId,
                                            isEpisode = false,
                                        )
                                    },
                                )
                            }
                            is LibraryShowEntity -> LibraryPosterCard(
                                title = cell.name,
                                subtitle = cell.firstAirYear?.toString(),
                                posterUrl = tmdbImageUrl(cell.posterPath, TmdbImageSize.POSTER),
                                width = cardWidth,
                                isTelevision = isTelevision,
                                mediaType = MediaType.TV,
                                onClick = { onOpenShow(cell.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Square-portrait cards, so they pack tighter than the poster grid. */
@Composable
private fun PeopleGrid(
    people: List<PersonItem>,
    edgeMargin: Dp,
    isTelevision: Boolean,
    onOpenPerson: (Int, String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = edgeMargin)) {
        val spacing = if (isTelevision) 24.dp else 16.dp
        val minimumWidth = if (isTelevision) 140.dp else 105.dp
        val columnCount = ((maxWidth + spacing) / (minimumWidth + spacing)).toInt().coerceAtLeast(2)
        val cardWidth = (maxWidth - spacing * (columnCount - 1)) / columnCount
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            people.chunked(columnCount).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowItems.forEach { person ->
                        PersonCard(
                            person = person,
                            width = cardWidth,
                            isTelevision = isTelevision,
                            onClick = { onOpenPerson(person.id, person.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCard(
    person: PersonItem,
    width: Dp,
    isTelevision: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .tvFocusLift(isTelevision)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width)
                .clip(RoundedCornerShape(8.dp))
                .background(EdendaleColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            val url = person.profileUrl()
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_circle_user_fill),
                    contentDescription = null,
                    modifier = Modifier.size(width * 0.42f),
                    tint = EdendaleColors.SurfaceHigh,
                )
            }
        }
        Text(
            text = person.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (person.knownFor.isNotEmpty()) {
            Text(
                text = person.knownFor.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun scopePromptIcon(scope: SearchScope): Int = when (scope) {
    SearchScope.PEOPLE -> R.drawable.ic_circle_user_fill
    SearchScope.MOVIES -> R.drawable.ic_film
    SearchScope.SHOWS -> R.drawable.ic_tv
    SearchScope.ALL -> R.drawable.ic_magnifying_glass_play
}

@StringRes
private fun scopePromptTitle(scope: SearchScope): Int = when (scope) {
    SearchScope.PEOPLE -> R.string.scope_prompt_people_title
    SearchScope.MOVIES -> R.string.scope_prompt_movies_title
    SearchScope.SHOWS -> R.string.scope_prompt_shows_title
    SearchScope.ALL -> R.string.scope_prompt_all_title
}

@StringRes
private fun scopePromptMessage(scope: SearchScope): Int = when (scope) {
    SearchScope.PEOPLE -> R.string.scope_prompt_people_message
    SearchScope.MOVIES -> R.string.scope_prompt_movies_message
    SearchScope.SHOWS -> R.string.scope_prompt_shows_message
    SearchScope.ALL -> R.string.scope_prompt_all_message
}

@Composable
private fun SearchResultsGrid(
    items: List<MediaItem>,
    edgeMargin: Dp,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = edgeMargin)) {
        val spacing = if (isTelevision) 24.dp else 16.dp
        val minimumWidth = if (isTelevision) 180.dp else 145.dp
        val columnCount = ((maxWidth + spacing) / (minimumWidth + spacing)).toInt().coerceAtLeast(2)
        val cardWidth = (maxWidth - spacing * (columnCount - 1)) / columnCount
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columnCount).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowItems.forEach { item ->
                        PosterCard(
                            item = item,
                            width = cardWidth,
                            isTelevision = isTelevision,
                            onClick = { onOpenDetail(item.ref) },
                        )
                    }
                }
            }
        }
    }
}
