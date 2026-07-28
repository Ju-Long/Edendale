package com.babasama.edendale.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.tmdbImageUrl

@Composable
fun MediaDetailScreen(
    state: DetailUiState,
    isTelevision: Boolean,
    onBack: () -> Unit,
    onToggleFavourite: (com.babasama.edendale.domain.MediaRef) -> Unit = {},
    onToggleWatchlist: (com.babasama.edendale.domain.MediaRef) -> Unit = {},
    onSetRating: (com.babasama.edendale.domain.MediaRef, Double?) -> Unit = { _, _ -> },
    onToggleWatched: (com.babasama.edendale.domain.MediaRef) -> Unit = {},
    onOpenFilmography: (Int, String) -> Unit = { _, _ -> },
    onSelectSeason: (Int) -> Unit = {},
    onToggleEpisodeWatched: (com.babasama.edendale.tmdb.TmdbEpisodeDetail) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading -> ArchiveLoadingState()
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
                    Button(onClick = onBack) { Text(stringResource(R.string.action_go_back)) }
                },
            )
            state.detail != null -> DetailContent(
                state = state,
                isTelevision = isTelevision,
                onBack = onBack,
                onToggleFavourite = onToggleFavourite,
                onToggleWatchlist = onToggleWatchlist,
                onSetRating = onSetRating,
                onToggleWatched = onToggleWatched,
                onOpenFilmography = onOpenFilmography,
                onSelectSeason = onSelectSeason,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
            )
        }
    }
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    isTelevision: Boolean,
    onBack: () -> Unit,
    onToggleFavourite: (com.babasama.edendale.domain.MediaRef) -> Unit,
    onToggleWatchlist: (com.babasama.edendale.domain.MediaRef) -> Unit,
    onSetRating: (com.babasama.edendale.domain.MediaRef, Double?) -> Unit,
    onToggleWatched: (com.babasama.edendale.domain.MediaRef) -> Unit,
    onOpenFilmography: (Int, String) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleEpisodeWatched: (com.babasama.edendale.tmdb.TmdbEpisodeDetail) -> Unit,
) {
    val detail = state.detail!!
    // Without remember this reset to false on the next recomposition, so the
    // trailer closed itself the moment anything else in the screen changed.
    var showTrailer by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    val heroHeight = when {
        isTelevision -> (windowSize.height * .72f).coerceIn(340.dp, 520.dp)
        windowSize.width >= 600.dp -> 560.dp
        else -> 500.dp
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        item("hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
            ) {
                val backdrop = detail.backdropUrl()
                if (showTrailer && state.trailer != null) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { context ->
                            android.webkit.WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                webChromeClient = android.webkit.WebChromeClient()
                                setBackgroundColor(android.graphics.Color.BLACK)
                                val url = "https://www.youtube-nocookie.com/embed/${state.trailer.key}?autoplay=1&modestbranding=1&playsinline=1"
                                loadUrl(url)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (backdrop != null) {
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    PosterPlaceholder(modifier = Modifier.fillMaxSize())
                }
                if (!showTrailer) {
                    Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(edgeMargin)
                        .tvFocusLift(isTelevision),
                    onClick = onBack,
                    shape = RoundedCornerShape(50),
                    color = EdendaleColors.SurfaceLow.copy(alpha = .9f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(id = R.drawable.ic_chevron_left), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_back))
                    }
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
                            if (detail.ref.mediaType == com.babasama.edendale.domain.MediaType.MOVIE) {
                                R.string.detail_kind_feature_film
                            } else {
                                R.string.detail_kind_series
                            },
                        ).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = detail.title.uppercase(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = if (isTelevision) MaterialTheme.typography.displayLarge
                        else MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    detail.tagline?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!isTelevision && state.trailer != null) {
                        androidx.compose.material3.OutlinedButton(onClick = { showTrailer = !showTrailer }) {
                            Icon(painterResource(id = if (showTrailer) R.drawable.ic_eye_slash else R.drawable.ic_play), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (showTrailer) R.string.detail_hide_trailer else R.string.detail_watch_trailer))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DetailMetadata(detail, state.watchProgress)
                        UserMediaActions(
                            detail = detail,
                            userMedia = state.userMedia,
                            isWatched = state.watchProgress?.isCompleted == true,
                            isTelevision = isTelevision,
                            onToggleFavourite = onToggleFavourite,
                            onToggleWatchlist = onToggleWatchlist,
                            onToggleWatched = onToggleWatched,
                        )
                    }
                    // Five stars take a line of their own; sharing one with the
                    // badges and toggles ran them off the edge of a phone hero.
                    StarRatingRow(
                        rating = state.userMedia?.rating,
                        onSetRating = { onSetRating(detail.ref, it) },
                        isTelevision = isTelevision,
                    )
                }
            }
        }

        detail.overview?.let { overview ->
            item("overview") {
                Column(
                    modifier = Modifier.padding(horizontal = edgeMargin),
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

        if (detail.cast.isNotEmpty()) {
            item("cast-title") {
                SectionHeader(
                    title = stringResource(R.string.detail_section_cast),
                    modifier = Modifier.padding(horizontal = edgeMargin),
                    large = isTelevision,
                )
            }
            item("cast") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = edgeMargin,
                        vertical = if (isTelevision) 12.dp else 0.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(detail.cast, key = { it.id }) { member ->
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
        if (detail.seasons.isNotEmpty()) {
            item("seasons") {
                SeasonBrowser(
                    seasons = detail.seasons,
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

        item("bottom-space") { Spacer(Modifier.height(56.dp)) }
    }
}

@Composable
private fun DetailMetadata(detail: MediaDetail, watchProgress: com.babasama.edendale.domain.WatchProgress?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        detail.year?.let {
            MetadataBadge(R.drawable.ic_calendar_days, it.toString())
        }
        detail.runtimeMinutes?.let {
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
        detail.score?.takeIf { it > 0 }?.let {
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
    detail: MediaDetail,
    userMedia: com.babasama.edendale.domain.UserMediaRecord?,
    isWatched: Boolean,
    isTelevision: Boolean,
    onToggleFavourite: (com.babasama.edendale.domain.MediaRef) -> Unit,
    onToggleWatchlist: (com.babasama.edendale.domain.MediaRef) -> Unit,
    onToggleWatched: (com.babasama.edendale.domain.MediaRef) -> Unit,
) {
    // These take D-pad focus like any other control, but without a lift they
    // showed nothing at all when focused, so on TV they read as dead icons.
    val focusable = Modifier.tvFocusLift(isTelevision, CircleShape)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Shows are marked watched per episode in the season browser, so the
        // whole-title toggle only makes sense for movies.
        if (detail.ref.mediaType == com.babasama.edendale.domain.MediaType.MOVIE) {
            androidx.compose.material3.IconButton(
                onClick = { onToggleWatched(detail.ref) },
                modifier = focusable,
            ) {
                Icon(
                    painter = painterResource(id = if (isWatched) R.drawable.ic_eye else R.drawable.ic_eye_slash),
                    contentDescription = stringResource(R.string.detail_mark_watched),
                    tint = if (isWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        androidx.compose.material3.IconButton(
            onClick = { onToggleFavourite(detail.ref) },
            modifier = focusable,
        ) {
            Icon(
                painter = painterResource(id = if (userMedia?.favourite == true) R.drawable.ic_heart_fill else R.drawable.ic_heart),
                contentDescription = stringResource(R.string.detail_favorite),
                tint = if (userMedia?.favourite == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.IconButton(
            onClick = { onToggleWatchlist(detail.ref) },
            modifier = focusable,
        ) {
            Icon(
                painter = painterResource(id = if (userMedia?.watchlist == true) R.drawable.ic_bookmark_slash else R.drawable.ic_bookmark_plus),
                contentDescription = stringResource(R.string.detail_watchlist),
                tint = if (userMedia?.watchlist == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
