package com.babasama.edendale.parental

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * How the store keeps the policy and the cached lists: one JSON string each,
 * so a change is one write. The Young Audience preset is not in the policy
 * text; it stays in `edendale_audience` (O-D7) and is merged in on read.
 */
object ParentalCodec {
    private const val VERSION = 1

    /** The policy without the preset, as `edendale_parental` holds it. */
    fun encodePolicy(policy: ParentalPolicy): String = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        policy.ceiling?.let { ceiling ->
            put(
                "ceiling",
                buildJsonObject {
                    put("region", JsonPrimitive(ceiling.region))
                    ceiling.movie?.let { put("movie", JsonPrimitive(it)) }
                    ceiling.tv?.let { put("tv", JsonPrimitive(it)) }
                },
            )
        }
        put("hide", JsonPrimitive(policy.hideRestrictedTitles))
        put("blockTrailers", JsonPrimitive(policy.blockTrailersAndLinks))
        put("lockSettings", JsonPrimitive(policy.lockSettings))
        put("lockLibrary", JsonPrimitive(policy.lockLibraryEdits))
    }.toString()

    /** The stored policy with the live preset merged in; absent or unreadable text is the default policy. */
    fun decodePolicy(text: String?, youngAudienceFriendly: Boolean): ParentalPolicy {
        val fields = parse(text) ?: return ParentalPolicy(youngAudienceFriendly = youngAudienceFriendly)
        val ceiling = (fields["ceiling"] as? JsonObject)?.let { c ->
            val region = c.string("region") ?: return@let null
            RatingCeiling(region, c.string("movie"), c.string("tv"))
        }
        return ParentalPolicy(
            youngAudienceFriendly = youngAudienceFriendly,
            ceiling = ceiling,
            hideRestrictedTitles = fields.boolean("hide") ?: true,
            blockTrailersAndLinks = fields.boolean("blockTrailers") ?: false,
            lockSettings = fields.boolean("lockSettings") ?: false,
            lockLibraryEdits = fields.boolean("lockLibrary") ?: false,
        )
    }

    fun encodeLists(cached: CachedCertificationLists): String = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        put("region", JsonPrimitive(cached.lists.region))
        put("deviceRegion", JsonPrimitive(cached.deviceRegion))
        put("fetchedAt", JsonPrimitive(cached.fetchedAt))
        put("movie", entries(cached.lists.movie))
        put("tv", entries(cached.lists.tv))
    }.toString()

    fun decodeLists(text: String?): CachedCertificationLists? {
        val fields = parse(text) ?: return null
        val region = fields.string("region") ?: return null
        val movie = entries(fields["movie"]) ?: return null
        val tv = entries(fields["tv"]) ?: return null
        return CachedCertificationLists(
            lists = CertificationLists(region, movie, tv),
            deviceRegion = fields.string("deviceRegion") ?: region,
            fetchedAt = fields["fetchedAt"]?.jsonPrimitive?.longOrNull ?: return null,
        )
    }

    private fun entries(list: List<CertificationEntry>): JsonArray = buildJsonArray {
        list.forEach { entry ->
            add(
                buildJsonObject {
                    put("c", JsonPrimitive(entry.certification))
                    put("o", JsonPrimitive(entry.order))
                },
            )
        }
    }

    private fun entries(element: kotlinx.serialization.json.JsonElement?): List<CertificationEntry>? {
        val array = element as? JsonArray ?: return null
        return array.map { item ->
            val fields = item as? JsonObject ?: return null
            CertificationEntry(fields.string("c") ?: return null, fields["o"]?.jsonPrimitive?.intOrNull ?: return null)
        }
    }

    private fun parse(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
}
