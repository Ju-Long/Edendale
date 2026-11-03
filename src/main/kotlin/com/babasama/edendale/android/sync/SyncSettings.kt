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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.babasama.edendale.android.ApprovalQrCode
import com.babasama.edendale.android.ArchiveButton
import com.babasama.edendale.android.ArchiveButtonKind
import com.babasama.edendale.android.BodyCopyStyle
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.FocusableRows
import com.babasama.edendale.android.InfoRow
import com.babasama.edendale.android.LabeledRow
import com.babasama.edendale.android.R
import com.babasama.edendale.android.SettingsRowDivider
import com.babasama.edendale.android.SettingsSection
import com.babasama.edendale.android.connectorFailureMessage
import com.babasama.edendale.android.data.SavedServerLogin
import com.babasama.edendale.android.data.SavedSmbLogin
import com.babasama.edendale.android.data.ServerLoginStore
import com.babasama.edendale.android.data.SmbCredentialsStore
import com.babasama.edendale.android.handoff.DiscoveredTelevision
import com.babasama.edendale.android.handoff.HandoffDiscovery
import com.babasama.edendale.android.handoff.PairingGuest
import com.babasama.edendale.android.handoff.PairingHost
import com.babasama.edendale.android.handoff.handoffFailureMessage
import com.babasama.edendale.android.sourceKindLabel
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.handoff.HandoffCrypto
import com.babasama.edendale.handoff.HandoffProtocolV2
import com.babasama.edendale.handoff.HandoffV2
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudProviders
import com.babasama.edendale.sync.DeviceFileCodec
import com.babasama.edendale.sync.SpaceName
import com.babasama.edendale.sync.SyncLocation
import com.babasama.edendale.sync.SyncProvider
import com.babasama.edendale.sync.SyncWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * removing a device, and turning off. Never claims instant sync.
 */
@Composable
internal fun SyncSettingsSection(isTelevision: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val sync = app.sync
    val status by sync.status.collectAsState()
    val changes by sync.settings.changes.collectAsState()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<Boolean?>(null) } // true hosting, false guest
    var turningOff by remember { mutableStateOf(false) }
    var removingDevice by remember { mutableStateOf(false) }
    var accountLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(changes) {
        sync.refreshStatus()
        val location = sync.settings.location
        accountLabel = location?.accountKey?.let { key ->
            location.provider.kind?.let { kind -> withContext(Dispatchers.IO) { runCatching { app.cloudAccounts.vault.account(kind, key)?.label }.getOrNull() } }
        }
    }

    SettingsSection(
        header = stringResource(R.string.settings_section_sync),
        isTelevision = isTelevision,
        focusableContent = false,
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (status.isOn) {
                    ArchiveButton(label = stringResource(R.string.sync_now), onClick = { sync.syncNow() }, enabled = !status.syncing, iconRes = R.drawable.ic_arrow_rotate_right)
                    ArchiveButton(label = stringResource(R.string.sync_set_up_device), onClick = { pairing = true }, iconRes = R.drawable.ic_tv)
                } else {
                    ArchiveButton(label = stringResource(R.string.sync_choose_location), onClick = { choosing = true }, kind = ArchiveButtonKind.Primary, iconRes = R.drawable.ic_folder_circle_plus)
                }
                ArchiveButton(label = stringResource(R.string.sync_receive_from_device), onClick = { pairing = false }, iconRes = R.drawable.ic_link)
            }
            if (status.isOn) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ArchiveButton(label = stringResource(R.string.sync_choose_location), onClick = { choosing = true })
                    ArchiveButton(label = stringResource(R.string.sync_remove_device), onClick = { removingDevice = true })
                    ArchiveButton(label = stringResource(R.string.sync_turn_off), onClick = { turningOff = true }, iconRes = R.drawable.ic_xmark)
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
    }

    if (choosing) {
        ChooseLocationDialog(isTelevision = isTelevision, onDismiss = { choosing = false })
    }
    pairing?.let { hosting ->
        PairingDialog(isTelevision = isTelevision, hosting = hosting, onDismiss = { pairing = null })
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
 * linked cloud account, or a saved server login with the folder inside it,
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
    var smbLogins by remember { mutableStateOf<List<SavedSmbLogin>>(emptyList()) }
    var chosenAccount by remember { mutableStateOf<CloudAccount?>(null) }
    var chosenServer by remember { mutableStateOf<Triple<SyncProvider, String, Int?>?>(null) }
    var serverFolder by remember { mutableStateOf("") }
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            accounts = runCatching { app.cloudAccounts.vault.all() }.getOrDefault(emptyList())
            serverLogins = runCatching { ServerLoginStore(context).all().filter { it.kind == MediaSourceKind.WEBDAV || it.kind == MediaSourceKind.SFTP } }.getOrDefault(emptyList())
            smbLogins = runCatching { SmbCredentialsStore(context).savedLogins() }.getOrDefault(emptyList())
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
                            if (serverFolder.isBlank()) serverFolder = if (provider == SyncProvider.WEBDAV) "https://${login.address}/" else "/"
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
            ArchiveButton(label = stringResource(R.string.action_save), onClick = { save() }, enabled = !saving, kind = ArchiveButtonKind.Primary)
        },
        dismissButton = { ArchiveButton(label = stringResource(R.string.action_cancel), onClick = onDismiss) },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, iconRes: Int, onClick: () -> Unit) {
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
private fun Waiting(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the sender ticks (§9.6 step 2): the location, and each other account and login. */
private data class OfferChoice(
    val location: HandoffV2.LocationPayload?,
    val accounts: List<AccountHandoff.Account>,
    val logins: List<AccountHandoff.Login>,
    val sendLocation: Boolean = location != null,
    val tickedAccounts: Set<Int> = emptySet(),
    val tickedLogins: Set<Int> = emptySet(),
) {
    val isEmpty: Boolean get() = location == null && accounts.isEmpty() && logins.isEmpty()
}

/**
 * Pairing with another device (§9), as host (this device shows the QR code
 * and the code) or guest (this device scans, pastes, finds, or types).
 * Either side may send; the viewer picks first. The sender confirms what to
 * send with the other device's name on screen before anything travels.
 */
@Composable
internal fun PairingDialog(isTelevision: Boolean, hosting: Boolean, onDismiss: () -> Unit, initialLink: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val sync = app.sync
    val handoff = remember(app) { SyncHandoff(app) }
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf<HandoffV2.Role?>(null) }
    var host by remember { mutableStateOf<PairingHost?>(null) }
    val hostState = host?.state?.collectAsState()?.value
    val discovery = remember(context) { HandoffDiscovery(context) }
    val found by discovery.televisions.collectAsState()
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var typedAddress by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<DiscoveredTelevision?>(null) }
    var guestJob by remember { mutableStateOf<Job?>(null) }
    var guestStatus by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<Pair<HandoffV2.RoleMessage, HandoffV2.ResultMessage?>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var followUp by remember { mutableStateOf<SyncHandoff.FollowUp?>(null) }
    // The sender's confirmation, awaited by the protocol thread.
    var pendingOffer by remember { mutableStateOf<Pair<HandoffV2.RoleMessage, CompletableDeferred<HandoffV2.Offer?>>?>(null) }
    var choice by remember { mutableStateOf<OfferChoice?>(null) }

    val platform = sync.platform
    val deviceName = sync.deviceName()

    suspend fun buildChoice(): OfferChoice {
        val location = handoff.locationPayload()
        val accounts = handoff.otherAccounts().filter { it.subject != location?.account?.subject }
        val logins = handoff.otherLogins().filter { it.host != location?.login?.host || it.kind != location.login?.kind }
        return OfferChoice(location, accounts, logins)
    }

    fun participant(myRole: HandoffV2.Role) = HandoffProtocolV2.Participant(
        myRole, deviceName, platform,
        offer = { peer ->
            val deferred = CompletableDeferred<HandoffV2.Offer?>()
            pendingOffer = peer to deferred
            runBlocking { choice = buildChoice() }
            runBlocking { deferred.await() }
        },
        receive = { incoming, _ ->
            val received = runBlocking { handoff.receive(incoming) }
            followUp = received.followUp
            received.result
        },
    )

    DisposableEffect(hosting) {
        if (!hosting) discovery.start()
        onDispose {
            discovery.stop()
            host?.stop()
            guestJob?.cancel()
            pendingOffer?.second?.complete(null)
        }
    }

    fun startHost(chosen: HandoffV2.Role) {
        val created = PairingHost(context, chosen, platform, deviceName, offer = { peer -> participant(chosen).offer(peer) }, receive = { incoming, peer -> participant(chosen).receive(incoming, peer) })
        host = created
        created.start(scope)
    }

    fun connect(address: java.net.InetAddress, port: Int, pairing: HandoffProtocolV2.GuestPairing, chosen: HandoffV2.Role, peerName: String) {
        failure = null
        guestStatus = context.getString(R.string.handoff_connecting, peerName)
        guestJob = scope.launch(Dispatchers.IO) {
            try {
                val result = PairingGuest.run(address, port, pairing, participant(chosen))
                outcome = result.peer to result.result
                guestStatus = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = pairingFailureMessage(context, error)
                guestStatus = null
            } finally {
                pendingOffer = null
            }
        }
    }

    fun connectWithLink(chosen: HandoffV2.Role) {
        val payload = HandoffV2.QrPayload.parse(link) ?: run { failure = context.getString(R.string.pairing_wrong_secret); return }
        val address = payload.host?.let { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
            ?: found.firstOrNull { it.name == payload.name }?.host
            ?: run { failure = context.getString(R.string.handoff_connection_failed); return }
        val port = payload.port ?: found.firstOrNull { it.name == payload.name }?.port ?: run { failure = context.getString(R.string.handoff_connection_failed); return }
        connect(address, port, HandoffProtocolV2.GuestPairing.Qr(payload), chosen, payload.name)
    }

    fun connectWithCode(chosen: HandoffV2.Role) {
        val device = target ?: return
        val typed = code.filter(Char::isDigit)
        if (typed.length != HandoffCrypto.CODE_LENGTH) return
        connect(device.host, device.port, HandoffProtocolV2.GuestPairing.Code(typed), chosen, device.name)
    }

    val finished = outcome ?: (hostState?.status as? PairingHost.Status.Done)?.let { it.peer to it.result }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    pendingOffer != null -> stringResource(R.string.pairing_confirm_send_title, pendingOffer!!.first.name)
                    hosting -> stringResource(R.string.sync_set_up_device)
                    else -> stringResource(R.string.sync_receive_from_device)
                },
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val chosenRole = role
                val offer = pendingOffer
                val current = choice
                when {
                    finished != null -> {
                        val (peer, result) = finished
                        Text(stringResource(if (chosenRole == HandoffV2.Role.SENDER) R.string.pairing_sent else R.string.pairing_received, peer.name), style = MaterialTheme.typography.bodyMedium)
                        result?.let {
                            Text(context.resources.getQuantityString(R.plurals.pairing_stored_count, it.stored.size, it.stored.size), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (it.failed.isNotEmpty()) Text(context.resources.getQuantityString(R.plurals.pairing_failed_count, it.failed.size, it.failed.size), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.error)
                        }
                        when (val next = followUp) {
                            is SyncHandoff.FollowUp.PickFolder -> Text(stringResource(R.string.sync_folder_relink, next.space), style = BodyCopyStyle(), color = EdendaleColors.Gold)
                            is SyncHandoff.FollowUp.GoogleSignIn -> Text(stringResource(R.string.sync_google_relink, next.hint ?: "Google", next.space), style = BodyCopyStyle(), color = EdendaleColors.Gold)
                            null -> Unit
                        }
                    }
                    offer != null && current != null -> OfferChoices(current, context, onChange = { choice = it })
                    offer != null -> Waiting(stringResource(R.string.pairing_connected, offer.first.name))
                    chosenRole == null -> {
                        Text(stringResource(R.string.pairing_role_question), style = MaterialTheme.typography.bodyMedium)
                        ChoiceRow(label = stringResource(R.string.pairing_role_send), selected = false, iconRes = R.drawable.ic_forward) { role = HandoffV2.Role.SENDER; if (hosting) startHost(HandoffV2.Role.SENDER) }
                        ChoiceRow(label = stringResource(R.string.pairing_role_receive), selected = false, iconRes = R.drawable.ic_backward) { role = HandoffV2.Role.RECEIVER; if (hosting) startHost(HandoffV2.Role.RECEIVER) }
                        Text(stringResource(R.string.handoff_request_message), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    hosting -> {
                        val state = hostState
                        Text(stringResource(R.string.pairing_instructions), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state?.qrUri?.let { ApprovalQrCode(it, stringResource(R.string.pairing_qr_description), isTelevision) }
                        state?.code?.let { shown ->
                            Text(stringResource(R.string.pairing_code_label), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(shown.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall, color = EdendaleColors.Gold)
                        }
                        if (!state?.addresses.isNullOrEmpty()) Text(stringResource(R.string.handoff_tv_address, state!!.addresses.joinToString("  ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (val status = state?.status) {
                            is PairingHost.Status.Connected -> Waiting(status.peerName?.let { stringResource(R.string.pairing_connected, it) } ?: stringResource(R.string.handoff_tv_connected))
                            is PairingHost.Status.Failed -> Text(pairingFailureMessage(context, status.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            is PairingHost.Status.LockedOut -> Text(stringResource(R.string.pairing_locked_out), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            else -> Waiting(stringResource(R.string.pairing_waiting))
                        }
                    }
                    guestStatus != null -> Waiting(guestStatus!!)
                    target != null -> {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(HandoffCrypto.CODE_LENGTH) },
                            label = { Text(stringResource(R.string.pairing_enter_code, target!!.name)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { connectWithCode(chosenRole) }),
                        )
                    }
                    else -> {
                        Text(stringResource(R.string.pairing_scan_hint), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text(stringResource(R.string.pairing_link_label)) },
                            placeholder = { Text("edendale://handoff?v=2&…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { connectWithLink(chosenRole) }),
                        )
                        val hosts = found.filter { it.speaksVersion2 }
                        if (hosts.isNotEmpty()) {
                            Text(stringResource(R.string.pairing_devices_found).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            hosts.forEach { device -> ChoiceRow(label = device.name, selected = false, iconRes = R.drawable.ic_tv) { code = ""; target = device } }
                        } else {
                            Waiting(stringResource(R.string.pairing_searching))
                        }
                        OutlinedTextField(
                            value = typedAddress,
                            onValueChange = { typedAddress = it },
                            label = { Text(stringResource(R.string.pairing_address_label)) },
                            placeholder = { Text("192.168.1.20:40123") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { typedDevice(typedAddress)?.let { code = ""; target = it } }),
                        )
                    }
                }
                failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val chosenRole = role
            val offer = pendingOffer
            val current = choice
            when {
                finished != null -> {
                    when (val next = followUp) {
                        is SyncHandoff.FollowUp.PickFolder, is SyncHandoff.FollowUp.GoogleSignIn -> FollowUpButton(next, onDone = onDismiss)
                        null -> ArchiveButton(label = stringResource(R.string.action_close), onClick = onDismiss, kind = ArchiveButtonKind.Primary)
                    }
                }
                offer != null && current != null -> ArchiveButton(
                    label = stringResource(R.string.pairing_send),
                    enabled = !current.isEmpty,
                    kind = ArchiveButtonKind.Primary,
                    onClick = {
                        val built = HandoffV2.offerFor(
                            if (current.sendLocation) current.location else null,
                            current.accounts.filterIndexed { index, _ -> index in current.tickedAccounts },
                            current.logins.filterIndexed { index, _ -> index in current.tickedLogins },
                            platform,
                            offer.first.platform,
                        )
                        offer.second.complete(built)
                        pendingOffer = null
                        choice = null
                    },
                )
                !hosting && chosenRole != null && target != null && guestStatus == null -> ArchiveButton(label = stringResource(R.string.action_connect), onClick = { connectWithCode(chosenRole) }, enabled = code.length == HandoffCrypto.CODE_LENGTH, kind = ArchiveButtonKind.Primary)
                !hosting && chosenRole != null && target == null && guestStatus == null && link.isNotBlank() -> ArchiveButton(label = stringResource(R.string.action_connect), onClick = { connectWithLink(chosenRole) }, kind = ArchiveButtonKind.Primary)
                !hosting && chosenRole != null && target == null && guestStatus == null && typedAddress.isNotBlank() -> ArchiveButton(label = stringResource(R.string.action_connect), onClick = { typedDevice(typedAddress)?.let { code = ""; target = it } }, enabled = typedDevice(typedAddress) != null, kind = ArchiveButtonKind.Primary)
                else -> Unit
            }
        },
        dismissButton = {
            val offer = pendingOffer
            if (offer != null) {
                ArchiveButton(label = stringResource(R.string.handoff_decline), onClick = { offer.second.complete(null); pendingOffer = null; choice = null })
            } else if (finished == null) {
                ArchiveButton(
                    label = stringResource(if (target != null && guestStatus == null) R.string.action_back else R.string.action_cancel),
                    onClick = { if (target != null && guestStatus == null) target = null else onDismiss() },
                )
            }
        },
    )
}

@Composable
private fun OfferChoices(choice: OfferChoice, context: Context, onChange: (OfferChoice) -> Unit) {
    if (choice.isEmpty) {
        Text(stringResource(R.string.pairing_nothing_to_send), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(stringResource(R.string.handoff_request_message), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    choice.location?.let { location ->
        val label = if (location.provider == SyncProvider.FOLDER) {
            stringResource(R.string.pairing_send_location_folder, location.space)
        } else {
            stringResource(R.string.pairing_send_location, sourceKindLabel(location.provider.kind), location.space)
        }
        TickRow(label, choice.sendLocation) { onChange(choice.copy(sendLocation = it)) }
    }
    if (choice.accounts.isNotEmpty()) {
        Text(stringResource(R.string.pairing_send_accounts).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        choice.accounts.forEachIndexed { index, account ->
            TickRow("${sourceKindLabel(account.kind)} · ${account.email ?: account.displayName ?: account.subject}", index in choice.tickedAccounts) { ticked ->
                onChange(choice.copy(tickedAccounts = if (ticked) choice.tickedAccounts + index else choice.tickedAccounts - index))
            }
        }
    }
    if (choice.logins.isNotEmpty()) {
        Text(stringResource(R.string.pairing_send_logins).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        choice.logins.forEachIndexed { index, login ->
            TickRow("${sourceKindLabel(login.kind)} · ${login.user} @ ${login.host}${login.port?.let { ":$it" }.orEmpty()}", index in choice.tickedLogins) { ticked ->
                onChange(choice.copy(tickedLogins = if (ticked) choice.tickedLogins + index else choice.tickedLogins - index))
            }
        }
    }
}

@Composable
private fun TickRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Surface(onClick = { onChange(!checked) }, modifier = Modifier.fillMaxWidth(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** After a handoff that needs one more step here: pick the folder, or sign in to Google with the hint, then turn sync on. */
@Composable
private fun FollowUpButton(followUp: SyncHandoff.FollowUp, onDone: () -> Unit) {
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
                            val account = app.cloudAccounts.signIn(activity, MediaSourceKind.GOOGLE_DRIVE, loginHint = followUp.hint).account
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

/** A host from an `address:port` the viewer typed off the other screen. */
private fun typedDevice(text: String): DiscoveredTelevision? {
    val trimmed = text.trim()
    val port = trimmed.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val host = trimmed.substringBeforeLast(':').trim('[', ']').takeIf { it.isNotBlank() } ?: return null
    val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return null
    return DiscoveredTelevision(trimmed, address, port, version = 2)
}
