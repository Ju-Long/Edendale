package com.babasama.edendale.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.data.LibraryRepository

/** The one repository instance the whole app shares. */
@Composable
fun rememberLibrary(): LibraryRepository {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as EdendaleApplication).libraryRepository
    }
}

/**
 * Add-source flow for SMB: sign in to the server, then walk its shares and
 * folders and import the one you actually want. Importing the whole server
 * dragged in every share on it, so the browse step is where the choice is made.
 *
 * [onImport] receives a full `smb://host/share/folder/` URL, which
 * `LibraryRepository.importSmbFolder` normalises like any other typed address.
 */
@Composable
fun SmbImportDialog(onDismiss: () -> Unit, onImport: (String, String, String) -> Unit) {
    val library = rememberLibrary()
    val scope = rememberCoroutineScope()
    val unreachableMessage = stringResource(R.string.error_server_unreachable)

    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    var browsing by remember { mutableStateOf(false) }
    // Path segments below the server, so the dialog can walk back up.
    var path by remember { mutableStateOf(emptyList<String>()) }
    var folders by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // What people type is an address, not a URL — "10.0.0.4", "10.0.0.4/media",
    // "\\10.0.0.4\media" all have to land on the same server and starting path.
    val typed = host.trim().replace('\\', '/').removePrefix("smb://").removePrefix("//").trim('/')
    val server = typed.substringBefore('/')
    val typedPath = typed.substringAfter('/', "").split('/').filter { it.isNotBlank() }
    fun urlFor(segments: List<String>) = "smb://" + (listOf(server) + segments).joinToString("/") + "/"

    fun open(segments: List<String>) {
        loading = true
        error = null
        scope.launch {
            library.listSmbDirectories(urlFor(segments), user, pass)
                .onSuccess {
                    folders = it
                    path = segments
                    browsing = true
                }
                .onFailure {
                    error = it.message ?: unreachableMessage
                    // A failure at the root leaves the credentials on screen to fix.
                    if (!browsing) browsing = false
                }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (browsing) R.string.smb_choose_folder else R.string.add_network_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (browsing) {
                    Text(
                        text = urlFor(path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        loading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.reading), style = MaterialTheme.typography.bodyMedium)
                        }

                        else -> LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (path.isNotEmpty()) {
                                item("up") {
                                    SmbFolderRow(
                                        label = stringResource(R.string.smb_parent_folder),
                                        iconRes = R.drawable.ic_chevron_left,
                                        onClick = { open(path.dropLast(1)) },
                                    )
                                }
                            }
                            items(folders.size, key = { folders[it] }) { index ->
                                val name = folders[index]
                                SmbFolderRow(
                                    label = name,
                                    iconRes = R.drawable.ic_folder_closed,
                                    onClick = { open(path + name) },
                                )
                            }
                            if (folders.isEmpty()) {
                                item("empty") {
                                    Text(
                                        text = stringResource(R.string.smb_no_folders),
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.smb_host_label)) },
                        placeholder = { Text(stringResource(R.string.smb_host_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text(stringResource(R.string.smb_username_label)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(stringResource(R.string.smb_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (browsing) {
                Button(
                    onClick = { onImport(urlFor(path), user, pass) },
                    enabled = !loading && path.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.smb_import_this_folder))
                }
            } else {
                Button(
                    onClick = { open(typedPath) },
                    enabled = server.isNotBlank() && !loading,
                ) {
                    Text(stringResource(if (loading) R.string.action_connecting else R.string.action_connect))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (browsing) {
                        browsing = false
                        error = null
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(stringResource(if (browsing) R.string.action_back else R.string.action_cancel))
            }
        }
    )
}

/** One tappable folder in the SMB browser; a Surface so the D-pad can focus it. */
@Composable
private fun SmbFolderRow(label: String, iconRes: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EdendaleRadii.Soft.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Scan failures used to live only in a StateFlow field nothing rendered, which
 * is why a share that could not be reached looked like it had been removed.
 */
@Composable
fun ScanErrorNotice(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        color = EdendaleColors.SurfaceLow,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_cloud_slash),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}
