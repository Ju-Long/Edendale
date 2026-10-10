package com.babasama.edendale.introdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JSON decoder for TheIntroDB responses.
 */
object IntroDbDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(rawJson: String, request: IntroDbRequest): List<PlaybackSegment> {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            throw IntroDbException.InvalidResponse()
        }

        val tmdbId = root["tmdb_id"]?.jsonPrimitive?.intOrNull ?: throw IntroDbException.InvalidResponse()
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: throw IntroDbException.InvalidResponse()
        val season = root["season"]?.jsonPrimitive?.intOrNull
        val episode = root["episode"]?.jsonPrimitive?.intOrNull

        if (tmdbId != request.media.tmdbId ||
            type != request.media.type ||
            season != request.media.season ||
            episode != request.media.episode
        ) {
            throw IntroDbException.InvalidResponse()
        }

        val candidates = mutableListOf<PlaybackSegment>()

        fun parseGroup(kind: SegmentKind, arrayElement: JsonElement?) {
            if (arrayElement == null) return
            val array = try {
                arrayElement.jsonArray
            } catch (_: Exception) {
                throw IntroDbException.InvalidResponse()
            }
            for (element in array) {
                val obj = try {
                    element.jsonObject
                } catch (_: Exception) {
                    throw IntroDbException.InvalidResponse()
                }
                val startMsPrimitive = obj["start_ms"]?.jsonPrimitive
                val endMsPrimitive = obj["end_ms"]?.jsonPrimitive

                val startMs = startMsPrimitive?.contentOrNull?.toLongOrNull()
                val endMs = endMsPrimitive?.contentOrNull?.toLongOrNull()

                // Both-null entries represent "no segment"
                if (startMs == null && endMs == null) continue

                val (resolvedStart, resolvedEnd, reachesEnd) = when (kind) {
                    SegmentKind.CREDITS -> {
                        // Credits need start > 0. A missing credits start must never become a full-file skip.
                        if (startMs == null || startMs <= 0) continue
                        val end = endMs ?: request.durationMs
                        val reaches = (end == request.durationMs)
                        Triple(startMs, end, reaches)
                    }
                    SegmentKind.INTRO, SegmentKind.RECAP -> {
                        // Intro and recap need an end; a null start means 0.
                        if (endMs == null) continue
                        val start = startMs ?: 0L
                        Triple(start, endMs, false)
                    }
                }

                // Keep only when 0 <= start < end <= duration
                if (resolvedStart in 0 until resolvedEnd && resolvedEnd <= request.durationMs) {
                    candidates.add(
                        PlaybackSegment(
                            kind = kind,
                            startMs = resolvedStart,
                            endMs = resolvedEnd,
                            reachesEnd = reachesEnd,
                        )
                    )
                }
            }
        }

        parseGroup(SegmentKind.INTRO, root["intro"])
        parseGroup(SegmentKind.RECAP, root["recap"])
        parseGroup(SegmentKind.CREDITS, root["credits"])

        // Deduplicate
        val unique = candidates.distinct()

        // Drop every segment that overlaps another (never decide which content to cut)
        val nonOverlapping = unique.filter { segment ->
            unique.none { other ->
                other != segment && max(segment.startMs, other.startMs) < min(segment.endMs, other.endMs)
            }
        }

        // Sort by start
        return nonOverlapping.sortedBy { it.startMs }
    }
}
