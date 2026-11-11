package com.babasama.edendale.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.data.SourceScanRules
import com.babasama.edendale.android.parental.rememberParentalGate
import com.babasama.edendale.android.parental.restrictedMessageRes
import com.babasama.edendale.android.parental.restrictedTitleRes
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.android.player.PlayerLogic
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl
import com.babasama.edendale.parental.GatedAction
import com.babasama.edendale.parental.ParentalGateRules
import com.babasama.edendale.parental.ParentalPolicy
import com.babasama.edendale.tmdb.TmdbEpisodeDetail

/**
 * One page for every title (Apple's `MediaDetailView`): a TMDB title, or a
 * library movie or show opened from Downloaded or Search ([DetailSource]). A
 * library page draws from its row until, or unless, TMDB has a record for
 * it; a page whose movie the library holds can play it, and one whose show
 * the library holds lists its episodes, merged across every copy, in place
 * of TMDB's season browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailScreen(
    state: DetailUiState,
    audienceFilter: YoungAudienceFilter,
    isTelevision: Boolean,
    onBack: () -> Unit,
    onToggleFavourite: (MediaRef) -> Unit = {},
    onToggleWatchlist: (MediaRef) -> Unit = {},
    onSetRating: (MediaRef, Double?) -> Unit = { _, _ -> },
    /** Mark Watched, with the page's live watch state. */
    onToggleWatched: (MediaRef, Boolean) -> Unit = { _, _ -> },
    onOpenFilmography: (Int, String) -> Unit = { _, _ -> },
    onSelectSeason: (Int) -> Unit = {},
    onToggleEpisodeWatched: (TmdbEpisodeDetail) -> Unit = {},
    /** The library movie behind the page, whenever it loads or changes. */
    onLibraryMovie: (LibraryMovieEntity) -> Unit = {},
    /** The library show behind the page, whenever it loads or changes. */
    onLibraryShow: (LibraryShowEntity) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val ref = state.ref
    val context = LocalContext.current

    // The library, live: the movie the page plays, its copies, and its watch
    // state, which the player changes while the page stays open. Null until
    // Room answers, as on the show page.
    val library = rememberLibrary()
    val movies by library.movies.collectAsState(initial = null)
    val shows by library.shows.collectAsState(initial = null)
    val episodes by library.episodes.collectAsState(initial = null)
    val folders by library.folders.collectAsState(initial = null)
    val progressList by library.watchProgress.collectAsState(initial = null)
    // The source each title last played from (27.1): Play starts there, and Play From marks it.
    val lastSources by library.sourceMemory.changes.collectAsState()
    val libraryLoaded = movies != null && shows != null && episodes != null && folders != null
    val foldersByUri = remember(folders) { folders.orEmpty().associateBy { it.treeUri } }
    val pageMovie = remember(state.source, movies, foldersByUri) {
        movies?.let { pageMovie(state.source, it, foldersByUri) }
    }
    val copies = remember(pageMovie, movies, foldersByUri) {
        pageMovie?.let { movieCopies(it, movies.orEmpty(), foldersByUri) }.orEmpty()
    }
    val pageShow = remember(state.source, shows, episodes, foldersByUri, lastSources) {
        if (shows == null || episodes == null) null else pageShow(state.source, shows.orEmpty(), episodes.orEmpty(), foldersByUri, lastSources)
    }
    val header = detailHeader(state.detail, pageMovie, pageShow?.show)
    val movieId = ref?.takeIf { it.mediaType == MediaType.MOVIE }?.id ?: pageMovie?.tmdbId
    val progressByKey = remember(progressList) { progressList.orEmpty().byStorageKey() }
    val progress = remember(progressList, movieId) { progressList?.byStorageKey()?.forMovie(movieId) }
    val isWatched = progress?.isCompleted == true

    LaunchedEffect(pageMovie) { pageMovie?.let(onLibraryMovie) }
    LaunchedEffect(pageShow?.show) { pageShow?.show?.let(onLibraryShow) }
    // A library page whose file or show left the library (removed, or
    // rescanned away) has nothing to show.
    val rowGone = (state.source is DetailSource.LocalMovie && movies != null && pageMovie == null) ||
        (state.source is DetailSource.LocalShow && shows != null && episodes != null && pageShow == null)
    LaunchedEffect(rowGone) { if (rowGone) onBack() }

    // The title the policy judges: the page's TMDB title, or a library row's
    // match the moment the row has one. A library show without a match has
    // none, so it fails closed while a rule is on, as an unmatched movie does.
    val gateRef = pageGateRef(ref, pageMovie, pageShow?.show)
    LaunchedEffect(
        gateRef,
        audienceFilter.isEnabled,
        audienceFilter.contextIdentifier,
    ) {
        if (gateRef != null) audienceFilter.verify(listOf(gateRef))
    }
    // Fails closed: an active policy blocks the page until this title is
    // verified as allowed, so a deep link cannot flash restricted content,
    // and a file without a TMDB match stays blocked (O-D8).
    val restricted = detailRestricted(audienceFilter.isEnabled, gateRef, audienceFilter::allows)
    // Undecided while the policy is unread, the title unverified, or a library page's row not loaded yet.
    val verifying = audienceFilter.policy == null ||
        (gateRef != null && audienceFilter.isVerifying(listOf(gateRef))) ||
        (state.source is DetailSource.LocalMovie && movies == null) ||
        (state.source is DetailSource.LocalShow && (shows == null || episodes == null))
    // O-D10: with Block trailers and links on, Watch Trailer is gone and no trailer is ever fetched.
    val trailersAllowed = ParentalGateRules.allowsTrailers(audienceFilter.policy)
    val windowSize = currentWindowSizeDp()
    val topBarEdgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    val contentFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val firstEpisodeFocus = remember { FocusRequester() }
    // Remove from Library asks for the parental PIN while library changes are locked (O.5).
    val gate = rememberParentalGate(isTelevision)

    // TV: the page opens with focus on Play — a show's first episode — else
    // on its content, once per title (as the player's playlist panel opens on
    // the playing row). A link can open another title in place, which builds
    // its content afresh.
    val contentShown = header != null && !restricted && libraryLoaded
    var initialFocusTaken by remember(state.source) { mutableStateOf(false) }
    LaunchedEffect(contentShown, state.source) {
        if (!isTelevision || !contentShown || initialFocusTaken) return@LaunchedEffect
        initialFocusTaken = true
        // The hero and the episodes are lazy items: they compose in the layout pass after this starts.
        withFrameNanos { }
        val first = when {
            pageMovie != null -> playFocus
            pageShow?.slots?.isNotEmpty() == true -> firstEpisodeFocus
            else -> contentFocus
        }
        runCatching { first.requestFocus() }.onFailure { runCatching { contentFocus.requestFocus() } }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DetailTopBar(
                // The marks show once they are read: each toggle flips what it shows.
                ref = ref?.takeIf { header != null && !restricted && state.userMediaLoaded && progressList != null },
                userMedia = state.userMedia,
                isWatched = isWatched,
                isTelevision = isTelevision,
                edgeMargin = topBarEdgeMargin,
                contentFocus = contentFocus,
                onBack = onBack,
                onToggleFavourite = onToggleFavourite,
                onToggleWatchlist = onToggleWatchlist,
                onToggleWatched = { onToggleWatched(it, isWatched) },
            )
        },
    ) { _ ->
        // The detail artwork remains full-bleed behind the transparent top bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusGroup(),
        ) {
            when {
                restricted && verifying -> ArchiveLoadingState()
                restricted -> AudienceRestrictedState(policy = audienceFilter.policy, isTelevision = isTelevision, onBack = onBack)
                // Keyed to the title: a link that opens another one in place
                // starts it at the top with its trailer closed (a trailer only
                // ever plays on a tap).
                header != null -> key(state.source) {
                    DetailContent(
                        header = header,
                        state = state,
                        progress = progress,
                        trailersAllowed = trailersAllowed,
                        isTelevision = isTelevision,
                        playActions = if (pageMovie == null) null else {
                            {
                                PlayActions(
                                    copies = copies,
                                    foldersByUri = foldersByUri,
                                    remembered = PlaybackSources.movieMemoryKey(pageMovie.tmdbId ?: copies.firstNotNullOfOrNull { it.tmdbId })
                                        ?.let(lastSources::get),
                                    resumes = PlayerLogic.resumeFraction(progress) != null,
                                    isTelevision = isTelevision,
                                    playFocus = playFocus,
                                    onPlay = { copy, remembersSource ->
                                        // The player picks up where the title stopped by itself.
                                        PlayerActivity.play(
                                            context = context,
                                            uri = copy.uri,
                                            title = header.title,
                                            // An alias whose match is pending plays as its title.
                                            tmdbId = copy.tmdbId ?: copies.firstNotNullOfOrNull { it.tmdbId },
                                            isEpisode = false,
                                            rememberSource = remembersSource,
                                        )
                                    },
                                )
                            }
                        },
                        localEpisodes = pageShow?.let { page ->
                            LocalEpisodes(
                                seasons = page.slots.groupBy { it.season }.toSortedMap().toList(),
                                firstRowFocus = firstEpisodeFocus,
                                row = { slot, rowFocus ->
                                    PageEpisodeRow(
                                        page = page,
                                        slot = slot,
                                        foldersByUri = foldersByUri,
                                        progress = progressByKey.forEpisode(slot.copies.firstNotNullOfOrNull { it.tmdbId }),
                                        isTelevision = isTelevision,
                                        focusRequester = rowFocus,
                                        onToggleWatched = { episode ->
                                            val watched = progressByKey.forEpisode(episode.tmdbId)?.isCompleted == true
                                            library.setEpisodeWatched(episode, page.show.tmdbId, !watched)
                                        },
                                        onRemove = { copies ->
                                            gate.require(GatedAction.LIBRARY_EDIT) { copies.forEach { library.removeEpisode(it.uri) } }
                                        },
                                    )
                                },
                            )
                        },
                        onSetRating = onSetRating,
                        onOpenFilmography = onOpenFilmography,
                        onSelectSeason = onSelectSeason,
                        onToggleEpisodeWatched = onToggleEpisodeWatched,
                    )
                }
                state.errorMessage != null -> ArchiveEmptyState(
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_film),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    },
                    title = stringResource(R.string.empty_reel_snapped_title),
                    message = state.errorMessage,
                    action = {
                        ArchiveButton(
                            label = stringResource(R.string.action_go_back),
                            onClick = onBack,
                            kind = ArchiveButtonKind.Primary,
                            isTelevision = isTelevision,
                        )
                    },
                )
                // TMDB still loading, or a library page waiting for its row.
                else -> ArchiveLoadingState()
            }
        }
    }
}

/**
 * Play — Resume where the player would resume — and beside it Play From when
 * the library holds the movie more than once (D.5): the same file through
 * another link counts (27.1), as on Apple's action row. Play starts the copy
 * on the source the movie last played from ([remembered]), else the first
 * reachable one — what its Continue Watching card starts. Play From lists
 * every copy in its stable order with that source's copy marked "Last
 * played"; a pick there is the viewer's choice and is remembered.
 */
@Composable
private fun PlayActions(
    copies: List<LibraryMovieEntity>,
    foldersByUri: Map<String, LibraryFolderEntity>,
    remembered: String?,
    resumes: Boolean,
    isTelevision: Boolean,
    playFocus: FocusRequester,
    onPlay: (LibraryMovieEntity, Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchiveButton(
            label = stringResource(if (resumes) R.string.action_resume else R.string.action_play),
            onClick = { moviePlayStart(copies, foldersByUri, remembered)?.let { onPlay(it.copy, it.remembersSource) } },
            modifier = Modifier.focusRequester(playFocus),
            kind = ArchiveButtonKind.Primary,
            iconRes = R.drawable.ic_play,
            isTelevision = isTelevision,
        )
        if (copies.size > 1) {
            val ordered = PlaybackSources.order(null, copies, { foldersByUri.copySource(it.folderUri) }, { it.uri })
            PlayFromButton(
                copies = ordered,
                source = { foldersByUri.copySource(it.folderUri) },
                path = { it.uri },
                onPlay = { onPlay(it, true) },
                isTelevision = isTelevision,
                marked = lastPlayedCopy(ordered, { it.folderUri }, remembered),
                markLabel = R.string.play_from_last_played,
            )
        }
    }
}

/** A library show's episodes on its page: its seasons, and how to draw one episode. */
private class LocalEpisodes(
    val seasons: List<Pair<Int, List<PlaybackSources.EpisodeSlot<LibraryEpisodeEntity>>>>,
    /** The first episode's focus, which TV takes as the page opens. */
    val firstRowFocus: FocusRequester,
    val row: @Composable (PlaybackSources.EpisodeSlot<LibraryEpisodeEntity>, FocusRequester?) -> Unit,
)

/**
 * One episode of a library show's page, merged across every copy of the
 * show (D.5): [LocalEpisodeRow], which the show's own page drew before 27.1.
 * One copy plays on a tap; several open Play From with the copy on the
 * source the show last played from checked. Only copies whose removal lasts
 * can be removed (D21).
 */
@Composable
private fun PageEpisodeRow(
    page: PageShow,
    slot: PlaybackSources.EpisodeSlot<LibraryEpisodeEntity>,
    foldersByUri: Map<String, LibraryFolderEntity>,
    progress: WatchProgress?,
    isTelevision: Boolean,
    focusRequester: FocusRequester?,
    onToggleWatched: (LibraryEpisodeEntity) -> Unit,
    onRemove: (List<LibraryEpisodeEntity>) -> Unit,
) {
    val context = LocalContext.current
    // The row reads as its matched copy: a link added after the others may
    // still wait for TMDB, and plays under the episode's id meanwhile.
    val episode = slot.copies.firstOrNull { it.tmdbId != null } ?: slot.primary
    val play: (LibraryEpisodeEntity) -> Unit = { copy ->
        PlayerActivity.play(
            context = context,
            uri = copy.uri,
            title = copy.title ?: episode.title ?: copy.fileName,
            tmdbId = copy.tmdbId ?: episode.tmdbId,
            isEpisode = true,
            showTmdbId = page.show.tmdbId,
            season = copy.season,
            episode = copy.episode,
        )
    }
    val removable = slot.copies.filter { SourceScanRules.removalLasts(it.folderUri) }
    LocalEpisodeRow(
        episode = episode,
        copies = slot.copies,
        foldersByUri = foldersByUri,
        progress = progress,
        isTelevision = isTelevision,
        onRemove = if (removable.isEmpty()) null else ({ onRemove(removable) }),
        onPlay = {
            // The source the show last played from, else the first reachable copy (D.5).
            val copy = PlaybackSources.startingCopy(slot.copies, { PlaybackSources.sourceKey(it.folderUri) }, page.remembered) {
                foldersByUri.copySource(it.folderUri).isUnavailable
            } ?: episode
            play(copy)
        },
        onPlayCopy = play,
        onToggleWatched = { onToggleWatched(episode) },
        lastPlayed = lastPlayedCopy(slot.copies, { it.folderUri }, page.remembered),
        focusRequester = focusRequester,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    /** The title the actions mark; null hides them. */
    ref: MediaRef?,
    userMedia: com.babasama.edendale.domain.UserMediaRecord?,
    isWatched: Boolean,
    isTelevision: Boolean,
    edgeMargin: androidx.compose.ui.unit.Dp,
    contentFocus: FocusRequester,
    onBack: () -> Unit,
    onToggleFavourite: (MediaRef) -> Unit,
    onToggleWatchlist: (MediaRef) -> Unit,
    onToggleWatched: (MediaRef) -> Unit,
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            Box(
                modifier = Modifier
                    .padding(start = edgeMargin)
                    .background(
                        color = EdendaleColors.SurfaceLow.copy(alpha = .9f),
                        shape = CircleShape,
                    ),
            ) {
                ArchiveIconButton(
                    onClick = onBack,
                    modifier = Modifier.focusProperties {
                        if (isTelevision) down = contentFocus
                    },
                    isTelevision = isTelevision,
                ) { focused ->
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_left),
                        contentDescription = stringResource(R.string.action_back),
                        tint = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        actions = {
            ref?.let {
                Box(
                    modifier = Modifier
                        .padding(end = edgeMargin)
                        .background(
                            color = EdendaleColors.SurfaceLow.copy(alpha = .9f),
                            shape = RoundedCornerShape(50),
                        ),
                ) {
                    UserMediaActions(
                        ref = it,
                        userMedia = userMedia,
                        isWatched = isWatched,
                        isTelevision = isTelevision,
                        onToggleFavourite = onToggleFavourite,
                        onToggleWatchlist = onToggleWatchlist,
                        onToggleWatched = onToggleWatched,
                        modifier = Modifier.focusProperties {
                            if (isTelevision) down = contentFocus
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
    )
}

/** The blocked page: the Young Audience wording for the preset alone, the rating-limit wording once a ceiling exists. */
@Composable
private fun AudienceRestrictedState(
    policy: ParentalPolicy?,
    isTelevision: Boolean,
    onBack: () -> Unit,
) {
    ArchiveEmptyState(
        icon = {
            Icon(
                painter = painterResource(id = R.drawable.ic_circle_xmark),
                contentDescription = null,
                modifier = Modifier.size(if (isTelevision) 64.dp else 48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        title = stringResource(restrictedTitleRes(policy)),
        message = stringResource(restrictedMessageRes(policy)),
        action = {
            ArchiveButton(
                label = stringResource(R.string.action_go_back),
                onClick = onBack,
                kind = ArchiveButtonKind.Primary,
                isTelevision = isTelevision,
            )
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DetailContent(
    header: DetailHeader,
    state: DetailUiState,
    /** The title's live watch state. */
    progress: WatchProgress?,
    /** False while Block trailers and links is on (O-D10): no Watch Trailer button, and a playing trailer stops. */
    trailersAllowed: Boolean,
    isTelevision: Boolean,
    /** Play and Play From, when the library holds the movie. */
    playActions: (@Composable () -> Unit)?,
    /** The episodes, when the library holds the show: they take the season browser's place. */
    localEpisodes: LocalEpisodes?,
    onSetRating: (MediaRef, Double?) -> Unit,
    onOpenFilmography: (Int, String) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleEpisodeWatched: (TmdbEpisodeDetail) -> Unit,
) {
    // Cast and seasons come from TMDB alone; the rest of the page from the header.
    val detail = state.detail
    val ref = state.ref
    val trailer = state.trailer?.takeIf { trailersAllowed }
    // Without remember this reset to false on the next recomposition, so the
    // trailer closed itself the moment anything else in the screen changed.
    // Keyed to the TMDB title: a library page matched to another title while
    // open must not start that title's trailer when it lands.
    var showTrailer by remember(ref) { mutableStateOf(false) }
    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    val heroHeight = when {
        isTelevision -> (windowSize.height * .72f).coerceIn(340.dp, 520.dp)
        windowSize.width >= 600.dp -> 560.dp
        else -> 500.dp
    }

    // The sections keep 30 dp apart, and a show's episodes 12 dp. Each item
    // carries its own gap, so a long season is one lazy item per episode.
    val sectionGap = 30.dp
    // TV's default scroll puts whatever takes focus 30% down the screen, which
    // pushed the hero's title off the top as Play or a show's first episode
    // took focus with the page. Something already on screen now stays put.
    val platformSpec = LocalBringIntoViewSpec.current
    val bringIntoView = remember(platformSpec) { OnScreenStaysBringIntoViewSpec(platformSpec) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item("hero") {
            // At least the hero's height, and taller when the title, tagline,
            // Play and stars need more (a large font size), rather than
            // squeezing the stars; the artwork fills whatever it ends up.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = heroHeight),
            ) {
                val backdrop = tmdbImageUrl(header.backdropPath, TmdbImageSize.ORIGINAL)
                if (showTrailer && trailer != null) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { context ->
                            android.webkit.WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                webChromeClient = android.webkit.WebChromeClient()
                                setBackgroundColor(android.graphics.Color.BLACK)
                                loadUrl(
                                    TrailerEmbed.url(trailer.key),
                                    TrailerEmbed.headers(context.packageName),
                                )
                            }
                        },
                        modifier = Modifier.matchParentSize()
                    )
                } else if (backdrop != null) {
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    PosterPlaceholder(modifier = Modifier.matchParentSize(), mediaType = header.mediaType)
                }
                if (!showTrailer || trailer == null) {
                    Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    EdendaleColors.Background.copy(alpha = .98f),
                                    EdendaleColors.Background.copy(alpha = .55f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    EdendaleColors.Background.copy(alpha = .3f),
                                    Color.Transparent,
                                    EdendaleColors.Background,
                                ),
                            ),
                        ),
                )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = edgeMargin, vertical = 30.dp)
                        .fillMaxWidth(if (isTelevision) .72f else .9f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (header.mediaType == MediaType.MOVIE) {
                                R.string.detail_kind_feature_film
                            } else {
                                R.string.detail_kind_series
                            },
                        ).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = header.title.uppercase(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = if (isTelevision) MaterialTheme.typography.displayLarge
                        else MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    header.tagline?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!isTelevision && trailer != null) {
                        androidx.compose.material3.OutlinedButton(onClick = { showTrailer = !showTrailer }) {
                            Icon(painterResource(id = if (showTrailer) R.drawable.ic_eye_slash else R.drawable.ic_play), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (showTrailer) R.string.detail_hide_trailer else R.string.detail_watch_trailer))
                        }
                    }
                    DetailMetadata(header, progress)
                    // A rating needs a TMDB title, so a file without a match has
                    // none, and it shows once the user's own rating is read.
                    val stars: (@Composable () -> Unit)? = if (ref == null || !state.userMediaLoaded) null else {
                        {
                            StarRatingRow(
                                rating = state.userMedia?.rating,
                                onSetRating = { onSetRating(ref, it) },
                                isTelevision = isTelevision,
                            )
                        }
                    }
                    // Play comes after the metadata and ahead of the stars, so
                    // on TV it is the first thing the remote reaches. TV keeps
                    // the two on one line, as its shorter hero has no room for
                    // another; elsewhere the five stars take a line of their
                    // own so the metadata stays readable within the hero. The
                    // TV line stays one Row whichever of the two is there, so
                    // a page gaining a match (the stars) or a library copy
                    // (Play) keeps the remote's focus where it is.
                    if (isTelevision) {
                        if (playActions != null || stars != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                playActions?.invoke()
                                stars?.invoke()
                            }
                        }
                    } else {
                        playActions?.invoke()
                        stars?.invoke()
                    }
                }
            }
        }

        // TV: a show's episodes come straight after the hero, where Play
        // would be, so the remote starts on the first one; elsewhere they
        // take the season browser's place at the foot of the page.
        if (isTelevision) localEpisodes?.let { localSeasons(it, edgeMargin, isTelevision, sectionGap) }

        header.overview?.let { overview ->
            item("overview") {
                Column(
                    modifier = Modifier.padding(start = edgeMargin, end = edgeMargin, top = sectionGap),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader(stringResource(R.string.detail_section_story), large = isTelevision)
                    Text(
                        text = overview,
                        modifier = Modifier.fillMaxWidth(if (isTelevision) .76f else 1f),
                        style = if (isTelevision) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val cast = detail?.cast.orEmpty()
        if (cast.isNotEmpty()) {
            item("cast-title") {
                SectionHeader(
                    title = stringResource(R.string.detail_section_cast),
                    modifier = Modifier.padding(start = edgeMargin, end = edgeMargin, top = sectionGap),
                    large = isTelevision,
                )
            }
            item("cast") {
                LazyRow(
                    modifier = Modifier.padding(top = sectionGap),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = edgeMargin,
                        vertical = if (isTelevision) 12.dp else 0.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(cast, key = { it.id }) { member ->
                        Surface(
                            onClick = { onOpenFilmography(member.id, member.name) },
                            modifier = Modifier
                                .width(if (isTelevision) 230.dp else 180.dp)
                                .tvFocusLift(isTelevision),
                            shape = RoundedCornerShape(EdendaleRadii.Card.dp),
                            color = EdendaleColors.SurfaceLow,
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val profile = tmdbImageUrl(member.profilePath, TmdbImageSize.POSTER)
                                if (profile != null) {
                                    AsyncImage(
                                        model = profile,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(27.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                Column(Modifier.padding(start = if (profile != null) 10.dp else 0.dp)) {
                                    Text(
                                        member.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    member.character?.let {
                                        Text(
                                            it,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val seasons = detail?.seasons.orEmpty()
        if (localEpisodes != null) {
            if (!isTelevision) localSeasons(localEpisodes, edgeMargin, isTelevision, sectionGap)
        } else if (seasons.isNotEmpty()) {
            item("seasons") {
                Column(Modifier.padding(top = sectionGap)) {
                    SeasonBrowser(
                        seasons = seasons,
                        selectedSeason = state.selectedSeason,
                        episodes = state.selectedSeason?.let { state.episodesBySeason[it] },
                        isLoading = state.loadingSeason != null,
                        errorMessage = state.seasonErrorMessage,
                        watchedEpisodeIds = state.watchedEpisodeIds,
                        isTelevision = isTelevision,
                        edgeMargin = edgeMargin,
                        onSelectSeason = onSelectSeason,
                        onToggleWatched = onToggleEpisodeWatched,
                    )
                }
            }
        }

        item("bottom-space") { Spacer(Modifier.height(sectionGap + 56.dp)) }
    }
    }
}

/**
 * How the page scrolls to what takes focus: not at all for something already
 * wholly on screen; for something partly on screen, far enough to show it
 * with a little room past it (a focused card grows, and its ring shouldn't
 * meet the edge); and the platform's way ([platform]; on TV, 30% from the
 * top) for anything further.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class OnScreenStaysBringIntoViewSpec(private val platform: BringIntoViewSpec) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        val room = (containerSize * 0.06f).coerceAtMost((containerSize - size).coerceAtLeast(0f))
        return when {
            offset >= 0f && end <= containerSize -> 0f
            size <= containerSize && end > 0f && offset < containerSize ->
                if (offset < 0f) offset - room else end - containerSize + room
            else -> platform.calculateScrollDistance(offset, size, containerSize)
        }
    }
}

/**
 * A library show's seasons in order, Specials (season 0) first, as the show
 * page drew them: each season's header, then one lazy item per episode, so a
 * long season composes only the rows on screen.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.localSeasons(
    episodes: LocalEpisodes,
    edgeMargin: androidx.compose.ui.unit.Dp,
    isTelevision: Boolean,
    sectionGap: androidx.compose.ui.unit.Dp,
) {
    episodes.seasons.forEachIndexed { seasonIndex, (season, slots) ->
        item("local-season-$season") {
            SectionHeader(
                title = if (season == 0) stringResource(R.string.season_specials)
                else stringResource(R.string.season_number, season),
                modifier = Modifier.padding(start = edgeMargin, end = edgeMargin, top = sectionGap),
                large = isTelevision,
            )
        }
        slots.forEachIndexed { index, slot ->
            item("local-episode-${slot.id}") {
                Box(Modifier.padding(start = edgeMargin, end = edgeMargin, top = 12.dp)) {
                    episodes.row(slot, episodes.firstRowFocus.takeIf { seasonIndex == 0 && index == 0 })
                }
            }
        }
    }
}

@Composable
private fun DetailMetadata(header: DetailHeader, watchProgress: WatchProgress?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        header.year?.let {
            MetadataBadge(R.drawable.ic_calendar_days, it.toString())
        }
        header.runtimeMinutes?.let {
            val iconRes = if (watchProgress != null && !watchProgress.isCompleted && watchProgress.position > 0) {
                when {
                    watchProgress.position < 0.35 -> R.drawable.ic_hourglass_start
                    watchProgress.position < 0.75 -> R.drawable.ic_hourglass_half
                    else -> R.drawable.ic_hourglass_end
                }
            } else {
                R.drawable.ic_hourglass
            }
            MetadataBadge(iconRes, stringResource(R.string.runtime_minutes, it))
        }
        header.score?.takeIf { it > 0 }?.let {
            MetadataBadge(R.drawable.ic_star, "${((it * 10).toInt() / 10.0)}")
        }
    }
}

@Composable
private fun MetadataBadge(iconRes: Int, label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(painterResource(id = iconRes), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun UserMediaActions(
    ref: MediaRef,
    userMedia: com.babasama.edendale.domain.UserMediaRecord?,
    isWatched: Boolean,
    isTelevision: Boolean,
    onToggleFavourite: (MediaRef) -> Unit,
    onToggleWatchlist: (MediaRef) -> Unit,
    onToggleWatched: (MediaRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Shows are marked watched per episode in the season browser, so the
        // whole-title toggle only makes sense for movies.
        if (ref.mediaType == MediaType.MOVIE) {
            ArchiveIconButton(
                onClick = { onToggleWatched(ref) },
                isTelevision = isTelevision,
            ) { focused ->
                Icon(
                    painter = painterResource(id = if (isWatched) R.drawable.ic_eye else R.drawable.ic_eye_slash),
                    contentDescription = stringResource(R.string.detail_mark_watched),
                    tint = stateTint(focused, isWatched),
                )
            }
        }
        ArchiveIconButton(
            onClick = { onToggleFavourite(ref) },
            isTelevision = isTelevision,
        ) { focused ->
            Icon(
                painter = painterResource(id = if (userMedia?.favourite == true) R.drawable.ic_heart_fill else R.drawable.ic_heart),
                contentDescription = stringResource(R.string.detail_favorite),
                tint = stateTint(focused, userMedia?.favourite == true),
            )
        }
        ArchiveIconButton(
            onClick = { onToggleWatchlist(ref) },
            isTelevision = isTelevision,
        ) { focused ->
            Icon(
                painter = painterResource(id = if (userMedia?.watchlist == true) R.drawable.ic_bookmark_slash else R.drawable.ic_bookmark_plus),
                contentDescription = stringResource(R.string.detail_watchlist),
                tint = stateTint(focused, userMedia?.watchlist == true),
            )
        }
    }
}

/**
 * Tint for a toggle glyph that colours itself when it is on. Gold says "on"
 * here and gold is also the focus fill, so focus takes the glyph with it —
 * otherwise the focused favourite disappears into its own highlight.
 */
@Composable
private fun stateTint(focused: Boolean, on: Boolean): Color = when {
    focused -> EdendaleColors.OnGold
    on -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
