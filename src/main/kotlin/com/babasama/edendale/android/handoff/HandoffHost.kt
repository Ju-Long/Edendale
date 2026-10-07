package com.babasama.edendale.android.handoff

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.handoff.HandoffCrypto
import com.babasama.edendale.handoff.HandoffProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The TV side of the handoff (I.2.2): a listening socket registered as
 * `_edendale-handoff._tcp` with Network Service Discovery, and the six-digit
 * code the phone must type. One phone at a time; three wrong codes rotate
 * the code. [store] validates and keeps what the phone sent (an account or a
 * login) and says whether it did. Lives for one Link Source attempt.
 */
class HandoffHost(
    context: Context,
    private val kind: MediaSourceKind,
    private val store: suspend (AccountHandoff.Response) -> AccountHandoff.Result,
) {
    sealed interface Status {
        /** Advertising; waiting for a phone. */
        data object Waiting : Status

        /** A phone connected and the viewer is choosing what to send. */
        data object Connected : Status

        /** The phone's answer arrived and is being validated. */
        data object Validating : Status

        data class Done(val response: AccountHandoff.Response) : Status

        /** The last attempt failed; the host keeps waiting for the next phone. */
        data class Failed(val error: Exception) : Status
    }

    data class State(val code: String, val status: Status = Status.Waiting, val deviceName: String)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val deviceName = deviceName(appContext)
    private val _state = MutableStateFlow(State(HandoffCrypto.newCode(), deviceName = deviceName))
    val state: StateFlow<State> = _state.asStateFlow()

    private var listener: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var job: Job? = null
    private var wrongCodes = 0

    /** Opens the port, registers the service, and accepts phones until [stop]. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        val socket = ServerSocket(0, 1, null as InetAddress?)
        listener = socket
        register(socket.localPort)
        job = scope.launch(Dispatchers.IO) {
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

    private fun serve(connection: Socket) {
        val current = _state.value
        _state.value = current.copy(status = Status.Connected)
        try {
            val request = AccountHandoff.Request(kind, deviceName)
            val response = HandoffProtocol.receive(connection, current.code, request) { answer ->
                _state.value = _state.value.copy(status = Status.Validating)
                runBlocking { store(answer) }
            }
            _state.value = _state.value.copy(status = Status.Done(response))
            runCatching { listener?.close() }
        } catch (wrong: HandoffException.WrongCode) {
            // Three guesses per code, then a fresh one: one in a million per handshake, as designed.
            wrongCodes += 1
            val code = if (wrongCodes >= MAX_WRONG_CODES) HandoffCrypto.newCode().also { wrongCodes = 0 } else current.code
            _state.value = State(code, Status.Failed(wrong), deviceName)
        } catch (error: Exception) {
            if (_state.value.status !is Status.Done) _state.value = _state.value.copy(status = Status.Failed(error))
        }
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = AccountHandoff.SERVICE_TYPE.trimEnd('.')
            setPort(port)
            setAttribute("v", AccountHandoff.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // The system renames a service whose name is taken; the phone shows that name.
                serviceInfo.serviceName?.let { name -> _state.value = _state.value.copy(deviceName = name) }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _state.value = _state.value.copy(status = Status.Failed(HandoffException.ConnectionFailed))
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { _state.value = _state.value.copy(status = Status.Failed(HandoffException.ConnectionFailed)) }
    }

    /** Unregisters and closes; the code and keys are gone with it. */
    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { listener?.close() }
        listener = null
        job?.cancel()
        job = null
    }

    companion object {
        const val MAX_WRONG_CODES = 3

        /** The name the viewer gave the device, else its model. */
        fun deviceName(context: Context): String =
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
