package com.babasama.edendale.android.handoff

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.ArchiveButton
import com.babasama.edendale.android.ArchiveButtonKind
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.R
import com.babasama.edendale.android.connectorFailureMessage
import com.babasama.edendale.android.rememberLibrary
import com.babasama.edendale.android.sourceKindLabel
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.handoff.HandoffCrypto
import com.babasama.edendale.handoff.HandoffProtocol
import com.babasama.edendale.oauth.CloudAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** What the viewer reads when a handoff fails (I.2, Apple's `HandoffError` messages). */
internal fun handoffFailureMessage(context: Context, error: Throwable): String = when (error) {
    is HandoffException.UnsupportedVersion -> context.getString(R.string.handoff_unsupported_version)
    HandoffException.MalformedMessage, HandoffException.MessageTooLarge, HandoffException.WrongKind -> context.getString(R.string.handoff_malformed)
    HandoffException.Declined -> context.getString(R.string.handoff_declined)
    HandoffException.WrongCode -> context.getString(R.string.handoff_wrong_code)
    HandoffException.TimedOut -> context.getString(R.string.handoff_timed_out)
    HandoffException.ConnectionFailed -> context.getString(R.string.handoff_connection_failed)
    is HandoffException.Rejected -> error.reason?.takeIf { it.isNotBlank() }
        ?.let { context.getString(R.string.handoff_rejected, it) }
        ?: context.getString(R.string.handoff_rejected_plain)
    else -> connectorFailureMessage(context, error) ?: context.getString(R.string.handoff_connection_failed)
}

/**
 * The TV's side of the handoff on screen (I.2.2): where to go on the phone,
 * the code to type there, and the wait. Shown in Link Source after Continue
 * on a Phone.
 */
@Composable
internal fun HandoffCodeBlock(
    state: HandoffHost.State,
    isTelevision: Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.handoff_tv_instructions, state.deviceName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val spoken = stringResource(R.string.device_code_accessibility, state.code.toList().joinToString(" "))
        Text(
            text = state.code.chunked(3).joinToString(" "),
            modifier = Modifier.semantics { contentDescription = spoken },
            style = MaterialTheme.typography.displaySmall,
            color = EdendaleColors.Gold,
        )
        val status = state.status
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status !is HandoffHost.Status.Failed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = when (status) {
                    HandoffHost.Status.Waiting -> stringResource(R.string.handoff_tv_waiting)
                    HandoffHost.Status.Connected -> stringResource(R.string.handoff_tv_connected)
                    HandoffHost.Status.Validating -> stringResource(R.string.handoff_tv_validating)
                    is HandoffHost.Status.Done -> stringResource(R.string.reading)
                    // The last phone's attempt failed; the code (new after three wrong ones) still waits.
                    is HandoffHost.Status.Failed -> handoffFailureMessage(context, status.error) + "\n" + stringResource(R.string.handoff_tv_waiting)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (status is HandoffHost.Status.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ArchiveButton(label = stringResource(R.string.action_cancel), onClick = onCancel, isTelevision = isTelevision)
    }
}

/** The phone side's steps (I.2.2). */
private sealed interface Step {
    data object Discover : Step
    data class Code(val television: DiscoveredTelevision) : Step
    data class Connecting(val television: DiscoveredTelevision) : Step
    data class Confirm(val television: DiscoveredTelevision, val request: AccountHandoff.Request) : Step
    data class Sending(val television: DiscoveredTelevision) : Step
    data class Done(val television: DiscoveredTelevision) : Step
    data class Failed(val television: DiscoveredTelevision?, val message: String) : Step
}

/**
 * Settings → Accounts → Link to a TV (I.2.2, Apple's `AccountHandoffRequestView`
 * turned around): the TVs found on this network, the code the TV shows, then
 * "Link Google Drive on Living Room?" with an account already linked here,
 * a fresh sign-in, or a saved login. Nothing is sent until the viewer picks
 * one, and the TV's answer closes the exchange.
 */
@Composable
fun HandoffToTelevisionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val library = rememberLibrary()
    val cloud = remember(context) { (context.applicationContext as EdendaleApplication).cloudAccounts }
    val scope = rememberCoroutineScope()
    val discovery = remember(context) { HandoffDiscovery(context) }
    val televisions by discovery.televisions.collectAsState()
    val discoveryFailed by discovery.failed.collectAsState()
    val stepFlow = remember { MutableStateFlow<Step>(Step.Discover) }
    val step by stepFlow.collectAsState()
    var code by remember { mutableStateOf("") }
    var exchange by remember { mutableStateOf<Job?>(null) }
    // The viewer's answer, awaited by the protocol thread while the dialog asks.
    var pending by remember { mutableStateOf<CompletableDeferred<AccountHandoff.Response>?>(null) }
    var accounts by remember { mutableStateOf(emptyList<CloudAccount>()) }
    var logins by remember { mutableStateOf(emptyList<AccountHandoff.Login>()) }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(discovery) {
        discovery.start()
        onDispose {
            discovery.stop()
            exchange?.cancel()
            pending?.complete(AccountHandoff.Response.DECLINED)
        }
    }

    fun connect(television: DiscoveredTelevision) {
        val typed = code.filter(Char::isDigit)
        if (typed.length != HandoffCrypto.CODE_LENGTH) return
        stepFlow.value = Step.Connecting(television)
        error = null
        exchange = scope.launch(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(television.host, television.port), CONNECT_TIMEOUT_MILLIS)
                    HandoffProtocol.send(socket, typed) { request ->
                        val answer = CompletableDeferred<AccountHandoff.Response>()
                        pending = answer
                        stepFlow.value = Step.Confirm(television, request)
                        runBlocking { answer.await() }.also { stepFlow.value = Step.Sending(television) }
                    }
                }
                stepFlow.value = if (pending?.isCompleted == true && pending?.getCompleted()?.status == AccountHandoff.Status.DECLINED) {
                    Step.Discover
                } else {
                    Step.Done(television)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                stepFlow.value = Step.Failed(television, handoffFailureMessage(context, failure))
            } finally {
                pending = null
            }
        }
    }

    // What this device can offer for the TV's request.
    val confirm = step as? Step.Confirm
    LaunchedEffect(confirm?.request) {
        val request = confirm?.request ?: return@LaunchedEffect
        if (request.kind.isCloudAccount) {
            accounts = withContext(Dispatchers.IO) { runCatching { cloud.vault.accounts(request.kind) }.getOrDefault(emptyList()) }
        } else {
            logins = runCatching { library.handedOffLogins(request.kind) }.getOrDefault(emptyList())
        }
    }

    fun answer(response: AccountHandoff.Response) {
        pending?.complete(response)
    }

    fun signInAndSend(kind: MediaSourceKind) {
        val activity = context.findActivity() ?: return
        signingIn = true
        error = null
        scope.launch {
            try {
                val account = cloud.signIn(activity, kind)
                answer(AccountHandoff.Response(AccountHandoff.Status.APPROVED, account = AccountHandoff.Account(account)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = connectorFailureMessage(context, failure) ?: failure.message
            } finally {
                signingIn = false
            }
        }
    }

    val current = step
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (current) {
                    is Step.Confirm -> stringResource(R.string.handoff_request_title, sourceKindLabel(current.request.kind), current.television.name)
                    else -> stringResource(R.string.handoff_link_to_tv)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (current) {
                    Step.Discover -> {
                        if (televisions.isEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!discoveryFailed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = stringResource(if (discoveryFailed) R.string.handoff_discovery_failed else R.string.handoff_searching),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = stringResource(R.string.handoff_no_tvs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            televisions.forEach { television ->
                                HandoffRow(label = television.name, iconRes = R.drawable.ic_tv) {
                                    code = ""
                                    stepFlow.value = Step.Code(television)
                                }
                            }
                        }
                    }
                    is Step.Code -> {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(HandoffCrypto.CODE_LENGTH) },
                            label = { Text(stringResource(R.string.handoff_code_label, current.television.name)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { connect(current.television) }),
                        )
                        Text(
                            text = stringResource(R.string.handoff_request_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is Step.Connecting -> Waiting(stringResource(R.string.handoff_connecting, current.television.name))
                    is Step.Confirm -> {
                        val kind = current.request.kind
                        val provider = sourceKindLabel(kind)
                        Text(
                            text = stringResource(R.string.handoff_request_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            signingIn -> Waiting(stringResource(R.string.link_source_waiting, provider))
                            kind.isCloudAccount -> {
                                if (accounts.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.link_source_linked_accounts).uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    accounts.forEach { account ->
                                        HandoffRow(label = account.label, iconRes = R.drawable.ic_circle_user_fill) {
                                            answer(AccountHandoff.Response(AccountHandoff.Status.APPROVED, account = AccountHandoff.Account(account)))
                                        }
                                    }
                                }
                                ArchiveButton(
                                    label = stringResource(if (accounts.isEmpty()) R.string.link_source_sign_in_to else R.string.link_source_another_account, provider),
                                    onClick = { signInAndSend(kind) },
                                    kind = if (accounts.isEmpty()) ArchiveButtonKind.Primary else ArchiveButtonKind.Secondary,
                                    iconRes = R.drawable.ic_link,
                                )
                            }
                            logins.isEmpty() -> Text(
                                text = stringResource(R.string.handoff_no_saved_logins, provider),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> {
                                Text(
                                    text = stringResource(R.string.handoff_saved_logins).uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    items(logins.size) { index ->
                                        val login = logins[index]
                                        val address = login.s3?.let { "${it.bucket} @ ${it.endpoint.substringAfter("://").substringBefore('/')}" }
                                            ?: (login.host + (login.port?.let { ":$it" }.orEmpty()))
                                        HandoffRow(label = if (login.s3 != null) address else "${login.user} @ $address", iconRes = R.drawable.ic_folder_tree) {
                                            answer(AccountHandoff.Response(AccountHandoff.Status.APPROVED, login = login))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is Step.Sending -> Waiting(stringResource(R.string.handoff_sending, current.television.name))
                    is Step.Done -> Text(
                        text = stringResource(R.string.handoff_done, current.television.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    is Step.Failed -> Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { message ->
                    Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (current) {
                is Step.Code -> ArchiveButton(
                    label = stringResource(R.string.action_connect),
                    onClick = { connect(current.television) },
                    enabled = code.length == HandoffCrypto.CODE_LENGTH,
                    kind = ArchiveButtonKind.Primary,
                )
                is Step.Confirm -> ArchiveButton(
                    label = stringResource(R.string.handoff_decline),
                    onClick = { answer(AccountHandoff.Response.DECLINED) },
                    enabled = !signingIn,
                )
                is Step.Failed -> ArchiveButton(
                    label = stringResource(R.string.action_try_again),
                    onClick = { stepFlow.value = current.television?.let { Step.Code(it) } ?: Step.Discover },
                    kind = ArchiveButtonKind.Primary,
                )
                is Step.Done -> ArchiveButton(label = stringResource(R.string.action_close), onClick = onDismiss, kind = ArchiveButtonKind.Primary)
                else -> Unit
            }
        },
        dismissButton = {
            if (current !is Step.Done) {
                ArchiveButton(
                    label = stringResource(if (current is Step.Code || current is Step.Failed) R.string.action_back else R.string.action_cancel),
                    onClick = {
                        when (current) {
                            is Step.Code, is Step.Failed -> stepFlow.value = Step.Discover
                            else -> {
                                exchange?.cancel()
                                pending?.complete(AccountHandoff.Response.DECLINED)
                                onDismiss()
                            }
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun Waiting(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One tappable row: a TV found, a linked account, or a saved login. */
@Composable
private fun HandoffRow(label: String, iconRes: Int, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(com.babasama.edendale.android.EdendaleRadii.Soft.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(label, style = MaterialTheme.typography.bodyLarge)
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

/** Connecting to a TV the discovery just resolved on the same network. */
private const val CONNECT_TIMEOUT_MILLIS = 10_000
