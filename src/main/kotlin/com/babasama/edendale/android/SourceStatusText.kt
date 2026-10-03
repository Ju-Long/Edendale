package com.babasama.edendale.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.SmbClient
import com.babasama.edendale.android.data.SourceScanRules
import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceStatus
import com.babasama.edendale.connectors.SourceUrl

/**
 * Why a source's last scan failed, for its row in Settings → Sources and in
 * Downloaded (D.3); null once a scan succeeds. Names the server for a network
 * share, the folder otherwise.
 */
@Composable
internal fun sourceStatusMessage(folder: LibraryFolderEntity): String? {
    val status = SourceStatus.fromRaw(folder.status) ?: return null
    val name = SmbClient.hostOf(folder.treeUri)
        ?: SourceUrl.credentialHost(folder.treeUri)?.takeIf { SourceScanRules.kindOf(folder)?.isRemote == true }
        ?: folder.displayName
    return when (status) {
        SourceStatus.OFFLINE -> stringResource(R.string.sources_status_offline, name)
        SourceStatus.NEEDS_SIGN_IN -> stringResource(R.string.sources_status_needs_sign_in, name)
    }
}

/** What the viewer reads when linking or listing a server fails (H.3); null for an error that isn't a connector's. */
internal fun connectorFailureMessage(context: Context, error: Throwable): String? =
    when (val failure = (error as? ConnectorException)?.failure) {
        null -> null
        ConnectorFailure.InvalidAddress -> context.getString(R.string.connector_invalid_address)
        ConnectorFailure.InsecureConnection -> context.getString(R.string.connector_insecure_connection)
        is ConnectorFailure.Unreachable -> context.getString(R.string.connector_unreachable, failure.host)
        is ConnectorFailure.AuthenticationFailed -> context.getString(R.string.connector_authentication_failed, failure.host)
        is ConnectorFailure.ListingFailed -> context.getString(R.string.connector_listing_failed, failure.path)
        is ConnectorFailure.BucketInAnotherRegion -> failure.region
            ?.let { context.getString(R.string.connector_bucket_region, it) }
            ?: context.getString(R.string.connector_bucket_other_region)
        is ConnectorFailure.ServerError ->
            context.getString(R.string.player_error_server, sourceKindLabel(context, failure.kind), failure.status)
        is ConnectorFailure.SignInRequired ->
            context.getString(R.string.sources_status_needs_sign_in, sourceKindLabel(context, failure.kind))
    }
