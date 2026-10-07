package com.babasama.edendale.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.babasama.edendale.android.handoff.HandoffCodeBlock
import com.babasama.edendale.android.handoff.HandoffHost
import com.babasama.edendale.android.handoff.handoffFailureMessage
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.oauth.CloudProviders
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.babasama.edendale.connectors.ConnectorEntry
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.DeviceAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.S3
import com.babasama.edendale.connectors.S3Configuration
import com.babasama.edendale.connectors.Sftp
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.connectors.SshHostKey
import com.babasama.edendale.android.data.sftpAddress
import com.babasama.edendale.connectors.WebDav
import com.babasama.edendale.remote.ServerLogin
import com.babasama.edendale.remote.TlsCertificate
import java.text.DateFormat
import java.util.Date

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
    // SFTP (H.4): the port, the server being browsed, and a host key waiting for approval.
    var port by remember { mutableStateOf("") }
    var sftpServer by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var hostKeyReview by remember { mutableStateOf<HostKeyReview?>(null) }
    // WebDAV and S3 (D10): a server certificate the device doesn't trust, waiting for approval.
    val tlsPins = remember(context) { (context.applicationContext as EdendaleApplication).tlsPins }
    var certificateReview by remember { mutableStateOf<CertificateReview?>(null) }
    // Cloud accounts (H.11): the provider's linked accounts, the one being
    // browsed, and its folders from the root down as (URL, name).
    val cloud = remember(context) { (context.applicationContext as EdendaleApplication).cloudAccounts }
    var cloudAccounts by remember { mutableStateOf(emptyList<CloudAccount>()) }
    var cloudAccountKey by remember { mutableStateOf<String?>(null) }
    var cloudTrail by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var cloudFolders by remember { mutableStateOf(emptyList<ConnectorEntry>()) }
    // Whether the cloud folder being browsed can be linked (Drive's root only gathers others, H.9).
    var cloudCanIndex by remember { mutableStateOf(true) }
    var signIn by remember { mutableStateOf<Job?>(null) }
    // A TV sign-in's code while it waits for approval (I.1).
    var deviceAuthorization by remember { mutableStateOf<DeviceAuthorization?>(null) }
    // Phone-to-TV handoff (I.2): this TV advertising itself while the viewer continues on a phone.
    var handoffHost by remember { mutableStateOf<HandoffHost?>(null) }
    DisposableEffect(Unit) { onDispose { handoffHost?.stop() } }
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
            val listing = if (kind == MediaSourceKind.SFTP) {
                val server = sftpServer
                if (server == null) {
                    Result.failure(ConnectorException(ConnectorFailure.InvalidAddress))
                } else {
                    library.listSftpFolders(server.first, server.second, ServerLogin(user.trim(), pass), trail.lastOrNull())
                        .map { (folder, entries) -> (if (trail.isEmpty()) listOf(folder) else trail) to entries }
                }
            } else if (kind == MediaSourceKind.S3) {
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
                .onFailure { failure ->
                    // A certificate the device doesn't trust is reviewed, not reported (D10).
                    when (val reason = (failure as? ConnectorException)?.failure) {
                        is ConnectorFailure.CertificateUntrusted ->
                            certificateReview = CertificateReview(reason.host, reason.port, reason.certificate, replacesPinned = false, retry = trail)
                        is ConnectorFailure.CertificateMismatch ->
                            certificateReview = CertificateReview(reason.host, reason.port, reason.certificate, replacesPinned = true, retry = trail)
                        else -> error = connectorFailureMessage(context, failure) ?: unreachableMessage
                    }
                }
            loading = false
        }
    }

    fun trustCertificate(review: CertificateReview) {
        certificateReview = null
        scope.launch {
            withContext(Dispatchers.IO) { tlsPins.pin(review.host, review.port, review.certificate.fingerprint) }
            openDav(review.retry)
        }
    }

    fun stopHandoff() {
        handoffHost?.stop()
        handoffHost = null
    }

    /**
     * TV (I.2): advertise this TV and show a code; the phone's account is
     * validated and stored here, a login tested and saved, and either way
     * the phone hears the outcome.
     */
    fun startHandoff() {
        stopHandoff()
        error = null
        val host = HandoffHost(context, kind) { response ->
            response.account?.let { account ->
                runCatching { cloud.adoptHandedOffAccount(account.cloudAccount) }.fold(
                    { AccountHandoff.Result(stored = true) },
                    { AccountHandoff.Result(stored = false, message = handoffFailureMessage(context, it)) },
                )
            } ?: response.login?.let { library.adoptHandedOffLogin(it) }
                ?: AccountHandoff.Result(stored = false)
        }
        handoffHost = host
        host.start(scope)
    }

    fun openCloud(account: CloudAccount, trail: List<Pair<String, String>>) {
        loading = true
        error = null
        scope.launch {
            library.listAccountFolders(kind, account.key, trail.lastOrNull()?.first)
                .onSuccess { listing ->
                    // The root reads as the account, so the source's path names it.
                    cloudTrail = trail.ifEmpty { listOf(listing.folder to account.label) }
                    cloudFolders = listing.entries
                    cloudCanIndex = listing.canIndex
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
                val account = if (isTelevision) {
                    // A remote can't drive a browser: approve on a phone or computer instead.
                    val authorization = cloud.startDeviceSignIn(kind)
                    deviceAuthorization = authorization
                    cloud.finishDeviceSignIn(kind, authorization)
                } else {
                    cloud.signIn(activity, kind)
                }
                cloudAccounts = withContext(Dispatchers.IO) { cloud.vault.accounts(kind) }
                openCloud(account, emptyList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = connectorFailureMessage(context, failure) ?: unreachableMessage
            } finally {
                signIn = null
                deviceAuthorization = null
            }
        }
    }

    /**
     * SFTP (H.4): fetch the server's key first; a key that matches the one
     * pinned goes straight on, anything else waits for the viewer to approve it.
     */
    fun connectSftp() {
        val (typedHost, typedPort, _) = sftpAddress(host) ?: run {
            error = context.getString(R.string.connector_invalid_address)
            return
        }
        val serverPort = port.trim().toIntOrNull() ?: typedPort
        loading = true
        error = null
        scope.launch {
            library.fetchSftpHostKey(typedHost, serverPort)
                .onSuccess { key ->
                    sftpServer = typedHost to serverPort
                    val pinned = withContext(Dispatchers.IO) { library.sshHostKeys.pinned(typedHost, serverPort) }
                    if (pinned == key.fingerprint) {
                        loading = false
                        openDav(emptyList())
                        return@launch
                    }
                    hostKeyReview = HostKeyReview(typedHost, serverPort, key, replacesPinnedKey = pinned != null)
                }
                .onFailure { error = connectorFailureMessage(context, it) ?: unreachableMessage }
            loading = false
        }
    }

    fun trustHostKey(review: HostKeyReview) {
        hostKeyReview = null
        scope.launch {
            withContext(Dispatchers.IO) { library.sshHostKeys.pin(review.host, review.port, review.key.fingerprint) }
            openDav(emptyList())
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
            // SFTP logs in with a password; there's no guest.
            kind == MediaSourceKind.SFTP && user.isBlank() && reusedLogin -> userFocus.requestFocus()
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
            kind == MediaSourceKind.SFTP -> connectSftp()
            // Plain http:// reaches private-network addresses only; the transport enforces it (D10).
            isS3 -> openDav(emptyList())
            else -> {
                val root = WebDav.canonicalRoot(host)
                if (root == null) error = context.getString(R.string.connector_invalid_address) else openDav(listOf(root))
            }
        }
    }
    // A finished handoff (I.2): an account opens its folder browser; a login fills the form and connects.
    val handoffState = handoffHost?.state?.collectAsState()?.value
    LaunchedEffect(handoffState?.status) {
        val done = handoffState?.status as? HandoffHost.Status.Done ?: return@LaunchedEffect
        stopHandoff()
        done.response.account?.let { account ->
            cloudAccounts = withContext(Dispatchers.IO) { runCatching { cloud.vault.accounts(kind) }.getOrDefault(emptyList()) }
            openCloud(account.cloudAccount, emptyList())
        }
        done.response.login?.let { login ->
            val first = login.addresses.firstOrNull()
            when (login.kind) {
                MediaSourceKind.SMB -> host = first?.removePrefix("smb://")?.trimEnd('/') ?: login.host
                MediaSourceKind.SFTP -> {
                    host = login.host
                    port = login.port?.toString().orEmpty()
                }
                MediaSourceKind.S3 -> login.s3?.let { configuration ->
                    host = configuration.endpoint
                    region = configuration.region
                    bucket = configuration.bucket
                }
                else -> host = first?.let(WebDav::httpUrl) ?: "https://${login.host}${login.port?.let { ":$it" }.orEmpty()}/"
            }
            user = login.user
            pass = login.password
            reusedLogin = true
            connect()
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
    val isDav = kind == MediaSourceKind.WEBDAV || kind == MediaSourceKind.S3 || kind == MediaSourceKind.SFTP
    val isCloud = kind.isCloudAccount
    val cloudAccount = cloudAccounts.firstOrNull { it.key == cloudAccountKey }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (browsing) R.string.smb_choose_folder else R.string.add_network_source)) },
        text = {
            // The form scrolls when a TV's sign-in code and QR code don't fit; the
            // folder browser has its own bounded list.
            Column(
                modifier = if (browsing) Modifier else Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                            MediaSourceKind.SFTP -> (listOfNotNull(sftpServer?.first) + SourceUrl.pathSegments(davTrail.last())).joinToString(" › ")
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
                    // with a code, which only OneDrive offers (I.1).
                    val providers = listOf(MediaSourceKind.SMB, MediaSourceKind.WEBDAV, MediaSourceKind.SFTP, MediaSourceKind.S3) +
                        listOf(MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX, MediaSourceKind.GOOGLE_DRIVE)
                            .filter { if (isTelevision) cloud.isOfferedOnTelevision(it) else cloud.isOffered(it) }
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
                                    hostKeyReview = null
                                    certificateReview = null
                                    stopHandoff()
                                },
                                // "S3" fits the chip; the description below names the services.
                                label = { Text(if (option == MediaSourceKind.S3) "S3" else sourceKindLabel(option)) },
                                isTelevision = isTelevision,
                            )
                        }
                    }
                    handoffState?.let { state ->
                        HandoffCodeBlock(state = state, isTelevision = isTelevision, onCancel = ::stopHandoff)
                    }
                    if (handoffHost == null) {
                        Text(
                            text = stringResource(
                                when (kind) {
                                    MediaSourceKind.WEBDAV -> R.string.link_source_webdav_description
                                    MediaSourceKind.SFTP -> R.string.link_source_sftp_description
                                    MediaSourceKind.S3 -> R.string.link_source_s3_description
                                    MediaSourceKind.ONE_DRIVE -> R.string.link_source_onedrive_description
                                    MediaSourceKind.DROPBOX -> R.string.link_source_dropbox_description
                                    MediaSourceKind.GOOGLE_DRIVE -> R.string.link_source_gdrive_description
                                    else -> R.string.link_source_smb_description
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (kind == MediaSourceKind.SFTP) {
                            Text(
                                text = stringResource(R.string.sftp_host_key_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (kind == MediaSourceKind.WEBDAV || kind == MediaSourceKind.S3) {
                            Text(
                                text = stringResource(R.string.link_source_tls_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        hostKeyReview?.let { review ->
                            HostKeyReviewBlock(
                                review = review,
                                isTelevision = isTelevision,
                                onTrust = { trustHostKey(review) },
                                onCancel = { hostKeyReview = null },
                            )
                        }
                        certificateReview?.let { review ->
                            CertificateReviewBlock(
                                review = review,
                                isTelevision = isTelevision,
                                onTrust = { trustCertificate(review) },
                                onCancel = { certificateReview = null },
                            )
                        }
                        if (isCloud) {
                            CloudAccountStep(
                                kind = kind,
                                accounts = cloudAccounts,
                                signingIn = signIn != null,
                                deviceAuthorization = deviceAuthorization,
                                onCancelSignIn = { signIn?.cancel() },
                                loading = loading,
                                isTelevision = isTelevision,
                                onUse = { openCloud(it, emptyList()) },
                                onSignIn = ::signInToCloud,
                                onContinueOnPhone = if (isTelevision) ::startHandoff else null,
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
                        if (kind == MediaSourceKind.SFTP) {
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                                modifier = formKeys,
                                enabled = !loading,
                                label = { Text(stringResource(R.string.sftp_port_label)) },
                                placeholder = { Text(Sftp.DEFAULT_PORT.toString()) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            )
                        }
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
                        // TV (I.2): a phone can hand over a login it has saved.
                        if (isTelevision && !isCloud) {
                            ArchiveButton(
                                label = stringResource(R.string.handoff_continue_on_phone),
                                onClick = ::startHandoff,
                                enabled = !loading,
                                kind = ArchiveButtonKind.Secondary,
                                iconRes = R.drawable.ic_tv,
                                isTelevision = isTelevision,
                            )
                        }
                    }
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
                            MediaSourceKind.SFTP -> library.importSftpFolder(davTrail.last(), ServerLogin(user.trim(), pass))
                            MediaSourceKind.S3 -> s3Configuration()?.let { configuration ->
                                library.importS3Folder(configuration, ServerLogin(user.trim(), pass), davTrail.last())
                            }
                            else -> library.importSmbFolder(urlFor(path), user, pass)
                        }
                        onLinked()
                    },
                    // An SMB server's top level lists shares, which are the smallest thing to import;
                    // Drive's root and its list of shared drives would import all of Drive.
                    enabled = !loading && ((isCloud && cloudCanIndex) || isDav || path.isNotEmpty()),
                    kind = ArchiveButtonKind.Primary,
                    isTelevision = isTelevision,
                )
            } else if (!isCloud && hostKeyReview == null && certificateReview == null && handoffHost == null) {
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
    deviceAuthorization: DeviceAuthorization?,
    onCancelSignIn: () -> Unit,
    loading: Boolean,
    isTelevision: Boolean,
    onUse: (CloudAccount) -> Unit,
    onSignIn: () -> Unit,
    /** TV (I.2): hand the account over from a phone; null on handhelds. */
    onContinueOnPhone: (() -> Unit)? = null,
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
            deviceAuthorization != null -> DeviceCodeSignIn(kind, deviceAuthorization, isTelevision, onCancelSignIn)
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
            else -> {
                // A TV signs in with a code only where the provider grants Edendale's scopes that way (OneDrive, I.1).
                if (!isTelevision || CloudProviders.supportsDeviceCode(kind)) {
                    ArchiveButton(
                        label = when {
                            isTelevision -> stringResource(R.string.device_code_sign_in)
                            accounts.isEmpty() -> stringResource(R.string.link_source_sign_in_to, provider)
                            else -> stringResource(R.string.link_source_another_account)
                        },
                        onClick = onSignIn,
                        kind = if (accounts.isEmpty()) ArchiveButtonKind.Primary else ArchiveButtonKind.Secondary,
                        iconRes = R.drawable.ic_link,
                        isTelevision = isTelevision,
                    )
                }
                onContinueOnPhone?.let { continueOnPhone ->
                    ArchiveButton(
                        label = stringResource(R.string.handoff_continue_on_phone),
                        onClick = continueOnPhone,
                        kind = if (accounts.isEmpty() && !CloudProviders.supportsDeviceCode(kind)) ArchiveButtonKind.Primary else ArchiveButtonKind.Secondary,
                        iconRes = R.drawable.ic_tv,
                        isTelevision = isTelevision,
                    )
                }
            }
        }
    }
}

/**
 * A TV sign-in with a code (I.1, Apple's device-code view): where to go on a
 * phone or computer, the code, a QR code for the address (Microsoft has no
 * `verification_uri_complete`), and the wait.
 */
@Composable
private fun DeviceCodeSignIn(
    kind: MediaSourceKind,
    authorization: DeviceAuthorization,
    isTelevision: Boolean,
    onCancel: () -> Unit,
) {
    val provider = sourceKindLabel(kind)
    val address = authorization.verificationUri.substringAfter("://")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.device_code_instructions, address.substringBefore('/'), address.removePrefix(address.substringBefore('/'))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val spoken = stringResource(R.string.device_code_accessibility, authorization.userCode.toList().joinToString(" "))
        Text(
            text = authorization.userCode,
            modifier = Modifier.semantics { contentDescription = spoken },
            style = MaterialTheme.typography.displaySmall,
            color = EdendaleColors.Gold,
        )
        ApprovalQrCode(
            url = authorization.verificationUriComplete ?: authorization.verificationUri,
            contentDescription = stringResource(R.string.device_code_qr_description, provider),
            isTelevision = isTelevision,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.device_code_waiting), style = MaterialTheme.typography.bodyMedium)
        }
        ArchiveButton(label = stringResource(R.string.action_cancel), onClick = onCancel, isTelevision = isTelevision)
    }
}

/** An SSH server's key waiting for the viewer's approval (H.4). */
private class HostKeyReview(val host: String, val port: Int, val key: SshHostKey, val replacesPinnedKey: Boolean) {
    /** The key type and its fingerprint, as `ssh-keygen -l` shows them. */
    val displayedKey: String get() = "${key.type}\n${key.fingerprint}"
}

/**
 * Trust on first use (H.4, Apple's host-key alert): the key the server
 * presents, to compare with the server's own, and Trust; a key that differs
 * from the approved one says so.
 */
@Composable
private fun HostKeyReviewBlock(
    review: HostKeyReview,
    isTelevision: Boolean,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (review.replacesPinnedKey) R.string.sftp_changed_title else R.string.sftp_verify_title),
            style = MaterialTheme.typography.titleMedium,
            color = if (review.replacesPinnedKey) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (review.replacesPinnedKey) {
                stringResource(R.string.sftp_changed_message, review.host, review.displayedKey)
            } else {
                stringResource(R.string.sftp_verify_message, review.displayedKey)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArchiveButton(
                label = stringResource(if (review.replacesPinnedKey) R.string.sftp_trust_new else R.string.sftp_trust),
                onClick = onTrust,
                kind = ArchiveButtonKind.Primary,
                isTelevision = isTelevision,
            )
            ArchiveButton(label = stringResource(R.string.action_cancel), onClick = onCancel, isTelevision = isTelevision)
        }
    }
}

/** A server certificate waiting for the viewer's approval (D10), and the listing to retry once it's trusted. */
private class CertificateReview(
    val host: String,
    val port: Int,
    val certificate: TlsCertificate,
    val replacesPinned: Boolean,
    val retry: List<String>,
) {
    /** `host:port` unless the port is HTTPS's default. */
    val address: String get() = if (port == 443) host else "$host:$port"
}

/**
 * Trust on first use for HTTPS (D10, the TLS twin of [HostKeyReviewBlock]):
 * the certificate's subject, fingerprint, and expiry, to compare with the
 * server's own, and Trust; a certificate that differs from the approved one
 * says so.
 */
@Composable
private fun CertificateReviewBlock(
    review: CertificateReview,
    isTelevision: Boolean,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    val expires = remember(review) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(review.certificate.notAfterEpochMillis)) }
    val displayed = "${review.certificate.subject}\n${review.certificate.fingerprint}\n" + stringResource(R.string.tls_expires, expires)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (review.replacesPinned) R.string.tls_changed_title else R.string.tls_verify_title),
            style = MaterialTheme.typography.titleMedium,
            color = if (review.replacesPinned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                if (review.replacesPinned) R.string.tls_changed_message else R.string.tls_verify_message,
                review.address,
                displayed,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArchiveButton(
                label = stringResource(if (review.replacesPinned) R.string.tls_trust_new else R.string.sftp_trust),
                onClick = onTrust,
                kind = ArchiveButtonKind.Primary,
                isTelevision = isTelevision,
            )
            ArchiveButton(label = stringResource(R.string.action_cancel), onClick = onCancel, isTelevision = isTelevision)
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
