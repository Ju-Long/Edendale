package com.babasama.edendale

import android.content.Context
import com.babasama.edendale.tmdb.BrowseRepository
import com.babasama.edendale.tmdb.ContentCertificationProvider
import com.babasama.edendale.tmdb.TmdbAccountApi
import com.babasama.edendale.tmdb.TmdbApi
import com.babasama.edendale.tmdb.TmdbContentCertificationProvider
import com.babasama.edendale.tmdb.TmdbTransport
import com.babasama.edendale.tmdb.UserMediaSyncEngine
import com.babasama.edendale.wyzie.MAX_SUBTITLE_BYTES
import com.babasama.edendale.wyzie.WyzieException
import com.babasama.edendale.wyzie.WyzieResponse
import com.babasama.edendale.wyzie.WyzieSubtitle
import com.babasama.edendale.wyzie.WyzieSubtitleService
import com.babasama.edendale.wyzie.WyzieTransport
import com.babasama.edendale.wyzie.badStatus
import com.babasama.edendale.wyzie.cacheFileName
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android composition root for domain and TMDB services. */
object AndroidEdendaleCore {
    fun browseRepository(): BrowseRepository = BrowseRepository(
        TmdbApi(AndroidTmdbTransport()),
    )

    fun accountApi(): TmdbAccountApi = TmdbAccountApi(AndroidTmdbTransport())

    fun syncEngine(): UserMediaSyncEngine = UserMediaSyncEngine(accountApi())

    /**
     * Young Audience certification lookups. [regionProvider] is read on each
     * request so a device region change is picked up without rebuilding the
     * provider; its cache is invalidated by the changed region identifier.
     */
    fun contentCertificationProvider(
        regionProvider: () -> String,
    ): ContentCertificationProvider =
        TmdbContentCertificationProvider(TmdbApi(AndroidTmdbTransport()), regionProvider)

    fun wyzieService(): WyzieSubtitleService = WyzieSubtitleService(AndroidWyzieTransport())

    fun hasTmdbCredentials(): Boolean = EdendaleCore.hasTmdbCredentials()
}

private class AndroidTmdbTransport : TmdbTransport {
    private val bearerToken = TmdbSecrets.readAccessToken
        .removePrefix("Bearer ")
        .trim()
    private val apiKey = TmdbSecrets.apiKey.trim()

    override val isConfigured: Boolean
        get() = bearerToken.isNotEmpty() || apiKey.isNotEmpty()

    override suspend fun get(path: String, parameters: Map<String, String>): String =
        request("GET", path, parameters, jsonBody = null)

    override suspend fun send(
        method: String,
        path: String,
        parameters: Map<String, String>,
        jsonBody: String?,
    ): String = request(method, path, parameters, jsonBody)

    private suspend fun request(
        method: String,
        path: String,
        parameters: Map<String, String>,
        jsonBody: String?,
    ): String = withContext(Dispatchers.IO) {
        val queryParameters = buildMap {
            putAll(parameters)
            if (bearerToken.isEmpty() && apiKey.isNotEmpty()) put("api_key", apiKey)
        }
        val query = queryParameters.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        val url = URI(
            "https://api.themoviedb.org/3$path${if (query.isEmpty()) "" else "?$query"}",
        ).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (bearerToken.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json;charset=utf-8")
            }
        }
        try {
            if (jsonBody != null) {
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("TMDB request failed (HTTP $status).")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

internal class AndroidWyzieTransport : WyzieTransport {
    override suspend fun get(url: String): WyzieResponse = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val status = connection.responseCode
            val body = connection.responseBody(status).bufferedReader().use { it.readText() }
            WyzieResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.responseBody(status).bufferedReader().use { it.readText() }
                throw badStatus(status, body)
            }
            if (connection.contentLengthLong > MAX_SUBTITLE_BYTES) {
                throw WyzieException.FileTooLarge()
            }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SUBTITLE_BYTES) throw WyzieException.FileTooLarge()
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpURLConnection.responseBody(status: Int) =
        if (status in 200..299) inputStream else errorStream ?: ByteArray(0).inputStream()
}

/**
 * Downloads and atomically publishes one subtitle in the app cache. The
 * player never receives the temporary path, so interrupted writes remain
 * invisible.
 */
internal suspend fun cacheWyzieSubtitle(
    context: Context,
    service: WyzieSubtitleService,
    subtitle: WyzieSubtitle,
): File = withContext(Dispatchers.IO) {
    val bytes = service.download(subtitle)
    val directory = File(context.cacheDir, "subtitles").apply { mkdirs() }
    check(directory.isDirectory) { "Subtitle cache directory is unavailable." }
    val destination = File(directory, cacheFileName(subtitle))
    val temporary = File.createTempFile("wyzie-", ".part", directory)
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        destination
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}
