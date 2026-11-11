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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.babasama.edendale.android.parental.rememberParentalGate
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.tmdbImageUrl
import com.babasama.edendale.android.data.SourceScanRules
import com.babasama.edendale.parental.GatedAction

/**
 * One linked source: local folder or network share, its item count, and the
 * credential-free path. Passwords live in the credential store and are never
 * part of the URL shown here. A Google Drive source also offers Choose More
 * Videos, which reopens Google's picker; a TV, which never opens the picker,
 * says where to choose them instead (H.9.4).
 */
@Composable
internal fun SourceRow(
    folder: LibraryFolderEntity,
    itemCount: Int,
    isTelevision: Boolean,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
    /** What the last Choose More Videos did, until the next scan. */
    note: String? = null,
    chooseMoreVideos: ChooseMoreVideosState? = null,
) {
    val kind = SourceScanRules.kindOf(folder)
    val isNetwork = kind?.isRemote == true
    val statusMessage = sourceStatusMessage(folder)
    val isGoogleDrive = kind == MediaSourceKind.GOOGLE_DRIVE
    val accountKey = folder.accountKey
    val chooser = chooseMoreVideos?.takeIf { isGoogleDrive && !isTelevision && accountKey != null }
    val choosing = chooser != null && chooser.choosingFor == accountKey
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
                val status = if (choosing) stringResource(R.string.link_source_waiting, sourceKindLabel(kind)) else statusMessage ?: note
                status?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = EdendaleColors.Gold)
                }
                if (isGoogleDrive && isTelevision) {
                    Text(
                        text = stringResource(R.string.gdrive_choose_on_another_device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (chooser != null && accountKey != null) {
                ArchiveIconButton(
                    onClick = { if (choosing) chooser.cancel() else chooser.start(accountKey) },
                    enabled = choosing || chooser.choosingFor == null,
                    isTelevision = isTelevision,
                ) { _ ->
                    Icon(
                        painter = painterResource(id = if (choosing) R.drawable.ic_circle_xmark else R.drawable.ic_plus),
                        contentDescription = stringResource(if (choosing) R.string.action_cancel else R.string.gdrive_choose_more),
                    )
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
 * One episode, merged across every copy of the show (D.5). With one copy a
 * tap plays it; with several, a tap opens Play From at the row's trailing
 * chevron, the copy on the source the show last played from ([lastPlayed])
 * checked and marked "Last played" (27.1). A long press (the Menu key on TV)
 * opens the same list plus Remove from Library where removing lasts (D21).
 *
 * The tappable part and the trailing controls are siblings rather than one
 * clickable card around them: Compose's D-pad search never moves from a
 * focused node into its own children, so on TV the watched tick and the
 * chevron were out of reach. Right now walks row → tick → chevron (or Play),
 * and the card still lifts while any of them has focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalEpisodeRow(
    episode: LibraryEpisodeEntity,
    copies: List<LibraryEpisodeEntity>,
    foldersByUri: Map<String, LibraryFolderEntity>,
    progress: WatchProgress?,
    isTelevision: Boolean,
    onPlay: () -> Unit,
    onPlayCopy: (LibraryEpisodeEntity) -> Unit,
    onToggleWatched: () -> Unit,
    /** Removes the episode's removable copies; null when none would stay removed. */
    onRemove: (() -> Unit)? = null,
    /** The copy on the source the show last played from on this device, if any. */
    lastPlayed: LibraryEpisodeEntity? = null,
    /** The row's own focus target, which TV gives the page's first episode. */
    focusRequester: FocusRequester? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    val hasCopies = copies.size > 1
    val hasMenu = hasCopies || onRemove != null
    val playFrom = stringResource(R.string.play_from)
    val removeLabel = stringResource(R.string.remove_from_library)
    val source: (LibraryEpisodeEntity) -> CopySource = { foldersByUri.copySource(it.folderUri) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusLift(isTelevision),
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        color = EdendaleColors.SurfaceLow,
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (hasCopies) {
                PlayFromHeading()
                PlayFromItems(
                    copies = copies,
                    source = source,
                    path = { it.uri },
                    marked = lastPlayed,
                    markLabel = R.string.play_from_last_played,
                    onPlay = {
                        menuOpen = false
                        onPlayCopy(it)
                    },
                )
            }
            onRemove?.let { remove ->
                ArchiveMenuItem(
                    text = { Text(removeLabel) },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_trash_can), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        remove()
                    },
                )
            }
        }
        Column {
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .combinedClickable(
                            onClick = { if (hasCopies) sourcesOpen = true else onPlay() },
                            onClickLabel = if (hasCopies) playFrom else stringResource(R.string.action_play),
                            onLongClickLabel = if (hasCopies) playFrom else if (onRemove != null) removeLabel else null,
                            onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                        )
                        .onKeyEvent { event ->
                            if (hasMenu && event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                                menuOpen = true
                                true
                            } else {
                                false
                            }
                        }
                        .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = episode.episode.toString().padStart(2, '0'),
                        modifier = Modifier.width(34.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
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
                if (hasCopies) {
                    PlayFromChevron(
                        copies = copies,
                        source = source,
                        path = { it.uri },
                        onPlay = onPlayCopy,
                        expanded = sourcesOpen,
                        onExpandedChange = { sourcesOpen = it },
                        isTelevision = isTelevision,
                        marked = lastPlayed,
                        markLabel = R.string.play_from_last_played,
                    )
                } else {
                    ArchiveIconButton(onClick = onPlay, isTelevision = isTelevision) { _ ->
                        Icon(
                            painter = painterResource(id = R.drawable.ic_play),
                            contentDescription = stringResource(R.string.action_play),
                        )
                    }
                }
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
fun BackChip(isTelevision: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onBack,
        modifier = modifier.tvFocusLift(isTelevision),
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
