package com.babasama.edendale.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.data.LibraryActivity
import com.babasama.edendale.android.data.SourceScanRules
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.parental.rememberParentalGate
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl
import com.babasama.edendale.parental.GatedAction
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    audienceFilter: YoungAudienceFilter,
    isTelevision: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit,
    /** Opens a movie's page, as Apple's library does; the page plays it. */
    onOpenMovie: (LibraryMovieEntity) -> Unit,
    /** Opens a show's page, which lists its episodes. */
    onOpenShow: (LibraryShowEntity) -> Unit = {},
    /**
     * Shows only this section (the section picker's choice, or a navigation
     * child row, J.1); null shows the whole library.
     */
    section: DownloadedSection? = null,
    /** The sections with something to show, as the section picker offers them (27.1). */
    sections: List<DownloadedSection> = emptyList(),
    /** Chooses a section in the picker; null is the whole library. */
    onSelectSection: (DownloadedSection?) -> Unit = {},
    /** Hardware-keyboard commands: add a folder, link a source, rescan (J.3). */
    commands: Flow<LibraryCommand>? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        DownloadedScreenContent(
            audienceFilter = audienceFilter,
            isTelevision = isTelevision,
            availableWidth = maxWidth,
            contentPadding = contentPadding,
            onOpenSettings = onOpenSettings,
            onOpenMovie = onOpenMovie,
            onOpenShow = onOpenShow,
            section = section,
            sections = sections,
            onSelectSection = onSelectSection,
            commands = commands,
        )
    }
}

/** This process's identity, so a sweep remembered in saved state is one this process made. */
private val processToken: String = java.util.UUID.randomUUID().toString()

/** The library after the Young Audience filter, shared by the page and the navigation's child rows. */
internal data class VisibleLibrary(
    val movies: List<LibraryMovieEntity>,
    val shows: List<LibraryShowEntity>,
    val episodes: List<LibraryEpisodeEntity>,
)

/** The refs the Young Audience filter has to verify for these titles. */
internal fun libraryAudienceRefs(movies: List<LibraryMovieEntity>, shows: List<LibraryShowEntity>): List<MediaRef> =
    (movies.mapNotNull { m -> m.tmdbId?.let { MediaRef(it, MediaType.MOVIE) } } +
        shows.mapNotNull { s -> s.tmdbId?.let { MediaRef(it, MediaType.TV) } }).distinct()

/**
 * An imported title is shown only when its TMDB enrichment verifies it under
 * the parental policy; an un-enriched file has no id to verify, so it fails
 * closed. Episodes follow their show. With "Hide restricted titles" off the
 * lists keep everything and the detail page or the player blocks instead
 * (O.5); [strict] ignores that switch for a surface that cannot show a
 * restricted state, such as the TV home screen's Watch Next row.
 */
internal fun visibleLibrary(
    movies: List<LibraryMovieEntity>,
    shows: List<LibraryShowEntity>,
    episodes: List<LibraryEpisodeEntity>,
    audienceFilter: YoungAudienceFilter,
    strict: Boolean = false,
): VisibleLibrary {
    if (!audienceFilter.isEnabled) return VisibleLibrary(movies, shows, episodes)
    if (!strict && !audienceFilter.hidesRestrictedTitles) return VisibleLibrary(movies, shows, episodes)
    val visibleShows = shows.filter { it.tmdbId?.let { id -> audienceFilter.allows(MediaRef(id, MediaType.TV)) } == true }
    val showKeys = visibleShows.map { it.key }.toSet()
    return VisibleLibrary(
        movies = movies.filter { it.tmdbId?.let { id -> audienceFilter.allows(MediaRef(id, MediaType.MOVIE)) } == true },
        shows = visibleShows,
        episodes = episodes.filter { it.showKey in showKeys },
    )
}

/**
 * The Downloaded sections with something to show, for extended navigation's
 * child rows (J.1) and the page's section picker (27.1). Null until the
 * library has loaded and while audience ratings are being verified, so a
 * choice doesn't flicker or close its page.
 */
@Composable
internal fun rememberDownloadedSections(audienceFilter: YoungAudienceFilter): List<DownloadedSection>? {
    val library = rememberLibrary()
    val allMovies by library.movies.collectAsState(initial = null)
    val allShows by library.shows.collectAsState(initial = null)
    val episodes by library.episodes.collectAsState(initial = null)
    val folders by library.folders.collectAsState(initial = null)
    val progress by library.watchProgress.collectAsState(initial = null)
    val movies = allMovies ?: return null
    val shows = allShows ?: return null
    val audienceRefs = remember(movies, shows) { libraryAudienceRefs(movies, shows) }
    LaunchedEffect(audienceRefs, audienceFilter.isEnabled, audienceFilter.contextIdentifier) {
        audienceFilter.verify(audienceRefs)
    }
    if (audienceFilter.isVerifying(audienceRefs)) return null
    val visible = visibleLibrary(movies, shows, episodes ?: return null, audienceFilter)
    val sourceFolders = folders ?: return null
    val watchProgress = progress ?: return null
    val hasResumeItems = remember(watchProgress, visible, sourceFolders) {
        continueWatching(watchProgress, visible.movies, visible.episodes, visible.shows, limit = 1, folders = sourceFolders).isNotEmpty()
    }
    return DownloadedSection.available(
        hasResumeItems = hasResumeItems,
        hasMovies = visible.movies.isNotEmpty(),
        hasShows = visible.shows.isNotEmpty(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedScreenContent(
    audienceFilter: YoungAudienceFilter,
    isTelevision: Boolean,
    availableWidth: Dp,
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    onOpenMovie: (LibraryMovieEntity) -> Unit,
    onOpenShow: (LibraryShowEntity) -> Unit,
    section: DownloadedSection?,
    sections: List<DownloadedSection>,
    onSelectSection: (DownloadedSection?) -> Unit,
    commands: Flow<LibraryCommand>?,
) {
    val library = rememberLibrary()
    val compactWidth = !isTelevision && availableWidth < 600.dp
    val edgeMargin = if (compactWidth) 20.dp else 48.dp

    // Null until Room answers. The page waits for the whole library before it
    // lays out, so a scroll position brought back from a title's page lands on
    // the same row rather than on a list still filling in, and the empty state
    // no longer flashes up first.
    val loadedMovies by library.movies.collectAsState(initial = null)
    val loadedShows by library.shows.collectAsState(initial = null)
    val loadedEpisodes by library.episodes.collectAsState(initial = null)
    val loadedFolders by library.folders.collectAsState(initial = null)
    val loadedProgress by library.watchProgress.collectAsState(initial = null)
    val libraryLoaded = loadedMovies != null && loadedShows != null && loadedEpisodes != null &&
        loadedFolders != null && loadedProgress != null
    val allMovies = loadedMovies.orEmpty()
    val allShows = loadedShows.orEmpty()
    val episodes = loadedEpisodes.orEmpty()
    val folders = loadedFolders.orEmpty()
    val progressList = loadedProgress.orEmpty()
    val activity by library.activity.collectAsState(initial = LibraryActivity())
    val scanError = activity.errorMessage
    // Google Drive sources (H.9.4): what Choose More Videos added, and the picker itself.
    val sourceNotes by library.sourceNotes.collectAsState()
    // Every library edit on this page asks for the parental PIN while "Require PIN for library changes" is on (O.5).
    val gate = rememberParentalGate(isTelevision)
    val chooseMoreVideos = rememberChooseMoreVideos(gate)

    // The parental policy: see visibleLibrary.
    val audienceRefs = remember(allMovies, allShows) { libraryAudienceRefs(allMovies, allShows) }
    LaunchedEffect(audienceRefs, audienceFilter.isEnabled, audienceFilter.contextIdentifier) {
        audienceFilter.verify(audienceRefs)
    }
    val visible = visibleLibrary(allMovies, allShows, episodes, audienceFilter)
    val movies = visible.movies
    val shows = visible.shows
    val visibleEpisodes = visible.episodes
    val audienceVerifying = audienceFilter.isVerifying(audienceRefs)

    val runtimeFormat = rememberRuntimeFormat()
    val progressByKey = remember(progressList) { progressList.byStorageKey() }
    val upNextTemplate = stringResource(R.string.library_continue_up_next)
    // The source each title last played from (27.1), so a card resumes there.
    val lastSources by library.sourceMemory.changes.collectAsState()
    // Every resumable title: the Continue Watching page lists them all (J.2),
    // the shelf keeps its cap.
    val allContinueEntries = remember(progressList, movies, visibleEpisodes, shows, upNextTemplate, folders, lastSources) {
        continueWatching(
            progressList,
            movies,
            visibleEpisodes,
            shows,
            nextUpFormat = { upNextTemplate.format(it) },
            limit = null,
            folders = folders,
            lastSources = lastSources,
        )
    }
    val continueEntries = allContinueEntries.take(CONTINUE_WATCHING_LIMIT)
    // One poster per title (27.1): a movie's copies through every link, and
    // a show's rows, each counting an episode once however many copies it has.
    val foldersByUri = remember(folders) { folders.associateBy { it.treeUri } }
    val movieTitles = remember(movies, foldersByUri) { movieTitles(movies, foldersByUri) }
    val showTitles = remember(shows, visibleEpisodes) { showTitles(shows, visibleEpisodes) }

    val spacing = if (isTelevision) 20.dp else 14.dp
    val preferredPoster: Dp = when {
        isTelevision -> 210.dp
        availableWidth >= 600.dp -> 170.dp
        else -> 150.dp
    }
    val (columns, cellWidth) = libraryGridMetrics(
        availableWidth = availableWidth,
        edgeMargin = edgeMargin,
        spacing = spacing,
        preferredWidth = preferredPoster,
    )

    var pendingRemoval by remember { mutableStateOf<String?>(null) }

    // One sweep per visit (D.3). Back from a title's page brings this page
    // back with its saved state (EdendaleApp) rather than starting a new
    // visit; a page restored after process death sweeps again.
    var sweptBy by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (sweptBy != processToken) {
            sweptBy = processToken
            library.rescanAll()
        }
    }

    // Back from a title's page: the list where it was, by its first visible
    // row (playing from the page can add or empty the Continue Watching shelf
    // above it), and on TV the remote on the poster that opened the page.
    val listState = rememberShellListState("downloaded:${section?.name ?: "all"}")
    val posterReturn = rememberPosterReturn(isTelevision, ready = libraryLoaded)

    // The section picker (27.1). Choosing a section keeps this page — its
    // sweep, and the remote's focus on the picker — and starts the new
    // section at the top with the bar out. A page built afresh (Back from a
    // title's page) keeps the place it saved.
    val pickerShown = libraryLoaded && DownloadedSection.isPickerOffered(sections)
    val topBarState = rememberTopAppBarState()
    var shownSection by remember { mutableStateOf(section) }
    LaunchedEffect(section) {
        if (section != shownSection) {
            shownSection = section
            listState.scrollToItem(0)
            topBarState.heightOffset = 0f
            topBarState.contentOffset = 0f
        }
    }

    // Add Local Folder and Add Network Source, shared by the menu, the empty
    // state, and the keyboard.
    var showSmbDialog by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) library.importFolder(uri)
    }
    val addFolder: () -> Unit = { gate.require(GatedAction.LIBRARY_EDIT) { folderPicker.launch(null) } }
    val linkSource: () -> Unit = { gate.require(GatedAction.LIBRARY_EDIT) { showSmbDialog = true } }
    if (showSmbDialog) {
        LinkSourceDialog(
            isTelevision = isTelevision,
            onDismiss = { showSmbDialog = false },
            onLinked = { showSmbDialog = false },
        )
    }

    // Ctrl+N, Ctrl+Alt+N, and Ctrl+R or F5 (J.3). Nothing is offered while
    // an add flow is already up; a rescan needs a source and scans every one,
    // including remote sources the automatic sweep skipped.
    val hasSources by rememberUpdatedState(folders.isNotEmpty())
    val addFlowOpen by rememberUpdatedState(showSmbDialog)
    if (commands != null) {
        LaunchedEffect(commands) {
            commands.collect { command ->
                if (addFlowOpen) return@collect
                when (command) {
                    LibraryCommand.ADD_FOLDER -> if (!isTelevision) addFolder()
                    LibraryCommand.LINK_SOURCE -> linkSource()
                    LibraryCommand.RESCAN -> if (hasSources) gate.require(GatedAction.LIBRARY_EDIT) { library.rescanAll(force = true) }
                    LibraryCommand.TOGGLE_NAVIGATION -> Unit
                }
            }
        }
    }

    pendingRemoval?.let { treeUri ->
        val folder = folders.firstOrNull { it.treeUri == treeUri }
        if (folder == null) {
            pendingRemoval = null
        } else {
            RemoveSourceDialog(
                displayName = folder.displayName,
                isTelevision = isTelevision,
                onDismiss = { pendingRemoval = null },
                onConfirm = {
                    library.removeFolder(treeUri)
                    pendingRemoval = null
                },
            )
        }
    }

    val content = @Composable { padding: PaddingValues ->
        if (!libraryLoaded) {
            // The moment before Room answers draws nothing.
            Box(Modifier.fillMaxSize())
        } else if (movies.isEmpty() && shows.isEmpty() && folders.isEmpty() && !activity.isBusy) {
            DownloadedEmptyState(
                isTelevision = isTelevision,
                scanError = scanError,
                onDismissError = library::clearError,
                contentPadding = padding,
                onAddFolder = addFolder,
                onLinkSource = linkSource,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 24.dp,
                    bottom = padding.calculateBottomPadding() + 56.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Phone and tablet carry the title and the add-source menu in the
                // shell top bar; the TV shell's bar is a tab strip, so it keeps
                // them here.
                if (isTelevision) {
                    item("header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = edgeMargin),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionHeader(stringResource(R.string.tab_downloaded), modifier = Modifier.weight(1f), large = true)
                            AddSourceMenu(isTelevision = true, onAddFolder = addFolder, onLinkSource = linkSource)
                        }
                    }
                    // Under the heading at the top left; the TV bar above is the tab strip.
                    if (pickerShown) {
                        item("sections") {
                            DownloadedSectionPicker(
                                sections = sections,
                                selection = section,
                                onSelect = onSelectSection,
                                fillWidth = false,
                                isTelevision = true,
                                modifier = Modifier.padding(horizontal = edgeMargin),
                            )
                        }
                    }
                }

                if (scanError != null) {
                    item("error") {
                        ScanErrorNotice(
                            message = scanError,
                            onDismiss = library::clearError,
                            isTelevision = isTelevision,
                            modifier = Modifier.padding(horizontal = edgeMargin),
                        )
                    }
                }

                if (activity.isBusy) {
                    item("activity") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = edgeMargin),
                        ) {
                            Text(
                                text = activity.scanningFolder
                                    ?.let { stringResource(R.string.scanning_folder, it) }
                                    ?: stringResource(R.string.enriching_metadata),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                }

                if (audienceVerifying) {
                    item("audience-verifying") {
                        AudienceVerifyingRow(edgeMargin = edgeMargin)
                    }
                }

                if (section == DownloadedSection.CONTINUE_WATCHING && allContinueEntries.isNotEmpty()) {
                    item("continue-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_continue_watching),
                            modifier = Modifier.padding(horizontal = edgeMargin, vertical = 4.dp),
                            large = isTelevision,
                        )
                    }
                    posterRows(
                        items = allContinueEntries,
                        columns = columns,
                        cellWidth = cellWidth,
                        spacing = spacing,
                        edgeMargin = edgeMargin,
                        key = { it.uri },
                    ) { entry ->
                        ContinueCard(entry, cellWidth, isTelevision)
                    }
                }

                if (section == null && continueEntries.isNotEmpty()) {
                    item("continue-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_continue_watching),
                            modifier = Modifier.padding(horizontal = edgeMargin, vertical = 4.dp),
                            large = isTelevision,
                        )
                    }
                    item("continue") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = edgeMargin),
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                        ) {
                            items(continueEntries.size, key = { continueEntries[it].uri }) { index ->
                                ContinueCard(continueEntries[index], preferredPoster, isTelevision)
                            }
                        }
                    }
                }

                // The Movies page lists every movie, including those in Continue Watching (J.2).
                if ((section == null || section == DownloadedSection.MOVIES) && movies.isNotEmpty()) {
                    item("movies-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_movies),
                            modifier = Modifier.padding(horizontal = edgeMargin, vertical = 4.dp),
                            large = isTelevision,
                        )
                    }
                    posterRows(
                        items = movieTitles,
                        columns = columns,
                        cellWidth = cellWidth,
                        spacing = spacing,
                        edgeMargin = edgeMargin,
                        // Prefixed: a movie and a show can share a TMDB id number.
                        key = { "movie:${it.key}" },
                    ) { title ->
                        val posterKey = title.posterKey
                        LibraryMovieTile(
                            title = title,
                            width = cellWidth,
                            isTelevision = isTelevision,
                            progress = progressByKey.forMovie(title.tmdbId),
                            foldersByUri = foldersByUri,
                            remembered = PlaybackSources.movieMemoryKey(title.tmdbId)?.let(lastSources::get),
                            modifier = posterReturn.modifier(posterKey),
                            // The movie's page, which plays it (Apple's library does the same).
                            onOpen = {
                                posterReturn.opening(posterKey)
                                onOpenMovie(title.display)
                            },
                            onRemove = { copies -> gate.require(GatedAction.LIBRARY_EDIT) { copies.forEach { library.removeMovie(it.uri) } } },
                        )
                    }
                }

                if ((section == null || section == DownloadedSection.SHOWS) && shows.isNotEmpty()) {
                    item("shows-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_tv_shows),
                            modifier = Modifier.padding(horizontal = edgeMargin, vertical = 4.dp),
                            large = isTelevision,
                        )
                    }
                    posterRows(
                        items = showTitles,
                        columns = columns,
                        cellWidth = cellWidth,
                        spacing = spacing,
                        edgeMargin = edgeMargin,
                        key = { "show:${it.key}" },
                    ) { title ->
                        val show = title.display
                        val count = title.episodeCount
                        val posterKey = title.posterKey
                        LibraryPosterCard(
                            title = show.name,
                            subtitle = listOfNotNull(
                                show.firstAirYear?.toString(),
                                pluralStringResource(R.plurals.episode_count, count, count),
                            ).joinToString(" · "),
                            posterUrl = tmdbImageUrl(show.posterPath, TmdbImageSize.POSTER),
                            width = cellWidth,
                            isTelevision = isTelevision,
                            mediaType = MediaType.TV,
                            modifier = posterReturn.modifier(posterKey),
                            // The show's page, with its episodes from every copy.
                            onClick = {
                                posterReturn.opening(posterKey)
                                onOpenShow(show)
                            },
                        )
                    }
                }

                if (section == null && folders.isNotEmpty()) {
                    item("sources-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_sources),
                            modifier = Modifier.padding(horizontal = edgeMargin, vertical = 4.dp),
                            large = isTelevision,
                        )
                    }
                    items(folders.size, key = { folders[it].treeUri }) { index ->
                        val folder = folders[index]
                        val count = allMovies.count { it.folderUri == folder.treeUri } +
                            episodes.count { it.folderUri == folder.treeUri }
                        SourceRow(
                            folder = folder,
                            itemCount = count,
                            isTelevision = isTelevision,
                            onRescan = { gate.require(GatedAction.LIBRARY_EDIT) { library.rescanFolder(folder.treeUri) } },
                            onRemove = { gate.require(GatedAction.LIBRARY_EDIT) { pendingRemoval = folder.treeUri } },
                            note = sourceNotes[folder.treeUri],
                            chooseMoreVideos = chooseMoreVideos,
                        )
                    }
                }
            }
        }
    }

    if (isTelevision) {
        content(contentPadding)
    } else {
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topBarState)
        Scaffold(
            topBar = {
              Column {
                TopAppBar(
                    // The picker names the section on screen; without it the title does.
                    title = {
                        PageTitle(
                            stringResource(R.string.tab_downloaded),
                            section?.takeUnless { pickerShown }?.let { stringResource(it.title) },
                        )
                    },
                    actions = {
                        if (folders.isNotEmpty() || allMovies.isNotEmpty() || allShows.isNotEmpty()) {
                            AddSourceMenu(isTelevision = false, onAddFolder = addFolder, onLinkSource = linkSource)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_gear_complex),
                                contentDescription = stringResource(R.string.action_settings),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    scrollBehavior = scrollBehavior,
                )
                // Pinned under the bar, which scrolls away above it: the
                // choice stays in reach anywhere down the page, as iOS's tab
                // bar accessory does. It takes the bar's colour as content
                // passes under them both.
                if (pickerShown) {
                    // Derived, as Material's own bar reads it: the offset moves
                    // with every scrolled pixel, the answer only at the top.
                    val overlapped by remember(scrollBehavior) {
                        derivedStateOf { scrollBehavior.state.overlappedFraction > 0.01f }
                    }
                    val underBar by animateColorAsState(
                        targetValue = if (overlapped) {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        } else {
                            MaterialTheme.colorScheme.background
                        },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "Section picker bar",
                    )
                    DownloadedSectionPicker(
                        sections = sections,
                        selection = section,
                        onSelect = onSelectSection,
                        fillWidth = compactWidth,
                        isTelevision = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(underBar)
                            // Wider windows keep it to its own width at the top left.
                            .then(if (compactWidth) Modifier else Modifier.wrapContentWidth(Alignment.Start))
                            .padding(start = edgeMargin, end = edgeMargin, bottom = 10.dp),
                    )
                }
              }
            },
            containerColor = Color.Transparent,
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { innerPadding ->
            content(innerPadding)
        }
    }
}

/**
 * One movie's poster in Downloaded and Search (27.1): a title, however many
 * links reach its file and however many sources hold it. A tap opens the
 * movie's page, whose Play and Play From play it. A long press — the Menu
 * key on TV — opens Play From, the copy last played checked, then Remove
 * from Library for the copies whose removal lasts (D21): one item, or one
 * per copy, named as Play From names it, when several would go. The poster
 * itself carries no chevron: Play From sits beside the page's Play, as on
 * iOS.
 */
@Composable
internal fun LibraryMovieTile(
    title: LibraryTitle<LibraryMovieEntity>,
    width: Dp,
    isTelevision: Boolean,
    progress: WatchProgress?,
    foldersByUri: Map<String, LibraryFolderEntity>,
    /** The source key the movie last played from on this device (27.1), if any. */
    remembered: String?,
    onOpen: () -> Unit,
    /** Removes these copies, behind the parental gate. */
    onRemove: (List<LibraryMovieEntity>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val movie = title.display
    val copies = title.copies
    val hasCopies = copies.size > 1
    // Only copies that stay removed: Google Drive videos (D21) and files added on their own.
    val removable = copies.filter { SourceScanRules.removalLasts(it.folderUri) }
    val hasMenu = hasCopies || removable.isNotEmpty()
    val playFrom = stringResource(R.string.play_from)
    val removeLabel = stringResource(R.string.remove_from_library)
    var menuOpen by remember(title.key) { mutableStateOf(false) }
    val subtitle = listOfNotNull(
        mediaSubtitle(movie.year, movie.runtimeMinutes, rememberRuntimeFormat()),
        if (hasCopies) pluralStringResource(R.plurals.play_from_source_count, copies.size, copies.size) else null,
    ).joinToString(" · ").ifEmpty { null }
    Box {
        LibraryPosterCard(
            title = movie.title,
            subtitle = subtitle,
            posterUrl = tmdbImageUrl(movie.posterPath, TmdbImageSize.POSTER),
            width = width,
            isTelevision = isTelevision,
            isWatched = progress?.isCompleted == true,
            progress = progress.partialFraction(),
            mediaType = MediaType.MOVIE,
            onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
            onLongClickLabel = if (hasCopies) playFrom else if (removable.isNotEmpty()) removeLabel else null,
            modifier = modifier,
            onClick = onOpen,
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (hasCopies) {
                PlayFromHeading()
                PlayFromItems(
                    copies = copies,
                    source = { foldersByUri.copySource(it.folderUri) },
                    path = { it.uri },
                    marked = lastPlayedCopy(copies, { it.folderUri }, remembered),
                    markLabel = R.string.play_from_last_played,
                    onPlay = { copy ->
                        menuOpen = false
                        // A pick is the viewer's choice, so the movie remembers its source.
                        PlayerActivity.play(
                            context = context,
                            uri = copy.uri,
                            title = movie.title,
                            // An alias whose match is pending plays as its title.
                            tmdbId = copy.tmdbId ?: title.tmdbId,
                            isEpisode = false,
                        )
                    },
                )
            }
            val source: (LibraryMovieEntity) -> CopySource = { foldersByUri.copySource(it.folderUri) }
            val removeRows = if (removable.size > 1) {
                PlaybackSources.menuRows(
                    copies = removable,
                    source = source,
                    path = { it.uri },
                    label = { sourceEndpointLabel(context, it) },
                    kindLabel = { sourceKindLabel(context, it) },
                )
            } else {
                null
            }
            removable.forEachIndexed { index, copy ->
                ArchiveMenuItem(
                    text = { focused ->
                        Column {
                            Text(removeLabel)
                            removeRows?.get(index)?.let { row ->
                                Text(
                                    text = "${row.title} · ${row.detail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_trash_can), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRemove(listOf(copy))
                    },
                )
            }
        }
    }
}

/**
 * A Continue Watching card: resumes the title, or starts the next episode. A
 * part-watched card's menu (a long press, or Menu on TV) offers Clear
 * Progress, as Apple's resume card does, which syncs as a clear. It says
 * what it does rather than "remove": a movie or a show's first episode
 * leaves the shelf, but an episode after a finished one comes back as that
 * show's Up Next card. A next-up card has no record to clear, so no menu.
 */
@Composable
private fun ContinueCard(entry: ContinueEntry, width: Dp, isTelevision: Boolean) {
    val context = LocalContext.current
    val library = rememberLibrary()
    val progressKey = entry.progressKey
    val clearLabel = stringResource(R.string.clear_progress)
    var menuOpen by remember(entry.uri) { mutableStateOf(false) }
    Box {
        LibraryPosterCard(
            title = entry.title,
            subtitle = entry.subtitle,
            posterUrl = entry.posterUrl,
            width = width,
            isTelevision = isTelevision,
            progress = entry.fraction.takeUnless { entry.isNextUp },
            mediaType = if (entry.isEpisode) MediaType.TV else MediaType.MOVIE,
            onLongClick = if (progressKey != null) ({ menuOpen = true }) else null,
            onLongClickLabel = if (progressKey != null) clearLabel else null,
            onClick = {
                PlayerActivity.play(
                    context = context,
                    uri = entry.uri,
                    title = entry.title,
                    tmdbId = entry.tmdbId,
                    isEpisode = entry.isEpisode,
                    showTmdbId = entry.showTmdbId,
                    season = entry.season,
                    episode = entry.episode,
                    rememberSource = entry.remembersSource,
                )
            },
        )
        if (progressKey != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                AddSourceMenuItem(
                    labelRes = R.string.clear_progress,
                    iconRes = R.drawable.ic_trash_can,
                    onClick = {
                        menuOpen = false
                        library.clearWatchProgress(progressKey)
                    },
                )
            }
        }
    }
}

/**
 * Emits a poster grid as whole rows. A `LazyVerticalGrid` cannot nest inside the
 * screen's `LazyColumn`, and the sections have to scroll as one list.
 */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.posterRows(
    items: List<T>,
    columns: Int,
    cellWidth: Dp,
    spacing: Dp,
    edgeMargin: Dp,
    key: (T) -> Any,
    cell: @Composable (T) -> Unit,
) {
    val rows = items.chunked(columns)
    items(rows.size, key = { key(rows[it].first()) }) { index ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeMargin),
            horizontalArrangement = Arrangement.spacedBy(
                space = spacing,
                alignment = Alignment.CenterHorizontally,
            ),
        ) {
            rows[index].forEach { item -> cell(item) }
            repeat(columns - rows[index].size) {
                Spacer(Modifier.width(cellWidth))
            }
        }
    }
}

/**
 * Add Local Folder / Add Network Source behind a single icon. The top bar
 * shows it only once the library has something in it — while it is empty the
 * two actions are full buttons in the empty state instead, where they are the
 * whole point of the screen.
 */
@Composable
fun AddSourceMenu(
    isTelevision: Boolean,
    onAddFolder: () -> Unit,
    onLinkSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        ArchiveIconButton(
            onClick = { expanded = true },
            isTelevision = isTelevision,
        ) { _ ->
            Icon(
                painter = painterResource(id = R.drawable.ic_plus),
                contentDescription = stringResource(R.string.add_source),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Android TV cannot browse local folders reliably; network only.
            if (!isTelevision) {
                AddSourceMenuItem(
                    labelRes = R.string.add_local_folder,
                    iconRes = R.drawable.ic_folder_open,
                    onClick = {
                        expanded = false
                        onAddFolder()
                    },
                )
            }
            AddSourceMenuItem(
                labelRes = R.string.add_network_source,
                iconRes = R.drawable.ic_link,
                onClick = {
                    expanded = false
                    onLinkSource()
                },
            )
        }
    }
}

/** An Add Source row; [ArchiveMenuItem] paints its D-pad focus. */
@Composable
private fun AddSourceMenuItem(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
) {
    ArchiveMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
            )
        },
    )
}

@Composable
private fun DownloadedEmptyState(
    isTelevision: Boolean,
    scanError: String?,
    onDismissError: () -> Unit,
    contentPadding: PaddingValues,
    onAddFolder: () -> Unit,
    onLinkSource: () -> Unit,
) {
    ArchiveEmptyState(
        icon = {
            Icon(
                painter = painterResource(id = R.drawable.ic_folder_open),
                contentDescription = null,
                modifier = Modifier.size(if (isTelevision) 64.dp else 48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        title = stringResource(R.string.library_empty_title),
        message = stringResource(
            if (isTelevision) R.string.library_empty_message_tv else R.string.library_empty_message,
        ),
        modifier = Modifier.padding(contentPadding),
        action = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isTelevision) {
                    ArchiveButton(
                        label = stringResource(R.string.add_local_folder),
                        onClick = onAddFolder,
                        modifier = Modifier.widthIn(min = 240.dp),
                        kind = ArchiveButtonKind.Primary,
                        iconRes = R.drawable.ic_folder_open,
                        isTelevision = false,
                    )
                }
                ArchiveButton(
                    label = stringResource(R.string.add_network_source),
                    onClick = onLinkSource,
                    modifier = Modifier.widthIn(min = 240.dp),
                    kind = ArchiveButtonKind.Primary,
                    iconRes = R.drawable.ic_link,
                    isTelevision = isTelevision,
                )
                if (scanError != null) {
                    ScanErrorNotice(
                        message = scanError,
                        onDismiss = onDismissError,
                        isTelevision = isTelevision,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }
            }
        },
    )
}
