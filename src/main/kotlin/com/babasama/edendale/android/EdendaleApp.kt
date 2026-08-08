package com.babasama.edendale.android

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import com.babasama.edendale.domain.AppRoute
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class AppTab(
    @StringRes val label: Int,
    val icon: Int,
    val selectedIcon: Int,
) {
    MOVIES(R.string.tab_movies_shows, R.drawable.ic_film, R.drawable.ic_film),
    // Watchlist sits between Movies and Downloaded, and only appears while the
    // watchlist holds a title (see the visible-tab list in EdendaleApp).
    WATCHLIST(R.string.tab_watchlist, R.drawable.film_stack, R.drawable.film_stack),
    DOWNLOADED(R.string.tab_downloaded, R.drawable.ic_folder_closed, R.drawable.ic_folder_closed),
    SEARCH(R.string.tab_search, R.drawable.ic_magnifying_glass_play, R.drawable.ic_magnifying_glass_play),
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdendaleApp(
    isTelevision: Boolean,
    pendingRoute: AppRoute? = null,
    onRouteConsumed: () -> Unit = {}
) {
    val browseViewModel: BrowseViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()
    val tmdbAccountViewModel: TmdbAccountViewModel = viewModel()
    val watchlistViewModel: WatchlistViewModel = viewModel()
    val audienceFilter = (viewModel<AudienceFilterViewModel>()).filter
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.MOVIES) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    // The local show drill-in is reachable from both Downloaded and Search, so
    // it is hoisted here alongside the other full-screen overrides.
    var openShowKey by rememberSaveable { mutableStateOf<String?>(null) }
    val openTab: (AppTab) -> Unit = { tab ->
        selectedTab = tab
    }
    val context = LocalContext.current

    // The Watchlist tab appears only while the list holds a title. Its refs are
    // verified here (not just on the tab) so a fully filtered-out watchlist can
    // hide the tab, and so the count survives switching tabs.
    val watchlistRecords by watchlistViewModel.items.collectAsState()
    val watchlistRefs = watchlistRecords.map { it.ref }
    LaunchedEffect(watchlistRefs, audienceFilter.isEnabled, audienceFilter.contextIdentifier) {
        audienceFilter.verify(watchlistRefs)
    }
    val hasWatchlist = watchlistRecords.isNotEmpty() && (
        !audienceFilter.isEnabled ||
            audienceFilter.isVerifying(watchlistRefs) ||
            watchlistRecords.any { audienceFilter.allows(it.ref) }
        )
    val tabs = buildList {
        add(AppTab.MOVIES)
        if (hasWatchlist) add(AppTab.WATCHLIST)
        add(AppTab.DOWNLOADED)
        add(AppTab.SEARCH)
    }
    LaunchedEffect(hasWatchlist) {
        if (!hasWatchlist && selectedTab == AppTab.WATCHLIST) selectedTab = AppTab.MOVIES
    }

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            when (pendingRoute) {
                is AppRoute.Search -> {
                    selectedTab = AppTab.SEARCH
                    searchViewModel.updateQuery(pendingRoute.query)
                }
                is AppRoute.Media -> {
                    browseViewModel.openDetail(pendingRoute.ref)
                }
                is AppRoute.PlayMovie -> {
                    browseViewModel.openDetail(MediaRef(pendingRoute.tmdbId, MediaType.MOVIE))
                }
                is AppRoute.PlayEpisode -> {
                    browseViewModel.openDetail(MediaRef(pendingRoute.tmdbId, MediaType.TV))
                }
                is AppRoute.LocalMovie, is AppRoute.LocalShow,
                is AppRoute.PlayLocalMovie, is AppRoute.PlayLocalEpisode -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_local_media_unsupported),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            onRouteConsumed()
        }
    }

    openShowKey?.let { key ->
        LocalShowHost(
            showKey = key,
            isTelevision = isTelevision,
            onBack = { openShowKey = null },
        )
        return
    }

    if (browseViewModel.filmographyState.personId != null) {
        FilmographyScreen(
            state = browseViewModel.filmographyState,
            audienceFilter = audienceFilter,
            isTelevision = isTelevision,
            onBack = browseViewModel::closeFilmography,
            onOpenDetail = browseViewModel::openDetail,
        )
        return
    }

    if (browseViewModel.detailState.ref != null) {
        MediaDetailScreen(
            state = browseViewModel.detailState,
            audienceFilter = audienceFilter,
            isTelevision = isTelevision,
            onBack = browseViewModel::closeDetail,
            onToggleFavourite = { browseViewModel.toggleFavourite(it) },
            onToggleWatchlist = { browseViewModel.toggleWatchlist(it) },
            onSetRating = { ref, rating -> browseViewModel.setRating(ref, rating) },
            onToggleWatched = browseViewModel::toggleWatched,
            onOpenFilmography = browseViewModel::openFilmography,
            onSelectSeason = browseViewModel::selectSeason,
            onToggleEpisodeWatched = browseViewModel::toggleEpisodeWatched,
        )
        return
    }


    // TV takes Settings full screen instead of as a bottom sheet, and — like the
    // other full-screen overrides above — replaces the shell rather than
    // overlaying it. An overlay leaves the shell's cards focusable underneath,
    // so the D-pad wanders off Settings and into the content behind it. A remote
    // also cannot swipe a sheet away, and opening the Add Network Source dialog
    // from inside one tore the whole sheet down.
    if (isTelevision && showSettingsSheet) {
        BackHandler { showSettingsSheet = false }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            SettingsScreen(
                isTelevision = true,
                tmdbAccount = tmdbAccountViewModel,
                audienceFilter = audienceFilter,
                contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                onDismiss = { showSettingsSheet = false },
            )
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactPortrait = !isTelevision && maxWidth < 600.dp && maxHeight > maxWidth
        when {
            isTelevision -> TvShell(
                tabs = tabs,
                selectedTab = selectedTab,
                onSelectTab = openTab,
                browseViewModel = browseViewModel,
                searchViewModel = searchViewModel,
                watchlistViewModel = watchlistViewModel,
                audienceFilter = audienceFilter,
                onOpenSettings = { showSettingsSheet = true },
                onOpenShow = { openShowKey = it },
            )
            compactPortrait -> PhoneShell(
                tabs = tabs,
                selectedTab = selectedTab,
                onSelectTab = openTab,
                browseViewModel = browseViewModel,
                searchViewModel = searchViewModel,
                watchlistViewModel = watchlistViewModel,
                audienceFilter = audienceFilter,
                onOpenSettings = { showSettingsSheet = true },
                onOpenShow = { openShowKey = it },
            )
            else -> WideShell(
                tabs = tabs,
                selectedTab = selectedTab,
                onSelectTab = openTab,
                browseViewModel = browseViewModel,
                searchViewModel = searchViewModel,
                watchlistViewModel = watchlistViewModel,
                audienceFilter = audienceFilter,
                extendedNavigation = maxWidth >= 1100.dp,
                onOpenSettings = { showSettingsSheet = true },
                onOpenShow = { openShowKey = it },
            )
        }

        if (showSettingsSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                SettingsScreen(
                    isTelevision = false,
                    tmdbAccount = tmdbAccountViewModel,
                    audienceFilter = audienceFilter,
                    contentPadding = PaddingValues(bottom = 24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneShell(
    tabs: List<AppTab>,
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    browseViewModel: BrowseViewModel,
    searchViewModel: SearchViewModel,
    watchlistViewModel: WatchlistViewModel,
    audienceFilter: YoungAudienceFilter,
    onOpenSettings: () -> Unit,
    onOpenShow: (String) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        icon = {
                            Icon(
                                painter = painterResource(id = if (selectedTab == tab) tab.selectedIcon else tab.icon),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        AppTabContent(
            tab = selectedTab,
            browseViewModel = browseViewModel,
            searchViewModel = searchViewModel,
            watchlistViewModel = watchlistViewModel,
            audienceFilter = audienceFilter,
            isTelevision = false,
            contentPadding = padding,
            onOpenSettings = onOpenSettings,
            onOpenShow = onOpenShow,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WideShell(
    tabs: List<AppTab>,
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    browseViewModel: BrowseViewModel,
    searchViewModel: SearchViewModel,
    watchlistViewModel: WatchlistViewModel,
    audienceFilter: YoungAudienceFilter,
    extendedNavigation: Boolean,
    onOpenSettings: () -> Unit,
    onOpenShow: (String) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        WideNavigation(
            tabs = tabs,
            selectedTab = selectedTab,
            onSelectTab = onSelectTab,
            extended = extendedNavigation,
            onOpenSettings = onOpenSettings,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // The rail already sits inside the start inset, so the screens'
                // own Scaffolds must not pad for it a second time.
                .consumeWindowInsets(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Start)
                )
                .background(MaterialTheme.colorScheme.background)
        ) {
            AppTabContent(
                tab = selectedTab,
                browseViewModel = browseViewModel,
                searchViewModel = searchViewModel,
                watchlistViewModel = watchlistViewModel,
                audienceFilter = audienceFilter,
                isTelevision = false,
                contentPadding = PaddingValues(),
                onOpenSettings = onOpenSettings,
                onOpenShow = onOpenShow,
            )
        }
    }
}

// Status/navigation/caption bars plus a leading display cutout — the rail hugs
// the start edge, so a landscape notch lands on it. IME is deliberately left out
// (WindowInsets.safeDrawing would include it): the keyboard must not squeeze the
// rail's items when a text field elsewhere in the window takes focus.
@Composable
private fun railInsets(): WindowInsets =
    WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)

@Composable
private fun WideNavigation(
    tabs: List<AppTab>,
    selectedTab: AppTab?,
    onSelectTab: (AppTab) -> Unit,
    extended: Boolean,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (extended) 224.dp else 88.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                // The rail spans the full window height under edge-to-edge, so
                // it has to inset itself: without this the tablet taskbar eats
                // the Settings button at the bottom, and the desktop caption bar
                // (part of systemBars) sits on top of the wordmark.
                .windowInsetsPadding(railInsets())
                .padding(horizontal = 12.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (extended) {
                Text(
                    text = stringResource(R.string.brand_wordmark),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).padding(8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            tabs.forEach { tab ->
                NavigationButton(
                    tab = tab,
                    selected = selectedTab == tab,
                    extended = extended,
                    onClick = { onSelectTab(tab) },
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (extended) Arrangement.Start else Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_gear_complex),
                        contentDescription = if (extended) null else stringResource(R.string.action_settings),
                    )
                    if (extended) {
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.action_settings), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationButton(
    tab: AppTab,
    selected: Boolean,
    extended: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (extended) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(id = if (selected) tab.selectedIcon else tab.icon),
                contentDescription = if (extended) null else stringResource(tab.label),
            )
            if (extended) {
                Spacer(Modifier.width(14.dp))
                Text(stringResource(tab.label), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun TvShell(
    tabs: List<AppTab>,
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    browseViewModel: BrowseViewModel,
    searchViewModel: SearchViewModel,
    watchlistViewModel: WatchlistViewModel,
    audienceFilter: YoungAudienceFilter,
    onOpenSettings: () -> Unit,
    onOpenShow: (String) -> Unit,
) {
    // D-pad Down out of the tab strip had nothing to land on: Compose finds the
    // body when searching up from it (Up already returns to the bar), but not
    // when searching down from the bar, so the strip hands focus over by hand.
    val contentFocus = remember { FocusRequester() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 48.dp, vertical = 12.dp)
                        .focusProperties { down = contentFocus },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.brand_wordmark),
                        modifier = Modifier.padding(end = 22.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    tabs.forEach { tab ->
                        TvNavigationTab(
                            tab = tab,
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    ArchiveIconButton(onClick = onOpenSettings, isTelevision = true) { _ ->
                        Icon(painterResource(id = R.drawable.ic_gear_complex), contentDescription = stringResource(R.string.action_settings))
                    }
                }
            }
        },
    ) { padding ->
        // focusGroup so the requester above lands on the body's first focusable
        // child rather than on the wrapper itself.
        Box(
            modifier = Modifier
                .focusRequester(contentFocus)
                .focusGroup(),
        ) {
            AppTabContent(
                tab = selectedTab,
                browseViewModel = browseViewModel,
                searchViewModel = searchViewModel,
                watchlistViewModel = watchlistViewModel,
                audienceFilter = audienceFilter,
                isTelevision = true,
                contentPadding = padding,
                onOpenSettings = onOpenSettings,
                onOpenShow = onOpenShow,
            )
        }
    }
}

@Composable
private fun TvNavigationTab(tab: AppTab, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(54.dp)
            .tvFocusLift(true),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        // Focus fills gold with dark ink, selection is a dark chip with gold
        // ink — the tab strip is where the two states most often coexist, so
        // they have to be told apart at a glance and not just by a ring.
        color = when {
            focused -> EdendaleColors.Gold
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> androidx.compose.ui.graphics.Color.Transparent
        },
        contentColor = when {
            focused -> EdendaleColors.OnGold
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(id = if (selected) tab.selectedIcon else tab.icon), contentDescription = null)
            Text(stringResource(tab.label), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AppTabContent(
    tab: AppTab,
    browseViewModel: BrowseViewModel,
    searchViewModel: SearchViewModel,
    watchlistViewModel: WatchlistViewModel,
    audienceFilter: YoungAudienceFilter,
    isTelevision: Boolean,
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    onOpenShow: (String) -> Unit,
) {
    when (tab) {
        AppTab.MOVIES -> MoviesShowsScreen(
            viewModel = browseViewModel,
            audienceFilter = audienceFilter,
            isTelevision = isTelevision,
            onOpenDetail = browseViewModel::openDetail,
            contentPadding = contentPadding,
            onOpenSettings = onOpenSettings,
        )
        AppTab.WATCHLIST -> WatchlistScreen(
            viewModel = watchlistViewModel,
            audienceFilter = audienceFilter,
            isTelevision = isTelevision,
            onOpenDetail = browseViewModel::openDetail,
            contentPadding = contentPadding,
            onOpenSettings = onOpenSettings,
        )
        AppTab.DOWNLOADED -> DownloadedScreen(
            audienceFilter = audienceFilter,
            isTelevision = isTelevision,
            contentPadding = contentPadding,
            onOpenSettings = onOpenSettings,
            onOpenShow = onOpenShow,
        )
        AppTab.SEARCH -> SearchScreen(
            viewModel = searchViewModel,
            audienceFilter = audienceFilter,
            isTelevision = isTelevision,
            onOpenDetail = browseViewModel::openDetail,
            onOpenPerson = browseViewModel::openFilmography,
            contentPadding = contentPadding,
            onOpenSettings = onOpenSettings,
            onOpenShow = onOpenShow,
        )
    }
}
