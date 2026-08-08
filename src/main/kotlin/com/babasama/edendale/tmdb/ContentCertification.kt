package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * PG / PG-13 audience verification for the Young Audience filter. List
 * responses do not carry certifications, so the lookup is deliberately kept
 * separate from browse and search; a title is verified only once it enters a
 * surface. All parsing here is pure so the hermetic tests in `src/test` can
 * reach it without a network — parity with Apple's `YoungAudienceFilter`.
 */

/** The outcome of one certification lookup for a title. */
sealed interface ContentCertificationLookup {
    /** A certification string was found in the requested region. */
    data class Found(val certification: String) : ContentCertificationLookup

    /** The region has no certification for this title (fails closed). */
    data object Unrated : ContentCertificationLookup

    /** A network/auth failure that may recover on a later attempt. */
    data object Unavailable : ContentCertificationLookup
}

/**
 * Resolves a title's certification for a region and coalesces overlapping
 * callers. `contextIdentifier` is the region the answers belong to; it changes
 * when the device region changes, which invalidates any cached decisions.
 */
interface ContentCertificationProvider {
    val contextIdentifier: String
    suspend fun certification(ref: MediaRef): ContentCertificationLookup
}

object YoungAudienceCertificationPolicy {
    /**
     * PG and PG-13 are accepted exactly after normalizing punctuation and
     * spacing. Television services use TV-PG / TV-14 in regions such as the
     * United States, while regions such as Singapore use PG / PG13 for both.
     */
    fun allows(certification: String, mediaType: MediaType): Boolean {
        val normalized = certification.uppercase().filter { it.isLetterOrDigit() }
        if (normalized == "PG" || normalized == "PG13") return true
        return mediaType == MediaType.TV && (normalized == "TVPG" || normalized == "TV14")
    }
}

// MARK: - Movie release-date certifications

/**
 * Best certification for [regionCode] from a `/movie/{id}/release_dates`
 * payload. Theatrical releases outrank digital, physical, TV and premieres;
 * when the best tier carries two different certifications (separate cuts) the
 * lookup is abandoned rather than guessing the more permissive one.
 */
fun parseMovieCertification(payload: JsonObject, regionCode: String): String? {
    val region = payload.arrayOf("results")
        .mapNotNull { it as? JsonObject }
        .firstOrNull {
            it.stringOf("iso_3166_1").equals(regionCode, ignoreCase = true)
        } ?: return null

    val rated = region.arrayOf("release_dates")
        .mapNotNull { it as? JsonObject }
        .filter { !it.stringOf("certification").isNullOrBlank() }
    if (rated.isEmpty()) return null

    val bestPriority = rated.minOf { releaseTypePriority(it.intOf("type")) }
    val preferred = rated.filter { releaseTypePriority(it.intOf("type")) == bestPriority }

    val distinct = preferred
        .mapNotNull { it.stringOf("certification") }
        .map { cert -> cert.uppercase().filter { it.isLetterOrDigit() } }
        .toSet()
    if (distinct.size != 1) return null

    return preferred
        .sortedBy { it.stringOf("release_date") ?: "9999" }
        .firstOrNull()
        ?.stringOf("certification")
        ?.trim()
}

/**
 * TMDB release-type ranking, lowest wins: 3 Theatrical, 2 Theatrical
 * (limited), 4 Digital, 5 Physical, 6 TV, 1 Premiere. Unknown types sort last.
 */
private fun releaseTypePriority(type: Int?): Int = when (type) {
    3 -> 0
    2 -> 1
    4 -> 2
    5 -> 3
    6 -> 4
    1 -> 5
    else -> Int.MAX_VALUE
}

// MARK: - TV content ratings

/**
 * The rating for [regionCode] from a `/tv/{id}/content_ratings` payload, or
 * null when the region carries no non-blank rating.
 */
fun parseTvCertification(payload: JsonObject, regionCode: String): String? =
    payload.arrayOf("results")
        .mapNotNull { it as? JsonObject }
        .firstOrNull {
            it.stringOf("iso_3166_1").equals(regionCode, ignoreCase = true) &&
                !it.stringOf("rating").isNullOrBlank()
        }
        ?.stringOf("rating")
        ?.trim()

private fun JsonObject.stringOf(key: String): String? =
    (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun JsonObject.intOf(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull

private fun JsonObject.arrayOf(key: String): JsonArray =
    (get(key) as? JsonArray) ?: JsonArray(emptyList())
