package com.babasama.edendale.android

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choose More Videos (H.9.6, Apple's `SourceRow` and `AccountsSection`
 * action): reopens Google's picker for a Google Drive account in a Custom
 * Tab, then rescans that account's "Videos you chose" here, adding the
 * source if this device has none. The source's row then says how many
 * videos were added. Handhelds only: a TV never opens the picker. Cancel
 * abandons the run and changes nothing.
 */
@Stable
internal class ChooseMoreVideosState(private val context: Context, private val scope: CoroutineScope) {
    /** The account key whose picker is open, if any. */
    var choosingFor by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun start(accountKey: String) {
        val activity = context.findActivity() ?: return
        if (job != null) return
        val app = context.applicationContext as EdendaleApplication
        choosingFor = accountKey
        job = scope.launch {
            try {
                val account = withContext(Dispatchers.IO) { app.cloudAccounts.vault.account(MediaSourceKind.GOOGLE_DRIVE, accountKey) }
                    ?: throw ConnectorException(ConnectorFailure.SignInRequired(MediaSourceKind.GOOGLE_DRIVE))
                app.libraryRepository.addChosenVideos(app.cloudAccounts.chooseMoreVideos(activity, account))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                app.libraryRepository.showError(
                    connectorFailureMessage(context, failure) ?: context.getString(R.string.error_server_unreachable),
                )
            } finally {
                choosingFor = null
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

@Composable
internal fun rememberChooseMoreVideos(): ChooseMoreVideosState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) { ChooseMoreVideosState(context, scope) }
}

/**
 * Once, after the move to `drive.file` removed Google Drive accounts or
 * sources from an older build (H.9.4, D20): why they went, and how to link
 * Google Drive again (on a TV, by choosing on a phone first).
 */
@Composable
internal fun GoogleDriveRelinkNotice(isTelevision: Boolean) {
    val library = rememberLibrary()
    val due by library.needsGoogleDriveRelinkNotice.collectAsState()
    if (!due) return
    AlertDialog(
        onDismissRequest = library::dismissGoogleDriveRelinkNotice,
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        containerColor = EdendaleColors.Surface,
        title = {
            Text(
                text = stringResource(R.string.gdrive_relink_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = stringResource(if (isTelevision) R.string.gdrive_relink_message_tv else R.string.gdrive_relink_message),
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            ArchiveButton(
                label = stringResource(R.string.action_dismiss),
                onClick = library::dismissGoogleDriveRelinkNotice,
                kind = ArchiveButtonKind.Primary,
                isTelevision = isTelevision,
            )
        },
    )
}
