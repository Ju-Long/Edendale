package com.babasama.edendale.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.UserMediaRecord
import com.babasama.edendale.domain.tmdbImageUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel,
    audienceFilter: YoungAudienceFilter,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit,
) {
    val records by viewModel.items.collectAsState()

    // Verify audience ratings for the saved titles as they change or the
    // preference flips; the grids fail closed until each verification lands.
    LaunchedEffect(records, audienceFilter.isEnabled, audienceFilter.contextIdentifier) {
        audienceFilter.verify(records.map { it.ref })
    }

    val visible = records.filter { audienceFilter.allows(it.ref) }
    val movies = visible.filter { it.mediaType == MediaType.MOVIE }
    val shows = visible.filter { it.mediaType == MediaType.TV }
    val verifying = audienceFilter.isVerifying(records.map { it.ref })

    val content = @Composable { padding: PaddingValues ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val edgeMargin = if (isTelevision || maxWidth >= 600.dp) 48.dp else 20.dp
            val preferredPoster: Dp = when {
                isTelevision -> 210.dp
                maxWidth >= 600.dp -> 180.dp
                else -> 140.dp
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + if (isTelevision) 8.dp else 24.dp,
                    bottom = padding.calculateBottomPadding() + 56.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                if (isTelevision) {
                    item("header") {
                        SectionHeader(
                            title = stringResource(R.string.tab_watchlist),
                            modifier = Modifier.padding(horizontal = edgeMargin),
                            large = true,
                        )
                    }
                }

                if (movies.isEmpty() && shows.isEmpty()) {
                    item("empty") {
                        WatchlistEmptyOrVerifying(
                            verifying = verifying,
                            filtered = audienceFilter.isEnabled && records.isNotEmpty(),
                            edgeMargin = edgeMargin,
                        )
                    }
                }

                if (movies.isNotEmpty()) {
                    item("movies-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_movies),
                            modifier = Modifier.padding(horizontal = edgeMargin),
                            large = isTelevision,
                        )
                    }
                    item("movies") {
                        WatchlistGrid(
                            records = movies,
                            edgeMargin = edgeMargin,
                            preferredWidth = preferredPoster,
                            isTelevision = isTelevision,
                            onOpenDetail = onOpenDetail,
                            onRemove = viewModel::remove,
                        )
                    }
                }

                if (shows.isNotEmpty()) {
                    item("shows-header") {
                        SectionHeader(
                            title = stringResource(R.string.section_tv_shows),
                            modifier = Modifier.padding(horizontal = edgeMargin),
                            large = isTelevision,
                        )
                    }
                    item("shows") {
                        WatchlistGrid(
                            records = shows,
                            edgeMargin = edgeMargin,
                            preferredWidth = preferredPoster,
                            isTelevision = isTelevision,
                            onOpenDetail = onOpenDetail,
                            onRemove = viewModel::remove,
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
                    title = { Text(stringResource(R.string.tab_watchlist)) },
                    actions = {
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

@Composable
private fun WatchlistEmptyOrVerifying(
    verifying: Boolean,
    filtered: Boolean,
    edgeMargin: Dp,
) {
    if (verifying) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else if (filtered) {
        Text(
            text = stringResource(R.string.watchlist_no_young_audience_titles),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeMargin, vertical = 40.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WatchlistGrid(
    records: List<UserMediaRecord>,
    edgeMargin: Dp,
    preferredWidth: Dp,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
    onRemove: (MediaRef) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = edgeMargin)) {
        val spacing = if (isTelevision) 24.dp else 20.dp
        val columns = ((maxWidth + spacing) / (preferredWidth + spacing)).toInt().coerceAtLeast(2)
        val cardWidth = (maxWidth - spacing * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            records.chunked(columns).forEach { rowRecords ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowRecords.forEach { record ->
                        WatchlistCard(
                            record = record,
                            width = cardWidth,
                            isTelevision = isTelevision,
                            onOpen = { onOpenDetail(record.ref) },
                            onRemove = { onRemove(record.ref) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchlistCard(
    record: UserMediaRecord,
    width: Dp,
    isTelevision: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val fallbackTitle = stringResource(
        if (record.mediaType == MediaType.MOVIE) R.string.media_type_movie
        else R.string.media_type_series,
    )
    Box {
        Card(
            modifier = Modifier
                .width(width)
                .tvFocusLift(isTelevision)
                // Tap opens the archive record (whose Watchlist toggle also
                // removes); long-press is a quick remove on touch devices.
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = if (isTelevision) null else ({ menuOpen = true }),
                ),
            shape = RoundedCornerShape(EdendaleRadii.Card.dp),
            colors = CardDefaults.cardColors(containerColor = EdendaleColors.SurfaceLow),
        ) {
            Column {
                val poster = tmdbImageUrl(record.posterPath, TmdbImageSize.POSTER)
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                    if (poster != null) {
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        PosterPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            mediaType = record.mediaType,
                        )
                    }
                }
                Text(
                    text = record.title ?: fallbackTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    style = if (isTelevision) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_remove)) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
                leadingIcon = {
                    Icon(painterResource(id = R.drawable.ic_bookmark_slash), contentDescription = null)
                },
            )
        }
    }
}
