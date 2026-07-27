package com.babasama.edendale.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    isTelevision: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit,
    onOpenShow: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val library = rememberLibrary()
    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp

    val movies by library.movies.collectAsState(initial = emptyList())
    val shows by library.shows.collectAsState(initial = emptyList())
    val episodes by library.episodes.collectAsState(initial = emptyList())
    val folders by library.folders.collectAsState(initial = emptyList())
    val progressList by library.watchProgress.collectAsState(initial = emptyList())
    val activity by library.activity.collectAsState(initial = LibraryActivity())
    val scanError = activity.errorMessage

    val runtimeFormat = rememberRuntimeFormat()
    val progressByKey = remember(progressList) { progressList.byStorageKey() }
    val continueEntries = remember(progressList, movies, episodes, shows) {
        continueWatching(progressList, movies, episodes, shows)
    }
    val episodeCounts = remember(episodes) { episodes.groupingBy { it.showKey }.eachCount() }

    val spacing = if (isTelevision) 20.dp else 14.dp
    val preferredPoster: Dp = when {
        isTelevision -> 210.dp
        windowSize.width >= 600.dp -> 170.dp
        else -> 150.dp
    }
    val (columns, cellWidth) = libraryGridMetrics(
        availableWidth = windowSize.width,
        edgeMargin = edgeMargin,
        spacing = spacing,
        preferredWidth = preferredPoster,
    )

    var pendingRemoval by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        library.rescanAll()
    }

    pendingRemoval?.let { treeUri ->
        val folder = folders.firstOrNull { it.treeUri == treeUri }
        if (folder == null) {
            pendingRemoval = null
        } else {
            RemoveSourceDialog(
                displayName = folder.displayName,
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
                            AddSourceMenu(isTelevision = true)
                        }
                    }
                }

                if (scanError != null) {
                    item("error") {
                        ScanErrorNotice(
                            message = scanError,
                            onDismiss = library::clearError,
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

                if (continueEntries.isNotEmpty()) {
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
                                val entry = continueEntries[index]
                                LibraryPosterCard(
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    posterUrl = entry.posterUrl,
                                    width = preferredPoster,
                                    isTelevision = isTelevision,
                                    progress = entry.fraction,
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
                        }
                    }
                }

                if (movies.isNotEmpty()) {
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
                                PlayerActivity.play(
                                    context = context,
                                    uri = movie.uri,
                                    title = movie.title,
                                    tmdbId = movie.tmdbId,
                                    isEpisode = false,
                                )
                            },
                        )
                    }
                }

                if (shows.isNotEmpty()) {
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

                if (folders.isNotEmpty()) {
                    item("sources-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_sources),
                            modifier = Modifier.padding(horizontal = edgeMargin, vertical = 4.dp),
                            large = isTelevision,
                        )
                    }
                    items(folders.size, key = { folders[it].treeUri }) { index ->
                        val folder = folders[index]
                        val count = movies.count { it.folderUri == folder.treeUri } +
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
                    title = { Text(stringResource(R.string.tab_downloaded)) },
                    actions = {
                        DownloadedTopBarActions(isTelevision = false)
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
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            rows[index].forEach { item -> cell(item) }
            repeat(columns - rows[index].size) {
                Spacer(Modifier.width(cellWidth))
            }
        }
    }
}

/**
 * Top-bar slot for the Downloaded tab. It appears only once the library has
 * something in it — while it is empty the two actions are full buttons in the
 * empty state instead, where they are the whole point of the screen.
 */
@Composable
fun DownloadedTopBarActions(isTelevision: Boolean) {
    val library = rememberLibrary()
    val folders by library.folders.collectAsState(initial = emptyList())
    val movies by library.movies.collectAsState(initial = emptyList())
    val shows by library.shows.collectAsState(initial = emptyList())
    if (folders.isEmpty() && movies.isEmpty() && shows.isEmpty()) return
    AddSourceMenu(isTelevision = isTelevision)
}

/** Add Local Folder / Add Network Source behind a single icon. */
@Composable
fun AddSourceMenu(isTelevision: Boolean, modifier: Modifier = Modifier) {
    val library = rememberLibrary()
    var expanded by remember { mutableStateOf(false) }
    var showSmbDialog by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) library.importFolder(uri)
    }

    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_plus),
                contentDescription = stringResource(R.string.add_source),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Android TV cannot browse local folders reliably; network only.
            if (!isTelevision) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_local_folder)) },
                    onClick = {
                        expanded = false
                        folderPicker.launch(null)
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_folder_open),
                            contentDescription = null,
                        )
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_network_source)) },
                onClick = {
                    expanded = false
                    showSmbDialog = true
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_link),
                        contentDescription = null,
                    )
                },
            )
        }
    }

    if (showSmbDialog) {
        SmbImportDialog(
            onDismiss = { showSmbDialog = false },
            onImport = { host, user, pass ->
                library.importSmbFolder(host, user, pass)
                showSmbDialog = false
            },
        )
    }
}

@Composable
private fun DownloadedEmptyState(
    isTelevision: Boolean,
    scanError: String?,
    onDismissError: () -> Unit,
    contentPadding: PaddingValues,
) {
    val library = rememberLibrary()
    var showSmbDialog by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) library.importFolder(uri)
    }

    if (showSmbDialog) {
        SmbImportDialog(
            onDismiss = { showSmbDialog = false },
            onImport = { host, user, pass ->
                library.importSmbFolder(host, user, pass)
                showSmbDialog = false
            },
        )
    }

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
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.widthIn(min = 240.dp),
                    ) {
                        Icon(painterResource(id = R.drawable.ic_folder_open), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_local_folder))
                    }
                }
                Button(
                    onClick = { showSmbDialog = true },
                    modifier = Modifier.widthIn(min = 240.dp),
                ) {
                    Icon(painterResource(id = R.drawable.ic_link), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_network_source))
                }
                if (scanError != null) {
                    ScanErrorNotice(
                        message = scanError,
                        onDismiss = onDismissError,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }
            }
        },
    )
}
