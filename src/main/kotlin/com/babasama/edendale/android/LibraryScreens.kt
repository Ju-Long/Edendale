package com.babasama.edendale.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl

/**
 * One linked source: local folder or network share, its item count, and the
 * credential-free path. Passwords live in the credential store and are never
 * part of the URL shown here.
 */
@Composable
fun SourceRow(
    folder: LibraryFolderEntity,
    itemCount: Int,
    isTelevision: Boolean,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    val isNetwork = folder.treeUri.startsWith("smb://")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusLift(isTelevision),
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        color = EdendaleColors.SurfaceLow,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    id = if (isNetwork) R.drawable.ic_link else R.drawable.ic_folder_closed,
                ),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = folder.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.metadata_separator,
                        stringResource(
                            if (isNetwork) R.string.source_kind_network_share
                            else R.string.source_kind_local_folder_lower,
                        ),
                        pluralStringResource(R.plurals.item_count, itemCount, itemCount),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = sourceDisplayPath(folder.treeUri),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onRescan, modifier = Modifier.tvFocusLift(isTelevision)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_rotate_right),
                    contentDescription = stringResource(R.string.rescan_folder, folder.displayName),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.tvFocusLift(isTelevision)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_trash_can),
                    contentDescription = stringResource(R.string.remove_folder, folder.displayName),
                )
            }
        }
    }
}

/** Confirmation for unlinking a source — files on disk are never touched. */
@Composable
fun RemoveSourceDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_source_confirm_title, displayName)) },
        text = {
            Text(stringResource(R.string.remove_source_message_generic))
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Binds [LocalShowScreen] to the library flows. Collecting here rather
 * than in the shell keeps the query off every other tab.
 */
@Composable
fun LocalShowHost(
    showKey: String,
    isTelevision: Boolean,
    onBack: () -> Unit,
) {
    val library = rememberLibrary()
    val shows by library.shows.collectAsState(initial = emptyList())
    val episodes by library.episodes.collectAsState(initial = emptyList())
    val progressList by library.watchProgress.collectAsState(initial = emptyList())
    val show = shows.firstOrNull { it.key == showKey }

    if (show == null) {
        // Either the first frame before Room answers, or the show was removed
        // while it was open; both leave the screen escapable.
        BackHandler(onBack = onBack)
        ArchiveLoadingState()
        return
    }

    val progressByKey = progressList.byStorageKey()
    LocalShowScreen(
        show = show,
        episodes = episodes.filter { it.showKey == showKey },
        progressByKey = progressByKey,
        isTelevision = isTelevision,
        onBack = onBack,
        onToggleWatched = { episode ->
            val watched = progressByKey.forEpisode(episode.tmdbId)?.isCompleted == true
            library.setEpisodeWatched(episode, show.tmdbId, !watched)
        },
    )
}

/**
 * Local show drill-in: seasons in order, each episode playable with its watch
 * tick — parity with the season-grouped list Apple's MediaDetailView renders
 * for imported shows.
 */
@Composable
fun LocalShowScreen(
    show: LibraryShowEntity,
    episodes: List<LibraryEpisodeEntity>,
    progressByKey: Map<String, WatchProgress>,
    isTelevision: Boolean,
    onBack: () -> Unit,
    onToggleWatched: (LibraryEpisodeEntity) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    val seasons = episodes.groupBy { it.season }.toSortedMap()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = edgeMargin,
                end = edgeMargin,
                top = 16.dp,
                bottom = 56.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("header") {
                Column(
                    modifier = Modifier.statusBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BackChip(isTelevision = isTelevision, onBack = onBack)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val poster = tmdbImageUrl(show.posterPath, TmdbImageSize.POSTER)
                        if (poster != null) {
                            AsyncImage(
                                model = poster,
                                contentDescription = null,
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(144.dp)
                                    .clip(RoundedCornerShape(EdendaleRadii.Card.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            PosterPlaceholder(
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(144.dp)
                                    .clip(RoundedCornerShape(EdendaleRadii.Card.dp)),
                                mediaType = com.babasama.edendale.domain.MediaType.TV,
                            )
                        }
                        Column(
                            modifier = Modifier.padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = show.name.uppercase(),
                                style = if (isTelevision) MaterialTheme.typography.displaySmall
                                else MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = listOfNotNull(
                                    show.firstAirYear?.toString(),
                                    pluralStringResource(R.plurals.season_count, seasons.size, seasons.size),
                                    pluralStringResource(R.plurals.episode_count, episodes.size, episodes.size),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            seasons.forEach { (season, seasonEpisodes) ->
                item("season-$season") {
                    SectionHeader(
                        title = if (season == 0) stringResource(R.string.season_specials)
                        else stringResource(R.string.season_number, season),
                        modifier = Modifier.padding(top = 14.dp),
                        large = isTelevision,
                    )
                }
                items(seasonEpisodes.size, key = { seasonEpisodes[it].uri }) { index ->
                    val episode = seasonEpisodes[index]
                    val progress = progressByKey.forEpisode(episode.tmdbId)
                    LocalEpisodeRow(
                        episode = episode,
                        progress = progress,
                        isTelevision = isTelevision,
                        onPlay = {
                            PlayerActivity.play(
                                context = context,
                                uri = episode.uri,
                                title = episode.title ?: episode.fileName,
                                tmdbId = episode.tmdbId,
                                isEpisode = true,
                                showTmdbId = show.tmdbId,
                                season = episode.season,
                                episode = episode.episode,
                            )
                        },
                        onToggleWatched = { onToggleWatched(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalEpisodeRow(
    episode: LibraryEpisodeEntity,
    progress: WatchProgress?,
    isTelevision: Boolean,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    Surface(
        onClick = onPlay,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusLift(isTelevision),
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        color = EdendaleColors.SurfaceLow,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = episode.episode.toString().padStart(2, '0'),
                    modifier = Modifier.width(34.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = episode.title ?: episode.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    mediaSubtitle(null, episode.runtimeMinutes, rememberRuntimeFormat())?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Episodes only carry watch state once enrichment has given them
                // a TMDB id — progress is keyed by that id, not by file path.
                if (episode.tmdbId != null) {
                    IconButton(
                        onClick = onToggleWatched,
                        modifier = Modifier.tvFocusLift(isTelevision),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = stringResource(
                                if (progress?.isCompleted == true) R.string.mark_unwatched
                                else R.string.mark_watched,
                            ),
                            tint = if (progress?.isCompleted == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_play),
                    contentDescription = stringResource(R.string.action_play),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            progress.partialFraction()?.let { fraction ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(EdendaleColors.Gold),
                )
            }
        }
    }
}

/** The escape hatch every full-screen override needs, clear of the status bar. */
@Composable
fun BackChip(isTelevision: Boolean, onBack: () -> Unit) {
    Surface(
        onClick = onBack,
        modifier = Modifier.tvFocusLift(isTelevision),
        shape = RoundedCornerShape(50),
        color = EdendaleColors.SurfaceLow,
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
}
