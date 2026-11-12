package com.babasama.edendale.android.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import com.babasama.edendale.android.EdendaleRadii
import com.babasama.edendale.android.R
import com.babasama.edendale.android.handoff.DiscoveredTelevision
import com.babasama.edendale.android.handoff.HandoffDiscovery
import com.babasama.edendale.android.handoff.PairingGuest
import com.babasama.edendale.android.handoff.PairingHost
import com.babasama.edendale.android.handoff.QrCodeScanner
import com.babasama.edendale.android.sourceKindLabel
import com.babasama.edendale.android.tvFocusLift
import com.babasama.edendale.android.xr.XrTheaterRules
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.handoff.HandoffProtocolV2
import com.babasama.edendale.handoff.HandoffV2
import com.babasama.edendale.handoff.PairingFlow
import com.babasama.edendale.sync.SyncProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress

/** What the sender ticks before pairing (§9.6 step 2): the location, and each other account and login. */
private data class OfferChoice(
    val location: HandoffV2.LocationPayload?,
    val accounts: List<AccountHandoff.Account>,
    val logins: List<AccountHandoff.Login>,
    val sendLocation: Boolean = location != null,
    val tickedAccounts: Set<Int> = emptySet(),
    val tickedLogins: Set<Int> = emptySet(),
) {
    val canContinue: Boolean get() = PairingFlow.canContinue(location != null, sendLocation, tickedAccounts.size, tickedLogins.size)

    /** The offer for a receiver on [receiverPlatform], after the Google rule (§9.8). */
    fun offer(senderPlatform: String, receiverPlatform: String): HandoffV2.Offer = HandoffV2.offerFor(
        if (sendLocation) location else null,
        accounts.filterIndexed { index, _ -> index in tickedAccounts },
        logins.filterIndexed { index, _ -> index in tickedLogins },
        senderPlatform,
        receiverPlatform,
    )
}

/** The guest's steps before the channel is up. */
private enum class GuestStep { CONNECTING, PAIRING }

/**
 * Setting up another device, or receiving from one, over the local network
 * (SYNC-PROTOCOL.md §9), built as `apple-27.1`'s `SyncHandoffView`. The
 * button chose the direction ([role]); the sender first ticks what to send
 * (its sync location, other linked accounts, saved logins). Then a phone or
 * tablet scans the other device's QR code or shows one, and a TV or a
 * headset shows one ([PairingFlow.modes]). From the scanner, or under a code
 * shown by a device that can't scan, Enter the Other Device's Code joins
 * instead: pick the device found on the network (or the only one) and type
 * the six digits it shows (Section T). The sender confirms what goes to
 * the named device before anything is sent; the receiver stores what
 * validates, and both show the result. A code scanned by another app
 * arrives as [scannedLink] with no direction yet, so the dialog asks for one
 * first, as Apple's "Pair with …?" does.
 */
@Composable
internal fun SyncHandoffDialog(
    isTelevision: Boolean,
    role: HandoffV2.Role?,
    onDismiss: () -> Unit,
    scannedLink: String? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as EdendaleApplication
    val sync = app.sync
    val handoff = remember(app) { SyncHandoff(app) }
    val scope = rememberCoroutineScope()
    val platform = sync.platform
    val deviceName = remember { sync.deviceName() }
    val modes = remember(isTelevision) { PairingFlow.modes(isTelevision, isHeadset(context), hasCamera(context)) }
    val linked = remember(scannedLink) { scannedLink?.let(PairingFlow::scanned) }

    var chosenRole by remember { mutableStateOf(role) }
    var choosing by remember { mutableStateOf(role == HandoffV2.Role.SENDER) }
    var choice by remember { mutableStateOf<OfferChoice?>(null) }
    var mode by remember { mutableStateOf(if (linked != null) PairingFlow.Mode.SCAN else null) }
    var scanned by remember { mutableStateOf(linked) }
    // Entering the other device's code (Section T): the screen it was opened from, the host picked, the digits.
    var enteredFrom by remember { mutableStateOf<PairingFlow.Mode?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(PairingFlow.CodeAttempts()) }
    var locked by remember { mutableStateOf(false) }
    // The port this device's own code was shown on, so its advertisement isn't listed while it fades.
    var ownPort by remember { mutableStateOf<Int?>(null) }

    var host by remember { mutableStateOf<PairingHost?>(null) }
    val hostState = host?.state?.collectAsState()?.value
    val discovery = remember(context) { HandoffDiscovery(context) }
    val found by discovery.televisions.collectAsState()
    val searchFailed by discovery.failed.collectAsState()
    val foundHosts = found.map { PairingFlow.Host(it.name, it.platform, it.version, it.port) }
    val codeNames = PairingFlow.codeHosts(foundHosts, ownName = deviceName.take(PairingHost.ADVERTISED_NAME_LENGTH), ownPort = ownPort).map { it.name }
    val codeDevices = codeNames.mapNotNull { name -> found.firstOrNull { it.name == name } }
    val codeTarget = PairingFlow.codeHost(codeNames, picked)?.let { name -> codeDevices.firstOrNull { it.name == name } }
    var guestJob by remember { mutableStateOf<Job?>(null) }
    var guestStep by remember { mutableStateOf<GuestStep?>(null) }

    var peer by remember { mutableStateOf<HandoffV2.RoleMessage?>(null) }
    // The sender's approval, awaited by the protocol thread.
    var pendingOffer by remember { mutableStateOf<CompletableDeferred<HandoffV2.Offer?>?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sentLocation by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<HandoffProtocolV2.Outcome?>(null) }
    var declinedBy by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var followUp by remember { mutableStateOf<SyncHandoff.FollowUp?>(null) }

    fun participant(myRole: HandoffV2.Role) = HandoffProtocolV2.Participant(
        myRole, deviceName, platform,
        offer = {
            val deferred = CompletableDeferred<HandoffV2.Offer?>()
            pendingOffer = deferred
            runBlocking { deferred.await() }
        },
        onPeer = { peer = it },
        receive = { incoming, _ ->
            val received = runBlocking { handoff.receive(incoming) }
            followUp = received.followUp
            received.result
        },
    )

    fun startHosting(myRole: HandoffV2.Role) {
        if (host != null) return
        val created = PairingHost(
            context, myRole, platform, deviceName,
            offer = { other -> participant(myRole).offer(other) },
            receive = { incoming, other -> participant(myRole).receive(incoming, other) },
            onPeer = { peer = it },
        )
        host = created
        created.start(scope)
    }

    /** Connects to the host the code names: at its address when the code has one, else found by its name (§9.1). */
    fun startGuest(payload: HandoffV2.QrPayload, myRole: HandoffV2.Role) {
        if (guestJob != null) return
        failure = null
        guestStep = GuestStep.CONNECTING
        discovery.start()
        guestJob = scope.launch(Dispatchers.IO) {
            var connected = false
            val onConnected = {
                connected = true
                guestStep = GuestStep.PAIRING
            }
            try {
                val pairing = HandoffProtocolV2.GuestPairing.Qr(payload)
                val direct = payload.host?.let { address -> runCatching { InetAddress.getByName(address) }.getOrNull() }
                val port = payload.port
                var result: HandoffProtocolV2.Outcome? = null
                if (direct != null && port != null) {
                    try {
                        result = PairingGuest.run(direct, port, pairing, participant(myRole), onConnected)
                    } catch (unreachable: IOException) {
                        // Only a connection that never opened looks the host up by name; a spent code stays spent.
                        if (connected) throw unreachable
                    }
                }
                outcome = result ?: run {
                    val found = withTimeoutOrNull(DISCOVERY_TIMEOUT_MILLIS) {
                        discovery.televisions.first { list -> PairingFlow.target(list.filter { it.speaksVersion2 }.map { it.name }, payload.name) != null }
                    } ?: throw HandoffException.TimedOut
                    val v2 = found.filter { it.speaksVersion2 }
                    val name = PairingFlow.target(v2.map { it.name }, payload.name)
                    val device = v2.first { it.name == name }
                    PairingGuest.run(device.host, device.port, pairing, participant(myRole), onConnected)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error === HandoffException.Declined) declinedBy = peer?.name ?: payload.name else failure = pairingFailureMessage(context, error)
            } finally {
                pendingOffer = null
                guestStep = null
            }
        }
    }

    /** Connects to the host picked from those found and pairs by the six digits it shows (J-PAKE, §9.4). */
    fun startCodeGuest(device: DiscoveredTelevision, code: String, myRole: HandoffV2.Role) {
        if (guestJob?.isActive == true) return
        failure = null
        codeError = null
        picked = device.name
        guestStep = GuestStep.CONNECTING
        guestJob = scope.launch(Dispatchers.IO) {
            try {
                outcome = PairingGuest.run(device.host, device.port, HandoffProtocolV2.GuestPairing.Code(code), participant(myRole)) {
                    guestStep = GuestStep.PAIRING
                }
                attempts = PairingFlow.CodeAttempts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                typed = ""
                when {
                    error === HandoffException.Declined -> declinedBy = peer?.name ?: device.name
                    // One attempt per code: the host shows a new one, unless five wrong codes in a row make it wait a minute.
                    error === HandoffException.WrongCode -> {
                        attempts = attempts.failed(System.currentTimeMillis())
                        codeError = context.getString(R.string.pairing_wrong_code, device.name)
                    }
                    // The host had no code for this attempt: it expired, was replaced, or the host is waiting after wrong codes.
                    error === HandoffException.WrongSecret -> codeError = context.getString(R.string.pairing_code_expired, device.name)
                    else -> failure = pairingFailureMessage(context, error)
                }
            } finally {
                pendingOffer = null
                guestStep = null
            }
        }
    }

    fun stopPairing() {
        guestJob?.cancel()
        guestJob = null
        guestStep = null
        host?.stop()
        host = null
        pendingOffer?.complete(null)
        pendingOffer = null
        peer = null
        sending = false
    }

    /** From the scanner, or from this device's own code: join by the other device's code instead. */
    fun enterCode() {
        ownPort = hostState?.qrUri?.let(HandoffV2.QrPayload::parse)?.port
        stopPairing()
        scanned = null
        enteredFrom = mode
        picked = null
        typed = ""
        codeError = null
        mode = PairingFlow.Mode.ENTER
    }

    /** Back from the code: to the list where one was picked from several, else to the screen it was opened from. */
    fun leaveEntry() {
        typed = ""
        codeError = null
        if (picked != null && codeDevices.size > 1) {
            picked = null
            return
        }
        picked = null
        mode = enteredFrom
        enteredFrom = null
    }

    fun submitCode(myRole: HandoffV2.Role) {
        val device = codeTarget ?: return
        if (!PairingFlow.isWholeCode(typed) || locked) return
        startCodeGuest(device, typed, myRole)
    }

    fun retry() {
        stopPairing()
        outcome = null
        declinedBy = null
        failure = null
        followUp = null
        sentLocation = false
        scanned = null
        typed = ""
        codeError = null
        val myRole = chosenRole ?: return
        when {
            mode == PairingFlow.Mode.SHOW -> startHosting(myRole)
            mode == PairingFlow.Mode.SCAN && PairingFlow.Mode.SCAN !in modes -> mode = null
        }
    }

    LaunchedEffect(chosenRole) {
        if (chosenRole == HandoffV2.Role.SENDER && choice == null) {
            val location = handoff.locationPayload()
            // The location's own account and login travel with it, so they aren't listed twice.
            val accounts = handoff.otherAccounts().filter { it.subject != location?.account?.subject }
            val logins = handoff.otherLogins().filter { it.host != location?.login?.host || it.kind != location.login?.kind }
            choice = OfferChoice(location, accounts, logins)
        }
    }

    // Past the direction and the ticks: a device with one way to pair takes it, a scanned link connects, a shown code starts.
    LaunchedEffect(chosenRole, choosing, mode) {
        val myRole = chosenRole ?: return@LaunchedEffect
        if (choosing) return@LaunchedEffect
        if (mode == null && modes.size == 1) mode = modes.single()
        when (mode) {
            PairingFlow.Mode.SHOW -> startHosting(myRole)
            PairingFlow.Mode.SCAN -> {
                discovery.start()
                scanned?.let { startGuest(it, myRole) }
            }
            PairingFlow.Mode.ENTER -> discovery.start()
            null -> Unit
        }
    }

    LaunchedEffect(hostState?.status) {
        when (val status = hostState?.status) {
            is PairingHost.Status.Done -> outcome = HandoffProtocolV2.Outcome(status.peer, status.result)
            is PairingHost.Status.Failed -> if (status.error === HandoffException.Declined) {
                declinedBy = peer?.name.orEmpty() // known: the roles come before the offer
                host?.stop()
            } else {
                // A new code is up; whoever connected starts again.
                peer = null
                pendingOffer = null
                sending = false
            }
            else -> Unit
        }
    }

    // A picked host that left the network: back to the list (or the only one left), not to a host the code wasn't read from.
    LaunchedEffect(codeNames, picked, guestStep) {
        if (picked != null && picked !in codeNames && guestStep == null) {
            picked = null
            typed = ""
        }
    }

    // After five wrong codes in a row the field waits a minute, as the host does (§9.4).
    LaunchedEffect(attempts) {
        val wait = attempts.lockedUntil - System.currentTimeMillis()
        locked = wait > 0
        if (wait > 0) {
            delay(wait)
            locked = false
            codeError = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            discovery.stop()
            host?.stop()
            guestJob?.cancel()
            pendingOffer?.complete(null)
        }
    }

    val myRole = chosenRole
    val other = peer
    val offerWaiting = pendingOffer
    val done = outcome
    val hostStatus = hostState?.status
    val inProgress = other != null || guestStep != null || hostStatus is PairingHost.Status.Connected
    // A link that isn't a version-2 handoff code (or a mangled one) goes no further.
    val badLink = scannedLink != null && linked == null
    val canGoBack = !inProgress && done == null && declinedBy == null && failure == null && when (mode) {
        PairingFlow.Mode.ENTER -> enteredFrom != null
        PairingFlow.Mode.SCAN, PairingFlow.Mode.SHOW -> modes.size > 1 && linked == null
        null -> false
    }
    // Enter the Other Device's Code, from the scanner or from a code shown by a device that can't scan.
    val offersEntry = myRole != null && !choosing && !inProgress && done == null && declinedBy == null && failure == null &&
        PairingFlow.offersEntry(mode, modes) && scanned == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (myRole) {
                    null -> if (badLink) stringResource(R.string.sync_receive_from_device) else stringResource(R.string.pairing_link_title, linked?.name.orEmpty())
                    HandoffV2.Role.SENDER -> stringResource(R.string.sync_set_up_device)
                    HandoffV2.Role.RECEIVER -> stringResource(R.string.sync_receive_from_device)
                },
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    badLink -> Statement(stringResource(R.string.pairing_wrong_secret))
                    myRole == null -> {
                        Note(stringResource(R.string.pairing_link_message))
                        ChoiceRow(label = stringResource(R.string.pairing_role_send), selected = false, iconRes = R.drawable.ic_forward) {
                            chosenRole = HandoffV2.Role.SENDER
                            choosing = true
                        }
                        ChoiceRow(label = stringResource(R.string.pairing_role_receive), selected = false, iconRes = R.drawable.ic_backward) {
                            chosenRole = HandoffV2.Role.RECEIVER
                        }
                    }
                    choosing -> when (val current = choice) {
                        null -> Waiting(stringResource(R.string.reading))
                        else -> OfferChoices(current, context, onChange = { choice = it })
                    }
                    mode == null -> {
                        ChoiceRow(label = stringResource(R.string.pairing_mode_scan), selected = false, iconRes = R.drawable.ic_magnifying_glass_play) { mode = PairingFlow.Mode.SCAN }
                        ChoiceRow(label = stringResource(R.string.pairing_mode_show), selected = false, iconRes = R.drawable.ic_link) { mode = PairingFlow.Mode.SHOW }
                        Note(stringResource(R.string.pairing_mode_footer, otherDeviceAction(context, myRole)))
                    }
                    done != null && done.result != null -> ResultView(myRole, done.peer, done.result, sentLocation, followUp, context)
                    done != null -> Statement(stringResource(R.string.pairing_nothing_sent, done.peer.name))
                    declinedBy != null -> Statement(stringResource(R.string.pairing_nothing_received, declinedBy!!))
                    failure != null -> Statement(failure!!)
                    offerWaiting != null && other != null -> Approval(choice?.offer(platform, other.platform) ?: HandoffV2.Offer(approved = true), other, context)
                    sending && other != null -> Waiting(stringResource(R.string.pairing_sending, other.name))
                    other != null -> Waiting(
                        if (myRole == HandoffV2.Role.RECEIVER) {
                            stringResource(R.string.pairing_waiting_for_send, other.name)
                        } else {
                            stringResource(R.string.pairing_connected, other.name)
                        },
                    )
                    mode == PairingFlow.Mode.SHOW -> when {
                        hostStatus is PairingHost.Status.Connected -> Waiting(stringResource(R.string.pairing_pairing))
                        hostStatus is PairingHost.Status.LockedOut -> Statement(stringResource(R.string.pairing_locked_out))
                        hostState?.qrUri == null -> Waiting(stringResource(R.string.pairing_starting))
                        else -> {
                            ApprovalQrCode(hostState.qrUri, stringResource(R.string.pairing_qr_description), isTelevision, Modifier.align(Alignment.CenterHorizontally))
                            Note(stringResource(R.string.pairing_show_instructions, otherDeviceAction(context, myRole)))
                            // FEATURES-27.1 §6.2: a PC has no camera, so it pairs by the typed code (J-PAKE, §9.4); so can
                            // an Android device or a Quest that can't or won't scan (Section T). Apple's devices can't type it.
                            hostState.code?.let { code -> Note(stringResource(R.string.pairing_pc_code, code.chunked(3).joinToString(" "))) }
                            (hostStatus as? PairingHost.Status.Failed)?.let {
                                Text(pairingFailureMessage(context, it.error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    guestStep == GuestStep.CONNECTING -> Waiting(stringResource(R.string.pairing_connecting))
                    guestStep == GuestStep.PAIRING -> Waiting(stringResource(R.string.pairing_pairing))
                    mode == PairingFlow.Mode.ENTER -> CodeEntry(
                        isTelevision = isTelevision,
                        devices = codeDevices,
                        target = codeTarget,
                        searchFailed = searchFailed,
                        onlyQrHosts = PairingFlow.onlyQrHosts(foundHosts),
                        typed = typed,
                        locked = locked,
                        error = codeError,
                        otherAction = otherDeviceAction(context, myRole),
                        onPick = { name ->
                            picked = name
                            typed = ""
                            codeError = null
                            attempts = PairingFlow.CodeAttempts()
                        },
                        onType = { digits ->
                            typed = digits
                            codeError = null
                            // The code is read from this host: keep it even if another one appears.
                            if (picked == null) picked = codeTarget?.name
                        },
                        onSubmit = { submitCode(myRole) },
                    )
                    else -> {
                        QrCodeScanner(onCode = { payload ->
                            scanned = payload
                            startGuest(payload, myRole)
                        })
                        Note(stringResource(R.string.pairing_scan_instructions))
                    }
                }
            }
        },
        confirmButton = {
            when {
                badLink -> ArchiveButton(label = stringResource(R.string.action_close), onClick = onDismiss, kind = ArchiveButtonKind.Primary)
                myRole == null -> Unit
                choosing -> ArchiveButton(
                    label = stringResource(R.string.action_continue),
                    enabled = choice?.canContinue == true,
                    kind = ArchiveButtonKind.Primary,
                    onClick = { choosing = false },
                )
                mode == null -> Unit
                done != null && done.result != null && myRole == HandoffV2.Role.RECEIVER &&
                    (followUp is SyncHandoff.FollowUp.PickFolder || followUp is SyncHandoff.FollowUp.GoogleSignIn) ->
                    FollowUpButton(followUp!!, onDone = onDismiss)
                done != null || declinedBy != null -> ArchiveButton(label = stringResource(R.string.action_close), onClick = onDismiss, kind = ArchiveButtonKind.Primary)
                failure != null -> ArchiveButton(label = stringResource(R.string.action_try_again), onClick = { retry() }, kind = ArchiveButtonKind.Primary)
                offerWaiting != null && other != null -> ArchiveButton(
                    label = stringResource(R.string.pairing_send),
                    kind = ArchiveButtonKind.Primary,
                    onClick = {
                        val offer = choice?.offer(platform, other.platform) ?: HandoffV2.Offer(approved = true)
                        sentLocation = offer.location != null
                        sending = true
                        pendingOffer = null
                        offerWaiting.complete(offer)
                    },
                )
                mode == PairingFlow.Mode.ENTER && codeTarget != null && !inProgress -> ArchiveButton(
                    label = stringResource(R.string.action_continue),
                    enabled = PairingFlow.isWholeCode(typed) && !locked,
                    kind = ArchiveButtonKind.Primary,
                    onClick = { submitCode(myRole) },
                )
                offersEntry -> ArchiveButton(label = stringResource(R.string.pairing_mode_enter), onClick = { enterCode() }, kind = ArchiveButtonKind.Secondary)
                else -> Unit
            }
        },
        dismissButton = {
            when {
                badLink -> Unit
                offerWaiting != null && other != null -> ArchiveButton(
                    label = stringResource(R.string.pairing_dont_send),
                    onClick = {
                        pendingOffer = null
                        offerWaiting.complete(null)
                    },
                )
                done != null || declinedBy != null -> Unit
                canGoBack -> ArchiveButton(
                    label = stringResource(R.string.action_back),
                    onClick = {
                        if (mode == PairingFlow.Mode.ENTER) {
                            leaveEntry()
                        } else {
                            stopPairing()
                            scanned = null
                            mode = null
                        }
                    },
                )
                else -> ArchiveButton(label = stringResource(if (failure != null) R.string.action_close else R.string.action_cancel), onClick = onDismiss)
            }
        },
    )
}

/** What the other device chooses: the opposite of this one's direction. */
private fun otherDeviceAction(context: Context, role: HandoffV2.Role): String = context.getString(
    if (role == HandoffV2.Role.SENDER) R.string.sync_receive_from_device else R.string.sync_set_up_device,
)

/** Phones and tablets scan; a headset shows its code, as Vision Pro does. */
private fun isHeadset(context: Context): Boolean =
    context.packageManager.hasSystemFeature(XrTheaterRules.FEATURE_SPATIAL) ||
        Build.MANUFACTURER.equals("Oculus", ignoreCase = true) || Build.MANUFACTURER.equals("Meta", ignoreCase = true)

private fun hasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** Sender: what to send, before pairing. */
@Composable
private fun OfferChoices(choice: OfferChoice, context: Context, onChange: (OfferChoice) -> Unit) {
    SectionLabel(stringResource(R.string.settings_section_sync))
    val location = choice.location
    if (location != null) {
        val provider = if (location.provider == SyncProvider.FOLDER) stringResource(R.string.sync_provider_folder) else sourceKindLabel(context, location.provider.kind)
        TickRow(
            label = stringResource(R.string.pairing_send_location, provider),
            detail = if (location.provider == SyncProvider.FOLDER) {
                stringResource(R.string.pairing_send_location_folder, location.space)
            } else {
                stringResource(R.string.pairing_send_location_login, location.space)
            },
            checked = choice.sendLocation,
        ) { onChange(choice.copy(sendLocation = it)) }
    } else {
        Note(stringResource(R.string.pairing_no_location))
    }
    if (choice.accounts.isNotEmpty()) {
        SectionLabel(stringResource(R.string.pairing_send_accounts))
        choice.accounts.forEachIndexed { index, account ->
            TickRow("${sourceKindLabel(context, account.kind)} · ${account.email ?: account.displayName ?: account.subject}", checked = index in choice.tickedAccounts) { ticked ->
                onChange(choice.copy(tickedAccounts = if (ticked) choice.tickedAccounts + index else choice.tickedAccounts - index))
            }
        }
    }
    if (location?.provider == SyncProvider.GOOGLE_DRIVE || choice.accounts.any { it.kind == MediaSourceKind.GOOGLE_DRIVE }) {
        Note(stringResource(R.string.pairing_google_note))
    }
    if (choice.logins.isNotEmpty()) {
        SectionLabel(stringResource(R.string.pairing_send_logins))
        choice.logins.forEachIndexed { index, login ->
            TickRow("${sourceKindLabel(context, login.kind)} · ${login.user} @ ${login.host}${login.port?.let { ":$it" }.orEmpty()}", checked = index in choice.tickedLogins) { ticked ->
                onChange(choice.copy(tickedLogins = if (ticked) choice.tickedLogins + index else choice.tickedLogins - index))
            }
        }
    }
    Note(stringResource(R.string.pairing_encrypted_note))
}

/** Sender: the summary before anything is sent (FEATURES-27.1.md §6.1). */
@Composable
private fun Approval(offer: HandoffV2.Offer, peer: HandoffV2.RoleMessage, context: Context) {
    Statement(stringResource(R.string.pairing_confirm_send_title, peer.name))
    offer.location?.let { location ->
        Bullet(
            when {
                location.provider == SyncProvider.FOLDER -> stringResource(R.string.pairing_offer_folder, location.space, peer.name)
                location.provider == SyncProvider.GOOGLE_DRIVE && location.account == null -> stringResource(R.string.pairing_offer_google_hint, location.space, peer.name)
                else -> stringResource(R.string.pairing_offer_location, sourceKindLabel(context, location.provider.kind), location.space)
            },
        )
    }
    offer.accounts.forEach { account ->
        Bullet(stringResource(R.string.pairing_offer_account, sourceKindLabel(context, account.kind), account.email ?: account.displayName.orEmpty()))
    }
    offer.logins.forEach { login -> Bullet(stringResource(R.string.pairing_login_for, sourceKindLabel(context, login.kind), login.host)) }
    if (offer.location == null && offer.accounts.isEmpty() && offer.logins.isEmpty()) Bullet(stringResource(R.string.pairing_nothing))
    Note(stringResource(R.string.pairing_keeps_note, peer.name))
}

/** Both sides: what the receiver kept, and what it still finishes itself. */
@Composable
private fun ResultView(
    role: HandoffV2.Role,
    peer: HandoffV2.RoleMessage,
    result: HandoffV2.ResultMessage,
    sentLocation: Boolean,
    followUp: SyncHandoff.FollowUp?,
    context: Context,
) {
    Statement(stringResource(if (role == HandoffV2.Role.SENDER) R.string.pairing_result_sent else R.string.pairing_result_received, peer.name))
    if (result.stored.isEmpty() && result.failed.isEmpty()) Bullet(stringResource(R.string.pairing_nothing))
    result.stored.forEach { Bullet(describeItem(context, it)) }
    result.failed.forEach { failure ->
        val item = describeItem(context, failure.item)
        Bullet(failure.reason?.let { stringResource(R.string.pairing_item_failed_reason, item, it) } ?: stringResource(R.string.pairing_item_failed, item))
    }
    if (role == HandoffV2.Role.SENDER && HandoffV2.locationPending(result, sentLocation)) {
        Note(stringResource(R.string.pairing_location_pending, peer.name))
    }
    if (role == HandoffV2.Role.RECEIVER) {
        when (followUp) {
            is SyncHandoff.FollowUp.PickFolder -> Text(stringResource(R.string.sync_folder_relink, followUp.space), style = BodyCopyStyle(), color = EdendaleColors.Gold)
            is SyncHandoff.FollowUp.GoogleSignIn -> Text(stringResource(R.string.sync_google_relink, followUp.hint ?: "Google", followUp.space), style = BodyCopyStyle(), color = EdendaleColors.Gold)
            // §9.8: a TV says where to set the location up instead of offering what it can't do.
            is SyncHandoff.FollowUp.OnAnotherDevice -> Text(
                stringResource(if (followUp.provider == SyncProvider.FOLDER) R.string.sync_folder_tv_note else R.string.sync_google_tv_note),
                style = BodyCopyStyle(),
                color = EdendaleColors.Gold,
            )
            null -> Unit
        }
    }
}

/**
 * Joining by the other device's code (Section T): the devices found that show
 * one (or the search), then the six digits the chosen one shows. The field
 * takes focus, so a remote's OK opens the on-screen keyboard and a phone's
 * keyboard comes up; Go sends the code, as Continue does.
 */
@Composable
private fun CodeEntry(
    isTelevision: Boolean,
    devices: List<DiscoveredTelevision>,
    target: DiscoveredTelevision?,
    searchFailed: Boolean,
    onlyQrHosts: Boolean,
    typed: String,
    locked: Boolean,
    error: String?,
    otherAction: String,
    onPick: (String) -> Unit,
    onType: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    if (target != null) {
        val focus = remember { FocusRequester() }
        OutlinedTextField(
            value = typed,
            onValueChange = { onType(PairingFlow.typedCode(it)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(stringResource(R.string.pairing_enter_code, target.name)) },
            singleLine = true,
            enabled = !locked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        )
        LaunchedEffect(target.name, locked) {
            if (!locked) runCatching { focus.requestFocus() }
        }
        when {
            locked -> ErrorText(stringResource(R.string.pairing_locked_out))
            error != null -> ErrorText(error)
        }
    } else {
        when {
            devices.size > 1 -> {
                SectionLabel(stringResource(R.string.pairing_devices_found))
                devices.forEach { device ->
                    ChoiceRow(
                        label = device.name,
                        selected = false,
                        iconRes = if (device.platform == "androidtv" || device.platform == "tvos") R.drawable.ic_tv else R.drawable.ic_link,
                        modifier = Modifier.tvFocusLift(isTelevision, RoundedCornerShape(EdendaleRadii.Soft.dp)),
                    ) { onPick(device.name) }
                }
            }
            searchFailed -> Statement(stringResource(R.string.pairing_discovery_failed))
            else -> Waiting(stringResource(R.string.pairing_searching))
        }
        if (onlyQrHosts) Note(stringResource(R.string.pairing_code_apple_only))
    }
    Note(stringResource(R.string.pairing_mode_footer, otherAction))
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

private fun describeItem(context: Context, raw: String): String = when (val item = PairingFlow.item(raw)) {
    PairingFlow.Item.Location -> context.getString(R.string.pairing_item_location)
    is PairingFlow.Item.Account -> context.getString(R.string.pairing_item_account, item.kind?.let { sourceKindLabel(context, it) } ?: item.rawKind)
    is PairingFlow.Item.Login -> context.getString(R.string.pairing_login_for, item.kind?.let { sourceKindLabel(context, it) } ?: item.rawKind, item.host)
    is PairingFlow.Item.Other -> item.raw
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Statement(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TickRow(label: String, detail: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                detail?.let { Text(it, style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** How long a guest looks for the host by name when the code's address doesn't answer (Apple waits 15 s). */
private const val DISCOVERY_TIMEOUT_MILLIS = 15_000L
