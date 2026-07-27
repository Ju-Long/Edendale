package com.babasama.edendale

import com.babasama.edendale.tmdb.BrowseRepository
import com.babasama.edendale.tmdb.TmdbAccountApi
import com.babasama.edendale.tmdb.TmdbApi
import com.babasama.edendale.tmdb.TmdbTransport
import com.babasama.edendale.tmdb.UserMediaSyncEngine
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android composition root for domain and TMDB services. */
object AndroidEdendaleCore {
    fun browseRepository(): BrowseRepository = BrowseRepository(
        TmdbApi(AndroidTmdbTransport()),
    )

    fun accountApi(): TmdbAccountApi = TmdbAccountApi(AndroidTmdbTransport())

    fun syncEngine(): UserMediaSyncEngine = UserMediaSyncEngine(accountApi())

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
