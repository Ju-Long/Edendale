package com.babasama.edendale.parental

import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.tmdb.ContentCertificationLookup
import com.babasama.edendale.tmdb.YoungAudienceCertificationPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The product rules of Section O, with no Android or network code: what a
 * ceiling allows, how TMDB's ordered lists are read, which region they come
 * from, how failed PINs back off, and how the preset and the ceiling combine
 * into the filter's decision.
 */
object ParentalRules {
    /** The region used when TMDB publishes no list for the device's (O-D5). */
    const val FALLBACK_REGION = "US"

    /** After this many failures the back-off starts (O-D3). */
    const val FREE_ATTEMPTS = 3

    private val BACKOFF_MILLIS = longArrayOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

    /** Labels that mean "no rating" wherever they appear; a title carrying one is restricted. */
    private val UNRATED_LABELS = setOf("NR", "NOTRATED", "UNRATED")

    /** Punctuation and spacing differ between sources (`PG-13`, `PG 13`, `PG13`); the preset normalises the same way. */
    fun normalize(certification: String): String = certification.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Whether [certification] sits at or under the ceiling for [mediaType]. A
     * type with no limit allows everything rated; otherwise the label is looked
     * up in that type's list for the ceiling's region, and an unknown label, an
     * unrated label, a missing list, or lists for another region fail closed.
     */
    fun allows(
        certification: String,
        mediaType: MediaType,
        ceiling: RatingCeiling,
        lists: CertificationLists?,
    ): Boolean {
        val limit = when (mediaType) {
            MediaType.MOVIE -> ceiling.movie
            MediaType.TV -> ceiling.tv
        } ?: return true
        if (lists == null || !lists.region.equals(ceiling.region, ignoreCase = true)) return false
        val list = when (mediaType) {
            MediaType.MOVIE -> lists.movie
            MediaType.TV -> lists.tv
        }
        val label = normalize(certification)
        if (label.isEmpty() || label in UNRATED_LABELS) return false
        val entry = list.firstOrNull { normalize(it.certification) == label } ?: return false
        val limitEntry = list.firstOrNull { normalize(it.certification) == normalize(limit) } ?: return false
        return entry.order <= limitEntry.order
    }

    /**
     * The filter's verdict for one title: the preset and the ceiling must both
     * allow it. Unrated titles are blocked while any rule is active (FEATURES
     * §4); a lookup that failed is marked unavailable so a later verification
     * can retry it.
     */
    fun decision(
        lookup: ContentCertificationLookup,
        mediaType: MediaType,
        policy: ParentalPolicy,
        lists: CertificationLists?,
    ): AudienceDecision {
        if (!policy.isActive) return AudienceDecision.ALLOWED
        return when (lookup) {
            ContentCertificationLookup.Unavailable -> AudienceDecision.UNAVAILABLE
            ContentCertificationLookup.Unrated -> AudienceDecision.BLOCKED
            is ContentCertificationLookup.Found -> {
                val presetAllows = !policy.youngAudienceFriendly ||
                    YoungAudienceCertificationPolicy.allows(lookup.certification, mediaType)
                val ceilingAllows = policy.ceiling == null || allows(lookup.certification, mediaType, policy.ceiling, lists)
                if (presetAllows && ceilingAllows) AudienceDecision.ALLOWED else AudienceDecision.BLOCKED
            }
        }
    }

    /** The regions a `/certification/{movie,tv}/list` payload publishes a list for, upper-cased. */
    fun availableRegions(payload: JsonObject): Set<String> =
        (payload["certifications"] as? JsonObject)?.keys?.map { it.uppercase() }?.toSet() ?: emptySet()

    /**
     * One region's ordered list from a `/certification/{movie,tv}/list`
     * payload, sorted by order, or null when the payload has none for it.
     * Entries without a label or an order are skipped rather than guessed.
     */
    fun parseCertificationList(payload: JsonObject, region: String): List<CertificationEntry>? {
        val certifications = payload["certifications"] as? JsonObject ?: return null
        val key = certifications.keys.firstOrNull { it.equals(region, ignoreCase = true) } ?: return null
        val entries = certifications[key] as? JsonArray ?: return null
        return entries
            .mapNotNull { it as? JsonObject }
            .mapNotNull { entry ->
                val label = (entry["certification"] as? JsonPrimitive)?.contentOrNull?.trim()
                val order = entry["order"]?.jsonPrimitive?.intOrNull
                if (label.isNullOrEmpty() || order == null) null else CertificationEntry(label, order)
            }
            .sortedBy { it.order }
    }

    /**
     * O-D5: the device region when TMDB publishes a list for it, otherwise
     * [FALLBACK_REGION], so the ceiling and the per-title lookups agree.
     */
    fun effectiveRegion(region: String, available: Collection<String>): String {
        val device = region.trim().uppercase()
        return if (device.isNotEmpty() && available.any { it.equals(device, ignoreCase = true) }) device else FALLBACK_REGION
    }

    /**
     * The region the per-title lookups use once lists are known: the lists'
     * own region, which already applied the fallback, so a title and the
     * ceiling are always read in the same vocabulary. Without lists the device
     * region is used, as the filter always has.
     */
    fun effectiveRegion(region: String, lists: CertificationLists?): String =
        lists?.region ?: region.trim().uppercase()

    /**
     * Both lists for the device region, or for [FALLBACK_REGION] when either
     * payload lacks it (O-D5: one region for both types, so a TV label is
     * never compared against another country's scale). Null when even the
     * fallback is missing, which leaves the previous lists in place.
     */
    fun parseCertificationLists(moviePayload: JsonObject, tvPayload: JsonObject, region: String): CertificationLists? {
        val available = availableRegions(moviePayload) intersect availableRegions(tvPayload)
        val effective = effectiveRegion(region, available)
        val movie = parseCertificationList(moviePayload, effective) ?: return null
        val tv = parseCertificationList(tvPayload, effective) ?: return null
        return CertificationLists(effective, movie, tv)
    }

    /**
     * O-D3: attempts 1–3 are free; the fourth failure waits 30 s, then 1, 5,
     * 15, and 60 minutes for every further failure.
     */
    fun retryDelayMillis(failures: Int): Long {
        if (failures <= FREE_ATTEMPTS) return 0L
        return BACKOFF_MILLIS[minOf(failures - FREE_ATTEMPTS - 1, BACKOFF_MILLIS.lastIndex)]
    }
}
