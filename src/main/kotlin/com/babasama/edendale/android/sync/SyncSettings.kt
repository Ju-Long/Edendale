package com.babasama.edendale.android.sync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.ArchiveButton
import com.babasama.edendale.android.ArchiveButtonKind
import com.babasama.edendale.android.BodyCopyStyle
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.FocusableRows
import com.babasama.edendale.android.InfoRow
import com.babasama.edendale.android.SettingsActionRow
import com.babasama.edendale.android.LabeledRow
import com.babasama.edendale.android.R
import com.babasama.edendale.android.SettingsRowDivider
import com.babasama.edendale.android.SettingsSection
import com.babasama.edendale.android.connectorFailureMessage
import com.babasama.edendale.android.oauthFailureMessage
import com.babasama.edendale.android.data.SavedServerLogin
import com.babasama.edendale.android.data.SavedSmbLogin
import com.babasama.edendale.android.data.ServerLoginStore
import com.babasama.edendale.android.data.SmbCredentialsStore
import com.babasama.edendale.android.handoff.handoffFailureMessage
import com.babasama.edendale.android.parental.ParentalGate
import com.babasama.edendale.android.sourceKindLabel
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.parental.GatedAction
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.handoff.HandoffV2
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudProviders
import com.babasama.edendale.oauth.OAuthException
import com.babasama.edendale.oauth.OAuthFailure
import com.babasama.edendale.sync.DeviceFileCodec
import com.babasama.edendale.sync.SpaceName
import com.babasama.edendale.sync.SyncLocation
import com.babasama.edendale.sync.SyncProvider
import com.babasama.edendale.sync.SyncWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pairing link waiting to be acted on: set by a scanned `edendale://handoff` QR code, cleared when the dialog closes. */
object PendingPairing {
    val link = MutableStateFlow<String?>(null)
}

/** The provider's name and what identifies the location: an email, a host, or the picked folder's name. */
internal fun locationLabel(context: Context, location: SyncLocation, accountLabel: String?): String {
    val provider = when (location.provider) {
        SyncProvider.FOLDER -> context.getString(R.string.sync_provider_folder)
        else -> sourceKindLabel(context, location.provider.kind)
    }
    val detail = when (location.provider) {
        SyncProvider.FOLDER -> location.path?.let { runCatching { Uri.parse(it).lastPathSegment?.substringAfterLast(':') }.getOrNull() }
        SyncProvider.GOOGLE_DRIVE, SyncProvider.ONE_DRIVE, SyncProvider.DROPBOX -> accountLabel
        else -> location.host
    }
    return if (detail.isNullOrBlank()) provider else "$provider · $detail"
}

internal fun syncErrorMessage(context: Context, error: Throwable): String = when (error) {
    is SyncLocationException -> when (val problem = error.problem) {
        is SyncLocationProblem.NeedsConsent -> context.getString(R.string.sync_needs_consent, sourceKindLabel(context, problem.kind))
        SyncLocationProblem.LoginMissing -> context.getString(R.string.sync_login_missing)
    }
    is HandoffException -> handoffFailureMessage(context, error)
    else -> connectorFailureMessage(context, error) ?: error.javaClass.simpleName
}

internal fun warningMessage(context: Context, warning: SyncWarning): String = when (warning) {
    is SyncWarning.FarFuture -> context.getString(R.string.sync_warning_far_future, warning.deviceName)
    is SyncWarning.FileSkipped -> {
        val who = warning.deviceName ?: warning.fileName
        if (warning.reason == DeviceFileCodec.SkipReason.NEWER_FORMAT) {
            context.getString(R.string.sync_warning_newer_format, who)
        } else {
            context.getString(R.string.sync_warning_skipped, who, warning.reason.name.lowercase().replace('_', ' '))
        }
    }
}

internal fun pairingFailureMessage(context: Context, error: Throwable): String = when (error) {
    HandoffException.WrongSecret -> context.getString(R.string.pairing_wrong_secret)
    HandoffException.SameRole -> context.getString(R.string.pairing_same_role)
    HandoffException.Busy -> context.getString(R.string.pairing_busy)
    is HandoffException -> handoffFailureMessage(context, error)
    else -> connectorFailureMessage(context, error) ?: context.getString(R.string.handoff_connection_failed)
}

/**
 * Settings → Sync (FEATURES-27.1.md §5.1): off by default; a location and a
 * space name turn it on. On, it shows the location, the space, this device,
 * and the last sync, and offers Sync Now, pairing another device both ways,
 * removing a device, and turning off. Never claims instant sync. The section
 * stays readable while the parental controls are locked; every button asks
 * for the PIN through [gate] (O-D14).
 */
@Composable
internal fun SyncSettingsSection(isTelevision: Boolean, gate: ParentalGate) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val sync = app.sync
    val status by sync.status.collectAsState()
    val changes by sync.settings.changes.collectAsState()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<HandoffV2.Role?>(null) }
    var turningOff by remember { mutableStateOf(false) }
    var removingDevice by remember { mutableStateOf(false) }
    var accountLabel by remember { mutableStateOf<String?>(null) }
    var previousAccountLabel by remember { mutableStateOf<String?>(null) }
    var deletingOldFile by remember { mutableStateOf(false) }
    var oldFileError by remember { mutableStateOf<String?>(null) }
    // Every change in this section is a sync change the acceptance criteria put behind the PIN.
    fun gated(action: () -> Unit): () -> Unit = { gate.require(GatedAction.SYNC, onUnlocked = action) }

    LaunchedEffect(changes) {
        sync.refreshStatus()
        val location = sync.settings.location
        accountLabel = location?.accountKey?.let { key ->
            location.provider.kind?.let { kind -> withContext(Dispatchers.IO) { runCatching { app.cloudAccounts.vault.account(kind, key)?.label }.getOrNull() } }
        }
        val previous = sync.settings.previousLocation
        previousAccountLabel = previous?.accountKey?.let { key ->
            previous.provider.kind?.let { kind -> withContext(Dispatchers.IO) { runCatching { app.cloudAccounts.vault.account(kind, key)?.label }.getOrNull() } }
        }
    }

    SettingsSection(
        header = stringResource(R.string.settings_section_sync),
        isTelevision = isTelevision,
        focusableContent = false,
        actions = {
            SettingsActionRow {
                if (status.isOn) {
                    ArchiveButton(label = stringResource(R.string.sync_now), onClick = gated { sync.syncNow() }, enabled = !status.syncing, iconRes = R.drawable.ic_arrow_rotate_right)
                } else {
                    ArchiveButton(label = stringResource(R.string.sync_choose_location), onClick = gated { choosing = true }, kind = ArchiveButtonKind.Primary, iconRes = R.drawable.ic_folder_circle_plus)
                }
                // As on Apple, both directions are always offered: without a location the sender can still send accounts and logins.
                ArchiveButton(label = stringResource(R.string.sync_set_up_device), onClick = gated { pairing = HandoffV2.Role.SENDER }, iconRes = R.drawable.ic_forward)
                ArchiveButton(label = stringResource(R.string.sync_receive_from_device), onClick = gated { pairing = HandoffV2.Role.RECEIVER }, iconRes = R.drawable.ic_backward)
            }
            if (status.isOn) {
                SettingsActionRow {
                    ArchiveButton(label = stringResource(R.string.sync_choose_location), onClick = gated { choosing = true })
                    ArchiveButton(label = stringResource(R.string.sync_remove_device), onClick = gated { removingDevice = true })
                    ArchiveButton(label = stringResource(R.string.sync_turn_off), onClick = gated { turningOff = true }, iconRes = R.drawable.ic_xmark)
                }
            }
            // FEATURES-27.1 §5.1: a replaced location keeps this device's file until the viewer deletes it, and the app offers to.
            if (status.previousLocation != null) {
                SettingsActionRow {
                    ArchiveButton(
                        label = stringResource(if (deletingOldFile) R.string.watch_history_deleting else R.string.sync_previous_file_delete),
                        enabled = !deletingOldFile,
                        iconRes = R.drawable.ic_trash_can,
                        onClick = gated {
                            deletingOldFile = true
                            oldFileError = null
                            scope.launch {
                                sync.deletePreviousFile().onFailure { oldFileError = syncErrorMessage(context, it) }
                                deletingOldFile = false
                            }
                        },
                    )
                    ArchiveButton(label = stringResource(R.string.sync_previous_file_keep), enabled = !deletingOldFile, onClick = gated { oldFileError = null; sync.keepPreviousFile() })
                }
            }
        },
    ) {
        val location = status.location
        if (location == null) {
            FocusableRows(isTelevision) { InfoRow(stringResource(R.string.sync_off_note)) }
        } else {
            FocusableRows(isTelevision) {
                LabeledRow(stringResource(R.string.sync_location_label), locationLabel(context, location, accountLabel))
                SettingsRowDivider()
                LabeledRow(stringResource(R.string.sync_space_label), location.space)
                SettingsRowDivider()
                LabeledRow(stringResource(R.string.sync_device_label), status.deviceName)
                SettingsRowDivider()
                val problem = status.problem
                val error = status.lastError
                val line = when {
                    status.syncing -> stringResource(R.string.sync_syncing)
                    problem is SyncLocationProblem.NeedsConsent -> stringResource(R.string.sync_needs_consent, sourceKindLabel(problem.kind))
                    problem == SyncLocationProblem.LoginMissing -> stringResource(R.string.sync_login_missing)
                    error != null -> stringResource(R.string.sync_failed, syncErrorMessage(context, error))
                    status.lastSyncAt != null -> stringResource(R.string.sync_last_synced, DateUtils.getRelativeTimeSpanString(context, status.lastSyncAt!!).toString())
                    else -> stringResource(R.string.sync_never_synced)
                }
                InfoRow(line)
                status.warnings.forEach { warning ->
                    SettingsRowDivider()
                    InfoRow(warningMessage(context, warning))
                }
                SettingsRowDivider()
                InfoRow(stringResource(R.string.sync_privacy_note))
            }
        }
        status.previousLocation?.let { previous ->
            FocusableRows(isTelevision) {
                SettingsRowDivider()
                InfoRow(stringResource(R.string.sync_previous_file_note, locationLabel(context, previous, previousAccountLabel), previous.space))
                oldFileError?.let { message ->
                    SettingsRowDivider()
                    InfoRow(message)
                }
            }
        }
    }

    if (choosing) {
        ChooseLocationDialog(isTelevision = isTelevision, onDismiss = { choosing = false })
    }
    pairing?.let { role ->
        SyncHandoffDialog(isTelevision = isTelevision, role = role, onDismiss = { pairing = null })
    }
    if (turningOff) {
        val location = status.location
        AlertDialog(
            onDismissRequest = { turningOff = false },
            title = { Text(stringResource(R.string.sync_turn_off_title)) },
            text = { Text(stringResource(R.string.sync_turn_off_message, location?.let { locationLabel(context, it, accountLabel) }.orEmpty())) },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArchiveButton(label = stringResource(R.string.sync_turn_off_delete), onClick = { turningOff = false; scope.launch { sync.disable(deleteOwnFile = true) } }, kind = ArchiveButtonKind.Primary)
                    ArchiveButton(label = stringResource(R.string.sync_turn_off_keep), onClick = { turningOff = false; scope.launch { sync.disable(deleteOwnFile = false) } })
                }
            },
            dismissButton = { ArchiveButton(label = stringResource(R.string.action_cancel), onClick = { turningOff = false }) },
        )
    }
    if (removingDevice) {
        RemoveDeviceDialog(onDismiss = { removingDevice = false })
    }
}

/** The cloud providers Choose a Location can sign in to on its own, in the order Link Source lists them. */
private val SYNC_SIGN_IN_KINDS = listOf(MediaSourceKind.GOOGLE_DRIVE, MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX)

/** The activity a Compose context belongs to, for a sign-in's Custom Tab. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Choose a Location (§5.2): a folder on this device (phones and tablets), a
 * linked cloud account or one signed in to right here (phones and tablets;
 * the library's sources are untouched, so videos can stay on SMB while the
 * history goes to Drive), or a saved server login with the folder inside it,
 * plus the space name. Saving turns sync on and runs the first sync.
 */
@Composable
internal fun ChooseLocationDialog(
    isTelevision: Boolean,
    onDismiss: () -> Unit,
    /** A space name and provider from a handoff follow-up, when the viewer only has to finish the choice here. */
    presetSpace: String? = null,
    presetProvider: SyncProvider? = null,
    presetGoogleFolderId: String? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val sync = app.sync
    val scope = rememberCoroutineScope()
    var space by remember { mutableStateOf(presetSpace ?: sync.settings.location?.space ?: "") }
    var accounts by remember { mutableStateOf<List<CloudAccount>>(emptyList()) }
    var serverLogins by remember { mutableStateOf<List<SavedServerLogin>>(emptyList()) }
    var linkedFolderUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var smbLogins by remember { mutableStateOf<List<SavedSmbLogin>>(emptyList()) }
    var chosenAccount by remember { mutableStateOf<CloudAccount?>(null) }
    var chosenServer by remember { mutableStateOf<Triple<SyncProvider, String, Int?>?>(null) }
    var serverFolder by remember { mutableStateOf("") }
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A sign-in made from this dialog, so an account need not be linked as a library source first.
    var signingIn by remember { mutableStateOf<MediaSourceKind?>(null) }
    var signInJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            accounts = runCatching { app.cloudAccounts.vault.all() }.getOrDefault(emptyList())
            serverLogins = runCatching { ServerLoginStore(context).all().filter { it.kind == MediaSourceKind.WEBDAV || it.kind == MediaSourceKind.SFTP } }.getOrDefault(emptyList())
            smbLogins = runCatching { SmbCredentialsStore(context).savedLogins() }.getOrDefault(emptyList())
            linkedFolderUris = runCatching { app.database.libraryDao().folders().map { it.treeUri } }.getOrDefault(emptyList())
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            folderUri = uri
            chosenAccount = null
            chosenServer = null
        }
    }

    /**
     * Signs in to [kind] for sync only (no library source, no Google picker)
     * and chooses the new account. Tapping the waiting row again stops it:
     * a Custom Tab closed without signing in never reports back.
     */
    fun signIn(kind: MediaSourceKind) {
        if (signingIn != null) {
            signInJob?.cancel()
            return
        }
        val activity = context.findActivity() ?: return
        signingIn = kind
        error = null
        signInJob = scope.launch {
            try {
                val account = app.cloudAccounts.signInForSync(activity, kind)
                accounts = withContext(Dispatchers.IO) { runCatching { app.cloudAccounts.vault.all() }.getOrDefault(emptyList()) }
                chosenAccount = accounts.firstOrNull { it.kind == account.kind && it.key == account.key } ?: account
                chosenServer = null
                folderUri = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OAuthException) {
                if (failure.failure != OAuthFailure.Cancelled) error = oauthFailureMessage(context, failure)
            } catch (failure: Exception) {
                error = connectorFailureMessage(context, failure) ?: context.getString(R.string.error_server_unreachable)
            } finally {
                signingIn = null
                signInJob = null
            }
        }
    }

    fun save() {
        val name = SpaceName.normalize(space)
        if (name == null) {
            error = context.getString(R.string.sync_space_help)
            return
        }
        val account = chosenAccount
        val server = chosenServer
        val folder = folderUri
        val location = when {
            folder != null -> SyncLocation(SyncProvider.FOLDER, name, path = folder.toString())
            account != null -> {
                if (!CloudProviders.hasSyncWriteAccess(account.kind, account.scopes)) {
                    error = context.getString(R.string.sync_needs_consent, sourceKindLabel(context, account.kind))
                    return
                }
                SyncLocation(SyncProvider.forKind(account.kind) ?: return, name, accountKey = account.key, googleFolderId = presetGoogleFolderId)
            }
            server != null -> {
                val (provider, host, port) = server
                val path = when (provider) {
                    SyncProvider.WEBDAV -> com.babasama.edendale.connectors.WebDav.canonicalRoot(serverFolder)
                    SyncProvider.SMB -> com.babasama.edendale.android.data.SmbClient.normalizeUrl(serverFolder)
                    else -> "/" + serverFolder.trim().trim('/')
                }
                if (path.isNullOrBlank()) {
                    error = context.getString(R.string.connector_invalid_address)
                    return
                }
                SyncLocation(provider, name, host = host, port = port, path = path)
            }
            else -> {
                error = context.getString(R.string.sync_no_accounts)
                return
            }
        }
        saving = true
        error = null
        scope.launch {
            val report = sync.enable(location)
            saving = false
            val failure = sync.status.value.lastError
            if (report == null && failure != null) error = syncErrorMessage(context, failure) else onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = space,
                    onValueChange = { space = it },
                    label = { Text(stringResource(R.string.sync_space_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                Text(stringResource(R.string.sync_space_help), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!isTelevision && presetProvider != SyncProvider.GOOGLE_DRIVE) {
                    ChoiceRow(
                        label = folderUri?.lastPathSegment?.substringAfterLast(':')?.let { stringResource(R.string.sync_provider_folder) + " · " + it } ?: stringResource(R.string.sync_provider_folder),
                        selected = folderUri != null,
                        iconRes = R.drawable.ic_folder,
                    ) { folderPicker.launch(null) }
                }
                val offeredAccounts = accounts.filter { presetProvider == null || it.kind == presetProvider.kind }
                if (offeredAccounts.isNotEmpty()) {
                    Text(stringResource(R.string.sync_provider_accounts).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    offeredAccounts.forEach { account ->
                        ChoiceRow(label = "${sourceKindLabel(account.kind)} · ${account.label}", selected = chosenAccount == account, iconRes = R.drawable.ic_circle_user_fill) {
                            chosenAccount = account
                            chosenServer = null
                            folderUri = null
                        }
                    }
                }
                // Signing in here stores the account for sync alone: a library can stay on SMB while its history goes to Drive.
                val signInKinds = if (isTelevision) {
                    emptyList()
                } else {
                    SYNC_SIGN_IN_KINDS.filter { app.cloudAccounts.isOffered(it) && (presetProvider == null || it == presetProvider.kind) }
                }
                if (signInKinds.isNotEmpty()) {
                    if (offeredAccounts.isNotEmpty()) {
                        Text(stringResource(R.string.link_source_another_account).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    signInKinds.forEach { kind ->
                        val label = if (signingIn == kind) {
                            stringResource(R.string.link_source_waiting, sourceKindLabel(kind))
                        } else {
                            stringResource(R.string.link_source_sign_in_to, sourceKindLabel(kind))
                        }
                        ChoiceRow(label = label, selected = false, iconRes = R.drawable.ic_link) { signIn(kind) }
                    }
                }
                if (presetProvider == null && (serverLogins.isNotEmpty() || smbLogins.isNotEmpty())) {
                    Text(stringResource(R.string.sync_provider_servers).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    smbLogins.forEach { login ->
                        ChoiceRow(label = "${sourceKindLabel(MediaSourceKind.SMB)} · ${login.user} @ ${login.host}", selected = chosenServer?.second == login.host && chosenServer?.first == SyncProvider.SMB, iconRes = R.drawable.ic_folder_tree) {
                            chosenServer = Triple(SyncProvider.SMB, login.host, null)
                            if (serverFolder.isBlank()) serverFolder = "smb://${login.host}/"
                            chosenAccount = null
                            folderUri = null
                        }
                    }
                    serverLogins.forEach { login ->
                        val provider = SyncProvider.forKind(login.kind) ?: return@forEach
                        ChoiceRow(label = "${sourceKindLabel(login.kind)} · ${login.user} @ ${login.address}", selected = chosenServer?.second == login.host && chosenServer?.first == provider, iconRes = R.drawable.ic_folder_tree) {
                            chosenServer = Triple(provider, login.host, login.port)
                            if (serverFolder.isBlank()) {
                                serverFolder = if (provider == SyncProvider.WEBDAV) {
                                    com.babasama.edendale.sync.defaultWebDavSyncFolder(login.host, login.port, linkedFolderUris)
                                } else {
                                    "/"
                                }
                            }
                            chosenAccount = null
                            folderUri = null
                        }
                    }
                }
                chosenServer?.let { (_, host, _) ->
                    OutlinedTextField(
                        value = serverFolder,
                        onValueChange = { serverFolder = it },
                        label = { Text(stringResource(R.string.sync_server_folder_label, host)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                }
                if (isTelevision && offeredAccounts.none { it.kind == MediaSourceKind.GOOGLE_DRIVE }) {
                    Text(stringResource(R.string.sync_google_tv_note), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (accounts.isEmpty() && serverLogins.isEmpty() && smbLogins.isEmpty() && isTelevision) {
                    Text(stringResource(R.string.sync_no_accounts), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.sync_privacy_note), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (saving) Waiting(stringResource(R.string.sync_syncing))
            }
        },
        confirmButton = {
            ArchiveButton(
                label = stringResource(R.string.action_save),
                onClick = { save() },
                enabled = !saving && signingIn == null && (folderUri != null || chosenAccount != null || chosenServer != null),
                kind = ArchiveButtonKind.Primary,
            )
        },
        dismissButton = { ArchiveButton(label = stringResource(R.string.action_cancel), onClick = onDismiss) },
    )
}

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, iconRes: Int, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(com.babasama.edendale.android.EdendaleRadii.Soft.dp),
        color = if (selected) EdendaleColors.SurfaceLow else androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(id = if (selected) R.drawable.ic_check else iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (selected) EdendaleColors.Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun Waiting(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** After a handoff that needs one more step here: pick the folder, or sign in to Google with the hint, then turn sync on. */
@Composable
internal fun FollowUpButton(followUp: SyncHandoff.FollowUp, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val space = (followUp as? SyncHandoff.FollowUp.PickFolder)?.space
        if (uri != null && space != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            scope.launch {
                app.sync.enable(SyncLocation(SyncProvider.FOLDER, space, path = uri.toString()))
                onDone()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (followUp) {
            is SyncHandoff.FollowUp.PickFolder -> ArchiveButton(label = stringResource(R.string.sync_pick_folder), onClick = { folderPicker.launch(null) }, kind = ArchiveButtonKind.Primary)
            is SyncHandoff.FollowUp.GoogleSignIn -> ArchiveButton(
                label = stringResource(R.string.link_source_sign_in_to, "Google Drive"),
                kind = ArchiveButtonKind.Primary,
                onClick = {
                    val activity = context.findActivity() ?: return@ArchiveButton
                    scope.launch {
                        try {
                            val account = app.cloudAccounts.signInForSync(activity, MediaSourceKind.GOOGLE_DRIVE, loginHint = followUp.hint)
                            app.sync.enable(SyncLocation(SyncProvider.GOOGLE_DRIVE, followUp.space, accountKey = account.key, googleFolderId = followUp.folderId))
                            onDone()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = connectorFailureMessage(context, failure) ?: failure.message
                        }
                    }
                },
            )
            is SyncHandoff.FollowUp.OnAnotherDevice -> Unit
        }
        ArchiveButton(label = stringResource(R.string.action_close), onClick = onDone)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Remove a Device (§8.3): deletes another device's file from the space, with the provider's access page for the login (H6). */
@Composable
private fun RemoveDeviceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val sync = app.sync
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) { devices = sync.otherDevices() }
    val kind = sync.settings.location?.provider?.kind
    val managementUrl = kind?.let(CloudProviders::accessManagementUrl)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_remove_device_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sync_remove_device_message), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val list = devices) {
                    null -> Waiting(stringResource(R.string.reading))
                    else -> if (list.isEmpty()) {
                        Text(stringResource(R.string.sync_no_other_devices), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        list.forEach { (id, name) ->
                            ChoiceRow(label = name, selected = false, iconRes = R.drawable.ic_trash_can) {
                                scope.launch {
                                    sync.removeDevice(id)
                                    devices = sync.otherDevices()
                                }
                            }
                        }
                    }
                }
                managementUrl?.let { url ->
                    ArchiveButton(label = stringResource(R.string.sync_manage_access, sourceKindLabel(kind)), onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
                }
            }
        },
        confirmButton = { ArchiveButton(label = stringResource(R.string.action_close), onClick = onDismiss, kind = ArchiveButtonKind.Primary) },
    )
}
