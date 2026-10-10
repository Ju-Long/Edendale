package com.babasama.edendale.introdb

import kotlin.math.roundToLong

/**
 * Category of a community-provided timestamp segment.
 */
enum class SegmentKind {
    INTRO,
    RECAP,
    CREDITS;

    val title: String get() = when (this) {
        INTRO -> "Skip Intro"
        RECAP -> "Skip Recap"
        CREDITS -> "Skip Credits"
    }
}

/**
 * A time range within a media item representing an intro, recap, or credits window.
 */
data class PlaybackSegment(
    val kind: SegmentKind,
    val startMs: Long,
    val endMs: Long,
    val reachesEnd: Boolean,
) {
    val start: Double get() = startMs.toDouble() / 1000.0
    val end: Double get() = endMs.toDouble() / 1000.0

    fun contains(timeMs: Long): Boolean = timeMs in startMs until endMs
    fun contains(timeSeconds: Double): Boolean = contains((timeSeconds * 1000.0).roundToLong())
}

/**
 * Only public metadata identifiers leave the device. Episode IDs are always
 * the parent show's TMDB ID plus TMDB season/episode numbering.
 */
data class IntroDbMedia(
    val tmdbId: Int,
    val season: Int? = null,
    val episode: Int? = null,
) {
    init {
        require(tmdbId in 1..10_000_000) { "TMDB ID must be between 1 and 10,000,000" }
        if (season != null || episode != null) {
            require(season != null && episode != null && season > 0 && episode > 0) {
                "Season and episode must both be present and positive"
            }
        }
    }

    val type: String get() = if (season == null) "movie" else "tv"

    companion object {
        fun create(tmdbId: Int?, season: Int? = null, episode: Int? = null): IntroDbMedia? {
            if (tmdbId == null || tmdbId !in 1..10_000_000) return null
            if (season == null && episode == null) return IntroDbMedia(tmdbId, null, null)
            if (season != null && episode != null && season > 0 && episode > 0) {
                return IntroDbMedia(tmdbId, season, episode)
            }
            return null
        }
    }
}

/**
 * An HTTP request for TheIntroDB timestamp metadata.
 */
data class IntroDbRequest(
    val media: IntroDbMedia,
    val durationMs: Long,
) {
    init {
        require(durationMs in 1..21_600_000L) {
            "Duration must be between 1 ms and 21,600,000 ms (6 hours)"
        }
    }

    val duration: Double get() = durationMs.toDouble() / 1000.0

    val urlString: String
        get() {
            val base = "https://api.theintrodb.org/v3/media?tmdb_id=${media.tmdbId}"
            val ep = if (media.season != null && media.episode != null) {
                "&season=${media.season}&episode=${media.episode}"
            } else ""
            return "$base$ep&duration_ms=$durationMs"
        }

    companion object {
        fun create(media: IntroDbMedia?, durationSeconds: Double?): IntroDbRequest? {
            if (media == null || durationSeconds == null || !durationSeconds.isFinite()) return null
            if (durationSeconds <= 0.0 || durationSeconds > 21_600.0) return null
            val durationMs = (durationSeconds * 1000.0).roundToLong()
            if (durationMs <= 0 || durationMs > 21_600_000L) return null
            return IntroDbRequest(media, durationMs)
        }

        fun createFromMillis(media: IntroDbMedia?, durationMs: Long?): IntroDbRequest? {
            if (media == null || durationMs == null || durationMs <= 0 || durationMs > 21_600_000L) return null
            return IntroDbRequest(media, durationMs)
        }
    }
}

sealed class IntroDbException(message: String) : Exception(message) {
    class InvalidResponse : IntroDbException("Invalid response from TheIntroDB")
    class BadStatus(val statusCode: Int) : IntroDbException("TheIntroDB returned HTTP $statusCode")
    class RateLimited : IntroDbException("TheIntroDB rate limit active")
}
