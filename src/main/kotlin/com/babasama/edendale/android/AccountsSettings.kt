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
import com.babasama.edendale.android.data.SmbCredentialsStore
import com.babasama.edendale.android.data.smbLoginUsage
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
    val scope = rememberCoroutineScope()
    var logins by remember { mutableStateOf<List<SavedSmbLogin>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pendingForget by remember { mutableStateOf<SavedSmbLogin?>(null) }

    LaunchedEffect(store, reload) {
        logins = withContext(Dispatchers.IO) { runCatching { store.savedLogins() }.getOrDefault(emptyList()) }
    }
    val usage = remember(logins, folders) { smbLoginUsage(logins.orEmpty(), folders.map { it.treeUri }) }

    SettingsSection(
        header = stringResource(R.string.settings_section_accounts),
        isTelevision = isTelevision,
        focusableContent = false,
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
                    sourceCount = usage[login] ?: 0,
                    isTelevision = isTelevision,
                    onForget = { pendingForget = login },
                )
            }
        }
        SettingsRowDivider()
        FocusableRows(isTelevision) {
            InfoRow(stringResource(R.string.accounts_note))
        }
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
                    text = stringResource(R.string.accounts_forget_message, login.host),
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
                            runCatching { store.removeCredentials(login.host) }
                            reload++
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun LoginRow(
    login: SavedSmbLogin,
    sourceCount: Int,
    isTelevision: Boolean,
    onForget: () -> Unit,
) {
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
                text = "${login.user} @ ${login.host}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.metadata_separator,
                    stringResource(R.string.source_kind_smb),
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
            label = stringResource(R.string.accounts_forget),
            isTelevision = isTelevision,
            onClick = onForget,
        )
    }
}
