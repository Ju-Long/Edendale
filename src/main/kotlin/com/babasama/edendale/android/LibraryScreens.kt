package com.babasama.edendale.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl
import com.babasama.edendale.android.data.SourceScanRules

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
    val kind = SourceScanRules.kindOf(folder)
    val isNetwork = kind?.isRemote == true
    val statusMessage = sourceStatusMessage(folder)
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
                        when {
                            !isNetwork -> stringResource(R.string.source_kind_local_folder_lower)
                            kind == MediaSourceKind.SMB -> stringResource(R.string.source_kind_network_share)
                            // Cloud accounts, SFTP, WebDAV, and S3 by name.
                            else -> sourceKindLabel(kind)
                        },
                        pluralStringResource(R.plurals.item_count, itemCount, itemCount),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // The readable path a linked source records (H.11), else one from its address.
                    text = folder.displayPath ?: sourceDisplayPath(folder.treeUri),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                statusMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = EdendaleColors.Gold)
                }
            }
            ArchiveIconButton(onClick = onRescan, isTelevision = isTelevision) { _ ->
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_rotate_right),
                    // A failed source's rescan is its retry.
                    contentDescription = if (statusMessage != null) {
                        stringResource(R.string.action_try_again)
                    } else {
                        stringResource(R.string.rescan_folder, folder.displayName)
                    },
                )
            }
            ArchiveIconButton(onClick = onRemove, isTelevision = isTelevision) { _ ->
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
    isTelevision: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_source_confirm_title, displayName)) },
        text = {
            Text(stringResource(R.string.remove_source_message_generic))
        },
        confirmButton = {
            ArchiveButton(
                label = stringResource(R.string.action_remove),
                onClick = onConfirm,
                kind = ArchiveButtonKind.Secondary,
                isTelevision = isTelevision,
            )
        },
        dismissButton = {
            ArchiveButton(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                isTelevision = isTelevision,
            )
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
    val folders by library.folders.collectAsState(initial = emptyList())
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
    val foldersByUri = folders.associateBy { it.treeUri }
    // Every copy of the show (same TMDB id, imported from other sources) merges
    // into one list, one row per episode (D.5).
    val copyKeys = shows.filter { it.key == showKey || (show.tmdbId != null && it.tmdbId == show.tmdbId) }
        .mapTo(HashSet()) { it.key }
    val rank = compareBy<LibraryEpisodeEntity> { it.showKey != showKey }
        .then(PlaybackSources.comparator({ foldersByUri.copySource(it.folderUri) }, { it.uri }))
    val slots = PlaybackSources.episodeSlots(
        episodes.filter { it.showKey in copyKeys },
        season = { it.season },
        number = { it.episode },
        rank = rank,
    )
    LocalShowScreen(
        show = show,
        slots = slots,
        foldersByUri = foldersByUri,
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
internal fun LocalShowScreen(
    show: LibraryShowEntity,
    slots: List<PlaybackSources.EpisodeSlot<LibraryEpisodeEntity>>,
    foldersByUri: Map<String, LibraryFolderEntity>,
    progressByKey: Map<String, WatchProgress>,
    isTelevision: Boolean,
    onBack: () -> Unit,
    onToggleWatched: (LibraryEpisodeEntity) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    val seasons = slots.groupBy { it.season }.toSortedMap()
    val episodeCount = slots.size

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
                                    pluralStringResource(R.plurals.episode_count, episodeCount, episodeCount),
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
                items(seasonEpisodes.size, key = { seasonEpisodes[it].id }) { index ->
                    val slot = seasonEpisodes[index]
                    val episode = slot.primary
                    val progress = progressByKey.forEpisode(episode.tmdbId)
                    val play: (LibraryEpisodeEntity) -> Unit = { copy ->
                        PlayerActivity.play(
                            context = context,
                            uri = copy.uri,
                            title = copy.title ?: copy.fileName,
                            tmdbId = copy.tmdbId,
                            isEpisode = true,
                            showTmdbId = show.tmdbId,
                            season = copy.season,
                            episode = copy.episode,
                        )
                    }
                    LocalEpisodeRow(
                        episode = episode,
                        copies = slot.copies,
                        foldersByUri = foldersByUri,
                        progress = progress,
                        isTelevision = isTelevision,
                        onPlay = {
                            // The first copy whose source is reachable (D.5).
                            val copy = PlaybackSources.preferred(slot.copies) {
                                foldersByUri.copySource(it.folderUri).isUnavailable
                            } ?: episode
                            play(copy)
                        },
                        onPlayCopy = play,
                        onToggleWatched = { onToggleWatched(episode) },
                    )
                }
            }
        }
    }
}

/**
 * One episode, merged across every copy of the show. A tap plays the first
 * reachable copy; with several copies, a long press (the menu key on TV)
 * opens Play From, and the subtitle counts the sources (D.5).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalEpisodeRow(
    episode: LibraryEpisodeEntity,
    copies: List<LibraryEpisodeEntity>,
    foldersByUri: Map<String, LibraryFolderEntity>,
    progress: WatchProgress?,
    isTelevision: Boolean,
    onPlay: () -> Unit,
    onPlayCopy: (LibraryEpisodeEntity) -> Unit,
    onToggleWatched: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasCopies = copies.size > 1
    val playFrom = stringResource(R.string.play_from)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusLift(isTelevision)
            .clip(RoundedCornerShape(EdendaleRadii.Card.dp))
            .combinedClickable(
                onClick = onPlay,
                onLongClickLabel = if (hasCopies) playFrom else null,
                onLongClick = if (hasCopies) ({ menuOpen = true }) else null,
            )
            .onKeyEvent { event ->
                if (hasCopies && event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    menuOpen = true
                    true
                } else {
                    false
                }
            },
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        color = EdendaleColors.SurfaceLow,
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                text = playFrom.uppercase(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlayFromItems(
                copies = copies,
                source = { foldersByUri.copySource(it.folderUri) },
                path = { it.uri },
                onPlay = {
                    menuOpen = false
                    onPlayCopy(it)
                },
            )
        }
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
                    listOfNotNull(
                        mediaSubtitle(null, episode.runtimeMinutes, rememberRuntimeFormat()),
                        if (hasCopies) pluralStringResource(R.plurals.play_from_source_count, copies.size, copies.size) else null,
                    ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
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
                    ArchiveIconButton(
                        onClick = onToggleWatched,
                        isTelevision = isTelevision,
                    ) { focused ->
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = stringResource(
                                if (progress?.isCompleted == true) R.string.mark_unwatched
                                else R.string.mark_watched,
                            ),
                            // The watched tick is gold, and so is the focus
                            // fill, so focus has to take the glyph with it.
                            tint = when {
                                focused -> EdendaleColors.OnGold
                                progress?.isCompleted == true -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
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
