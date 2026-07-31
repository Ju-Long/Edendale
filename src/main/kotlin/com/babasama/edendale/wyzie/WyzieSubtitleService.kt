package com.babasama.edendale.wyzie

import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class WyzieSubtitle(
    val id: String,
    val url: String,
    val format: String,
    val encoding: String,
    val isHearingImpaired: Boolean,
    val flagUrl: String,
    val media: String,
    val display: String,
    val language: String,
    val source: List<String>? = null,
    val release: String? = null,
    val releases: List<String>? = null,
    val fileName: String? = null,
    val downloadCount: Int? = null,
    val origin: String? = null,
    val matchedRelease: String? = null,
    val matchedFilter: String? = null,
)

data class WyzieSubtitleQuery(
    val id: String,
    val season: Int? = null,
    val episode: Int? = null,
    val language: String? = null,
    val format: String? = null,
    val hearingImpaired: Boolean? = null,
)

sealed class WyzieException(message: String) : Exception(message) {
    class MissingKey : WyzieException("A Wyzie API key is required.")

    class BadStatus(
        val code: Int,
        val serverMessage: String?,
    ) : WyzieException("Wyzie request failed (HTTP $code).")

    class EmptyFile : WyzieException("The downloaded subtitle was empty.")
    class FileTooLarge : WyzieException("The downloaded subtitle exceeded the size limit.")
    class BadUrl : WyzieException("The subtitle URL was not an HTTP or HTTPS URL.")
}

data class WyzieResponse(
    val statusCode: Int,
    val body: String,
)

interface WyzieTransport {
    suspend fun get(url: String): WyzieResponse
    suspend fun download(url: String): ByteArray
}

fun queryParameters(
    query: WyzieSubtitleQuery,
    key: String,
): List<Pair<String, String>> = buildList {
    add("id" to query.id)
    query.season?.let { season ->
        add("season" to season.toString())
        query.episode?.let { add("episode" to it.toString()) }
    }
    query.language?.let { add("language" to it) }
    query.format?.let { add("format" to it) }
    query.hearingImpaired?.let { add("hi" to it.toString()) }
    add("key" to key)
}

fun searchUrl(query: WyzieSubtitleQuery, key: String): String =
    "$BASE_URL/search?" + queryParameters(query, key).joinToString("&") { (name, value) ->
        "${name.percentEncoded()}=${value.percentEncoded()}"
    }

fun decodeSearchResponse(body: String, statusCode: Int): List<WyzieSubtitle> {
    if (statusCode !in 200..299) {
        throw badStatus(statusCode, body)
    }
    val payload = Json.parseToJsonElement(body)
    val rows = payload as? JsonArray
        ?: throw IllegalArgumentException("Wyzie success response was not an array.")
    return rows.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        WyzieSubtitle(
            id = item.string("id") ?: return@mapNotNull null,
            url = item.string("url") ?: return@mapNotNull null,
            format = item.string("format") ?: return@mapNotNull null,
            encoding = item.string("encoding") ?: return@mapNotNull null,
            isHearingImpaired = item.boolean("isHearingImpaired") ?: return@mapNotNull null,
            flagUrl = item.string("flagUrl") ?: return@mapNotNull null,
            media = item.string("media") ?: return@mapNotNull null,
            display = item.string("display") ?: return@mapNotNull null,
            language = item.string("language") ?: return@mapNotNull null,
            source = item.stringOrArray("source"),
            release = item.string("release"),
            releases = item.stringArray("releases"),
            fileName = item.string("fileName"),
            downloadCount = item.int("downloadCount"),
            origin = item.string("origin"),
            matchedRelease = item.string("matchedRelease"),
            matchedFilter = item.string("matchedFilter"),
        )
    }
}

fun cacheFileName(subtitle: WyzieSubtitle): String {
    val id = subtitle.id.safeFilePart()
    val language = subtitle.language.safeFilePart()
    val format = subtitle.format.safeFilePart().ifBlank { "srt" }
    return "wyzie-$id-$language.$format"
}

class WyzieSubtitleService(private val transport: WyzieTransport) {
    suspend fun search(query: WyzieSubtitleQuery, key: String): List<WyzieSubtitle> {
        val resolvedKey = key.trim()
        if (resolvedKey.isEmpty()) throw WyzieException.MissingKey()
        val response = transport.get(searchUrl(query, resolvedKey))
        return decodeSearchResponse(response.body, response.statusCode)
    }

    suspend fun download(subtitle: WyzieSubtitle): ByteArray {
        val uri = runCatching { URI(subtitle.url) }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            throw WyzieException.BadUrl()
        }
        val body = transport.download(subtitle.url)
        if (body.isEmpty()) throw WyzieException.EmptyFile()
        if (body.size > MAX_SUBTITLE_BYTES) throw WyzieException.FileTooLarge()
        return body
    }
}

internal fun badStatus(statusCode: Int, body: String): WyzieException.BadStatus {
    val message = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.string("message")
    }.getOrNull()
    return WyzieException.BadStatus(statusCode, message)
}

private fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    get(key)?.jsonPrimitive?.intOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    get(key)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.stringArray(key: String): List<String>? =
    (get(key) as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }

private fun JsonObject.stringOrArray(key: String): List<String>? = when (val value = get(key)) {
    is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
    null -> null
    else -> value.jsonPrimitive.contentOrNull?.let(::listOf)
}

private fun String.safeFilePart(): String =
    lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

private fun String.percentEncoded(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

private const val BASE_URL = "https://sub.wyzie.io"
internal const val MAX_SUBTITLE_BYTES = 5 * 1024 * 1024
