package com.babasama.edendale.remote

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.tls.OkHostnameVerifier
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * [RemoteHttp] over OkHttp (H.2): no cache, no cookie jar, and the system's
 * certificate validation plus the certificates the viewer pinned (D10).
 * Plain `http://` goes through a second client whose DNS refuses every
 * address outside the private networks. No interceptor logs anything,
 * since URLs and headers can carry tokens or signed links.
 */
class OkHttpRemoteHttp(private val client: OkHttpClient = sharedClient) : RemoteHttp {
    private val cleartextClient: OkHttpClient by lazy { RemoteTls.cleartextVariant(client) }

    override fun newCall(request: RemoteRequest, bodyLimit: Int): RemoteCall {
        val body = request.body?.toRequestBody(request.contentType?.toMediaTypeOrNull())
        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val chosen = if (request.url.startsWith("http://", ignoreCase = true)) cleartextClient else client
        val call = chosen.newCall(builder.build())
        return object : RemoteCall {
            override fun execute(): RemoteResponse = execute(call, bodyLimit)
            override fun cancel() = call.cancel()
        }
    }

    private fun execute(call: Call, bodyLimit: Int): RemoteResponse = call.execute().use { response ->
        val limit = if (response.isSuccessful) bodyLimit else ERROR_BODY_LIMIT
        val body = response.body?.source()?.let { source ->
            val out = java.io.ByteArrayOutputStream(minOf(limit, 1 shl 20))
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val read = source.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: ByteArray(0)
        // Repeated headers (several WWW-Authenticate challenges) join as one list.
        val headers = response.headers.names().associateWith { response.headers(it).joinToString(", ") }
        RemoteResponse(response.code, headers, body)
    }

    companion object {
        private const val ERROR_BODY_LIMIT = 64 * 1024

        /** One connection pool for every remote source, trusting whatever [RemoteTls.pins] holds at handshake time. */
        val sharedClient: OkHttpClient by lazy { RemoteTls.newClient(TlsPins { host, port -> RemoteTls.pins.pinned(host, port) }) }
    }
}

/** The OkHttp side of D10: clients that honor pinned certificates and keep plain HTTP on the private network. */
object RemoteTls {
    /** The device's pinned certificates; the app installs its store at startup, tests leave it empty. */
    @Volatile
    var pins: TlsPins = TlsPins.NONE

    /** A client with [PinnedTrustManager] over [pins]; its hostname check accepts a pinned certificate as well. */
    fun newClient(pins: TlsPins, dns: Dns = Dns.SYSTEM): OkHttpClient {
        val trust = PinnedTrustManager(pins)
        return OkHttpClient.Builder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(Tls.sslContext(trust).socketFactory, trust)
            .hostnameVerifier(trust.hostnameVerifier(OkHostnameVerifier))
            // A redirect from https:// to http:// would leave the secure connection the viewer entered.
            .followSslRedirects(false)
            .dns(dns)
            .build()
    }

    /** [client] with DNS that refuses every address outside the private networks, for `http://` requests. */
    fun cleartextVariant(client: OkHttpClient): OkHttpClient =
        client.newBuilder().dns(PrivateNetworkDns(client.dns)).build()
}

/**
 * Resolves through [system] and refuses a host with any address outside the
 * private networks (D10), on the addresses OkHttp is about to connect to.
 */
class PrivateNetworkDns(private val system: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = system.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !Tls.isPrivateAddress(it) }) throw CleartextRefusedException(hostname)
        return addresses
    }
}
