package com.babasama.edendale.android.player.video

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Motion Smoothing offered for the playing video (G.5.1): its frame rate,
 * doubled, and the display mode that shows the doubled rate evenly.
 */
data class MotionSmoothingOffer(val sourceFps: Float, val displayRate: Float) {
    /** One source frame, in microseconds. */
    val frameDurationUs: Long get() = (1_000_000.0 / sourceFps).roundToLong()

    /** The label's rates, "24 fps → 48 fps": rounded, so 23.976 fps reads as 24. */
    val sourceLabelFps: Int get() = sourceFps.roundToInt()
    val outputLabelFps: Int get() = (sourceFps * 2).roundToInt()
}

/** Why Motion Smoothing, switched on, isn't running right now (G.5). */
enum class MotionSmoothingPause {
    /** Playing faster than 1×. */
    SPEED,

    /** The governor turned it off: over budget, a moderate thermal status, or battery saver. */
    BUSY,
}

/**
 * When Motion Smoothing is offered (D12, decided by the owner on
 * 2026-11-12): phones and tablets only, never a TV; SDR only, since HDR and
 * Dolby Vision bypass every effect (F.1.4); a device that passed the
 * enhancement capability check (F.6.3); a source at 30 fps or less (Apple's
 * limit); and only when the display has a mode at a whole multiple of twice
 * the source rate, so the doubled rate is shown evenly: 24 fps needs 48, 96,
 * or 144 Hz, 25 fps 50 or 100 Hz, 30 fps 60 or 120 Hz. Pure.
 */
object MotionSmoothingRules {
    /** Faster sources are shown as they are (Apple's `FrameInterpolator.maxSourceFrameRate`). */
    const val MAX_SOURCE_FPS = 30f

    /** How far a rate may sit from an exact multiple: 59.94 Hz counts as 60, 47.952 fps as 48. */
    const val RATE_TOLERANCE = 0.01f

    /**
     * The offer for a source at [sourceFps] on a display with [displayRates]
     * (the refresh rates of the modes at the current resolution), or null
     * when Motion Smoothing isn't offered.
     */
    fun offer(
        sourceFps: Float?,
        displayRates: List<Float>,
        isTelevision: Boolean,
        isHdr: Boolean,
        capable: Boolean?,
    ): MotionSmoothingOffer? {
        if (isTelevision || isHdr || capable != true) return null
        val fps = sourceFps?.takeIf { it.isFinite() && it > 0f } ?: return null
        if (fps > MAX_SOURCE_FPS * (1 + RATE_TOLERANCE)) return null
        val doubled = fps * 2
        // The lowest such mode draws the least power.
        val rate = displayRates.filter { it.isFinite() && it > 0f && isWholeMultiple(it, doubled) }.minOrNull() ?: return null
        return MotionSmoothingOffer(fps, rate)
    }

    /** True when [rate] is k × [target] for a whole k ≥ 1, within [RATE_TOLERANCE]. */
    fun isWholeMultiple(rate: Float, target: Float): Boolean {
        if (!(rate > 0f && target > 0f)) return false
        val k = (rate / target).roundToInt()
        return k >= 1 && abs(rate / (k * target) - 1f) <= RATE_TOLERANCE
    }

    /**
     * Only at 1× or slower (Findings — G.1): above it the doubled rate outruns
     * the display, 48 fps at 1.5× being 72 frames a second.
     */
    fun runsAtSpeed(speed: Float): Boolean = speed.isFinite() && speed > 0f && speed <= 1f + 1e-3f
}

/**
 * A video's frame rate from the timestamps of its frames as they're shown,
 * for containers that don't state one: Media3 1.9.0 reads none from
 * Matroska. Feed it real frames only, never Motion Smoothing's midpoints.
 * Timestamps in Matroska are often whole milliseconds, so 30 fps arrives as
 * gaps of 33 and 34 ms: the rate comes from the mean of the gaps near the
 * median one, which keeps the quantisation out and drops a skipped frame or
 * a stall. A seek or a jump back starts again; a redraw (the same
 * timestamp) is ignored. Pure.
 */
class FrameRateEstimator(private val gapsNeeded: Int = 24) {
    private var lastUs: Long? = null
    private val gaps = ArrayDeque<Long>()

    fun add(timeUs: Long) {
        val last = lastUs
        if (last != null && timeUs == last) return
        lastUs = timeUs
        if (last == null) return
        val gap = timeUs - last
        if (gap < 0 || gap > MAX_GAP_US) {
            gaps.clear()
            return
        }
        gaps.addLast(gap)
        while (gaps.size > gapsNeeded) gaps.removeFirst()
    }

    /** The rate once enough neighbouring frames are in, else null. */
    val fps: Float?
        get() {
            if (gaps.size < gapsNeeded) return null
            val median = gaps.sorted()[gaps.size / 2]
            val typical = gaps.filter { it * 4 >= median * 3 && it * 4 <= median * 5 }
            if (typical.size * 2 < gaps.size) return null
            return (1_000_000.0 / typical.average()).toFloat()
        }

    fun reset() {
        lastUs = null
        gaps.clear()
    }

    private companion object {
        /** More than this between two frames is a seek or a stall, not the frame spacing (5 fps). */
        const val MAX_GAP_US = 200_000L
    }
}

/**
 * What the GL thread needs to run Motion Smoothing, set by the player (G.4,
 * G.5): whether to make midpoints now, the source's frame spacing, and the
 * governor's half-resolution step.
 */
data class MotionSmoothingRun(val running: Boolean, val frameDurationUs: Long, val halfResolution: Boolean) {
    companion object {
        val OFF = MotionSmoothingRun(running = false, frameDurationUs = 0, halfResolution = false)
    }
}

/**
 * When Motion Smoothing makes a frame (G.4.1). This is Apple's
 * `FrameInterpolationScheduler` reduced as Findings — G.1 describes: Media3
 * hands the effect each frame ahead of its release time, so the effect
 * outputs the midpoint and then frame N, each with its own timestamp, and
 * ExoPlayer paces them; Apple's one-refresh hold of frame N isn't needed.
 *
 * What stays: only direct neighbours are blended, frames less than 1.5
 * source frames apart; nothing is made for the first frame, after a seek
 * (a flush) or the end of a stream, after a size or search-resolution
 * change, across a dropped frame or a stall, or for a redraw of the same
 * frame while paused (G.4.2). The history moves on only through [commit],
 * after the midpoint is drawn, never before. Pure.
 */
class MotionSmoothingSchedule {
    private var lastTimeUs: Long? = null
    private var lastShape: Any? = null

    /**
     * The timestamp of the midpoint to make before the frame at [timeUs], or
     * null when there's none. [shape] is anything that must match between
     * neighbours (the frame and search sizes). Doesn't change the history.
     */
    fun midpointBefore(timeUs: Long, shape: Any, frameDurationUs: Long): Long? {
        val last = lastTimeUs ?: return null
        if (shape != lastShape || frameDurationUs <= 0) return null
        val gap = timeUs - last
        if (gap <= 0 || gap * 2 >= frameDurationUs * 3) return null
        return last + gap / 2
    }

    /** The frame at [timeUs] is drawn: it becomes the one the next frame blends with. */
    fun commit(timeUs: Long, shape: Any) {
        lastTimeUs = timeUs
        lastShape = shape
    }

    /** Forgets the history: a flush, the end of a stream, or Motion Smoothing pausing. */
    fun reset() {
        lastTimeUs = null
        lastShape = null
    }
}
