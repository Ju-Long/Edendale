package com.babasama.edendale.remote

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 for the JVM suite (A.5.5): one request
 * per connection, answered by [handler] on its own thread. The JDK's
 * `com.sun.net.httpserver` isn't on the Android unit-test compile classpath.
 * With [sslContext] it serves HTTPS with that context's certificate (D10).
 */
class LocalHttpServer(private val handler: (Request) -> Response, sslContext: SSLContext? = null) : Closeable {

    class Request(
        val method: String,
        val path: String,
        val query: String?,
        headers: Map<String, String>,
        val body: ByteArray = ByteArray(0),
    ) {
        val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

        fun header(name: String): String? = headers[name.lowercase()]

        fun queryValue(name: String): String? =
            query?.split('&')?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    class Response(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

    private val socket: ServerSocket = sslContext?.serverSocketFactory?.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        ?: ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { Thread(it, "LocalHttpServer").apply { isDaemon = true } }
    val requests = CopyOnWriteArrayList<Request>()

    val port: Int get() = socket.localPort

    init {
        workers.execute {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { runCatching { serve(connection) } }
            }
        }
    }

    private fun serve(connection: Socket): Unit = connection.use { client ->
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val (method, target) = requestLine.split(' ').let { it[0] to it.getOrElse(1) { "/" } }
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val length = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }?.value?.toIntOrNull() ?: 0
        // ISO-8859-1 maps each byte to one char, so the body reads back byte for byte.
        val body = CharArray(length).also { chars ->
            var read = 0
            while (read < length) {
                val count = reader.read(chars, read, length - read)
                if (count < 0) break
                read += count
            }
        }.concatToString().toByteArray(Charsets.ISO_8859_1)
        val request = Request(method, target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, body)
        requests += request
        val response = runCatching { handler(request) }.getOrElse { Response(500) }
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (response.headers.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
                append("Content-Length: ${response.body.size}\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            val out = client.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(response.body)
            out.flush()
        }
        Unit
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        207 -> "Multi-Status"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        410 -> "Gone"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        else -> "Status"
    }

    override fun close() {
        runCatching { socket.close() }
        workers.shutdownNow()
    }
}
