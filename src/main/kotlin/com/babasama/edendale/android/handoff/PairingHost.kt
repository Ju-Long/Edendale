package com.babasama.edendale.android.handoff

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.handoff.FrameStream
import com.babasama.edendale.handoff.HandoffCrypto
import com.babasama.edendale.handoff.HandoffProtocolV2
import com.babasama.edendale.handoff.HandoffV2
import com.babasama.edendale.sync.SyncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The host side of a version-2 handoff (SYNC-PROTOCOL.md §9): the device
 * whose pairing screen is open. It advertises `_edendale-handoff._tcp` with
 * `v=2`, `name`, and `platform` while the screen is open, shows a QR code
 * (an X25519 key and a 128-bit secret) and a six-digit code for a PC, which
 * has no camera (FEATURES-27.1.md §6.2), and takes one guest at a time.
 * Which side sends is the viewer's choice ([role]) and independent of who
 * hosts. A QR code or code lasts 2 minutes and one attempt, and a new one
 * replaces it when it expires, as on Apple; after 5 wrong codes in a row
 * the next code waits a minute.
 */
class PairingHost(
    context: Context,
    private val role: HandoffV2.Role,
    private val platform: String,
    deviceName: String,
    /** The sender's offer once the guest's name is known; may wait for the viewer. Null declines. */
    private val offer: suspend (peer: HandoffV2.RoleMessage) -> HandoffV2.Offer?,
    /** The receiver's validation and storage. */
    private val receive: suspend (offer: HandoffV2.Offer, peer: HandoffV2.RoleMessage) -> HandoffV2.ResultMessage,
    /** The guest's name and platform, once the roles are exchanged. */
    private val onPeer: (peer: HandoffV2.RoleMessage) -> Unit = {},
) {
    sealed interface Status {
        data object Waiting : Status
        data class Connected(val peerName: String?) : Status
        data class Done(val peer: HandoffV2.RoleMessage, val result: HandoffV2.ResultMessage?) : Status
        data class Failed(val error: Exception) : Status

        /** Too many wrong codes: no code is shown until [untilMillis]. */
        data class LockedOut(val untilMillis: Long) : Status
    }

    data class State(
        val qrUri: String?,
        val code: String?,
        val deviceName: String,
        val addresses: List<String> = emptyList(),
        val status: Status = Status.Waiting,
    )

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val deviceName = HandoffHost.deviceName(appContext).let { if (deviceName.isNotBlank()) deviceName else it }
    private val _state = MutableStateFlow(State(null, null, this.deviceName))
    val state: StateFlow<State> = _state.asStateFlow()

    private var listener: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var job: Job? = null
    private var pairing: HandoffProtocolV2.HostPairing? = null
    private var keyPair: HandoffV2.KeyPair? = null
    private var wrongCodes = 0
    private var lockedUntil = 0L
    @Volatile private var busy = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        val socket = ServerSocket(0, 1, null as InetAddress?)
        listener = socket
        newCode(socket.localPort)
        register(socket.localPort)
        job = scope.launch(Dispatchers.IO) {
            // An expired code is replaced while no guest is connected, and a lockout ends on time (§9.3, §9.4).
            launch {
                while (isActive && !socket.isClosed) {
                    delay(REFRESH_CHECK_MILLIS)
                    if (!busy && needsNewCode(System.currentTimeMillis())) newCode()
                }
            }
            while (!socket.isClosed) {
                val connection = try {
                    socket.accept()
                } catch (error: IOException) {
                    break
                }
                connection.use { serve(it) }
            }
        }
    }

    private fun needsNewCode(now: Long): Boolean = when (_state.value.status) {
        is Status.LockedOut -> now >= lockedUntil
        Status.Waiting, is Status.Failed -> pairing?.isExpired ?: false
        else -> false
    }

    /** A fresh QR code and code (§9.3, §9.4), or the lockout after too many wrong codes. */
    @Synchronized
    fun newCode(port: Int = listener?.localPort ?: 0) {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) {
            _state.value = _state.value.copy(qrUri = null, code = null, status = Status.LockedOut(lockedUntil))
            pairing = null
            return
        }
        val keys = HandoffV2.newKeyPair()
        val payload = HandoffV2.QrPayload(deviceName, HandoffHost.localAddresses().firstOrNull(), port.takeIf { it > 0 }, keys.publicKey, HandoffV2.newSecret())
        val code = HandoffCrypto.newCode()
        // The replaced code's key goes now; its secret went with its pairing (§9.3).
        keyPair?.destroy()
        keyPair = keys
        pairing = HandoffProtocolV2.HostPairing(payload, keys, code)
        _state.value = State(payload.uri(), code, deviceName, HandoffHost.localAddresses().map { "$it:$port" }, Status.Waiting)
    }

    private fun serve(connection: Socket) {
        val current = pairing
        if (busy || current == null) {
            runCatching {
                FrameStream(connection.getInputStream(), connection.getOutputStream()).write(HandoffV2.errorFrame(if (busy) HandoffV2.ErrorReason.BUSY else HandoffV2.ErrorReason.SECRET))
            }
            return
        }
        busy = true
        _state.value = _state.value.copy(status = Status.Connected(null))
        try {
            val frames = FrameStream(connection.getInputStream(), connection.getOutputStream())
            connection.soTimeout = com.babasama.edendale.handoff.HandoffProtocol.HANDSHAKE_TIMEOUT_MILLIS
            val first = frames.read()
            if (HandoffV2.peek(first).first == AccountHandoff.VERSION) {
                // A version-1 phone looking for a TV to link a source: that lives in Link Source, not here.
                runCatching { frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.VERSION)) }
                throw HandoffException.UnsupportedVersion(AccountHandoff.VERSION)
            }
            val participant = HandoffProtocolV2.Participant(
                role, deviceName, platform,
                offer = { peer ->
                    _state.value = _state.value.copy(status = Status.Connected(peer.name))
                    kotlinx.coroutines.runBlocking { offer(peer) }
                },
                onPeer = { peer ->
                    _state.value = _state.value.copy(status = Status.Connected(peer.name))
                    onPeer(peer)
                },
                receive = { incoming, peer ->
                    _state.value = _state.value.copy(status = Status.Connected(peer.name))
                    kotlinx.coroutines.runBlocking { receive(incoming, peer) }
                },
            )
            val outcome = HandoffProtocolV2.host(connection, frames, first, current, participant)
            wrongCodes = 0
            _state.value = _state.value.copy(status = Status.Done(outcome.peer, outcome.result))
            runCatching { listener?.close() }
        } catch (wrong: HandoffException.WrongCode) {
            wrongCodes += 1
            if (wrongCodes >= HandoffV2.MAX_CODE_FAILURES) {
                lockedUntil = System.currentTimeMillis() + HandoffV2.CODE_LOCKOUT_MILLIS
                wrongCodes = 0
            }
            SyncLog.log(TAG, "wrong code; failures in a row: $wrongCodes")
            newCode()
            _state.value = _state.value.copy(status = if (_state.value.status is Status.LockedOut) _state.value.status else Status.Failed(wrong))
        } catch (error: Exception) {
            SyncLog.log(TAG, "handoff failed: ${SyncLog.describe(error)}")
            // One attempt per code: show a new one.
            newCode()
            _state.value = _state.value.copy(status = Status.Failed(error))
        } finally {
            busy = false
        }
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = AccountHandoff.SERVICE_TYPE.trimEnd('.')
            setPort(port)
            setAttribute("v", HandoffV2.VERSION.toString())
            setAttribute("name", deviceName.take(60))
            setAttribute("platform", platform)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Discovery fails; the QR code carries the address, so scanning still works.
                SyncLog.log(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** Stops advertising and closes; the secret, the keys, and the code go with it (§9.3). */
    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { listener?.close() }
        listener = null
        job?.cancel()
        job = null
        keyPair?.destroy()
        keyPair = null
        pairing = null
    }

    companion object {
        const val TAG = "PairingHost"

        /** How often an expired code or a finished lockout is noticed. */
        const val REFRESH_CHECK_MILLIS = 1_000L
    }
}

/**
 * The guest side of a version-2 handoff: connects to the host a scanned QR
 * code names (by its address, or found by discovery) and runs the exchange
 * on an IO thread. The viewer's choices (what to send, what to keep) arrive
 * through the callbacks, which may wait.
 */
object PairingGuest {
    const val CONNECT_TIMEOUT_MILLIS = 10_000

    /** Runs one exchange; blocking, call on an IO dispatcher. */
    fun run(
        host: InetAddress,
        port: Int,
        pairing: HandoffProtocolV2.GuestPairing,
        participant: HandoffProtocolV2.Participant,
        /** The socket is open; pairing starts. */
        onConnected: () -> Unit = {},
    ): HandoffProtocolV2.Outcome = Socket().use { socket ->
        socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        onConnected()
        HandoffProtocolV2.guest(socket, pairing, participant)
    }
}
