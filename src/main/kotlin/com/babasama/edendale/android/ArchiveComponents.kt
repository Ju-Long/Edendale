package com.babasama.edendale.android

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaType

@Composable
fun ArchiveLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.loading_the_archive).uppercase(),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ArchiveEmptyState(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(
            text = title.uppercase(),
            modifier = Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            modifier = Modifier
                .padding(top = 10.dp)
                .width(460.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(20.dp))
            it()
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = if (large) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun MediaShelf(
    title: String,
    items: List<MediaItem>,
    edgeMargin: androidx.compose.ui.unit.Dp,
    posterWidth: androidx.compose.ui.unit.Dp,
    isTelevision: Boolean,
    onItemClick: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = edgeMargin),
            large = isTelevision,
        )
        LazyRow(
            contentPadding = PaddingValues(
                start = edgeMargin,
                end = edgeMargin,
                top = if (isTelevision) 12.dp else 0.dp,
                bottom = if (isTelevision) 18.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (isTelevision) 22.dp else 16.dp),
        ) {
            items(items, key = { "${it.mediaType}-${it.id}" }) { item ->
                PosterCard(
                    item = item,
                    width = posterWidth,
                    isTelevision = isTelevision,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
fun PosterCard(
    item: MediaItem,
    width: Dp,
    isTelevision: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWatched: Boolean = false,
) {
    PosterCardShell(
        width = width,
        isTelevision = isTelevision,
        isWatched = isWatched,
        progress = null,
        onClick = onClick,
        semanticsLabel = stringResource(R.string.poster_semantics, item.title, stringResource(item.mediaType.displayName)),
        modifier = modifier,
        artwork = { m -> PosterArtwork(item, m) },
        caption = { m -> PosterCaption(item = item, isTelevision = isTelevision, modifier = m) },
    )
}

/**
 * The poster frame shared by [PosterCard] and [LibraryPosterCard]: portrait
 * artwork, an optional watched tick in the corner, an optional progress
 * hairline along the bottom edge, and a caption slot (below the art on phones,
 * overlaid on the TV layout). Only the artwork and caption differ between the
 * TMDB-backed and library-backed cards, so they arrive as slots.
 */
@Composable
private fun PosterCardShell(
    width: Dp,
    isTelevision: Boolean,
    isWatched: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    semanticsLabel: String,
    modifier: Modifier = Modifier,
    artwork: @Composable (Modifier) -> Unit,
    caption: @Composable (Modifier) -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .tvFocusLift(isTelevision)
            .semantics { contentDescription = semanticsLabel },
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        colors = CardDefaults.cardColors(containerColor = EdendaleColors.SurfaceLow),
    ) {
        if (isTelevision) {
            Box {
                artwork(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                WatchedTick(isWatched)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(EdendaleColors.Background.copy(alpha = .88f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    caption(Modifier)
                }
                ProgressHairline(progress)
            }
        } else {
            Column {
                Box {
                    artwork(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    WatchedTick(isWatched)
                    ProgressHairline(progress)
                }
                caption(Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
            }
        }
    }
}

/** Gold check badge marking a finished title; a no-op unless [isWatched]. */
@Composable
private fun BoxScope.WatchedTick(isWatched: Boolean) {
    if (!isWatched) return
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .size(26.dp)
            .background(EdendaleColors.Gold, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_check),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = EdendaleColors.OnGold,
        )
    }
}

/** Gold watch-progress line pinned to the bottom edge; a no-op when [progress] is null. */
@Composable
private fun BoxScope.ProgressHairline(progress: Float?) {
    if (progress == null) return
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(progress.coerceIn(0f, 1f))
            .height(3.dp)
            .background(EdendaleColors.Gold),
    )
}

/**
 * Poster card for imported library titles, which may have no TMDB id yet — so
 * it takes raw fields instead of a [MediaItem]. Visually identical to
 * [PosterCard]; [mediaType] only selects the placeholder icon when no poster
 * has been fetched.
 */
@Composable
fun LibraryPosterCard(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    width: Dp,
    isTelevision: Boolean,
    isWatched: Boolean = false,
    progress: Float? = null,
    mediaType: MediaType? = null,
    onClick: () -> Unit,
) {
    PosterCardShell(
        width = width,
        isTelevision = isTelevision,
        isWatched = isWatched,
        progress = progress,
        onClick = onClick,
        semanticsLabel = title,
        artwork = { m ->
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    modifier = m,
                    contentScale = ContentScale.Crop,
                )
            } else {
                PosterPlaceholder(modifier = m, mediaType = mediaType)
            }
        },
        caption = { m -> LibraryPosterCaption(title, subtitle, isTelevision, m) },
    )
}

@Composable
private fun LibraryPosterCaption(
    title: String,
    subtitle: String?,
    isTelevision: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (isTelevision) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        subtitle?.let {
            Text(
                text = it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PosterCaption(
    item: MediaItem,
    isTelevision: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = item.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (isTelevision) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.year?.let { year ->
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.voteAverage?.takeIf { it > 0 }?.let { score ->
                if (item.year != null) Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_star),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = score.formatScore(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LandscapeCard(
    item: MediaItem,
    isTelevision: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .tvFocusLift(isTelevision),
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        colors = CardDefaults.cardColors(containerColor = EdendaleColors.SurfaceLow),
    ) {
        Box(Modifier.fillMaxSize()) {
            val model = item.backdropUrl() ?: item.posterUrl()
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                PosterPlaceholder(mediaType = item.mediaType)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(EdendaleColors.Background.copy(alpha = .88f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (isTelevision) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.titleMedium,
                )
            }
            ProgressHairline(progress)
        }
    }
}

@Composable
private fun PosterArtwork(item: MediaItem, modifier: Modifier = Modifier) {
    val model = item.posterUrl()
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        PosterPlaceholder(modifier = modifier, mediaType = item.mediaType)
    }
}

@Composable
fun PosterPlaceholder(
    modifier: Modifier = Modifier,
    mediaType: MediaType? = null,
) {
    Box(
        modifier = modifier.background(EdendaleColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = when (mediaType) {
                MediaType.MOVIE -> R.drawable.ic_film
                MediaType.TV -> R.drawable.ic_tv
                null -> R.drawable.ic_image_broken
            }),
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
fun currentWindowSizeDp(): DpSize {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(size.width.toDp(), size.height.toDp()) }
}

/**
 * Focus highlight for full-width rows — settings sections, list rows — where the
 * poster-style scale lift of [tvFocusLift] would distort a block that spans the
 * screen. It also makes the block itself focusable, which is what lets the D-pad
 * walk (and therefore scroll) a list whose only other focus targets are buttons.
 */
fun Modifier.tvFocusableBlock(enabled: Boolean): Modifier = if (!enabled) this else composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 2.dp else 0.dp,
            color = if (focused) EdendaleColors.Gold else Color.Transparent,
            shape = RoundedCornerShape(EdendaleRadii.Group.dp),
        )
        .focusable()
}

/**
 * [shape] should match the control being lifted — round icon buttons need a
 * circle, or the ring reads as a stray box floating around the glyph.
 */
fun Modifier.tvFocusLift(
    enabled: Boolean,
    shape: Shape = RoundedCornerShape(EdendaleRadii.Card.dp),
): Modifier = if (!enabled) this else composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.07f else 1f,
        animationSpec = tween(140),
        label = "TV focus scale",
    )
    this
        .onFocusChanged { focused = it.isFocused }
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (focused) {
                Modifier
                    .shadow(20.dp, shape)
                    .border(2.dp, EdendaleColors.Gold, shape)
            } else Modifier,
        )
}

/**
 * Five-star rating on TMDB's 0–10 scale. Tapping a star cycles it full → half →
 * removed, mirroring Apple's StarRatingControl: for star N, full = N*2 and
 * half = full-1; removing a star falls back to the full stars before it, or
 * clears the rating when N is the first star.
 */
@Composable
fun StarRatingRow(
    rating: Double?,
    onSetRating: (Double?) -> Unit,
    isTelevision: Boolean = false,
) {
    val current = rating ?: 0.0
    val starSize = if (isTelevision) 30.dp else 24.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { index ->
            val full = index * 2.0
            val half = full - 1.0
            val icon = when {
                current >= full -> R.drawable.ic_star_fill
                current >= half -> R.drawable.ic_star_half
                else -> R.drawable.ic_star
            }
            IconButton(onClick = { onSetRating(nextStarRating(index, current)) }) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = null,
                    modifier = Modifier.size(starSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** One cell of the Critical Consensus scorecard: caps label over a large value. */
@Composable
fun ScoreTile(
    source: String,
    value: String,
    suffix: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = source.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = value,
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            suffix?.let {
                Text(
                    text = it,
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@get:StringRes
private val MediaType.displayName: Int
    get() = if (this == MediaType.MOVIE) R.string.media_type_movie else R.string.media_type_series

private fun Double.formatScore(): String = ((this * 10).toInt() / 10.0).toString()
