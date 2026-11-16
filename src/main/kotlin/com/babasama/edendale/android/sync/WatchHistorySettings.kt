package com.babasama.edendale.android.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babasama.edendale.android.ArchiveButton
import com.babasama.edendale.android.ArchiveButtonKind
import com.babasama.edendale.android.BodyCopyStyle
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleRadii
import com.babasama.edendale.android.FocusableRows
import com.babasama.edendale.android.InfoRow
import com.babasama.edendale.android.LabeledRow
import com.babasama.edendale.android.R
import com.babasama.edendale.android.SettingsActionRow
import com.babasama.edendale.android.SettingsRowDivider
import com.babasama.edendale.android.SettingsSection
import com.babasama.edendale.android.parental.ParentalGate
import com.babasama.edendale.parental.GatedAction
import com.babasama.edendale.sync.SyncLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Sync → Watch History: how many titles and episodes this device
 * holds progress or a watched mark for, and Delete Watch History. With sync
 * on the deletion reaches the other devices through the location as
 * tombstones ([SyncCoordinator.deleteWatchHistory]); with sync off it stays
 * on this device. Only progress and watched marks go, which the dialog says
 * once. The button asks for the parental PIN like every Sync button (O-D14),
 * and the outcome line is a polite live region, so TalkBack reads it out.
 */
@Composable
internal fun WatchHistorySection(isTelevision: Boolean, gate: ParentalGate) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val sync = app.sync
    val status by sync.status.collectAsState()
    val deletion by sync.historyDeletion.collectAsState()
    val rows by remember(app) { app.database.watchProgressDao().observeCount() }.collectAsState(initial = 0)
    // Records other devices synced for episodes this library doesn't hold count too: with sync on they are cleared everywhere.
    val liveRecords by remember(app) { app.database.syncDao().observeLiveProgressCount() }.collectAsState(initial = 0)
    val location = status.location
    val label = rememberLocationLabel(location)
    var confirming by remember { mutableStateOf(false) }
    // A finished deletion's line stays until the page is left; a running or waiting one outlives it.
    DisposableEffect(sync) { onDispose { sync.acknowledgeHistoryDeletion() } }

    SettingsSection(
        header = stringResource(R.string.watch_history_section),
        isTelevision = isTelevision,
        focusableContent = false,
        actions = {
            SettingsActionRow {
                ArchiveButton(
                    label = stringResource(R.string.watch_history_delete_ellipsis),
                    onClick = { gate.require(GatedAction.SYNC) { confirming = true } },
                    enabled = (rows > 0 || liveRecords > 0) && deletion != HistoryDeletion.Running,
                    iconRes = R.drawable.ic_trash_can,
                    isTelevision = isTelevision,
                )
            }
        },
    ) {
        FocusableRows(isTelevision) {
            LabeledRow(stringResource(R.string.watch_history_on_device), pluralStringResource(R.plurals.item_count, rows, rows))
            SettingsRowDivider()
            InfoRow(stringResource(if (location != null) R.string.watch_history_note_sync_on else R.string.watch_history_note_sync_off))
            outcomeLine(deletion)?.let { line ->
                SettingsRowDivider()
                OutcomeRow(line, isProblem = deletion == HistoryDeletion.Failed)
            }
        }
    }

    if (confirming) {
        DeleteWatchHistoryDialog(
            isTelevision = isTelevision,
            // Null with sync off: the deletion stays on this device.
            through = location?.let { label },
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                sync.deleteWatchHistory()
            },
        )
    }
}

/**
 * What the outcome line says, or null when there is nothing to report. With
 * sync on, "done" covers this device and its own file only: each other
 * device's file at the location keeps its copy until that device next syncs.
 */
@Composable
private fun outcomeLine(deletion: HistoryDeletion): String? = when (deletion) {
    HistoryDeletion.Idle -> null
    HistoryDeletion.Running -> stringResource(R.string.watch_history_deleting)
    is HistoryDeletion.Done -> stringResource(if (deletion.othersFollow) R.string.watch_history_deleted_others_follow else R.string.watch_history_deleted)
    is HistoryDeletion.Pending -> stringResource(R.string.watch_history_pending, rememberLocationLabel(deletion.location).orEmpty())
    HistoryDeletion.Failed -> stringResource(R.string.watch_history_failed)
}

/** [InfoRow] as a polite live region: TalkBack reads each new outcome without moving focus. */
@Composable
private fun OutcomeRow(text: String, isProblem: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = BodyCopyStyle(),
        color = if (isProblem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The location as Settings → Sync names it (provider and account, host, or folder), or null while sync is off. */
@Composable
private fun rememberLocationLabel(location: SyncLocation?): String? {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    var accountLabel by remember(location) { mutableStateOf<String?>(null) }
    LaunchedEffect(location) {
        val key = location?.accountKey ?: return@LaunchedEffect
        val kind = location.provider.kind ?: return@LaunchedEffect
        accountLabel = withContext(Dispatchers.IO) { runCatching { app.cloudAccounts.vault.account(kind, key)?.label }.getOrNull() }
    }
    return location?.let { locationLabel(context, it, accountLabel) }
}

/**
 * The destructive confirmation, styled like Remove Source. [through] names
 * the location when sync is on (the deletion reaches every device), null when
 * it is off (this device only). The message scrolls on a short TV screen or
 * at a large font size. On TV, Cancel takes focus first, so a stray Select
 * deletes nothing.
 */
@Composable
private fun DeleteWatchHistoryDialog(
    isTelevision: Boolean,
    through: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        containerColor = EdendaleColors.Surface,
        title = {
            Text(
                text = stringResource(if (through != null) R.string.watch_history_everywhere_title else R.string.watch_history_local_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (through != null) {
                        stringResource(R.string.watch_history_everywhere_message, through)
                    } else {
                        stringResource(R.string.watch_history_local_message)
                    },
                    style = BodyCopyStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.watch_history_kept),
                    style = BodyCopyStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            ArchiveButton(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus),
                isTelevision = isTelevision,
            )
            if (isTelevision) {
                LaunchedEffect(Unit) {
                    // The dialog's window can take a frame or two before it accepts focus.
                    repeat(5) {
                        withFrameNanos { }
                        if (runCatching { cancelFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
                    }
                }
            }
        },
        confirmButton = {
            ArchiveButton(
                label = stringResource(if (through != null) R.string.watch_history_everywhere_confirm else R.string.watch_history_local_confirm),
                onClick = onConfirm,
                kind = ArchiveButtonKind.Secondary,
                iconRes = R.drawable.ic_trash_can,
                isTelevision = isTelevision,
            )
        },
    )
}
