package com.babasama.edendale.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.SavedSmbLogin
import com.babasama.edendale.android.data.SavedServerLogin
import com.babasama.edendale.android.data.ServerLoginStore
import com.babasama.edendale.android.data.SmbCredentialsStore
import com.babasama.edendale.android.data.serverLoginUsage
import com.babasama.edendale.android.data.SourceScanRules
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudProviders
import com.babasama.edendale.android.data.smbLoginUsage
import com.babasama.edendale.android.handoff.HandoffToTelevisionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Accounts (D.4): every saved login, with how many linked sources
 * use it. This is the only place a login is removed — removing a source keeps
 * it — and its sources then show "needs sign-in" at their next scan. Logins are
 * device-local and excluded from backup (D8). Section H adds cloud accounts.
 */
@Composable
internal fun AccountsSettingsSection(
    folders: List<LibraryFolderEntity>,
    isTelevision: Boolean,
) {
    val context = LocalContext.current
    val store = remember(context) { SmbCredentialsStore(context) }
    val serverStore = remember(context) { ServerLoginStore(context) }
    val scope = rememberCoroutineScope()
    var smbLogins by remember { mutableStateOf<List<SavedSmbLogin>?>(null) }
    var serverLogins by remember { mutableStateOf<List<SavedServerLogin>?>(null) }
    val cloud = remember(context) { (context.applicationContext as EdendaleApplication).cloudAccounts }
    var cloudAccounts by remember { mutableStateOf<List<CloudAccount>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pendingForget by remember { mutableStateOf<SavedLoginRow?>(null) }
    var pendingSignOut by remember { mutableStateOf<CloudAccount?>(null) }
    // Phone-to-TV handoff (I.2): send a linked account or saved login to a TV on this network.
    var linkingToTelevision by remember { mutableStateOf(false) }

    LaunchedEffect(store, reload) {
        smbLogins = withContext(Dispatchers.IO) { runCatching { store.savedLogins() }.getOrDefault(emptyList()) }
        serverLogins = withContext(Dispatchers.IO) { runCatching { serverStore.all() }.getOrDefault(emptyList()) }
        cloudAccounts = withContext(Dispatchers.IO) { runCatching { cloud.vault.all() }.getOrDefault(emptyList()) }
    }
    // Cloud accounts (H.6) first, then SMB logins, then other servers' (H.3) by kind and host.
    val logins = remember(smbLogins, serverLogins, cloudAccounts, folders) {
        val smb = smbLogins ?: return@remember null
        val servers = serverLogins ?: return@remember null
        val accounts = cloudAccounts ?: return@remember null
        val smbUsage = smbLoginUsage(smb, folders.map { it.treeUri })
        val serverUsage = serverLoginUsage(servers, folders)
        accounts.map { account ->
            val sources = folders.count { SourceScanRules.kindOf(it) == account.kind && it.accountKey == account.key }
            SavedLoginRow(account.kind, account.label, account.label, sources, detail = account.label, cloudAccount = account) {}
        } + smb.map { login ->
            SavedLoginRow(MediaSourceKind.SMB, login.user, login.host, smbUsage[login] ?: 0) { store.removeCredentials(login.host) }
        } + servers.map { login ->
            SavedLoginRow(login.kind, login.user, login.address, serverUsage[login] ?: 0, login.detail) {
                withContext(Dispatchers.IO) { serverStore.remove(login.kind, login.host, login.port) }
            }
        }
    }

    SettingsSection(
        header = stringResource(R.string.settings_section_accounts),
        isTelevision = isTelevision,
        focusableContent = false,
        // A TV receives handoffs in Link Source; only handhelds send them.
        actions = if (isTelevision) {
            null
        } else {
            {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArchiveButton(
                        label = stringResource(R.string.handoff_link_to_tv),
                        onClick = { linkingToTelevision = true },
                        iconRes = R.drawable.ic_tv,
                    )
                    Text(
                        text = stringResource(R.string.handoff_link_to_tv_note),
                        modifier = Modifier.weight(1f),
                        style = BodyCopyStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) {
        val current = logins
        when {
            // The encrypted store answers in a moment; nothing to say meanwhile.
            current == null -> Unit
            current.isEmpty() -> FocusableRows(isTelevision) {
                InfoRow(stringResource(R.string.accounts_empty))
            }
            else -> current.forEachIndexed { index, login ->
                if (index > 0) SettingsRowDivider()
                LoginRow(
                    login = login,
                    isTelevision = isTelevision,
                    onForget = {
                        val account = login.cloudAccount
                        if (account != null) pendingSignOut = account else pendingForget = login
                    },
                )
            }
        }
        SettingsRowDivider()
        FocusableRows(isTelevision) {
            InfoRow(stringResource(R.string.accounts_note))
        }
    }

    if (linkingToTelevision) {
        HandoffToTelevisionDialog(onDismiss = { linkingToTelevision = false })
    }

    pendingSignOut?.let { account ->
        val label = account.label
        AlertDialog(
            onDismissRequest = { pendingSignOut = null },
            shape = RoundedCornerShape(EdendaleRadii.Card.dp),
            containerColor = EdendaleColors.Surface,
            title = {
                Text(
                    text = stringResource(R.string.accounts_sign_out),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.accounts_sign_out_message, label),
                    style = BodyCopyStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                ArchiveButton(
                    label = stringResource(R.string.action_cancel),
                    isTelevision = isTelevision,
                    onClick = { pendingSignOut = null },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveButton(
                        label = stringResource(R.string.accounts_sign_out),
                        kind = ArchiveButtonKind.Secondary,
                        isTelevision = isTelevision,
                        onClick = {
                            pendingSignOut = null
                            scope.launch {
                                runCatching { cloud.signOut(account, revoke = false) }
                                reload++
                            }
                        },
                    )
                    // Google and Dropbox let Edendale end its own access; Microsoft's is removed on the account page.
                    if (CloudProviders.supportsRevocation(account.kind)) {
                        ArchiveButton(
                            label = stringResource(R.string.accounts_sign_out_revoke),
                            kind = ArchiveButtonKind.Secondary,
                            isTelevision = isTelevision,
                            onClick = {
                                pendingSignOut = null
                                scope.launch {
                                    runCatching { cloud.signOut(account, revoke = true) }
                                    reload++
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    pendingForget?.let { login ->
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            shape = RoundedCornerShape(EdendaleRadii.Card.dp),
            containerColor = EdendaleColors.Surface,
            title = {
                Text(
                    text = stringResource(R.string.accounts_forget_login),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.accounts_forget_message, login.detail ?: login.address),
                    style = BodyCopyStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                ArchiveButton(
                    label = stringResource(R.string.action_cancel),
                    isTelevision = isTelevision,
                    onClick = { pendingForget = null },
                )
            },
            confirmButton = {
                ArchiveButton(
                    label = stringResource(R.string.accounts_forget),
                    kind = ArchiveButtonKind.Secondary,
                    isTelevision = isTelevision,
                    onClick = {
                        pendingForget = null
                        scope.launch {
                            runCatching { login.forget() }
                            reload++
                        }
                    },
                )
            },
        )
    }
}

/** One saved server login: SMB, or another server kind (H.3). The password never leaves its store. */
private class SavedLoginRow(
    val kind: MediaSourceKind,
    val user: String,
    /** The host, with its port when one was typed. */
    val address: String,
    val sourceCount: Int,
    /** Replaces `user @ address` where that would show a key rather than a name (S3), or an account's email. */
    val detail: String? = null,
    /** A linked cloud account (H.6): signed out rather than forgotten. */
    val cloudAccount: CloudAccount? = null,
    val forget: suspend () -> Unit,
) {
    val title: String get() = detail ?: "$user @ $address"
}

@Composable
private fun LoginRow(
    login: SavedLoginRow,
    isTelevision: Boolean,
    onForget: () -> Unit,
) {
    val sourceCount = login.sourceCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .tvFocusableBlock(isTelevision),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = login.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.metadata_separator,
                    sourceKindLabel(login.kind),
                    if (sourceCount == 0) {
                        stringResource(R.string.accounts_no_sources)
                    } else {
                        pluralStringResource(R.plurals.accounts_source_count, sourceCount, sourceCount)
                    },
                ),
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        ArchiveButton(
            label = stringResource(if (login.cloudAccount != null) R.string.accounts_sign_out else R.string.accounts_forget),
            isTelevision = isTelevision,
            onClick = onForget,
        )
    }
}
