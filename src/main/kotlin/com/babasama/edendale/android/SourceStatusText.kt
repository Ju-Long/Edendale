package com.babasama.edendale.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.SmbClient
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceStatus

/**
 * Why a source's last scan failed, for its row in Settings → Sources and in
 * Downloaded (D.3); null once a scan succeeds. Names the server for a network
 * share, the folder otherwise.
 */
@Composable
internal fun sourceStatusMessage(folder: LibraryFolderEntity): String? {
    val status = SourceStatus.fromRaw(folder.status) ?: return null
    val name = SmbClient.hostOf(folder.treeUri) ?: folder.displayName
    return when (status) {
        SourceStatus.OFFLINE -> stringResource(R.string.sources_status_offline, name)
        SourceStatus.NEEDS_SIGN_IN -> stringResource(R.string.sources_status_needs_sign_in, name)
    }
}

/** A source kind's name: provider names stay as their owners write them; S3 and local folders are translated. */
@Composable
internal fun sourceKindName(kind: MediaSourceKind): String = when (kind) {
    MediaSourceKind.LOCAL -> stringResource(R.string.source_kind_local)
    MediaSourceKind.SMB -> "SMB"
    MediaSourceKind.NFS -> "NFS"
    MediaSourceKind.SFTP -> "SFTP"
    MediaSourceKind.WEBDAV -> "WebDAV"
    MediaSourceKind.S3 -> stringResource(R.string.source_kind_s3)
    MediaSourceKind.GOOGLE_DRIVE -> "Google Drive"
    MediaSourceKind.ONE_DRIVE -> "OneDrive"
    MediaSourceKind.DROPBOX -> "Dropbox"
}
