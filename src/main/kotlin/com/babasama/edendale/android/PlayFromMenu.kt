package com.babasama.edendale.android

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceEndpoint

/** A source kind's label: translated for local folders and S3, the provider's own name otherwise. */
@Composable
internal fun sourceKindLabel(kind: MediaSourceKind?): String = sourceKindLabel(LocalContext.current, kind)

internal fun sourceKindLabel(context: Context, kind: MediaSourceKind?): String = when (kind) {
    null, MediaSourceKind.LOCAL -> context.getString(R.string.source_kind_local_folder)
    MediaSourceKind.SMB -> context.getString(R.string.source_kind_smb)
    MediaSourceKind.NFS -> "NFS"
    MediaSourceKind.SFTP -> "SFTP"
    MediaSourceKind.WEBDAV -> "WebDAV"
    MediaSourceKind.S3 -> context.getString(R.string.source_kind_s3)
    MediaSourceKind.GOOGLE_DRIVE -> "Google Drive"
    MediaSourceKind.ONE_DRIVE -> "OneDrive"
    MediaSourceKind.DROPBOX -> "Dropbox"
}

/**
 * Where a source is reached from, as Play From and the source status name it
 * (27.1): "This device", "longs-mac-studio.local", "100.101.102.103",
 * "Google Drive - me@example.com".
 */
@Composable
internal fun sourceEndpointLabel(endpoint: SourceEndpoint): String = sourceEndpointLabel(LocalContext.current, endpoint)

internal fun sourceEndpointLabel(context: Context, endpoint: SourceEndpoint): String = SourceEndpoint.label(
    endpoint = endpoint,
    provider = { sourceKindLabel(context, it) },
    thisDevice = context.getString(R.string.source_endpoint_this_device),
    withIdentity = { provider, identity -> context.getString(R.string.source_endpoint_account, provider, identity) },
)

/**
 * The rows of a Play From menu (D.5): where each copy is reached from, then
 * "Kind · file name" (`PlaybackSources.menuRows`), plus "· Unavailable" when
 * its last scan failed. The [marked] copy — the one the title last played
 * from, or the one a player row plays — carries a gold check, reads as
 * selected, and adds [markLabel] (a template around its second line) to it.
 * Each row paints its own D-pad focus.
 */
@Composable
internal fun <T> PlayFromItems(
    copies: List<T>,
    source: (T) -> CopySource,
    path: (T) -> String,
    onPlay: (T) -> Unit,
    marked: T? = null,
    @StringRes markLabel: Int? = null,
) {
    val context = LocalContext.current
    val rows = PlaybackSources.menuRows(
        copies = copies,
        source = source,
        path = path,
        label = { sourceEndpointLabel(context, it) },
        kindLabel = { sourceKindLabel(context, it) },
    )
    copies.forEachIndexed { index, copy ->
        val row = rows[index]
        val isMarked = marked != null && copy == marked
        val available = if (source(copy).isUnavailable) stringResource(R.string.play_from_unavailable, row.detail) else row.detail
        val detail = if (isMarked && markLabel != null) stringResource(markLabel, available) else available
        ArchiveMenuItem(
            text = { focused ->
                Column {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = { onPlay(copy) },
            modifier = if (isMarked) Modifier.semantics { selected = true } else Modifier,
            trailingIcon = if (isMarked) {
                { focused ->
                    Icon(
                        painter = painterResource(id = R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        // The focus fill is gold too, so the check takes the focused ink.
                        tint = if (focused) EdendaleColors.OnGold else EdendaleColors.Gold,
                    )
                }
            } else {
                null
            },
        )
    }
}

/** The PLAY FROM heading over a menu's copies. */
@Composable
internal fun PlayFromHeading() {
    Text(
        text = stringResource(R.string.play_from).uppercase(),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A round chevron that opens Play From for an item with several copies: an
 * episode row's trailing control (the row's own tap opens the same menu, so
 * [expanded] is hoisted) and the player's episode rows. It lifts and fills
 * gold under the D-pad; Back closes only the menu.
 */
@Composable
internal fun <T> PlayFromChevron(
    copies: List<T>,
    source: (T) -> CopySource,
    path: (T) -> String,
    onPlay: (T) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isTelevision: Boolean,
    marked: T? = null,
    modifier: Modifier = Modifier,
    @StringRes markLabel: Int? = null,
) {
    Box(modifier) {
        ArchiveIconButton(onClick = { onExpandedChange(true) }, isTelevision = isTelevision) { _ ->
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_down),
                contentDescription = stringResource(R.string.play_from),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            PlayFromHeading()
            PlayFromItems(copies, source, path, marked = marked, markLabel = markLabel, onPlay = {
                onExpandedChange(false)
                onPlay(it)
            })
        }
    }
}

/** The icon-only Play From button beside a movie's Play, shown when it has several copies. */
@Composable
internal fun <T> PlayFromButton(
    copies: List<T>,
    source: (T) -> CopySource,
    path: (T) -> String,
    onPlay: (T) -> Unit,
    modifier: Modifier = Modifier,
    isTelevision: Boolean = false,
    marked: T? = null,
    @StringRes markLabel: Int? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.play_from)
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .tvFocusLift(isTelevision)
                .semantics { contentDescription = label },
        ) {
            Icon(painterResource(id = R.drawable.ic_chevron_down), contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayFromHeading()
            PlayFromItems(copies, source, path, marked = marked, markLabel = markLabel, onPlay = {
                expanded = false
                onPlay(it)
            })
        }
    }
}
