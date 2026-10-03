package com.babasama.edendale.android.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.ArchiveButton
import com.babasama.edendale.android.ArchiveButtonKind
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.R
import com.babasama.edendale.android.sourceKindName
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.remote.RemoteConnectionLostException
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException
import com.babasama.edendale.remote.RemoteOpenException

/** Why playback stopped, read from the player error's cause chain (pure). */
internal sealed interface PlaybackFailure {
    /** A remote file stopped answering mid-play after every reconnect (D.1). */
    data class ConnectionLost(val host: String, val detail: String?) : PlaybackFailure

    /** A remote file never opened: the host is down, or the login or path is wrong. */
    data class CouldNotConnect(val host: String, val detail: String?) : PlaybackFailure

    /** A storage provider refused or failed a read (H.2). */
    data class Provider(val kind: MediaSourceKind, val failure: RemoteFailure) : PlaybackFailure

    data object Other : PlaybackFailure

    companion object {
        fun of(error: Throwable): PlaybackFailure {
            var cause: Throwable? = error
            val seen = HashSet<Throwable>()
            while (cause != null && seen.add(cause)) {
                when (cause) {
                    is RemoteConnectionLostException -> return ConnectionLost(cause.host, cause.detail)
                    is RemoteOpenException -> return CouldNotConnect(cause.host, cause.cause?.message?.takeIf { it.isNotBlank() })
                    is RemoteSourceException -> return Provider(cause.kind, cause.failure)
                }
                cause = cause.cause
            }
            return Other
        }
    }
}

@Composable
internal fun playbackFailureMessage(failure: PlaybackFailure): String = when (failure) {
    is PlaybackFailure.ConnectionLost -> failure.detail?.let {
        stringResource(R.string.player_error_connection_lost_detail, failure.host, it)
    } ?: stringResource(R.string.player_error_connection_lost, failure.host)
    // The transport's own words say what went wrong (bad login, no such path).
    is PlaybackFailure.CouldNotConnect -> failure.detail
        ?: stringResource(R.string.player_error_could_not_connect, failure.host)
    is PlaybackFailure.Provider -> providerFailureMessage(failure.kind, failure.failure)
    PlaybackFailure.Other -> stringResource(R.string.player_error_could_not_open)
}

/** Apple's provider messages, named for the provider and never carrying a URL. */
@Composable
internal fun providerFailureMessage(kind: MediaSourceKind, failure: RemoteFailure): String {
    val provider = sourceKindName(kind)
    return when (failure) {
        RemoteFailure.SignInRequired -> stringResource(R.string.sources_status_needs_sign_in, provider)
        RemoteFailure.AccessDenied -> stringResource(R.string.player_error_access_denied, provider)
        RemoteFailure.NotFound -> stringResource(R.string.player_error_not_found, provider)
        RemoteFailure.RateLimited -> stringResource(R.string.player_error_rate_limited, provider)
        RemoteFailure.RangeUnsupported -> stringResource(R.string.player_error_range_unsupported, provider)
        RemoteFailure.AbusiveFile -> stringResource(R.string.player_error_abusive_file)
        RemoteFailure.Unreachable -> stringResource(R.string.sources_status_offline, provider)
        RemoteFailure.UntrustedCertificate -> stringResource(R.string.player_error_untrusted_certificate, provider)
        is RemoteFailure.ServerError -> stringResource(R.string.player_error_server, provider, failure.status)
    }
}

/** Shown in place of the picture when the file can't be opened or the connection is lost (Apple's PlaybackErrorView). */
@Composable
internal fun PlaybackErrorView(failure: PlaybackFailure, isTelevision: Boolean, onClose: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_film_circle_exclamation),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = EdendaleColors.SurfaceHigh,
            )
            Text(
                text = stringResource(R.string.player_error_title).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = EdendaleColors.TextPrimary,
            )
            Text(
                text = playbackFailureMessage(failure),
                modifier = Modifier.widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodySmall,
                color = EdendaleColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        ArchiveButton(
            label = stringResource(R.string.action_close),
            onClick = onClose,
            modifier = Modifier.focusRequester(closeFocus),
            kind = ArchiveButtonKind.Secondary,
            isTelevision = isTelevision,
        )
    }
    if (isTelevision) {
        LaunchedEffect(failure) { runCatching { closeFocus.requestFocus() } }
    }
}
