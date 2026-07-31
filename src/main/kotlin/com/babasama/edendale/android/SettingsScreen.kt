package com.babasama.edendale.android

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.SmbClient
import com.babasama.edendale.android.data.WyzieKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    isTelevision: Boolean,
    tmdbAccount: TmdbAccountViewModel,
    contentPadding: PaddingValues = PaddingValues(),
    onDismiss: (() -> Unit)? = null,
) {
    val library = rememberLibrary()
    val folders by library.folders.collectAsState(initial = emptyList())
    val movies by library.movies.collectAsState(initial = emptyList())
    val episodes by library.episodes.collectAsState(initial = emptyList())
    val activity by library.activity.collectAsState()

    var showSmbDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<LibraryFolderEntity?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) library.importFolder(uri)
    }

    if (showSmbDialog) {
        SmbImportDialog(
            isTelevision = isTelevision,
            onDismiss = { showSmbDialog = false },
            onImport = { host, user, pass ->
                library.importSmbFolder(host, user, pass)
                showSmbDialog = false
            },
        )
    }

    pendingRemoval?.let { folder ->
        RemoveSourceDialog(
            folder = folder,
            isTelevision = isTelevision,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                library.removeFolder(folder.treeUri)
                pendingRemoval = null
            },
        )
    }

    // Per-source counts have no repository API; they are derived from the already observed flows.
    val itemCounts = remember(folders, movies, episodes) {
        folders.associate { folder ->
            folder.treeUri to (
                movies.count { it.folderUri == folder.treeUri } +
                    episodes.count { it.folderUri == folder.treeUri }
                )
        }
    }

    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    val wyzieKeyStore = remember(context) { WyzieKeyStore(context) }
    var wyzieKeyStatus by remember { mutableStateOf<WyzieKeyStatus?>(null) }
    var wyzieKeyInput by remember { mutableStateOf("") }
    var wyzieKeyMessage by remember { mutableStateOf<String?>(null) }
    val wyzieStorageError = stringResource(R.string.wyzie_error_key_storage)
    val wyzieBrowserError = stringResource(R.string.wyzie_error_no_browser)

    LaunchedEffect(wyzieKeyStore) {
        wyzieKeyStatus = withContext(Dispatchers.IO) {
            runCatching {
                WyzieKeyStatus(
                    hasUserKey = wyzieKeyStore.hasUserKey(),
                    usesBuildKey = wyzieKeyStore.usesBuildKey(),
                )
            }.getOrNull()
        }
        if (wyzieKeyStatus == null) wyzieKeyMessage = wyzieStorageError
    }

    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "—" }
    }
    val openApprovalPage: (String) -> Unit = { approvalUrl ->
        val result = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(approvalUrl)))
        }
        if (result.isFailure) tmdbAccount.reportBrowserLaunchFailure()
    }

    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = edgeMargin,
            end = edgeMargin,
            top = contentPadding.calculateTopPadding() + 28.dp,
            bottom = contentPadding.calculateBottomPadding() + 56.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.settings_title), modifier = Modifier.weight(1f), large = isTelevision)
                // The sheet is swipe-dismissed everywhere else; a remote cannot
                // swipe, so TV gets an explicit way out.
                if (onDismiss != null) {
                    ArchiveButton(
                        label = stringResource(R.string.action_close),
                        iconRes = R.drawable.ic_xmark,
                        kind = ArchiveButtonKind.Secondary,
                        isTelevision = isTelevision,
                        onClick = onDismiss,
                    )
                }
            }
        }

        item {
            SettingsSection(header = stringResource(R.string.settings_section_about), isTelevision = isTelevision) {
                LabeledRow(label = stringResource(R.string.settings_version), value = versionName)
                SettingsRowDivider()
                LabeledRow(
                    label = stringResource(R.string.settings_watch_progress),
                    value = stringResource(R.string.settings_stored_on_device),
                )
                SettingsRowDivider()
                LabeledRow(
                    label = stringResource(R.string.settings_tmdb_access),
                    value = stringResource(
                        if (AndroidEdendaleCore.hasTmdbCredentials()) {
                            R.string.settings_tmdb_configured
                        } else {
                            R.string.settings_tmdb_missing
                        },
                    ),
                )
            }
        }

        if (isTelevision) {
            item {
                SettingsSection(
                    header = stringResource(R.string.settings_section_android_tv),
                    isTelevision = true,
                ) {
                    InfoRow(stringResource(R.string.settings_android_tv_note))
                }
            }
        }

        item {
            SettingsSection(
                header = stringResource(R.string.settings_section_sources),
                isTelevision = isTelevision,
                focusableContent = false,
                actions = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // SAF folder browsing is unreliable on TV, so local import stays off there.
                        if (!isTelevision) {
                            ArchiveButton(
                                label = stringResource(R.string.add_local_folder_ellipsis),
                                iconRes = R.drawable.ic_folder_circle_plus,
                                onClick = { folderPicker.launch(null) },
                            )
                        }
                        ArchiveButton(
                            label = stringResource(R.string.link_network_source_ellipsis),
                            iconRes = R.drawable.ic_link,
                            isTelevision = isTelevision,
                            onClick = { showSmbDialog = true },
                        )
                    }
                    activity.errorMessage?.let { message ->
                        SettingsRowDivider()
                        Text(
                            text = message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            style = BodyCopyStyle(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            ArchiveButton(
                                label = stringResource(R.string.action_dismiss),
                                isTelevision = isTelevision,
                                onClick = { library.clearError() },
                            )
                        }
                    }
                },
            ) {
                if (folders.isEmpty()) {
                    InfoRow(stringResource(R.string.settings_no_sources))
                } else {
                    folders.forEachIndexed { index, folder ->
                        if (index > 0) SettingsRowDivider()
                        SourceRow(
                            folder = folder,
                            itemCount = itemCounts[folder.treeUri] ?: 0,
                            isScanning = activity.scanningFolder == folder.displayName,
                            isTelevision = isTelevision,
                            onRescan = { library.rescanFolder(folder.treeUri) },
                            onRemove = { pendingRemoval = folder },
                        )
                    }
                }
            }
        }

        item {
            TmdbAccountSettingsSection(
                state = tmdbAccount.state,
                isTelevision = isTelevision,
                onConnect = { tmdbAccount.beginConnect(openApprovalPage) },
                onComplete = tmdbAccount::completeConnect,
                onCancel = tmdbAccount::cancelConnect,
                onSync = tmdbAccount::syncNow,
                onSignOut = tmdbAccount::signOut,
            )
        }

        item {
            WyzieKeySettingsSection(
                status = wyzieKeyStatus,
                keyInput = wyzieKeyInput,
                message = wyzieKeyMessage,
                isTelevision = isTelevision,
                onKeyInputChanged = {
                    wyzieKeyInput = it
                    wyzieKeyMessage = null
                },
                onOpenStore = {
                    val result = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(WYZIE_REDEEM_URL)),
                        )
                    }
                    if (result.isFailure) wyzieKeyMessage = wyzieBrowserError
                },
                onSave = {
                    settingsScope.launch {
                        val result = runCatching { wyzieKeyStore.save(wyzieKeyInput) }
                        if (result.isSuccess) {
                            wyzieKeyInput = ""
                            wyzieKeyMessage = null
                            wyzieKeyStatus = WyzieKeyStatus(
                                hasUserKey = true,
                                usesBuildKey = false,
                            )
                        } else {
                            wyzieKeyMessage = wyzieStorageError
                        }
                    }
                },
                onRemove = {
                    settingsScope.launch {
                        val result = runCatching { wyzieKeyStore.clear() }
                        if (result.isSuccess) {
                            wyzieKeyMessage = null
                            wyzieKeyStatus = WyzieKeyStatus(
                                hasUserKey = false,
                                usesBuildKey = wyzieKeyStore.buildKey.isNotEmpty(),
                            )
                        } else {
                            wyzieKeyMessage = wyzieStorageError
                        }
                    }
                },
            )
        }

        item {
            // Windows shows the equivalent OneDrive status; on Android the
            // platform default is Auto Backup, which needs no wiring of ours.
            SettingsSection(
                header = stringResource(R.string.settings_section_backup),
                isTelevision = isTelevision,
            ) {
                InfoRow(stringResource(R.string.settings_backup_note))
                SettingsRowDivider()
                InfoRow(stringResource(R.string.settings_backup_secrets_note))
            }
        }

        item {
            SettingsSection(
                header = stringResource(R.string.settings_section_privacy),
                isTelevision = isTelevision,
            ) {
                InfoRow(stringResource(R.string.settings_privacy_note))
            }
        }

        item {
            SettingsSection(
                header = stringResource(R.string.settings_section_attribution),
                isTelevision = isTelevision,
            ) {
                InfoRow(stringResource(R.string.tmdb_attribution))
                SettingsRowDivider()
                InfoRow(stringResource(R.string.settings_open_source))
            }
        }
    }
}

private data class WyzieKeyStatus(
    val hasUserKey: Boolean,
    val usesBuildKey: Boolean,
)

@Composable
private fun WyzieKeySettingsSection(
    status: WyzieKeyStatus?,
    keyInput: String,
    message: String?,
    isTelevision: Boolean,
    onKeyInputChanged: (String) -> Unit,
    onOpenStore: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.settings_section_subtitles),
        isTelevision = isTelevision,
        focusableContent = false,
    ) {
        when {
            status == null -> AccountProgressRow(stringResource(R.string.wyzie_loading_key))

            status.hasUserKey -> {
                FocusableRows(isTelevision) {
                    LabeledRow(
                        label = stringResource(R.string.wyzie_api_key),
                        value = stringResource(R.string.wyzie_key_saved),
                    )
                    SettingsRowDivider()
                    InfoRow(stringResource(R.string.wyzie_key_encrypted))
                }
                SettingsRowDivider()
                SettingsActionRow {
                    ArchiveButton(
                        label = stringResource(R.string.action_remove),
                        isTelevision = isTelevision,
                        onClick = onRemove,
                    )
                }
            }

            else -> {
                FocusableRows(isTelevision) {
                    InfoRow(
                        stringResource(
                            if (status.usesBuildKey) {
                                R.string.wyzie_using_build_key
                            } else {
                                R.string.wyzie_key_required
                            },
                        ),
                    )
                }
                if (!status.usesBuildKey) {
                    SettingsRowDivider()
                    SettingsActionRow {
                        ArchiveButton(
                            label = stringResource(R.string.wyzie_get_free_key),
                            kind = ArchiveButtonKind.Secondary,
                            isTelevision = isTelevision,
                            onClick = onOpenStore,
                        )
                    }
                }
                SettingsRowDivider()
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = onKeyInputChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    label = { Text(stringResource(R.string.wyzie_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                SettingsRowDivider()
                SettingsActionRow {
                    ArchiveButton(
                        label = stringResource(R.string.action_save),
                        enabled = keyInput.isNotBlank(),
                        kind = ArchiveButtonKind.Secondary,
                        isTelevision = isTelevision,
                        onClick = onSave,
                    )
                }
            }
        }

        message?.let {
            SettingsRowDivider()
            Text(
                text = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                style = BodyCopyStyle(),
                color = EdendaleColors.Gold,
            )
        }
    }
}

@Composable
private fun TmdbAccountSettingsSection(
    state: TmdbAccountUiState,
    isTelevision: Boolean,
    onConnect: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.settings_section_tmdb_account),
        isTelevision = isTelevision,
        focusableContent = false,
    ) {
        FocusableRows(isTelevision) {
            InfoRow(stringResource(R.string.tmdb_account_intro))
        }
        SettingsRowDivider()

        when (state.phase) {
            TmdbAccountPhase.LOADING -> AccountProgressRow(stringResource(R.string.tmdb_loading_account))

            TmdbAccountPhase.SIGNED_OUT -> {
                FocusableRows(isTelevision) {
                    LabeledRow(
                        label = stringResource(R.string.tmdb_account_label),
                        value = stringResource(R.string.tmdb_not_connected),
                    )
                }
                SettingsRowDivider()
                if (state.canConnect) {
                    SettingsActionRow {
                        ArchiveButton(
                            label = stringResource(R.string.tmdb_connect),
                            kind = ArchiveButtonKind.Secondary,
                            isTelevision = isTelevision,
                            onClick = onConnect,
                        )
                    }
                } else {
                    FocusableRows(isTelevision) {
                        InfoRow(stringResource(R.string.tmdb_needs_credentials))
                    }
                }
            }

            TmdbAccountPhase.STARTING -> AccountProgressRow(stringResource(R.string.tmdb_starting_sign_in))

            TmdbAccountPhase.AWAITING_APPROVAL -> {
                FocusableRows(isTelevision) {
                    InfoRow(stringResource(R.string.tmdb_awaiting_approval))
                }
                state.approvalUrl?.let { approvalUrl ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TmdbApprovalQrCode(
                            approvalUrl = approvalUrl,
                            isTelevision = isTelevision,
                        )
                    }
                }
                SettingsRowDivider()
                SettingsActionRow {
                    ArchiveButton(
                        label = stringResource(R.string.tmdb_click_to_continue),
                        kind = ArchiveButtonKind.Secondary,
                        isTelevision = isTelevision,
                        onClick = onComplete,
                    )
                    ArchiveButton(
                        label = stringResource(R.string.action_cancel),
                        isTelevision = isTelevision,
                        onClick = onCancel,
                    )
                }
            }

            TmdbAccountPhase.CONNECTING -> AccountProgressRow(stringResource(R.string.tmdb_finishing_sign_in))

            TmdbAccountPhase.CONNECTED -> {
                FocusableRows(isTelevision) {
                    LabeledRow(
                        label = stringResource(R.string.tmdb_account_label),
                        value = state.accountLabel?.let { stringResource(R.string.tmdb_connected_as, it) }
                            ?: stringResource(R.string.tmdb_connected),
                    )
                }
                state.lastSyncStatus?.let { status ->
                    SettingsRowDivider()
                    FocusableRows(isTelevision) {
                        LabeledRow(label = stringResource(R.string.tmdb_last_sync), value = status)
                    }
                }
                SettingsRowDivider()
                SettingsActionRow {
                    ArchiveButton(
                        label = stringResource(R.string.tmdb_sync_now),
                        kind = ArchiveButtonKind.Secondary,
                        isTelevision = isTelevision,
                        onClick = onSync,
                    )
                    ArchiveButton(
                        label = stringResource(R.string.tmdb_sign_out),
                        isTelevision = isTelevision,
                        onClick = onSignOut,
                    )
                }
            }
        }

        state.errorMessage?.let { message ->
            SettingsRowDivider()
            Text(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One D-pad focus stop wrapping a group of read-only rows. */
@Composable
private fun FocusableRows(
    isTelevision: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusableBlock(isTelevision),
        content = content,
    )
}

@Composable
private fun AccountProgressRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A settings group. On TV the reading matter in [content] is one focus target,
 * so the remote can walk the list — and therefore scroll it — instead of
 * skipping between the few buttons and leaving whole sections unreachable.
 * [actions] renders *below* that target, outside it, so buttons keep their own
 * focus and stay clickable.
 */
@Composable
private fun SettingsSection(
    header: String,
    isTelevision: Boolean = false,
    // Sections whose rows carry their own buttons focus each row instead, so the
    // buttons never end up nested inside a focus target.
    focusableContent: Boolean = isTelevision,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = header.uppercase(),
            modifier = Modifier.padding(bottom = 8.dp),
            style = LabelCapsStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(EdendaleRadii.Group.dp),
            color = EdendaleColors.SurfaceLow,
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableBlock(focusableContent),
                    content = content,
                )
                actions?.let { rows ->
                    SettingsRowDivider()
                    Column(modifier = Modifier.fillMaxWidth(), content = rows)
                }
            }
        }
    }
}

/** Hairline between sibling rows, inset to the row's text leading edge. */
@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun InfoRow(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        style = BodyCopyStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SourceRow(
    folder: LibraryFolderEntity,
    itemCount: Int,
    isScanning: Boolean,
    isTelevision: Boolean,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    val isRemote = SmbClient.hostOf(folder.treeUri) != null
    val kindLabel = stringResource(
        if (isRemote) R.string.source_kind_smb else R.string.source_kind_local_folder,
    )
    val detail = if (isScanning) {
        stringResource(R.string.scanning)
    } else {
        pluralStringResource(R.plurals.item_count, itemCount, itemCount)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                id = if (isRemote) R.drawable.ic_link else R.drawable.ic_folder_closed,
            ),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .tvFocusableBlock(isTelevision),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = folder.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.metadata_separator, kindLabel, detail),
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // TextOverflow.MiddleEllipsis needs Compose 1.9; truncate the string instead so
                // both the host and the leaf folder stay readable.
                text = folder.treeUri.middleTruncated(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        ArchiveButton(
            label = stringResource(R.string.action_rescan),
            iconRes = R.drawable.ic_arrow_rotate_right,
            isTelevision = isTelevision,
            onClick = onRescan,
        )
        Spacer(Modifier.width(8.dp))
        ArchiveButton(
            label = stringResource(R.string.action_remove),
            iconRes = R.drawable.ic_trash_can,
            isTelevision = isTelevision,
            onClick = onRemove,
        )
    }
}

@Composable
private fun RemoveSourceDialog(
    folder: LibraryFolderEntity,
    isTelevision: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val host = SmbClient.hostOf(folder.treeUri)
    val message = if (host != null) {
        stringResource(R.string.remove_source_message_smb, folder.displayName, host)
    } else {
        stringResource(R.string.remove_source_message_local, folder.displayName)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        containerColor = EdendaleColors.Surface,
        title = {
            Text(
                text = stringResource(R.string.remove_source_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = message,
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        dismissButton = {
            ArchiveButton(label = stringResource(R.string.action_cancel), isTelevision = isTelevision, onClick = onDismiss)
        },
        confirmButton = {
            ArchiveButton(
                label = stringResource(R.string.action_remove),
                kind = ArchiveButtonKind.Secondary,
                isTelevision = isTelevision,
                onClick = onConfirm,
            )
        },
    )
}

private const val WYZIE_REDEEM_URL = "https://store.wyzie.io/redeem"

@Composable
private fun LabelCapsStyle() = MaterialTheme.typography.labelLarge.copy(
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

@Composable
private fun BodyCopyStyle() = MaterialTheme.typography.bodyMedium.copy(
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

private fun String.middleTruncated(budget: Int = 56): String {
    if (length <= budget) return this
    val head = (budget - 1) / 2
    val tail = budget - 1 - head
    return take(head) + "…" + takeLast(tail)
}
