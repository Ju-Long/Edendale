package com.babasama.edendale.android

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Container and content a control paints while it holds focus. Scaling and
 * ringing a control leaves its own colours untouched, which is right for a
 * poster — the artwork is the subject — and wrong for a bare glyph or label,
 * which reads identically focused and unfocused from across a room. Filling
 * with gold and handing the content [EdendaleColors.OnGold] to draw on is the
 * same pair the player's segmented controls already use for "selected", so
 * focus and selection speak one visual language.
 */
private val FocusedContainer = EdendaleColors.Gold
private val FocusedContent = EdendaleColors.OnGold

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

/** The three button weights the design language uses. */
enum class ArchiveButtonKind { Primary, Secondary, Ghost }

/**
 * The app's button. Every kind repaints its own container and label on focus
 * rather than leaning on Material's state layer, because a remote has no
 * pointer: the focused control is the only thing saying where you are, and it
 * has to say it from the far side of a room.
 */
@Composable
fun ArchiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: ArchiveButtonKind = ArchiveButtonKind.Ghost,
    iconRes: Int? = null,
    isTelevision: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(EdendaleRadii.Soft.dp)
    // Apple casts the glow on hover for filled/outlined kinds, but only on focus for ghost.
    val glowing = focused || (hovered && kind != ArchiveButtonKind.Ghost)
    val contentPadding = when {
        kind == ArchiveButtonKind.Ghost && isTelevision -> PaddingValues(20.dp, 12.dp)
        kind == ArchiveButtonKind.Ghost -> PaddingValues(6.dp, 6.dp)
        isTelevision -> PaddingValues(32.dp, 16.dp)
        else -> PaddingValues(22.dp, 14.dp)
    }
    // Filled buttons already sit on gold, so focus brightens them the rest of
    // the way; the transparent kinds gain the fill outright and swap their
    // label to the dark ink that reads on it.
    val container by animateColorAsState(
        targetValue = when {
            kind == ArchiveButtonKind.Primary && focused -> EdendaleColors.Gold
            kind == ArchiveButtonKind.Primary -> EdendaleColors.GoldDeep
            focused -> FocusedContainer
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "Button focus container",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            kind == ArchiveButtonKind.Primary -> EdendaleColors.Background
            focused -> FocusedContent
            else -> EdendaleColors.Gold
        },
        animationSpec = tween(140),
        label = "Button focus content",
    )
    val buttonModifier = modifier
        .tvFocusLift(isTelevision, shape)
        .shadow(
            elevation = if (glowing) 14.dp else 0.dp,
            shape = shape,
            ambientColor = EdendaleColors.Gold,
            spotColor = EdendaleColors.Gold,
        )
    val content: @Composable RowScope.() -> Unit = {
        if (iconRes != null) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(if (isTelevision) 18.dp else 12.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label.uppercase(),
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = if (isTelevision) 16.sp else 12.sp,
                lineHeight = if (isTelevision) 22.sp else 16.sp,
            ),
        )
    }

    when (kind) {
        ArchiveButtonKind.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = labelColor,
            ),
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content,
        )

        ArchiveButtonKind.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = container,
                contentColor = labelColor,
            ),
            border = BorderStroke(
                1.dp,
                if (glowing) EdendaleColors.Gold else EdendaleColors.GoldDeep,
            ),
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content,
        )

        ArchiveButtonKind.Ghost -> TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(
                containerColor = container,
                contentColor = labelColor,
            ),
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content,
        )
    }
}

/**
 * Filter chip whose focus reads as clearly as its selection. Unselected chips
 * are outlines on the background, so Material leaves focus with nothing to
 * change; here focus fills the chip the same gold selection uses, and the ring
 * and lift of [tvFocusLift] are what separate "the remote is here" from "this
 * one is on".
 */
@Composable
fun ArchiveFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isTelevision: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val container by animateColorAsState(
        targetValue = if (focused) FocusedContainer else Color.Transparent,
        animationSpec = tween(140),
        label = "Chip focus container",
    )
    val ink by animateColorAsState(
        targetValue = if (focused) FocusedContent else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(140),
        label = "Chip focus content",
    )
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.tvFocusLift(isTelevision, FilterChipDefaults.shape),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container,
            labelColor = ink,
            iconColor = ink,
            selectedContainerColor = if (focused) FocusedContainer
            else MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        interactionSource = interactionSource,
    )
}

/**
 * Icon button that answers the D-pad. A plain [IconButton] leaves a lone glyph
 * on a transparent container, so focus changes nothing you can see; this fills
 * the circle and inverts the glyph.
 *
 * [content] is handed the focus state because glyphs that already colour
 * themselves to show state — a gold heart for a favourite — would otherwise
 * disappear into the gold fill. Buttons whose glyph takes the ambient content
 * colour can ignore it.
 */
@Composable
fun ArchiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isTelevision: Boolean = false,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val container by animateColorAsState(
        targetValue = if (focused) FocusedContainer else Color.Transparent,
        animationSpec = tween(140),
        label = "Icon button focus container",
    )
    val glyph by animateColorAsState(
        targetValue = if (focused) FocusedContent else LocalContentColor.current,
        animationSpec = tween(140),
        label = "Icon button focus content",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier.tvFocusLift(isTelevision, CircleShape),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = container,
            contentColor = glyph,
        ),
        interactionSource = interactionSource,
    ) {
        content(focused)
    }
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
    modifier: Modifier = Modifier,
    isTelevision: Boolean = false,
) {
    val current = rating ?: 0.0
    val starSize = if (isTelevision) 30.dp else 24.dp
    // IconButton centres a 48dp touch target on the icon, which indents the row
    // against whatever it sits under. Pull that padding back off the leading
    // edge so the stars line up with the text above them.
    val leadingInset = (48.dp - starSize) / 2
    Row(
        modifier = modifier.offset(x = -leadingInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (1..5).forEach { index ->
            val full = index * 2.0
            val half = full - 1.0
            val lit = current >= half
            val icon = when {
                current >= full -> R.drawable.ic_star_fill
                lit -> R.drawable.ic_star_half
                else -> R.drawable.ic_star
            }
            // Five identical glyphs in a row: without a fill, nothing says
            // which one the remote is on.
            ArchiveIconButton(
                onClick = { onSetRating(nextStarRating(index, current)) },
                isTelevision = isTelevision,
            ) { focused ->
                Icon(
                    painter = painterResource(id = icon),
                    // Every star is the same glyph, so the score it sets is the
                    // only thing that tells them apart by ear.
                    contentDescription = stringResource(R.string.detail_rate_score, full.toInt()),
                    modifier = Modifier.size(starSize),
                    tint = when {
                        focused -> FocusedContent
                        lit -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
