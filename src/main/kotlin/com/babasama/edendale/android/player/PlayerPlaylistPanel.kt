package com.babasama.edendale.android.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleRadii
import com.babasama.edendale.android.R
import com.babasama.edendale.android.rememberRuntimeFormat
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.tmdbImageUrl

/**
 * The playlist side panel (B.5): the show's episodes under season headings,
 * or the files in the playing file's folder. The current row and the focused
 * row both take the white fill, black text, and larger title; the playing
 * indicator tells the current one apart. The panel opens scrolled to the
 * current file — on TV with focus on it — and follows an auto-advance.
 */
@Composable
internal fun PlaylistPanel(
    chrome: PlayerChromeState,
    isTelevision: Boolean,
    currentUri: String,
    playlist: PlayerPlaylist?,
    panelWidth: Dp,
    panelFocus: FocusRequester,
    onSelectEntry: (PlaylistEntry) -> Unit,
) {
    val items = remember(playlist) { playlistItems(playlist) }
    val isEpisodeList = playlist?.isEpisodeList == true
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val reduceMotion = rememberReducedMotion()
    val runtimeFormat = rememberRuntimeFormat()

    var hasScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(currentUri, items) {
        val index = items.indexOfEntry(currentUri)
        if (index < 0) return@LaunchedEffect
        listState.centerOn(index, animate = hasScrolled && !reduceMotion)
        if (isTelevision) runCatching { currentFocus.requestFocus() }
        hasScrolled = true
    }

    PanelSurface(panelWidth, panelFocus, onDismiss = { chrome.closePanel() }) {
        Column(Modifier.padding(24.dp)) {
            PanelHeader(
                title = stringResource(if (isEpisodeList) R.string.player_episodes else R.string.player_in_this_folder),
                isTelevision = isTelevision,
                onClose = { chrome.closePanel() },
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    count = items.size,
                    key = { index ->
                        when (val item = items[index]) {
                            is PlaylistItem.Season -> "season-${item.number}"
                            is PlaylistItem.Entry -> item.entry.uri
                        }
                    },
                ) { index ->
                    when (val item = items[index]) {
                        is PlaylistItem.Season -> SeasonHeading(item.number)
                        is PlaylistItem.Entry -> {
                            val entry = item.entry
                            val isCurrent = entry.uri == currentUri
                            PlaylistRow(
                                entry = entry,
                                isCurrent = isCurrent,
                                showsArtwork = playlistShowsArtwork(entry, isEpisodeList, isCurrent),
                                playTime = entry.runtimeMinutes?.takeIf { it > 0 }?.let(runtimeFormat),
                                isTelevision = isTelevision,
                                reduceMotion = reduceMotion,
                                focusRequester = currentFocus.takeIf { isCurrent },
                                onClick = {
                                    chrome.noteInteraction()
                                    onSelectEntry(entry)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Scrolls so the line at [index] sits in the middle of the viewport. */
private suspend fun LazyListState.centerOn(index: Int, animate: Boolean) {
    if (animate) animateScrollToItem(index) else scrollToItem(index)
    val info = layoutInfo
    val line = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportMiddle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val delta = (line.offset + line.size / 2 - viewportMiddle).toFloat()
    if (delta == 0f) return
    if (animate) animateScrollBy(delta) else scrollBy(delta)
}

@Composable
private fun SeasonHeading(season: Int) {
    Text(
        text = if (season == 0) {
            stringResource(R.string.season_specials)
        } else {
            stringResource(R.string.season_number, season)
        }.uppercase(),
        modifier = Modifier
            .padding(top = 12.dp, bottom = 2.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PlaylistRow(
    entry: PlaylistEntry,
    isCurrent: Boolean,
    showsArtwork: Boolean,
    playTime: String?,
    isTelevision: Boolean,
    reduceMotion: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlighted = isCurrent || focused
    val scale by animateFloatAsState(
        targetValue = if (focused && isTelevision && !reduceMotion) 1.03f else 1f,
        animationSpec = tween(120),
        label = "Playlist row focus scale",
    )
    val shape = RoundedCornerShape(EdendaleRadii.Soft.dp)
    val ink = if (highlighted) EdendaleColors.PlaylistActiveText else MaterialTheme.colorScheme.onBackground
    val secondaryInk = if (highlighted) EdendaleColors.PlaylistActiveText else EdendaleColors.TextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (highlighted) EdendaleColors.PlaylistActiveBackground else Color.Transparent, shape)
            // The playing row stays clickable so it stays focusable; a press
            // on it does nothing.
            .clickable { if (!isCurrent) onClick() }
            .semantics { selected = isCurrent }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showsArtwork) PlaylistArtwork(entry.artworkPath)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.title,
                style = if (highlighted) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.detail?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = secondaryInk)
            }
            if (showsArtwork && playTime != null) {
                Text(text = playTime, style = MaterialTheme.typography.bodySmall, color = secondaryInk)
            }
        }
        if (isCurrent) {
            Icon(
                painter = painterResource(id = R.drawable.ic_play),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = EdendaleColors.PlaylistActiveText,
            )
        }
    }
}

@Composable
private fun PlaylistArtwork(path: String?) {
    val shape = RoundedCornerShape(EdendaleRadii.Soft.dp)
    Box(
        modifier = Modifier
            .size(width = 80.dp, height = 45.dp)
            .clip(shape)
            .background(EdendaleColors.SurfaceHigh)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_clapperboard),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = EdendaleColors.TextSecondary,
        )
        val url = remember(path) { tmdbImageUrl(path, TmdbImageSize.BACKDROP) }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
