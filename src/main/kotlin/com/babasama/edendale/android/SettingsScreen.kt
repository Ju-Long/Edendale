package com.babasama.edendale.android

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.WyzieKeyStore
import com.babasama.edendale.android.parental.ParentalControlsSection
import com.babasama.edendale.android.parental.ParentalControlsSummarySection
import com.babasama.edendale.android.parental.rememberParentalGate
import com.babasama.edendale.parental.GatedAction
import com.babasama.edendale.android.player.PlayerLogic
import com.babasama.edendale.android.player.PlayerPreferences
import com.babasama.edendale.android.player.PlayerPreferencesRules
import com.babasama.edendale.android.player.SkipDirection
import com.babasama.edendale.android.player.SkipInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceEndpoint
import com.babasama.edendale.android.data.SourceScanRules

/**
 * The pages Settings opens from its first page, in the order iOS 27.1 lists
 * them, each with the glyph its row shows. Android TV adds its home-screen
 * page; Parental Controls opens from its own group under the categories.
 */
internal enum class SettingsPage(@StringRes val title: Int, @DrawableRes val icon: Int) {
    PLAYER_CONTROLS(R.string.settings_player_controls, R.drawable.ic_play),
    AUDIO(R.string.settings_audio, R.drawable.ic_file_music),
    SUBTITLES(R.string.settings_section_subtitles, R.drawable.ic_subtitles),
    LIBRARY(R.string.settings_library, R.drawable.ic_folder_tree),
    SYNC(R.string.settings_section_sync, R.drawable.ic_arrow_rotate_right),
    ACCOUNTS(R.string.settings_section_accounts, R.drawable.ic_circle_user_fill),
    ANDROID_TV(R.string.settings_section_android_tv, R.drawable.ic_tv),
    PARENTAL_CONTROLS(R.string.settings_parental_more, R.drawable.ic_eye_slash),
    ;

    companion object {
        /** The category rows on the first page, in order; Android TV only on a TV. */
        fun categories(isTelevision: Boolean): List<SettingsPage> =
            listOf(PLAYER_CONTROLS, AUDIO, SUBTITLES, LIBRARY, SYNC, ACCOUNTS) +
                if (isTelevision) listOf(ANDROID_TV) else emptyList()
    }
}

/**
 * Settings, laid out as iOS 27.1 lays it out: a first page listing the
 * categories, then Parental Controls and About; each category opens as a page
 * of its own, and Back returns to the list.
 */
@Composable
fun SettingsScreen(
    isTelevision: Boolean,
    tmdbAccount: TmdbAccountViewModel,
    contentPadding: PaddingValues = PaddingValues(),
    onDismiss: (() -> Unit)? = null,
    /** Back on the first page, where the host doesn't handle it itself (the phone sheet). */
    onBackFromFirstPage: (() -> Unit)? = null,
) {
    val library = rememberLibrary()
    val folders by library.folders.collectAsState(initial = emptyList())
    val movies by library.movies.collectAsState(initial = emptyList())
    val episodes by library.episodes.collectAsState(initial = emptyList())
    val activity by library.activity.collectAsState()
    // Google Drive sources (H.9.4): what Choose More Videos added, and the picker itself.
    val sourceNotes by library.sourceNotes.collectAsState()
    // Library edits and every Sync button ask for the parental PIN while their lock is on (O.5).
    val gate = rememberParentalGate(isTelevision)
    val chooseMoreVideos = rememberChooseMoreVideos(gate)

    var showSmbDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<LibraryFolderEntity?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) library.importFolder(uri)
    }

    if (showSmbDialog) {
        LinkSourceDialog(
            isTelevision = isTelevision,
            onDismiss = { showSmbDialog = false },
            onLinked = { showSmbDialog = false },
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

    // The open page; null is the first page. Back (the key, the gesture, or
    // the page's Back chip) returns to the list, and on TV focus lands on the
    // row the page opened from.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var openedFrom by remember { mutableStateOf<SettingsPage?>(null) }
    val closePage: () -> Unit = {
        openedFrom = page
        page = null
    }
    BackHandler(enabled = page != null || onBackFromFirstPage != null) {
        if (page != null) closePage() else onBackFromFirstPage?.invoke()
    }
    val rowFocus = remember { SettingsPage.entries.associateWith { FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(page) {
        if (!isTelevision) return@LaunchedEffect
        val target = if (page != null) backFocus else openedFrom?.let(rowFocus::getValue) ?: return@LaunchedEffect
        // The new page composes first; retry for a few frames until its row can take focus.
        repeat(5) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    // The first page keeps its scroll position; each page starts at the top.
    val firstPageList = rememberLazyListState()
    val pageList = page?.let { key(it) { rememberLazyListState() } }

    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = pageList ?: firstPageList,
        contentPadding = PaddingValues(
            start = edgeMargin,
            end = edgeMargin,
            top = contentPadding.calculateTopPadding() + 28.dp,
            bottom = contentPadding.calculateBottomPadding() + 56.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        val current = page
        item(key = "header") {
            if (current == null) {
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
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BackChip(
                        isTelevision = isTelevision,
                        onBack = closePage,
                        modifier = Modifier.focusRequester(backFocus),
                    )
                    SectionHeader(stringResource(current.title), modifier = Modifier.weight(1f), large = isTelevision)
                }
            }
        }

        when (current) {
            null -> {
                item(key = "categories") {
                    SettingsCategoryList(
                        pages = SettingsPage.categories(isTelevision),
                        rowFocus = rowFocus,
                        onOpen = { page = it },
                    )
                }
                item(key = "parental") {
                    // Section O: the Young Audience switch here; ratings, locks, and the PIN on their page.
                    ParentalControlsSummarySection(
                        gate = gate,
                        isTelevision = isTelevision,
                        moreModifier = Modifier.focusRequester(rowFocus.getValue(SettingsPage.PARENTAL_CONTROLS)),
                        onOpenMore = { page = SettingsPage.PARENTAL_CONTROLS },
                    )
                }
                item(key = "about") {
                    AboutSettingsSection(versionName = versionName, isTelevision = isTelevision)
                }
            }

            SettingsPage.PLAYER_CONTROLS -> {
                item { AppControlsSettingsSection(isTelevision = isTelevision) }
                item { SkipPromptsSettingsSection(isTelevision = isTelevision) }
            }

            SettingsPage.AUDIO -> item {
                AudioEnhancementSettingsSection(isTelevision = isTelevision)
            }

            SettingsPage.SUBTITLES -> {
                item {
                    SettingsSection(
                        header = stringResource(R.string.settings_section_appearance),
                        isTelevision = isTelevision,
                        focusableContent = false,
                    ) {
                        SubtitleAppearanceRows(isTelevision)
                    }
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
            }

            SettingsPage.LIBRARY -> item {
                SettingsSection(
                    header = stringResource(R.string.settings_section_sources),
                    isTelevision = isTelevision,
                    focusableContent = false,
                    actions = {
                        SettingsActionRow {
                            // SAF folder browsing is unreliable on TV, so local import stays off there.
                            if (!isTelevision) {
                                ArchiveButton(
                                    label = stringResource(R.string.add_local_folder_ellipsis),
                                    iconRes = R.drawable.ic_folder_circle_plus,
                                    onClick = { gate.require(GatedAction.LIBRARY_EDIT) { folderPicker.launch(null) } },
                                )
                            }
                            ArchiveButton(
                                label = stringResource(R.string.link_network_source_ellipsis),
                                iconRes = R.drawable.ic_link,
                                isTelevision = isTelevision,
                                onClick = { gate.require(GatedAction.LIBRARY_EDIT) { showSmbDialog = true } },
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
                                isScanning = folder.treeUri in activity.scanningSources,
                                isTelevision = isTelevision,
                                onRescan = { gate.require(GatedAction.LIBRARY_EDIT) { library.rescanFolder(folder.treeUri) } },
                                onRemove = { gate.require(GatedAction.LIBRARY_EDIT) { pendingRemoval = folder } },
                                note = sourceNotes[folder.treeUri],
                                chooseMoreVideos = chooseMoreVideos,
                            )
                        }
                    }
                }
            }

            SettingsPage.SYNC -> {
                // 27.1: file-based sync on storage the viewer owns (FEATURES-27.1.md §5).
                item { com.babasama.edendale.android.sync.SyncSettingsSection(isTelevision = isTelevision, gate = gate) }
                item { com.babasama.edendale.android.sync.WatchHistorySection(isTelevision = isTelevision, gate = gate) }
            }

            SettingsPage.ACCOUNTS -> {
                item {
                    AccountsSettingsSection(folders = folders, isTelevision = isTelevision, chooseMoreVideos = chooseMoreVideos)
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
            }

            SettingsPage.ANDROID_TV -> item {
                AndroidTvSettingsSection()
            }

            SettingsPage.PARENTAL_CONTROLS -> item {
                // Section O: the rating ceilings, the switches, and the PIN (O-D12).
                ParentalControlsSection(gate = gate, isTelevision = isTelevision)
            }
        }
    }
}

/**
 * The first page's categories as one group: each row's glyph, name, and a
 * chevron, and on TV a gold fill under the remote, like the other rows.
 */
@Composable
private fun SettingsCategoryList(
    pages: List<SettingsPage>,
    rowFocus: Map<SettingsPage, FocusRequester>,
    onOpen: (SettingsPage) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EdendaleRadii.Group.dp),
        color = EdendaleColors.SurfaceLow,
    ) {
        Column {
            pages.forEachIndexed { index, page ->
                if (index > 0) {
                    // Inset to the names, past the glyphs, as the list's hairlines are.
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp + SETTINGS_ICON_SIZE + 16.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                SettingsNavigationRow(
                    title = stringResource(page.title),
                    iconRes = page.icon,
                    modifier = Modifier.focusRequester(rowFocus.getValue(page)),
                    onClick = { onOpen(page) },
                )
            }
        }
    }
}

private val SETTINGS_ICON_SIZE = 22.dp

/** A row that opens a page: an optional glyph, the page's name, and a chevron. */
@Composable
internal fun SettingsNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = if (focused) EdendaleColors.Gold else Color.Transparent,
        contentColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(SETTINGS_ICON_SIZE),
                    tint = if (focused) EdendaleColors.OnGold else EdendaleColors.TextSecondary,
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Settings → About, at the end of the first page as on iOS: the version,
 * TMDB's required notice, the bundled font's credit, and on handhelds the
 * link to TheIntroDB, whose timestamps the skip prompts use.
 */
@Composable
private fun AboutSettingsSection(versionName: String, isTelevision: Boolean) {
    val context = LocalContext.current
    SettingsSection(
        header = stringResource(R.string.settings_section_about),
        isTelevision = isTelevision,
        focusableContent = false,
    ) {
        FocusableRows(isTelevision) {
            LabeledRow(label = stringResource(R.string.settings_version), value = versionName)
            SettingsRowDivider()
            InfoRow(stringResource(R.string.tmdb_attribution))
            SettingsRowDivider()
            InfoRow(stringResource(R.string.settings_nunito_attribution))
        }
        // A TV has no browser to open it in.
        if (!isTelevision) {
            SettingsRowDivider()
            SettingsLinkRow(
                label = stringResource(R.string.settings_theintrodb_link),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://theintrodb.org")))
                    }
                },
            )
        }
    }
}

/** A link as a whole row of gold text, the way iOS lists one in a group. */
@Composable
private fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = EdendaleColors.Gold,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Settings → Android TV: Continue Watching on the home screen (I.3), off by default. */
@Composable
private fun AndroidTvSettingsSection() {
    val settings = (LocalContext.current.applicationContext as EdendaleApplication).watchNextSettings
    val watchNextEnabled by settings.changes.collectAsState(initial = settings.isEnabled)
    // Untitled: the page's own title names it.
    SettingsSection(
        header = null,
        isTelevision = true,
        focusableContent = false,
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_watch_next_title),
            detail = stringResource(R.string.settings_watch_next_description),
            checked = watchNextEnabled,
            onToggle = { settings.isEnabled = it },
        )
    }
}

/**
 * A setting with a title, a line of detail, and a switch. The whole row is one
 * focus target — it fills gold under the remote and the switch shows state — so
 * the toggle is reachable with the D-pad without a separately focusable control
 * nested inside.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // A toggleable row with the Switch role, so TalkBack says on or off; the
    // Switch inside only draws the state.
    Surface(
        checked = checked,
        onCheckedChange = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Switch },
        color = if (focused) EdendaleColors.Gold else Color.Transparent,
        contentColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = detail,
                    style = BodyCopyStyle(),
                    color = if (focused) EdendaleColors.OnGold
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null)
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
        header = stringResource(R.string.player_online_subtitles),
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
                // A key built into the app needs nothing from the viewer; their own key replaces it.
                if (!status.usesBuildKey) {
                    FocusableRows(isTelevision) {
                        InfoRow(stringResource(R.string.wyzie_key_required))
                    }
                    SettingsRowDivider()
                    SettingsActionRow {
                        ArchiveButton(
                            label = stringResource(R.string.wyzie_get_free_key),
                            kind = ArchiveButtonKind.Secondary,
                            isTelevision = isTelevision,
                            onClick = onOpenStore,
                        )
                    }
                    SettingsRowDivider()
                }
                // On TV the keyboard waits for Select, so walking Settings with the remote passes by.
                TvTextFieldGate(
                    isTelevision = isTelevision,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) { fieldModifier ->
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = onKeyInputChanged,
                        modifier = Modifier.fillMaxWidth().then(fieldModifier),
                        label = { Text(stringResource(R.string.wyzie_api_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                }
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
internal fun FocusableRows(
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
internal fun AccountProgressRow(label: String) {
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

/** Buttons side by side, wrapping onto another line rather than cutting a label short. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsActionRow(content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * A settings group. On TV the reading matter in [content] is one focus target,
 * so the remote can walk the list — and therefore scroll it — instead of
 * skipping between the few buttons and leaving whole sections unreachable.
 * [actions] renders *below* that target, outside it, so buttons keep their own
 * focus and stay clickable.
 */
@Composable
internal fun SettingsSection(
    /** Null for a page's only group, which the page title already names. */
    header: String?,
    isTelevision: Boolean = false,
    // Sections whose rows carry their own buttons focus each row instead, so the
    // buttons never end up nested inside a focus target.
    focusableContent: Boolean = isTelevision,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                modifier = Modifier.padding(bottom = 8.dp),
                style = LabelCapsStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
internal fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun LabeledRow(label: String, value: String) {
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
internal fun InfoRow(text: String) {
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

/**
 * A source in Settings → Sources. A Google Drive source also offers Choose
 * More Videos on handhelds, and on a TV says where to choose them (H.9.4).
 */
@Composable
private fun SourceRow(
    folder: LibraryFolderEntity,
    itemCount: Int,
    isScanning: Boolean,
    isTelevision: Boolean,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
    /** What the last Choose More Videos did, until the next scan. */
    note: String?,
    chooseMoreVideos: ChooseMoreVideosState,
) {
    val kind = SourceScanRules.kindOf(folder)
    val isRemote = kind?.isRemote == true
    val kindLabel = sourceKindLabel(kind)
    val statusMessage = if (isScanning) null else sourceStatusMessage(folder)
    val isGoogleDrive = kind == MediaSourceKind.GOOGLE_DRIVE
    val accountKey = folder.accountKey
    val choosing = accountKey != null && chooseMoreVideos.choosingFor == accountKey
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
                // The readable path a linked source records (H.11), else its address.
                text = (folder.displayPath ?: folder.treeUri).middleTruncated(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val status = if (choosing) stringResource(R.string.link_source_waiting, kindLabel) else statusMessage ?: note
            status?.let {
                Text(text = it, style = BodyCopyStyle(), color = EdendaleColors.Gold)
            }
            if (isGoogleDrive && isTelevision) {
                Text(
                    text = stringResource(R.string.gdrive_choose_on_another_device),
                    style = BodyCopyStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isGoogleDrive && !isTelevision && accountKey != null) {
                ArchiveButton(
                    label = stringResource(if (choosing) R.string.action_cancel else R.string.gdrive_choose_more),
                    iconRes = if (choosing) null else R.drawable.ic_plus,
                    enabled = choosing || chooseMoreVideos.choosingFor == null,
                    onClick = { if (choosing) chooseMoreVideos.cancel() else chooseMoreVideos.start(accountKey) },
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        ArchiveButton(
            // A failed source's rescan is its retry.
            label = stringResource(if (statusMessage != null) R.string.action_try_again else R.string.action_rescan),
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
    // Named by its endpoint (27.1): a server's address, never a cloud or S3 account key.
    val message = when (val endpoint = SourceEndpoint.of(SourceScanRules.kindOf(folder), folder.treeUri, folder.endpoint)) {
        is SourceEndpoint.Server -> stringResource(R.string.remove_source_message_smb, folder.displayName, endpoint.address)
        SourceEndpoint.ThisDevice -> stringResource(R.string.remove_source_message_local, folder.displayName)
        is SourceEndpoint.Account -> stringResource(R.string.remove_source_message_generic)
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

@Composable
private fun SkipPromptsSettingsSection(
    isTelevision: Boolean,
) {
    val context = LocalContext.current
    val playerPreferences = remember(context) { PlayerPreferences.from(context) }
    var isEnabled by remember { mutableStateOf(playerPreferences.segmentPromptsEnabled) }

    SettingsSection(
        header = stringResource(R.string.player_playback),
        isTelevision = isTelevision,
        focusableContent = false,
        // TheIntroDB's credit is in About; its privacy policy stays beside what it receives.
        actions = {
            SettingsActionRow {
                ArchiveButton(
                    label = stringResource(R.string.settings_theintrodb_privacy_link),
                    kind = ArchiveButtonKind.Secondary,
                    isTelevision = isTelevision,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://theintrodb.org/docs/privacy")))
                        }
                    },
                )
            }
        },
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        Surface(
            onClick = {
                val next = !isEnabled
                isEnabled = next
                playerPreferences.segmentPromptsEnabled = next
            },
            modifier = Modifier.fillMaxWidth(),
            color = if (focused) EdendaleColors.Gold else Color.Transparent,
            contentColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
            interactionSource = interactionSource,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_skip_prompts),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.player_skip_prompts_detail),
                        style = BodyCopyStyle(),
                        color = if (focused) EdendaleColors.OnGold
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = isEnabled, onCheckedChange = null)
            }
        }
        SettingsRowDivider()
        InfoRow(stringResource(R.string.settings_skip_prompts_note))
    }
}

@Composable
private fun AppControlsSettingsSection(
    isTelevision: Boolean,
) {
    val context = LocalContext.current
    val playerPreferences = remember(context) { PlayerPreferences.from(context) }
    var skipBackward by remember { mutableStateOf(playerPreferences.skipBackwardInterval) }
    var skipForward by remember { mutableStateOf(playerPreferences.skipForwardInterval) }
    var holdLeft by remember { mutableStateOf(playerPreferences.holdLeftRate) }
    var holdRight by remember { mutableStateOf(playerPreferences.holdRightRate) }

    SettingsSection(
        header = stringResource(R.string.settings_section_controls),
        isTelevision = isTelevision,
        focusableContent = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_app_controls_skip_backward),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SkipSegmentGroup(
                direction = SkipDirection.BACKWARD,
                selected = skipBackward,
                isTelevision = isTelevision,
                onSelect = {
                    skipBackward = it
                    playerPreferences.skipBackwardInterval = it
                },
            )
        }

        SettingsRowDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_app_controls_skip_forward),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SkipSegmentGroup(
                direction = SkipDirection.FORWARD,
                selected = skipForward,
                isTelevision = isTelevision,
                onSelect = {
                    skipForward = it
                    playerPreferences.skipForwardInterval = it
                },
            )
        }

        SettingsRowDivider()
        InfoRow(stringResource(R.string.settings_app_controls_skip_note))
        SettingsRowDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_app_controls_hold_left),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HoldSpeedStepper(
                title = stringResource(R.string.settings_app_controls_hold_left),
                rate = holdLeft,
                isTelevision = isTelevision,
                onRateChange = {
                    val normalized = PlayerPreferencesRules.normalizeHoldRate(it)
                    holdLeft = normalized
                    playerPreferences.holdLeftRate = normalized
                },
            )
        }

        SettingsRowDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_app_controls_hold_right),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HoldSpeedStepper(
                title = stringResource(R.string.settings_app_controls_hold_right),
                rate = holdRight,
                isTelevision = isTelevision,
                onRateChange = {
                    val normalized = PlayerPreferencesRules.normalizeHoldRate(it)
                    holdRight = normalized
                    playerPreferences.holdRightRate = normalized
                },
            )
        }

        SettingsRowDivider()
        InfoRow(stringResource(R.string.settings_app_controls_hold_note))
    }
}

@Composable
internal fun SkipSegmentGroup(
    direction: SkipDirection,
    selected: SkipInterval,
    isTelevision: Boolean,
    onSelect: (SkipInterval) -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = EdendaleColors.Surface,
    ) {
        Row(
            modifier = Modifier
                .padding(3.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipInterval.entries.forEach { interval ->
                val isSelected = interval == selected
                val iconRes = when (direction) {
                    SkipDirection.BACKWARD -> when (interval) {
                        SkipInterval.TEN -> R.drawable.ic_arrow_rotate_left_10
                        SkipInterval.FIFTEEN -> R.drawable.ic_arrow_rotate_left_15
                        SkipInterval.THIRTY -> R.drawable.ic_arrow_rotate_left_30
                    }
                    SkipDirection.FORWARD -> when (interval) {
                        SkipInterval.TEN -> R.drawable.ic_arrow_rotate_right_10
                        SkipInterval.FIFTEEN -> R.drawable.ic_arrow_rotate_right_15
                        SkipInterval.THIRTY -> R.drawable.ic_arrow_rotate_right_30
                    }
                }
                Surface(
                    onClick = { onSelect(interval) },
                    modifier = Modifier
                        .size(width = 48.dp, height = 36.dp)
                        .tvFocusLift(isTelevision, CircleShape)
                        // Radio semantics, so TalkBack says which length is chosen.
                        .semantics {
                            role = Role.RadioButton
                            // `this.`: the group's `selected` parameter shadows the property.
                            this.selected = isSelected
                        },
                    shape = CircleShape,
                    color = if (isSelected) EdendaleColors.Gold else Color.Transparent,
                    contentColor = if (isSelected) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = stringResource(R.string.settings_seconds_format, interval.seconds),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HoldSpeedStepper(
    title: String,
    rate: Float,
    isTelevision: Boolean,
    onRateChange: (Float) -> Unit,
) {
    // The glyphs alone read as "minus" and "plus"; name the row so the
    // two steppers can be told apart when the remote moves between them.
    val slower = stringResource(R.string.settings_app_controls_hold_slower, title)
    val faster = stringResource(R.string.settings_app_controls_hold_faster, title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArchiveIconButton(
            onClick = { onRateChange(rate - PlayerPreferencesRules.HOLD_RATE_STEP) },
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = slower },
            enabled = rate > PlayerPreferencesRules.HOLD_RATE_MIN,
            isTelevision = isTelevision,
        ) { focused ->
            Text(
                text = "−",
                style = MaterialTheme.typography.titleLarge,
                color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = PlayerLogic.rateLabel(rate),
            // Speaks the new speed after each − or + press.
            modifier = Modifier
                .widthIn(min = 64.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            color = EdendaleColors.Gold,
            textAlign = TextAlign.Center,
        )
        ArchiveIconButton(
            onClick = { onRateChange(rate + PlayerPreferencesRules.HOLD_RATE_STEP) },
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = faster },
            enabled = rate < PlayerPreferencesRules.HOLD_RATE_MAX,
            isTelevision = isTelevision,
        ) { focused ->
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private const val WYZIE_REDEEM_URL = "https://store.wyzie.io/redeem"

@Composable
internal fun LabelCapsStyle() = MaterialTheme.typography.labelLarge.copy(
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

@Composable
internal fun BodyCopyStyle() = MaterialTheme.typography.bodyMedium.copy(
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

internal fun String.middleTruncated(budget: Int = 56): String {
    if (length <= budget) return this
    val head = (budget - 1) / 2
    val tail = budget - 1 - head
    return take(head) + "…" + takeLast(tail)
}
