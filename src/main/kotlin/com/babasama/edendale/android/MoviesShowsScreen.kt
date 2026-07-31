package com.babasama.edendale.android

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.tmdb.CollectionFilter
import com.babasama.edendale.tmdb.HeroScene
import com.babasama.edendale.tmdb.collectionFilters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesShowsScreen(
    viewModel: BrowseViewModel,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit,
) {
    val state = viewModel.state

    val content = @Composable { padding: PaddingValues ->
        when (state.phase) {
            LoadPhase.IDLE, LoadPhase.LOADING -> ArchiveLoadingState(
                Modifier.padding(padding),
            )
            LoadPhase.MISSING_CREDENTIAL -> ArchiveEmptyState(
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_film),
                        contentDescription = null,
                        modifier = Modifier.size(if (isTelevision) 64.dp else 44.dp),
                        tint = EdendaleColors.SurfaceHigh,
                    )
                },
                title = stringResource(R.string.empty_projector_dark_title),
                message = stringResource(R.string.empty_projector_dark_message),
                modifier = Modifier.padding(padding),
            )
            LoadPhase.FAILED -> ArchiveEmptyState(
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_cloud_slash),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                },
                title = stringResource(R.string.empty_reel_snapped_title),
                message = state.errorMessage ?: stringResource(R.string.error_archive_not_loaded),
                modifier = Modifier.padding(padding),
                action = {
                    ArchiveButton(
                        label = stringResource(R.string.action_try_again),
                        onClick = viewModel::load,
                        kind = ArchiveButtonKind.Secondary,
                        isTelevision = isTelevision,
                    )
                },
            )
            LoadPhase.LOADED -> {
                val catalog = state.catalog
                if (catalog != null) {
                    MoviesShowsContent(
                        catalog = catalog,
                        selectedCollection = state.selectedCollection,
                        collectionItems = state.collectionItems,
                        isLoadingCollection = state.isLoadingCollection,
                        isTelevision = isTelevision,
                        contentPadding = padding,
                        onSelectCollection = viewModel::selectCollection,
                        onOpenDetail = onOpenDetail,
                        getLocalUri = viewModel::getLocalUri,
                    )
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
                    title = { Text(stringResource(R.string.tab_movies_shows)) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(painterResource(id = R.drawable.ic_gear_complex), contentDescription = stringResource(R.string.action_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    scrollBehavior = scrollBehavior
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
private fun MoviesShowsContent(
    catalog: com.babasama.edendale.tmdb.HomeCatalog,
    selectedCollection: CollectionFilter,
    collectionItems: List<MediaItem>,
    isLoadingCollection: Boolean,
    isTelevision: Boolean,
    contentPadding: PaddingValues,
    onSelectCollection: (CollectionFilter) -> Unit,
    onOpenDetail: (MediaRef) -> Unit,
    getLocalUri: suspend (Int) -> String?,
) {
    val windowSize = currentWindowSizeDp()
    val regularWidth = windowSize.width >= 600.dp
    val edgeMargin = when {
        isTelevision -> 48.dp
        regularWidth -> 48.dp
        else -> 20.dp
    }
    val posterWidth = when {
        isTelevision -> 210.dp
        regularWidth -> 180.dp
        else -> 140.dp
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 64.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (isTelevision) 56.dp else 48.dp),
    ) {
        if (catalog.heroScenes.isNotEmpty()) {
            item("hero") {
                HeroPager(
                    scenes = catalog.heroScenes,
                    edgeMargin = edgeMargin,
                    isTelevision = isTelevision,
                    onOpenDetail = onOpenDetail,
                    getLocalUri = getLocalUri,
                )
            }
        }
        item("trending") {
            MediaShelf(
                title = stringResource(R.string.shelf_trending),
                items = catalog.trending,
                edgeMargin = edgeMargin,
                posterWidth = posterWidth,
                isTelevision = isTelevision,
                onItemClick = { onOpenDetail(it.ref) },
            )
        }
        item("popular-movies") {
            MediaShelf(
                title = stringResource(R.string.shelf_popular_films),
                items = catalog.popularMovies,
                edgeMargin = edgeMargin,
                posterWidth = posterWidth,
                isTelevision = isTelevision,
                onItemClick = { onOpenDetail(it.ref) },
            )
        }
        item("popular-tv") {
            MediaShelf(
                title = stringResource(R.string.shelf_popular_series),
                items = catalog.popularShows,
                edgeMargin = edgeMargin,
                posterWidth = posterWidth,
                isTelevision = isTelevision,
                onItemClick = { onOpenDetail(it.ref) },
            )
        }
        item("top-rated") {
            MediaShelf(
                title = stringResource(R.string.shelf_top_rated),
                items = catalog.topRated,
                edgeMargin = edgeMargin,
                posterWidth = posterWidth,
                isTelevision = isTelevision,
                onItemClick = { onOpenDetail(it.ref) },
            )
        }
        item("collections") {
            CollectionsSection(
                filters = catalog.collectionFilters(),
                selected = selectedCollection,
                items = collectionItems.take(12),
                loading = isLoadingCollection,
                edgeMargin = edgeMargin,
                isTelevision = isTelevision,
                onSelect = onSelectCollection,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

private const val HERO_ROTATION_MILLIS = 10_000L

@Composable
private fun HeroPager(
    scenes: List<HeroScene>,
    edgeMargin: Dp,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
    getLocalUri: suspend (Int) -> String?,
) {
    val windowSize = currentWindowSizeDp()
    val pagerState = rememberPagerState(pageCount = { scenes.size })
    val heroHeight = when {
        isTelevision -> (windowSize.height * .62f).coerceIn(300.dp, 440.dp)
        windowSize.width >= 600.dp -> 520.dp
        else -> 420.dp
    }

    // Keyed on the pager and the scene count only — never on currentPage.
    // currentPage flips the moment the animation crosses the half-way point, so
    // keying on it re-launched this effect mid-flight, cancelled
    // animateScrollToPage, and parked the pager between two pages.
    if (!isTelevision && scenes.size > 1) {
        LaunchedEffect(pagerState, scenes.size) {
            while (true) {
                delay(HERO_ROTATION_MILLIS)
                if (pagerState.isScrollInProgress) continue // the reader is dragging
                try {
                    pagerState.animateScrollToPage((pagerState.settledPage + 1) % scenes.size)
                } catch (_: CancellationException) {
                    // A drag stole the scroll; resume rotating on the next tick.
                    // Leaving composition cancels the delay() above instead.
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.height(heroHeight),
        ) { page ->
            HeroCard(
                scene = scenes[page],
                edgeMargin = edgeMargin,
                isTelevision = isTelevision,
                getLocalUri = getLocalUri,
                onClick = { onOpenDetail(scenes[page].detail.ref) },
            )
        }
        if (scenes.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scenes.indices.forEach { index ->
                    val selected = index == pagerState.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .width(if (selected) 22.dp else 7.dp)
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    scene: HeroScene,
    edgeMargin: Dp,
    isTelevision: Boolean,
    getLocalUri: suspend (Int) -> String?,
    onClick: () -> Unit,
) {
    val detail = scene.detail
    val tmdbId = scene.progress?.tmdbId ?: detail.ref.id
    // Remembered per title: without this every recomposition reset the lookup
    // to null and the Play/Resume button vanished again.
    var localUri by remember(tmdbId) { mutableStateOf<String?>(null) }
    LaunchedEffect(tmdbId) {
        localUri = getLocalUri(tmdbId)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isTelevision) edgeMargin else 0.dp)
            .tvFocusLift(isTelevision),
        shape = RoundedCornerShape(if (isTelevision) EdendaleRadii.Hero.dp else 0.dp),
        colors = CardDefaults.cardColors(containerColor = EdendaleColors.SurfaceLow),
    ) {
        Box(Modifier.fillMaxSize()) {
            val backdrop = detail.backdropUrl(com.babasama.edendale.domain.TmdbImageSize.BACKDROP)
            if (backdrop != null) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                PosterPlaceholder(modifier = Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                EdendaleColors.Background.copy(alpha = .98f),
                                EdendaleColors.Background.copy(alpha = .58f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, EdendaleColors.Background.copy(alpha = .82f)),
                        ),
                    ),
            )
            HeroCopy(
                scene = scene,
                localUri = localUri,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = if (isTelevision) 34.dp else edgeMargin,
                        end = edgeMargin,
                        bottom = if (isTelevision) 30.dp else edgeMargin,
                    ),
                isTelevision = isTelevision,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun HeroCopy(
    scene: HeroScene,
    localUri: String?,
    modifier: Modifier,
    isTelevision: Boolean,
    onClick: () -> Unit,
) {
    val detail = scene.detail
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(if (isTelevision) .72f else .88f),
        verticalArrangement = Arrangement.spacedBy(if (isTelevision) 12.dp else 16.dp),
    ) {
        Text(
            text = if (scene.isContinueWatching) {
                listOfNotNull(
                    stringResource(R.string.hero_continue_watching).uppercase(),
                    scene.remainingText?.uppercase(),
                ).joinToString("  ·  ")
            } else {
                stringResource(R.string.hero_featured).uppercase()
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = detail.title.uppercase(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = if (isTelevision) MaterialTheme.typography.displayMedium
            else MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        HeroMetadata(detail)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (localUri != null && !isTelevision) {
                Button(onClick = {
                    com.babasama.edendale.android.player.PlayerActivity.play(
                        context = context,
                        uri = localUri,
                        title = detail.title,
                        tmdbId = scene.progress?.tmdbId ?: detail.ref.id,
                        isEpisode = scene.progress?.mediaType == com.babasama.edendale.domain.WatchMediaType.EPISODE,
                        showTmdbId = scene.progress?.showTmdbId,
                        season = scene.progress?.seasonNumber,
                        episode = scene.progress?.episodeNumber,
                    )
                }) {
                    Icon(painterResource(id = R.drawable.ic_play), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (scene.isContinueWatching) R.string.action_resume else R.string.action_play))
                }
            }
            if (!isTelevision) {
                OutlinedButton(onClick = onClick) {
                    Icon(painterResource(id = R.drawable.ic_circle_info), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_details))
                }
            }
        }
    }
}


@Composable
private fun HeroMetadata(detail: MediaDetail) {
    val values = buildList {
        detail.year?.let { add(it.toString()) }
        detail.attribution?.let(::add)
        if (detail.genres.isNotEmpty()) add(detail.genres.take(2).joinToString(" / "))
    }
    Text(
        text = values.joinToString("   |   "),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CollectionsSection(
    filters: List<CollectionFilter>,
    selected: CollectionFilter,
    items: List<MediaItem>,
    loading: Boolean,
    edgeMargin: Dp,
    isTelevision: Boolean,
    onSelect: (CollectionFilter) -> Unit,
    onOpenDetail: (MediaRef) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader(
            title = stringResource(R.string.section_curated_collections),
            modifier = Modifier.padding(horizontal = edgeMargin),
            large = isTelevision,
        )
        LazyRow(
            contentPadding = PaddingValues(
                start = edgeMargin,
                end = edgeMargin,
                top = if (isTelevision) 10.dp else 0.dp,
                bottom = if (isTelevision) 10.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(filters, key = { it.title }) { filter ->
                ArchiveFilterChip(
                    selected = selected == filter,
                    onClick = { onSelect(filter) },
                    label = { Text(filter.localizedTitle().uppercase()) },
                    isTelevision = isTelevision,
                )
            }
        }
        if (loading && items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            CollectionGrid(
                items = items,
                edgeMargin = edgeMargin,
                isTelevision = isTelevision,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

@Composable
private fun CollectionGrid(
    items: List<MediaItem>,
    edgeMargin: Dp,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = edgeMargin)) {
        val spacing = if (isTelevision) 24.dp else 20.dp
        val minimumWidth = if (isTelevision) 360.dp else 280.dp
        val columnCount = ((maxWidth + spacing) / (minimumWidth + spacing)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columnCount).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowItems.forEach { item ->
                        LandscapeCard(
                            item = item,
                            isTelevision = isTelevision,
                            onClick = { onOpenDetail(item.ref) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columnCount - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
