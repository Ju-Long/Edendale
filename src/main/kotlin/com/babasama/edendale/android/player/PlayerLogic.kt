package com.babasama.edendale.android.player

import com.babasama.edendale.domain.WatchProgress
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class WyzieLookup(
    val id: String,
    val season: Int?,
    val episode: Int?,
)

/**
 * Derives Wyzie's lookup identity without ever substituting an episode's own
 * TMDB id for its series id.
 */
internal fun subtitleLookup(
    tmdbId: Int?,
    isEpisode: Boolean,
    showTmdbId: Int?,
    season: Int?,
    episode: Int?,
): WyzieLookup? = if (isEpisode) {
    showTmdbId?.takeIf { it > 0 }?.let { WyzieLookup(it.toString(), season, episode) }
} else {
    tmdbId?.takeIf { it > 0 }?.let { WyzieLookup(it.toString(), null, null) }
}

/**
 * Pure playback rules — speed stepping, time formatting,
 * relative-seek clamping and hold-drag scrub math. Deliberately free of
 * Android and Media3 imports so the hermetic JVM suite can exercise it
 * directly, mirroring Apple's `PlayerLogic`.
 *
 * Times are milliseconds throughout, matching ExoPlayer. A duration of zero
 * or less stands for "not known yet" (`C.TIME_UNSET` normalized by the
 * caller), so every rule that needs a duration returns a safe fallback there
 * rather than dividing by it.
 */
object PlayerLogic {

    // ------------------------------------------------------------------
    // Chrome timing
    // ------------------------------------------------------------------

    /** Seconds of inactivity before the controls hide. */
    const val AUTO_HIDE_MILLIS: Long = 5_000

    /** Non-sticky HUD pills fade out this long after their last update. */
    const val HUD_DISMISS_MILLIS: Long = 1_000

    /**
     * Pause in remote input after which an accumulated D-pad seek preview is
     * committed to the player.
     */
    const val REMOTE_SEEK_COMMIT_MILLIS: Long = 800

    /** How long the lightweight TV timeline lingers after its last activity. */
    const val TIMELINE_HIDE_MILLIS: Long = 4_000

    // ------------------------------------------------------------------
    // Normalized levels
    // ------------------------------------------------------------------

    /** Volume and brightness move in five-percent steps inside 0…1. */
    const val LEVEL_STEP: Float = 0.05f

    /**
     * Stepping runs through integer hundredths so repeated adjustments stay
     * on the grid instead of accumulating float drift.
     */
    fun adjustedLevel(level: Float, delta: Float): Float {
        val hundredths = ((level + delta).toDouble() * 100).roundToLong()
        return (hundredths / 100.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Where a vertical swipe of [dy] pixels (down is positive) takes a level
     * that started at [baseline]: a swipe [travel] pixels long sweeps the
     * whole 0…1 range, up raises. Degenerate input leaves the baseline.
     */
    fun swipeLevel(baseline: Float, dy: Float, travel: Float): Float {
        val start = if (baseline.isNaN()) 0f else baseline.coerceIn(0f, 1f)
        if (!(travel > 0f) || !travel.isFinite() || !dy.isFinite()) return start
        return (start - dy / travel).coerceIn(0f, 1f)
    }

    /** A 0…1 level as a whole percentage in [locale]'s style ("50%", "50 %", "%50"), for TalkBack. */
    fun levelLabel(level: Float, locale: Locale): String {
        val clamped = if (level.isNaN()) 0.0 else level.coerceIn(0f, 1f).toDouble()
        return NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(clamped)
    }

    // ------------------------------------------------------------------
    // Playback speed
    // ------------------------------------------------------------------

    const val RATE_STEP: Float = 0.05f
    const val MIN_RATE: Float = 0.25f
    const val MAX_RATE: Float = 3.0f

    /** Snaps a rate onto the 0.05 grid and clamps it to the supported range. */
    fun normalizedRate(rate: Float): Float {
        val hundredths = (rate.toDouble() * 100).roundToLong()
        val snapped = (hundredths / 5.0).roundToLong() * 5 / 100.0
        return snapped.toFloat().coerceIn(MIN_RATE, MAX_RATE)
    }

    fun incrementedRate(rate: Float): Float = normalizedRate(rate + RATE_STEP)

    fun decrementedRate(rate: Float): Float = normalizedRate(rate - RATE_STEP)

    /**
     * Display string for a rate, e.g. "1.05×". Formatted against a fixed
     * locale like Apple's `String(format:)`, so the grid reads the same in
     * every language and the JVM tests don't depend on the default locale.
     */
    fun rateLabel(rate: Float): String = String.format(Locale.ROOT, "%.2f×", rate)

    // ------------------------------------------------------------------
    // Press-and-hold speed
    // ------------------------------------------------------------------

    /**
     * Determines which side of the surface a touch press-and-hold started in.
     * The left half engages the left hold rate, and the right half (including
     * the exact midpoint) engages the right hold rate.
     */
    fun holdSide(touchX: Float, width: Float): HoldSide =
        if (touchX < width / 2f) HoldSide.LEFT else HoldSide.RIGHT

    /**
     * Which hold rate a press starting at [touchX] on a [width]-wide surface implies.
     */
    fun holdRate(
        touchX: Float,
        width: Float,
        leftRate: Float = PlayerPreferencesRules.DEFAULT_HOLD_LEFT_RATE,
        rightRate: Float = PlayerPreferencesRules.DEFAULT_HOLD_RIGHT_RATE,
    ): Float = when (holdSide(touchX, width)) {
        HoldSide.LEFT -> leftRate
        HoldSide.RIGHT -> rightRate
    }

    // ------------------------------------------------------------------
    // Time formatting
    // ------------------------------------------------------------------

    /** "1:23:45" above an hour, "23:45" below. */
    fun timestamp(millis: Long): String {
        if (millis <= 0) return "0:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** Signed offset for the scrub readout, e.g. "+1:30" or "−0:10". */
    fun signedTimestamp(millis: Long): String =
        (if (millis < 0) "−" else "+") + timestamp(abs(millis))

    // ------------------------------------------------------------------
    // End of media
    // ------------------------------------------------------------------

    /** Fraction of a title watched before it counts as finished. */
    const val COMPLETE_FRACTION: Double = 0.95

    /**
     * Whether a stop at [positionMillis] counts as reaching the end rather
     * than a user-initiated stop. The 95% threshold marks titles stopped near
     * the end or during end credits as completed. The two-second tolerance
     * catches short clips, where 95% sits further in than two seconds and a
     * genuine finish would otherwise miss the completion threshold.
     */
    fun isNaturalEnd(positionMillis: Long, durationMillis: Long): Boolean {
        if (durationMillis <= 0) return false
        return positionMillis >= durationMillis * COMPLETE_FRACTION ||
            positionMillis >= durationMillis - 2_000
    }

    // ------------------------------------------------------------------
    // Another copy of the item playing (27.1)
    // ------------------------------------------------------------------

    /**
     * Where a Play From pick in the episodes panel starts. Another copy of
     * the episode playing ([sameEpisode]) carries on from [positionMillis]
     * once the playing copy has [started] — reached READY with its stored
     * resume point applied. Before that its position is still 0, so the pick
     * gets null and resumes from the stored progress, which the copies share
     * by TMDB id: picking the LAN link while the Tailscale one still buffers
     * mustn't restart the episode. A pick of another episode starts the usual
     * way (null).
     */
    fun copySwitchStartMillis(sameEpisode: Boolean, started: Boolean, positionMillis: Long): Long? =
        if (sameEpisode && started) positionMillis.coerceAtLeast(0L) else null

    // ------------------------------------------------------------------
    // Resuming
    // ------------------------------------------------------------------

    /**
     * Fractions of a title worth resuming from: before 2% there is nothing
     * to skip, and from the last few percent on, starting over is what a
     * viewer wants.
     */
    val RESUME_WINDOW: ClosedFloatingPointRange<Double> = 0.02..0.94

    /**
     * Where the player resumes a title, as a fraction of its length, or null
     * when it starts from the top: a finished title, or a stop outside
     * [RESUME_WINDOW]. The detail page labels Play as Resume by the same
     * rule, so the label and the player agree.
     */
    fun resumeFraction(progress: WatchProgress?): Double? =
        progress?.takeIf { !it.isCompleted }?.normalizedPosition?.takeIf { it in RESUME_WINDOW }

    // ------------------------------------------------------------------
    // Seeking
    // ------------------------------------------------------------------

    /**
     * A relative seek clamped to the timeline. An unknown duration clamps at
     * the start only, so seeking forward still works while the duration is
     * still being parsed.
     */
    fun seekTargetMillis(positionMillis: Long, offsetMillis: Long, durationMillis: Long): Long {
        val target = (positionMillis + offsetMillis).coerceAtLeast(0)
        return if (durationMillis > 0) target.coerceAtMost(durationMillis) else target
    }

    /** The same seek expressed as a normalized 0…1 timeline position. */
    fun seekPosition(positionMillis: Long, offsetMillis: Long, durationMillis: Long): Double {
        if (durationMillis <= 0) return 0.0
        return seekTargetMillis(positionMillis, offsetMillis, durationMillis).toDouble() / durationMillis
    }

    /** Position (0…1) of [positionMillis] within [durationMillis]. */
    fun progressFraction(positionMillis: Long, durationMillis: Long): Double {
        if (durationMillis <= 0) return 0.0
        return (positionMillis.toDouble() / durationMillis).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------
    // Hold-drag scrubbing
    // ------------------------------------------------------------------

    /** Milliseconds of media that one full-width hold-drag sweeps across. */
    const val SCRUB_WINDOW_MILLIS: Long = 300_000

    /**
     * Target position (0…1) for a hold-drag of [translation] pixels across a
     * surface [width] pixels wide, starting from [basePosition]. Degenerate
     * inputs leave the position untouched so a caller can apply this
     * unconditionally.
     */
    fun scrubTarget(
        basePosition: Double,
        translation: Float,
        width: Float,
        durationMillis: Long,
    ): Double {
        if (width <= 0f || durationMillis <= 0) return basePosition
        val offsetMillis = (translation / width) * SCRUB_WINDOW_MILLIS
        return (basePosition + offsetMillis / durationMillis).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------
    // Natural file ordering
    // ------------------------------------------------------------------

    /**
     * Case-insensitive comparison that reads digit runs as numbers, so
     * "Episode 2" sorts before "Episode 10" — SQLite's byte order and
     * Kotlin's default [String.compareTo] both get that wrong. Mirrors the
     * `localizedStandardCompare` sort Apple's sibling listing uses.
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val byValue = numA.compareTo(numB)
                if (byValue != 0) return byValue
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /**
     * [naturalCompare] made total: names it calls equal ("E01" and "E1", or
     * two cases of one name) fall back to plain order, so picking "the first
     * copy" gives the same answer on every scan.
     */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        naturalCompare(a, b).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
