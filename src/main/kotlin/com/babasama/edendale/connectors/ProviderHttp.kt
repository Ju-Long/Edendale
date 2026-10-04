package com.babasama.edendale.connectors

import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.remote.ProviderResponse
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteHttp
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.RemoteResponse
import com.babasama.edendale.remote.RemoteSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.time.Instant

/**
 * Authorized requests to a cloud provider's API for one linked account
 * (H.7, Apple's `ProviderHTTP`): a Bearer token from the token provider, one
 * refresh after a 401, and backoff on rate limits and server errors (see
 * [ProviderResponse]). Listing and link resolution for OneDrive, Dropbox,
 * and Google Drive go through here. Tokens and URLs are never logged.
 */
class ProviderHttp(
    val kind: MediaSourceKind,
    val accountKey: String,
    private val tokens: CloudTokenProvider,
    private val http: RemoteHttp,
    private val backoffDelaysMillis: List<Long> = listOf(500, 1_000, 2_000),
) {
    /**
     * Sends the request [build] makes for an access token and returns a 2xx
     * response. A refused second token asks for sign-in; other failures
     * throw [RemoteSourceException].
     */
    suspend fun send(build: (String) -> RemoteRequest): RemoteResponse = withContext(Dispatchers.IO) {
        var rejected: String? = null
        var refreshed = false
        var backoffs = 0
        var answer: RemoteResponse? = null
        while (answer == null) {
            val token = tokens.accessToken(kind, accountKey, rejected)
            val request = build(token).with("Authorization", "Bearer $token")
            val response = try {
                http.newCall(request, bodyLimit = MAX_BODY).execute()
            } catch (error: IOException) {
                if (backoffs >= backoffDelaysMillis.size) throw RemoteSourceException(kind, RemoteFailure.Unreachable, error)
                delay(backoffDelaysMillis[backoffs++])
                continue
            }
            if (response.status in 200..299) {
                answer = response
                continue
            }
            when (val action = ProviderResponse.action(response.status, response.body, response::header, preauthorizedLink = false)) {
                ProviderResponse.Action.Refresh -> {
                    if (refreshed) throw ConnectorException(ConnectorFailure.SignInRequired(kind))
                    refreshed = true
                    rejected = token
                }
                is ProviderResponse.Action.Backoff -> {
                    if (backoffs >= backoffDelaysMillis.size) throw RemoteSourceException(kind, RemoteFailure.RateLimited)
                    delay(action.retryAfterSeconds?.let { (it * 1_000).toLong() } ?: backoffDelaysMillis[backoffs])
                    backoffs += 1
                }
                is ProviderResponse.Action.Fail -> throw RemoteSourceException(kind, action.failure)
            }
        }
        answer
    }

    /** [send], parsing a JSON object body. */
    suspend fun json(build: (String) -> RemoteRequest): JsonObject {
        val response = send(build)
        return runCatching { Json.parseToJsonElement(response.body.decodeToString()).jsonObject }.getOrNull()
            ?: throw RemoteSourceException(kind, RemoteFailure.ServerError(200))
    }

    companion object {
        /** A listing page larger than this is cut off and fails to parse. */
        const val MAX_BODY = 16 shl 20

        /** A JSON POST (Dropbox's API). */
        fun jsonPost(url: String, body: JsonObject) =
            RemoteRequest(url, method = "POST", body = body.toString().toByteArray(), contentType = "application/json")
    }
}

// MARK: - JSON helpers for provider responses

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.number(name: String): Long? =
    (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.array(name: String): List<JsonObject> = (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** RFC 3339 dates, with or without fractional seconds. */
internal fun parseInstant(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
