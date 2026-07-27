package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.sanitizeTmdbRating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A signed-in TMDB v3 session: the id plus the numeric account it belongs to. */
data class TmdbSession(
    val sessionId: String,
    val accountId: Int,
)

/** Step one of the connect flow: send the user to [approvalUrl], then finish. */
data class TmdbAuthRequest(
    val requestToken: String,
    val approvalUrl: String,
)

data class TmdbAccount(
    val id: Int,
    val username: String? = null,
    val name: String? = null,
)

/** A pulled rated title: the item plus the user's 0.5–10 TMDB rating. */
data class TmdbRatedItem(
    val item: MediaItem,
    val rating: Double,
)

/**
 * TMDB v3 account client: user auth (request token → browser approval →
 * session) plus the favourite/watchlist/rating reads and writes the sync
 * engine drives.
 */
class TmdbAccountApi(private val transport: TmdbTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = transport.isConfigured

    // MARK: Authentication

    suspend fun createRequestToken(): TmdbAuthRequest {
        val token = fetch("/authentication/token/new").requiredString("request_token")
        return TmdbAuthRequest(
            requestToken = token,
            approvalUrl = "https://www.themoviedb.org/authenticate/$token",
        )
    }

    /** Call only after the user approved the token in the browser. */
    suspend fun createSession(requestToken: String): String = parse(
        transport.send(
            method = "POST",
            path = "/authentication/session/new",
            jsonBody = buildJsonObject { put("request_token", requestToken) }.toString(),
        ),
    ).requiredString("session_id")

    suspend fun deleteSession(sessionId: String) {
        transport.send(
            method = "DELETE",
            path = "/authentication/session",
            jsonBody = buildJsonObject { put("session_id", sessionId) }.toString(),
        )
    }

    suspend fun accountDetails(sessionId: String): TmdbAccount {
        val payload = fetch("/account", mapOf("session_id" to sessionId))
        return TmdbAccount(
            id = payload.int("id") ?: throw IllegalStateException(
                "TMDB account response is missing the account id.",
            ),
            username = payload.string("username"),
            name = payload.string("name")?.takeIf(String::isNotBlank),
        )
    }

    // MARK: Writes

    suspend fun setFavourite(session: TmdbSession, ref: MediaRef, value: Boolean) {
        transport.send(
            method = "POST",
            path = "/account/${session.accountId}/favorite",
            parameters = mapOf("session_id" to session.sessionId),
            jsonBody = buildJsonObject {
                put("media_type", ref.mediaType.pathSegment)
                put("media_id", ref.id)
                put("favorite", value)
            }.toString(),
        )
    }

    suspend fun setWatchlist(session: TmdbSession, ref: MediaRef, value: Boolean) {
        transport.send(
            method = "POST",
            path = "/account/${session.accountId}/watchlist",
            parameters = mapOf("session_id" to session.sessionId),
            jsonBody = buildJsonObject {
                put("media_type", ref.mediaType.pathSegment)
                put("media_id", ref.id)
                put("watchlist", value)
            }.toString(),
        )
    }

    /** Rates 0.5–10 in half steps; null removes the rating. */
    suspend fun setRating(session: TmdbSession, ref: MediaRef, value: Double?) {
        val path = "/${ref.mediaType.pathSegment}/${ref.id}/rating"
        val parameters = mapOf("session_id" to session.sessionId)
        val sanitized = sanitizeTmdbRating(value)
        if (sanitized == null) {
            transport.send(method = "DELETE", path = path, parameters = parameters)
        } else {
            transport.send(
                method = "POST",
                path = path,
                parameters = parameters,
                jsonBody = buildJsonObject { put("value", sanitized) }.toString(),
            )
        }
    }

    // MARK: Pulls (movies + TV, all pages)

    suspend fun favourites(session: TmdbSession): List<MediaItem> =
        pagedItems(session, "favorite/movies", MediaType.MOVIE) +
            pagedItems(session, "favorite/tv", MediaType.TV)

    suspend fun watchlist(session: TmdbSession): List<MediaItem> =
        pagedItems(session, "watchlist/movies", MediaType.MOVIE) +
            pagedItems(session, "watchlist/tv", MediaType.TV)

    suspend fun rated(session: TmdbSession): List<TmdbRatedItem> =
        pagedRated(session, "rated/movies", MediaType.MOVIE) +
            pagedRated(session, "rated/tv", MediaType.TV)

    private suspend fun pagedItems(
        session: TmdbSession,
        segment: String,
        type: MediaType,
    ): List<MediaItem> = buildList {
        forEachResultPage(session, segment) { results ->
            addAll(mediaItems(results, type))
        }
    }

    private suspend fun pagedRated(
        session: TmdbSession,
        segment: String,
        type: MediaType,
    ): List<TmdbRatedItem> = buildList {
        forEachResultPage(session, segment) { results ->
            val items = mediaItems(results, type).associateBy(MediaItem::id)
            results.forEach { element ->
                val payload = element as? JsonObject ?: return@forEach
                val item = payload.int("id")?.let(items::get) ?: return@forEach
                val rating = sanitizeTmdbRating(payload.double("rating")) ?: return@forEach
                add(TmdbRatedItem(item, rating))
            }
        }
    }

    private suspend inline fun forEachResultPage(
        session: TmdbSession,
        segment: String,
        onPage: (JsonArray) -> Unit,
    ) {
        var page = 1
        while (page <= MAX_PAGES) {
            val payload = fetch(
                "/account/${session.accountId}/$segment",
                mapOf(
                    "session_id" to session.sessionId,
                    "page" to page.toString(),
                    "sort_by" to "created_at.asc",
                ),
            )
            onPage((payload["results"] as? JsonArray) ?: JsonArray(emptyList()))
            val totalPages = payload.int("total_pages") ?: 1
            if (page >= totalPages) break
            page += 1
        }
    }

    private suspend fun fetch(
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): JsonObject {
        if (!transport.isConfigured) throw MissingTmdbCredentialsException()
        return parse(transport.get(path, parameters))
    }

    private fun parse(body: String): JsonObject =
        json.parseToJsonElement(body).jsonObject

    private companion object {
        /** Safety cap on account list pagination (20 pages × 20 items each). */
        const val MAX_PAGES = 20
    }
}

private fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw IllegalStateException("TMDB response is missing \"$key\".")

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (get(key) as? JsonPrimitive)?.intOrNull

private fun JsonObject.double(key: String): Double? =
    (get(key) as? JsonPrimitive)?.doubleOrNull
