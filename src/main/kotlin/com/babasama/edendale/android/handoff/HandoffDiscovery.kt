package com.babasama.edendale.android.handoff

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.babasama.edendale.handoff.AccountHandoff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.util.ArrayDeque

/** A TV offering a handoff on this network, resolved to an address the phone can connect to. */
data class DiscoveredTelevision(val name: String, val host: InetAddress, val port: Int)

/**
 * The phone side's search for TVs (I.2.2): browses `_edendale-handoff._tcp`
 * with Network Service Discovery and resolves each service found. Resolves
 * run one at a time, as older platforms require. Stop it when the dialog
 * closes.
 */
class HandoffDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _televisions = MutableStateFlow<List<DiscoveredTelevision>>(emptyList())
    val televisions: StateFlow<List<DiscoveredTelevision>> = _televisions.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _failed.value = true
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(pending) {
                    pending.addLast(service)
                    resolveNext()
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                _televisions.value = _televisions.value.filterNot { it.name == service.serviceName }
            }
        }
        listener = discovery
        runCatching { nsd.discoverServices(AccountHandoff.SERVICE_TYPE.trimEnd('.'), NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure {
                _failed.value = true
                listener = null
            }
    }

    private fun resolveNext() {
        if (resolving) return
        val next = pending.pollFirst() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(
            next,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finished()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    if (host != null && serviceInfo.port > 0) {
                        val television = DiscoveredTelevision(serviceInfo.serviceName, host, serviceInfo.port)
                        _televisions.value = (_televisions.value.filterNot { it.name == television.name } + television).sortedBy { it.name.lowercase() }
                    }
                    finished()
                }

                private fun finished() {
                    synchronized(pending) {
                        resolving = false
                        resolveNext()
                    }
                }
            },
        )
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        synchronized(pending) { pending.clear() }
    }
}
