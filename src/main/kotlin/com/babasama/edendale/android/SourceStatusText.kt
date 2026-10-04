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
import com.babasama.edendale.oauth.OAuthException
import com.babasama.edendale.oauth.OAuthFailure
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException

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
        null -> when (error) {
            is OAuthException -> oauthFailureMessage(context, error)
            is RemoteSourceException -> remoteFailureMessage(context, error.kind, error.failure)
            else -> null
        }
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
        is ConnectorFailure.NotConfigured ->
            context.getString(R.string.connector_not_configured, sourceKindLabel(context, failure.kind))
        is ConnectorFailure.SignInRequired ->
            context.getString(R.string.sources_status_needs_sign_in, sourceKindLabel(context, failure.kind))
    }

/** What the viewer reads when a sign-in fails (H.6, Apple's `OAuthError` messages). */
internal fun oauthFailureMessage(context: Context, error: OAuthException): String {
    val provider = sourceKindLabel(context, error.kind)
    return when (val failure = error.failure) {
        OAuthFailure.NotConfigured -> context.getString(R.string.connector_not_configured, provider)
        OAuthFailure.Cancelled -> context.getString(R.string.oauth_cancelled)
        OAuthFailure.StateMismatch, OAuthFailure.MissingAuthorizationCode -> context.getString(R.string.oauth_unverified)
        OAuthFailure.AuthorizationDenied -> context.getString(R.string.oauth_denied, provider)
        OAuthFailure.InvalidGrant -> context.getString(R.string.oauth_invalid_grant, provider)
        OAuthFailure.DeviceCodeExpired -> context.getString(R.string.oauth_device_code_expired)
        is OAuthFailure.Server -> failure.description?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.oauth_server_detail, provider, failure.code, it) }
            ?: context.getString(R.string.oauth_server, provider, failure.code)
        // A status of 0 means the request never got an answer.
        is OAuthFailure.Http -> if (failure.status == 0) {
            context.getString(R.string.sources_status_offline, provider)
        } else {
            context.getString(R.string.player_error_server, provider, failure.status)
        }
        OAuthFailure.MalformedResponse -> context.getString(R.string.oauth_malformed, provider)
    }
}

/** Apple's provider messages, named for the provider and never carrying a URL (H.2). */
internal fun remoteFailureMessage(context: Context, kind: MediaSourceKind, failure: RemoteFailure): String {
    val provider = sourceKindLabel(context, kind)
    return when (failure) {
        RemoteFailure.SignInRequired -> context.getString(R.string.sources_status_needs_sign_in, provider)
        RemoteFailure.AccessDenied -> context.getString(R.string.player_error_access_denied, provider)
        RemoteFailure.NotFound -> context.getString(R.string.player_error_not_found, provider)
        RemoteFailure.RateLimited -> context.getString(R.string.player_error_rate_limited, provider)
        RemoteFailure.RangeUnsupported -> context.getString(R.string.player_error_range_unsupported, provider)
        RemoteFailure.AbusiveFile -> context.getString(R.string.player_error_abusive_file)
        RemoteFailure.Unreachable -> context.getString(R.string.sources_status_offline, provider)
        RemoteFailure.UntrustedCertificate -> context.getString(R.string.player_error_untrusted_certificate, provider)
        is RemoteFailure.ServerError -> context.getString(R.string.player_error_server, provider, failure.status)
    }
}
