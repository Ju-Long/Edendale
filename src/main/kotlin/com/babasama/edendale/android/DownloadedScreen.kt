package com.babasama.edendale.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.data.LibraryActivity
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    audienceFilter: YoungAudienceFilter,
    isTelevision: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit,
    onOpenShow: (String) -> Unit = {},
    /** Shows only this section (a navigation child row, J.1); null shows the whole library. */
    section: DownloadedSection? = null,
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
            onOpenShow = onOpenShow,
            section = section,
            commands = commands,
        )
    }
}

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
 * An imported title is shown only when its TMDB enrichment verifies it as
 * PG / PG-13 while the filter is on; an un-enriched file has no id to
 * verify, so it fails closed. Episodes follow their show.
 */
internal fun visibleLibrary(
    movies: List<LibraryMovieEntity>,
    shows: List<LibraryShowEntity>,
    episodes: List<LibraryEpisodeEntity>,
    audienceFilter: YoungAudienceFilter,
): VisibleLibrary {
    if (!audienceFilter.isEnabled) return VisibleLibrary(movies, shows, episodes)
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
 * child rows (J.1). Null until the library has loaded and while audience
 * ratings are being verified, so a row doesn't flicker or close its page.
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
    onOpenShow: (String) -> Unit,
    section: DownloadedSection?,
    commands: Flow<LibraryCommand>?,
) {
    val context = LocalContext.current
    val library = rememberLibrary()
    val edgeMargin = if (isTelevision || availableWidth >= 600.dp) 48.dp else 20.dp

    val allMovies by library.movies.collectAsState(initial = emptyList())
    val allShows by library.shows.collectAsState(initial = emptyList())
    val episodes by library.episodes.collectAsState(initial = emptyList())
    val folders by library.folders.collectAsState(initial = emptyList())
    val progressList by library.watchProgress.collectAsState(initial = emptyList())
    val activity by library.activity.collectAsState(initial = LibraryActivity())
    val scanError = activity.errorMessage

    // Young Audience filter: see visibleLibrary.
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
    val foldersByUri = remember(folders) { folders.associateBy { it.treeUri } }
    // Every resumable title: the Continue Watching page lists them all (J.2),
    // the shelf keeps its cap.
    val allContinueEntries = remember(progressList, movies, visibleEpisodes, shows, upNextTemplate, folders) {
        continueWatching(
            progressList,
            movies,
            visibleEpisodes,
            shows,
            nextUpFormat = { upNextTemplate.format(it) },
            limit = null,
            folders = folders,
        )
    }
    val continueEntries = allContinueEntries.take(CONTINUE_WATCHING_LIMIT)
    val episodeCounts = remember(episodes) { episodes.groupingBy { it.showKey }.eachCount() }

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

    LaunchedEffect(Unit) {
        library.rescanAll()
    }

    // Add Local Folder and Add Network Source, shared by the menu, the empty
    // state, and the keyboard.
    var showSmbDialog by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) library.importFolder(uri)
    }
    val addFolder: () -> Unit = { folderPicker.launch(null) }
    val linkSource: () -> Unit = { showSmbDialog = true }
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
                    LibraryCommand.RESCAN -> if (hasSources) library.rescanAll(force = true)
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
        if (movies.isEmpty() && shows.isEmpty() && folders.isEmpty() && !activity.isBusy) {
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
                        items = movies,
                        columns = columns,
                        cellWidth = cellWidth,
                        spacing = spacing,
                        edgeMargin = edgeMargin,
                        key = { it.uri },
                    ) { movie ->
                        val progress = progressByKey.forMovie(movie.tmdbId)
                        LibraryPosterCard(
                            title = movie.title,
                            subtitle = mediaSubtitle(movie.year, movie.runtimeMinutes, runtimeFormat),
                            posterUrl = tmdbImageUrl(movie.posterPath, TmdbImageSize.POSTER),
                            width = cellWidth,
                            isTelevision = isTelevision,
                            isWatched = progress?.isCompleted == true,
                            progress = progress.partialFraction(),
                            mediaType = MediaType.MOVIE,
                            onClick = {
                                // This copy, unless its source is unreachable and another isn't (D.5).
                                val copy = preferredMovie(movie, allMovies, foldersByUri)
                                PlayerActivity.play(
                                    context = context,
                                    uri = copy.uri,
                                    title = copy.title,
                                    tmdbId = copy.tmdbId,
                                    isEpisode = false,
                                )
                            },
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
                        items = shows,
                        columns = columns,
                        cellWidth = cellWidth,
                        spacing = spacing,
                        edgeMargin = edgeMargin,
                        key = { it.key },
                    ) { show ->
                        val count = episodeCounts[show.key] ?: 0
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
                            onClick = { onOpenShow(show.key) },
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
                            onRescan = { library.rescanFolder(folder.treeUri) },
                            onRemove = { pendingRemoval = folder.treeUri },
                        )
                    }
                }
            }
        }
    }

    if (isTelevision) {
        content(contentPadding)
    } else {
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { PageTitle(stringResource(R.string.tab_downloaded), section?.let { stringResource(it.title) }) },
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
            },
            containerColor = Color.Transparent,
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { innerPadding ->
            content(innerPadding)
        }
    }
}

/** A Continue Watching card: resumes the title, or starts the next episode. */
@Composable
private fun ContinueCard(entry: ContinueEntry, width: Dp, isTelevision: Boolean) {
    val context = LocalContext.current
    LibraryPosterCard(
        title = entry.title,
        subtitle = entry.subtitle,
        posterUrl = entry.posterUrl,
        width = width,
        isTelevision = isTelevision,
        progress = entry.fraction.takeUnless { entry.isNextUp },
        mediaType = if (entry.isEpisode) MediaType.TV else MediaType.MOVIE,
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
            )
        },
    )
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

/**
 * A menu row carries no container colour of its own, so Material has nothing to
 * tint on focus and the D-pad appears to do nothing inside the popup. This
 * paints the row itself and flips the label and glyph to match.
 */
@Composable
private fun AddSourceMenuItem(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = onClick,
        modifier = Modifier.background(
            if (focused) EdendaleColors.Gold else Color.Transparent,
        ),
        leadingIcon = {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
            )
        },
        colors = MenuDefaults.itemColors(
            textColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
            leadingIconColor = if (focused) EdendaleColors.OnGold
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        interactionSource = interactionSource,
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
