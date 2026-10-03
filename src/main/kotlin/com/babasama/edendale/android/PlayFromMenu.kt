package com.babasama.edendale.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.babasama.edendale.connectors.MediaSourceKind

/** A source kind's label: translated for local folders, the provider's own name otherwise. */
@Composable
internal fun sourceKindLabel(kind: MediaSourceKind?): String = when (kind) {
    null, MediaSourceKind.LOCAL -> stringResource(R.string.source_kind_local_folder)
    MediaSourceKind.SMB -> stringResource(R.string.source_kind_smb)
    MediaSourceKind.NFS -> "NFS"
    MediaSourceKind.SFTP -> "SFTP"
    MediaSourceKind.WEBDAV -> "WebDAV"
    MediaSourceKind.S3 -> "S3"
    MediaSourceKind.GOOGLE_DRIVE -> "Google Drive"
    MediaSourceKind.ONE_DRIVE -> "OneDrive"
    MediaSourceKind.DROPBOX -> "Dropbox"
}

/**
 * The rows of a Play From menu (D.5): each copy's source name, then
 * "Kind · file name", plus "· Unavailable" when its last scan failed.
 */
@Composable
internal fun <T> PlayFromItems(
    copies: List<T>,
    source: (T) -> CopySource,
    path: (T) -> String,
    onPlay: (T) -> Unit,
) {
    copies.forEach { copy ->
        val from = source(copy)
        val detail = PlaybackSources.detail(sourceKindLabel(from.kind), path(copy))
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        text = from.name.ifBlank { PlaybackSources.fileName(path(copy)) },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (from.isUnavailable) stringResource(R.string.play_from_unavailable, detail) else detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = { onPlay(copy) },
        )
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
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.play_from)
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Icon(painterResource(id = R.drawable.ic_chevron_down), contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = label.uppercase(),
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlayFromItems(copies, source, path) {
                expanded = false
                onPlay(it)
            }
        }
    }
}
