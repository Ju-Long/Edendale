package com.babasama.edendale.remote

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * [RemoteHttp] over OkHttp (H.2): no cache, no cookie jar, and the system's
 * certificate validation. No interceptor logs anything, since URLs and
 * headers can carry tokens or signed links.
 */
class OkHttpRemoteHttp(private val client: OkHttpClient = sharedClient) : RemoteHttp {

    override fun newCall(request: RemoteRequest, bodyLimit: Int): RemoteCall {
        val builder = Request.Builder().url(request.url).get()
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())
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
        val headers = response.headers.names().associateWith { response.header(it).orEmpty() }
        RemoteResponse(response.code, headers, body)
    }

    companion object {
        private const val ERROR_BODY_LIMIT = 64 * 1024

        /** One connection pool for every remote source. */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cache(null)
                .cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
