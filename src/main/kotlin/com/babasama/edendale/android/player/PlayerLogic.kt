package com.babasama.edendale.android.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure playback rules — speed stepping, time formatting, auto-skip windows,
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
     * Rates a touch press-and-hold engages: the left half of the surface
     * slows down, the right half speeds up, reverting the moment the finger
     * lifts. These match the iOS gesture rather than tvOS's touch-surface
     * hold — an Android TV remote has no absolute touch position, so the
     * D-pad drives accumulated seeking instead (see [seekTargetMillis]).
     */
    const val HOLD_SLOW_RATE: Float = 0.5f
    const val HOLD_FAST_RATE: Float = 1.5f

    /** Which hold rate a press starting at [touchX] on a [width]-wide surface implies. */
    fun holdRate(touchX: Float, width: Float): Float =
        if (touchX < width / 2f) HOLD_SLOW_RATE else HOLD_FAST_RATE

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
    // Auto-skip windows
    // ------------------------------------------------------------------

    /** How far "skip recap" jumps from the start of an episode. */
    const val RECAP_LENGTH_MILLIS: Long = 90_000

    /** How close to the end "skip credits" takes effect. */
    const val CREDITS_LENGTH_MILLIS: Long = 180_000

    /**
     * Media shorter than this never auto-skips — the windows would eat most
     * of the runtime.
     */
    const val MINIMUM_SKIPPABLE_MILLIS: Long = 600_000

    /**
     * Where playback should jump when skip-recap applies at start, or null
     * when the media is too short (or its duration isn't known yet).
     */
    fun recapSkipTargetMillis(durationMillis: Long): Long? =
        if (durationMillis < MINIMUM_SKIPPABLE_MILLIS) null else RECAP_LENGTH_MILLIS

    /**
     * The position after which skip-credits should end playback, or null
     * when the media is too short to have a credits window.
     */
    fun creditsStartMillis(durationMillis: Long): Long? =
        if (durationMillis < MINIMUM_SKIPPABLE_MILLIS) null else durationMillis - CREDITS_LENGTH_MILLIS

    // ------------------------------------------------------------------
    // End of media
    // ------------------------------------------------------------------

    /** Fraction of a title watched before it counts as finished. */
    const val COMPLETE_FRACTION: Double = 0.95

    /**
     * Whether a stop at [positionMillis] counts as reaching the end rather
     * than a user-initiated stop. The 95% arm lets a skip-credits stop count
     * as finished — that preference ends playback a full
     * [CREDITS_LENGTH_MILLIS] before the tail, well outside a tight tail
     * window. The two-second arm still catches short clips, where 95% sits
     * further in than two seconds and a genuine finish would miss it.
     */
    fun isNaturalEnd(positionMillis: Long, durationMillis: Long): Boolean {
        if (durationMillis <= 0) return false
        return positionMillis >= durationMillis * COMPLETE_FRACTION ||
            positionMillis >= durationMillis - 2_000
    }

    // ------------------------------------------------------------------
    // Seeking
    // ------------------------------------------------------------------

    /** Step for the ±buttons, the D-pad, and the media transport keys. */
    const val SEEK_STEP_MILLIS: Long = 10_000

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
}
