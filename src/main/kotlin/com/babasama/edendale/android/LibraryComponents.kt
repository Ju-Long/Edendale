package com.babasama.edendale.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.data.LibraryRepository
import com.babasama.edendale.connectors.ConnectorEntry
import com.babasama.edendale.oauth.CloudAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.S3
import com.babasama.edendale.connectors.S3Configuration
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.connectors.WebDav
import com.babasama.edendale.remote.ServerLogin

/** The one repository instance the whole app shares. */
@Composable
fun rememberLibrary(): LibraryRepository {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as EdendaleApplication).libraryRepository
    }
}

/**
 * Link Source for servers (H.3): pick SMB or WebDAV, sign in, then walk the
 * server's folders and import the one you actually want. Importing a whole
 * server dragged in every share on it, so the browse step is where the
 * choice is made. Linking imports through the library and then calls
 * [onLinked].
 */
@Composable
fun LinkSourceDialog(
    onDismiss: () -> Unit,
    onLinked: () -> Unit,
    isTelevision: Boolean = false,
) {
    val library = rememberLibrary()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unreachableMessage = stringResource(R.string.error_server_unreachable)

    var kind by remember { mutableStateOf(MediaSourceKind.SMB) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    var browsing by remember { mutableStateOf(false) }
    // SMB: path segments below the server, so the dialog can walk back up.
    var path by remember { mutableStateOf(emptyList<String>()) }
    var folders by remember { mutableStateOf(emptyList<String>()) }
    // WebDAV and S3: the folder URLs from the root down.
    var davTrail by remember { mutableStateOf(emptyList<String>()) }
    var davFolders by remember { mutableStateOf(emptyList<ConnectorEntry>()) }
    // S3: the bucket's region and name (H.5); the address, user, and password
    // fields hold the endpoint and the key pair.
    var region by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    // Cloud accounts (H.11): the provider's linked accounts, the one being
    // browsed, and its folders from the root down as (URL, name).
    val cloud = remember(context) { (context.applicationContext as EdendaleApplication).cloudAccounts }
    var cloudAccounts by remember { mutableStateOf(emptyList<CloudAccount>()) }
    var cloudAccountKey by remember { mutableStateOf<String?>(null) }
    var cloudTrail by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var cloudFolders by remember { mutableStateOf(emptyList<ConnectorEntry>()) }
    var signIn by remember { mutableStateOf<Job?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind) {
        if (kind.isCloudAccount) cloudAccounts = withContext(Dispatchers.IO) { runCatching { cloud.vault.accounts(kind) }.getOrDefault(emptyList()) }
    }

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
                }
            loading = false
        }
    }

    fun s3Configuration(): S3Configuration? {
        val typedEndpoint = host.trim().trimEnd('/')
        if (typedEndpoint.isEmpty() || bucket.isBlank()) return null
        val endpoint = if ("://" in typedEndpoint) typedEndpoint else "https://$typedEndpoint"
        return S3Configuration(
            endpoint = endpoint,
            region = region.trim().ifEmpty { "us-east-1" },
            bucket = bucket.trim(),
            usesPathStyle = S3.defaultUsesPathStyle(endpoint, bucket.trim()),
        )
    }

    fun openDav(trail: List<String>) {
        loading = true
        error = null
        scope.launch {
            val listing = if (kind == MediaSourceKind.S3) {
                val configuration = s3Configuration()
                if (configuration == null) {
                    Result.failure(ConnectorException(ConnectorFailure.InvalidAddress))
                } else {
                    library.listS3Folders(configuration, ServerLogin(user.trim(), pass), trail.lastOrNull())
                        .map { (folder, entries) -> (if (trail.isEmpty()) listOf(folder) else trail) to entries }
                }
            } else {
                library.listWebDavFolders(trail.last(), user, pass).map { trail to it }
            }
            listing
                .onSuccess { (newTrail, entries) ->
                    davFolders = entries
                    davTrail = newTrail
                    browsing = true
                }
                .onFailure { error = connectorFailureMessage(context, it) ?: unreachableMessage }
            loading = false
        }
    }

    fun openCloud(account: CloudAccount, trail: List<Pair<String, String>>) {
        loading = true
        error = null
        scope.launch {
            library.listAccountFolders(kind, account.key, trail.lastOrNull()?.first)
                .onSuccess { (folder, entries) ->
                    // The root reads as the account, so the source's path names it.
                    cloudTrail = trail.ifEmpty { listOf(folder to account.label) }
                    cloudFolders = entries
                    cloudAccountKey = account.key
                    browsing = true
                }
                .onFailure { error = connectorFailureMessage(context, it) ?: unreachableMessage }
            loading = false
        }
    }

    fun signInToCloud() {
        val activity = context.findActivity() ?: return
        error = null
        signIn = scope.launch {
            try {
                val account = cloud.signIn(activity, kind)
                cloudAccounts = withContext(Dispatchers.IO) { cloud.vault.accounts(kind) }
                openCloud(account, emptyList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = connectorFailureMessage(context, failure) ?: unreachableMessage
            } finally {
                signIn = null
            }
        }
    }

    // Keyboard behavior (J.4): the address field has focus when the form
    // opens; Tab and Shift+Tab move between fields; Enter connects once the
    // address (the only required field) is filled, and otherwise focuses it.
    // User and password stay optional, so a guest connection is one tap.
    val focusManager = LocalFocusManager.current
    val hostFocus = remember { FocusRequester() }
    val bucketFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    // Whether this attempt already looked for a saved login.
    var reusedLogin by remember { mutableStateOf(false) }
    fun connect() {
        val isS3 = kind == MediaSourceKind.S3
        when {
            loading -> Unit
            host.isBlank() -> hostFocus.requestFocus()
            // S3 needs the bucket and the whole key pair; the servers take guests.
            isS3 && bucket.isBlank() -> bucketFocus.requestFocus()
            isS3 && user.isBlank() -> userFocus.requestFocus()
            isS3 && pass.isEmpty() -> passFocus.requestFocus()
            // A server whose login is saved connects with it when the fields are left empty (H.11).
            !isS3 && user.isBlank() && pass.isEmpty() && !reusedLogin -> scope.launch {
                reusedLogin = true
                library.savedLogin(kind, host)?.let { saved ->
                    user = saved.user
                    pass = saved.password
                }
                connect()
            }
            kind == MediaSourceKind.SMB -> open(typedPath)
            isS3 -> if (host.trim().startsWith("http://", ignoreCase = true)) {
                // Plain HTTP waits for D10.
                error = context.getString(R.string.connector_insecure_connection)
            } else {
                openDav(emptyList())
            }
            else -> {
                val root = WebDav.canonicalRoot(host)
                when {
                    root == null -> error = context.getString(R.string.connector_invalid_address)
                    // Plain HTTP waits for D10.
                    root.startsWith("dav://") -> error = context.getString(R.string.connector_insecure_connection)
                    else -> openDav(listOf(root))
                }
            }
        }
    }
    val formKeys = Modifier.onPreviewKeyEvent { event ->
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.Tab -> {
                if (down) focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                true
            }
            Key.Enter, Key.NumPadEnter -> {
                if (down) connect()
                true
            }
            else -> false
        }
    }
    if (!isTelevision) {
        LaunchedEffect(Unit) { hostFocus.requestFocus() }
    }

    // WebDAV and S3 browse through their connectors' URLs; cloud accounts through their trail.
    val isDav = kind == MediaSourceKind.WEBDAV || kind == MediaSourceKind.S3
    val isCloud = kind.isCloudAccount
    val cloudAccount = cloudAccounts.firstOrNull { it.key == cloudAccountKey }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (browsing) R.string.smb_choose_folder else R.string.add_network_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (browsing) {
                    Text(
                        text = when (kind) {
                            MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX, MediaSourceKind.GOOGLE_DRIVE ->
                                cloudTrail.joinToString(" › ") { it.second }
                            MediaSourceKind.S3 -> SourceUrl.parseS3(davTrail.last())
                                ?.let { listOf(it.bucket) + it.key.split('/').filter(String::isNotEmpty) }
                                ?.joinToString(" › ")
                                .orEmpty()
                            MediaSourceKind.WEBDAV -> WebDav.httpUrl(davTrail.last()).orEmpty()
                            else -> urlFor(path)
                        },
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
                            val canGoUp = when {
                                isCloud -> cloudTrail.size > 1
                                isDav -> davTrail.size > 1
                                else -> path.isNotEmpty()
                            }
                            if (canGoUp) {
                                item("up") {
                                    SmbFolderRow(
                                        label = stringResource(R.string.smb_parent_folder),
                                        iconRes = R.drawable.ic_chevron_left,
                                        onClick = {
                                            when {
                                                isCloud -> cloudAccount?.let { openCloud(it, cloudTrail.dropLast(1)) }
                                                isDav -> openDav(davTrail.dropLast(1))
                                                else -> open(path.dropLast(1))
                                            }
                                        },
                                    )
                                }
                            }
                            if (isCloud) {
                                items(cloudFolders.size, key = { cloudFolders[it].url }) { index ->
                                    val folder = cloudFolders[index]
                                    SmbFolderRow(
                                        label = folder.name,
                                        iconRes = R.drawable.ic_folder_closed,
                                        onClick = { cloudAccount?.let { openCloud(it, cloudTrail + (folder.url to folder.name)) } },
                                    )
                                }
                            } else if (isDav) {
                                items(davFolders.size, key = { davFolders[it].url }) { index ->
                                    val folder = davFolders[index]
                                    SmbFolderRow(
                                        label = folder.name,
                                        iconRes = R.drawable.ic_folder_closed,
                                        onClick = { openDav(davTrail + folder.url) },
                                    )
                                }
                            } else {
                                items(folders.size, key = { folders[it] }) { index ->
                                    val name = folders[index]
                                    SmbFolderRow(
                                        label = name,
                                        iconRes = R.drawable.ic_folder_closed,
                                        onClick = { open(path + name) },
                                    )
                                }
                            }
                            val empty = when {
                                isCloud -> cloudFolders.isEmpty()
                                isDav -> davFolders.isEmpty()
                                else -> folders.isEmpty()
                            }
                            if (empty) {
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
                    // The provider: servers on the network, then the cloud
                    // accounts this build can sign in to (H.11). TV signs in
                    // to OneDrive with a code (I.1); the browser flow is for handhelds.
                    val providers = listOf(MediaSourceKind.SMB, MediaSourceKind.WEBDAV, MediaSourceKind.S3) +
                        listOf(MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX, MediaSourceKind.GOOGLE_DRIVE)
                            .filter { !isTelevision && cloud.isOffered(it) }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        providers.forEach { option ->
                            ArchiveFilterChip(
                                selected = kind == option,
                                onClick = {
                                    signIn?.cancel()
                                    kind = option
                                    error = null
                                    reusedLogin = false
                                },
                                // "S3" fits the chip; the description below names the services.
                                label = { Text(if (option == MediaSourceKind.S3) "S3" else sourceKindLabel(option)) },
                                isTelevision = isTelevision,
                            )
                        }
                    }
                    Text(
                        text = stringResource(
                            when (kind) {
                                MediaSourceKind.WEBDAV -> R.string.link_source_webdav_description
                                MediaSourceKind.S3 -> R.string.link_source_s3_description
                                MediaSourceKind.ONE_DRIVE -> R.string.link_source_onedrive_description
                                MediaSourceKind.DROPBOX -> R.string.link_source_dropbox_description
                                else -> R.string.link_source_smb_description
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isCloud) {
                        CloudAccountStep(
                            kind = kind,
                            accounts = cloudAccounts,
                            signingIn = signIn != null,
                            loading = loading,
                            isTelevision = isTelevision,
                            onUse = { openCloud(it, emptyList()) },
                            onSignIn = ::signInToCloud,
                        )
                    }
                    // The attempt in flight captured these three values, so
                    // editing them mid-connect would leave the form describing
                    // something other than what is being tried. They unlock
                    // again when the attempt fails; success replaces them with
                    // the folder browser.
                    if (!isCloud) OutlinedTextField(
                        value = host,
                        onValueChange = {
                            host = it
                            reusedLogin = false
                        },
                        modifier = Modifier.focusRequester(hostFocus).then(formKeys),
                        enabled = !loading,
                        label = {
                            Text(stringResource(if (kind == MediaSourceKind.S3) R.string.s3_endpoint_label else R.string.smb_host_label))
                        },
                        placeholder = {
                            Text(
                                when (kind) {
                                    MediaSourceKind.WEBDAV -> WEBDAV_ADDRESS_EXAMPLE
                                    MediaSourceKind.S3 -> S3_ENDPOINT_EXAMPLE
                                    else -> stringResource(R.string.smb_host_placeholder)
                                },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                    )
                    if (kind == MediaSourceKind.S3) {
                        OutlinedTextField(
                            value = region,
                            onValueChange = { region = it },
                            modifier = formKeys,
                            enabled = !loading,
                            label = { Text(stringResource(R.string.s3_region_label)) },
                            placeholder = { Text("us-east-1") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = bucket,
                            onValueChange = { bucket = it },
                            modifier = Modifier.focusRequester(bucketFocus).then(formKeys),
                            enabled = !loading,
                            label = { Text(stringResource(R.string.s3_bucket_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        )
                    }
                    if (!isCloud) OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        modifier = Modifier.focusRequester(userFocus).then(formKeys),
                        enabled = !loading,
                        label = {
                            Text(stringResource(if (kind == MediaSourceKind.S3) R.string.s3_access_key_label else R.string.smb_username_label))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    if (!isCloud) OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        modifier = Modifier.focusRequester(passFocus).then(formKeys),
                        enabled = !loading,
                        label = {
                            Text(stringResource(if (kind == MediaSourceKind.S3) R.string.s3_secret_key_label else R.string.smb_password_label))
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { connect() }),
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
                ArchiveButton(
                    label = stringResource(R.string.smb_import_this_folder),
                    onClick = {
                        when (kind) {
                            MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX, MediaSourceKind.GOOGLE_DRIVE -> cloudAccountKey?.let { key ->
                                library.importAccountFolder(kind, key, cloudTrail.last().first, cloudTrail.map { it.second })
                            }
                            MediaSourceKind.WEBDAV -> library.importWebDavFolder(davTrail.last(), user, pass)
                            MediaSourceKind.S3 -> s3Configuration()?.let { configuration ->
                                library.importS3Folder(configuration, ServerLogin(user.trim(), pass), davTrail.last())
                            }
                            else -> library.importSmbFolder(urlFor(path), user, pass)
                        }
                        onLinked()
                    },
                    // An SMB server's top level lists shares, which are the smallest thing to import.
                    enabled = !loading && (isCloud || isDav || path.isNotEmpty()),
                    kind = ArchiveButtonKind.Primary,
                    isTelevision = isTelevision,
                )
            } else if (!isCloud) {
                ArchiveButton(
                    label = stringResource(
                        if (loading) R.string.action_connecting else R.string.action_connect,
                    ),
                    onClick = ::connect,
                    enabled = host.isNotBlank() && !loading,
                    kind = ArchiveButtonKind.Primary,
                    isTelevision = isTelevision,
                )
            }
        },
        dismissButton = {
            ArchiveButton(
                label = stringResource(
                    if (browsing) R.string.action_back else R.string.action_cancel,
                ),
                onClick = {
                    if (browsing) {
                        browsing = false
                        error = null
                    } else {
                        signIn?.cancel()
                        onDismiss()
                    }
                },
                isTelevision = isTelevision,
            )
        }
    )
}

/**
 * A cloud provider's step in Link Source (H.11, Apple's `CloudAccountStep`):
 * the accounts already linked, then signing in to one more. Sign-in opens a
 * Custom Tab and comes back here.
 */
@Composable
private fun CloudAccountStep(
    kind: MediaSourceKind,
    accounts: List<CloudAccount>,
    signingIn: Boolean,
    loading: Boolean,
    isTelevision: Boolean,
    onUse: (CloudAccount) -> Unit,
    onSignIn: () -> Unit,
) {
    val provider = sourceKindLabel(kind)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.link_source_cloud_privacy, provider),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accounts.isNotEmpty()) {
            Text(
                text = stringResource(R.string.link_source_linked_accounts).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            accounts.forEach { account ->
                SmbFolderRow(
                    label = account.label,
                    iconRes = R.drawable.ic_circle_user_fill,
                    onClick = { if (!loading && !signingIn) onUse(account) },
                )
            }
        }
        when {
            signingIn -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.link_source_waiting, provider), style = MaterialTheme.typography.bodyMedium)
            }
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.reading), style = MaterialTheme.typography.bodyMedium)
            }
            else -> ArchiveButton(
                label = if (accounts.isEmpty()) {
                    stringResource(R.string.link_source_sign_in_to, provider)
                } else {
                    stringResource(R.string.link_source_another_account)
                },
                onClick = onSignIn,
                kind = if (accounts.isEmpty()) ArchiveButtonKind.Primary else ArchiveButtonKind.Secondary,
                iconRes = R.drawable.ic_link,
                isTelevision = isTelevision,
            )
        }
    }
}

/** The activity a Compose context belongs to, for starting a Custom Tab. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** An address shape for Nextcloud and ownCloud; other servers have their own paths. */
private const val WEBDAV_ADDRESS_EXAMPLE = "https://cloud.example.com/remote.php/dav/files/me/"

/** AWS's endpoint shape; R2, B2, Wasabi, and MinIO have their own. */
private const val S3_ENDPOINT_EXAMPLE = "https://s3.us-east-1.amazonaws.com"

/** One tappable folder in the SMB browser; a Surface so the D-pad can focus it. */
@Composable
private fun SmbFolderRow(label: String, iconRes: Int, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // No focus ring here: the row is already its own focus target, and adding a
    // focusable wrapper would make the D-pad stop twice on every folder.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EdendaleRadii.Soft.dp),
        // A transparent row gives focus nothing to change, and the browser is a
        // list of near-identical folder names — the fill is what says which one
        // the remote is on.
        color = if (focused) EdendaleColors.Gold else Color.Transparent,
        contentColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
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
    isTelevision: Boolean = false,
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
            ArchiveButton(
                label = stringResource(R.string.action_dismiss),
                onClick = onDismiss,
                isTelevision = isTelevision,
            )
        }
    }
}
