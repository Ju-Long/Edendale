package com.babasama.edendale.android.player.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.min
import kotlin.math.roundToInt

/** Apple's enhancement presets (F.7). */
enum class EnhancementPreset(val raw: String) {
    /** Passthrough. */
    OFF("off"),

    /** Sharpening at the source size, no upscale. */
    SHARPEN_ONLY("sharpenOnly"),

    /** Upscale and sharpen. */
    BALANCED("balanced"),

    /** Upscale, sharpen, and temporal denoise. */
    HIGH_QUALITY("quality"),
    ;

    val upscales: Boolean get() = this == BALANCED || this == HIGH_QUALITY

    companion object {
        /** The preset stored as [raw], or null for anything else. */
        fun fromRaw(raw: String?): EnhancementPreset? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Enhancement as the viewer set it. Unlike Apple, which starts every playback
 * at Balanced (D11), Android keeps the viewer's choice on the device as one
 * JSON object under `video.enhancement` beside the picture adjustments, so it
 * survives the app closing. Show Original is a momentary comparison and is
 * never saved. Sharpness and denoise range 0–1 in steps of 0.05. Motion
 * Smoothing (G.5) is off until the viewer turns it on, and is then saved
 * with the rest (the owner's choice, 2026-11-12; Apple starts each playback
 * with it off).
 */
data class EnhancementSettings(
    val preset: EnhancementPreset = EnhancementPreset.BALANCED,
    val sharpness: Float = 0.5f,
    val denoise: Float = 0.5f,
    val showOriginal: Boolean = false,
    val motionSmoothing: Boolean = false,
) {
    fun withSharpness(value: Float) = copy(sharpness = normalizeUnit(value))
    fun withDenoise(value: Float) = copy(denoise = normalizeUnit(value))

    /** The stored form: the preset, both sliders, and Motion Smoothing; Show Original stays out. */
    fun toJson(): String = JsonObject(
        mapOf(
            KEY_PRESET to JsonPrimitive(preset.raw),
            KEY_SHARPNESS to JsonPrimitive(normalizeUnit(sharpness)),
            KEY_DENOISE to JsonPrimitive(normalizeUnit(denoise)),
            KEY_MOTION_SMOOTHING to JsonPrimitive(motionSmoothing),
        ),
    ).toString()

    companion object {
        const val STEP = 0.05f

        private const val KEY_PRESET = "preset"
        private const val KEY_SHARPNESS = "sharpness"
        private const val KEY_DENOISE = "denoise"
        private const val KEY_MOTION_SMOOTHING = "motionSmoothing"

        fun normalizeUnit(value: Float): Float {
            if (!value.isFinite()) return 0.5f
            return ((value.coerceIn(0f, 1f) / STEP).roundToInt() * STEP).coerceIn(0f, 1f)
        }

        /**
         * The stored choice, or null when there is none or it can't be read: a
         * missing or unknown preset never turns into the class default
         * (Balanced), so an unreadable value falls back to the device's own
         * default in [initial]. A missing or broken slider reads as 0.5, and
         * anything but `true` for Motion Smoothing as off (what a choice saved
         * before G.5 holds).
         */
        fun fromJson(raw: String?): EnhancementSettings? {
            val obj = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
            val preset = obj[KEY_PRESET]
                ?.let { runCatching { it.jsonPrimitive.takeIf { primitive -> primitive.isString }?.contentOrNull }.getOrNull() }
                ?.let(EnhancementPreset::fromRaw)
                ?: return null
            fun slider(key: String): Float =
                normalizeUnit(obj[key]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }?.toFloat() ?: Float.NaN)
            val motionSmoothing = obj[KEY_MOTION_SMOOTHING]
                ?.let { runCatching { it.jsonPrimitive.takeIf { primitive -> !primitive.isString }?.booleanOrNull }.getOrNull() }
                ?: false
            return EnhancementSettings(
                preset = preset,
                sharpness = slider(KEY_SHARPNESS),
                denoise = slider(KEY_DENOISE),
                motionSmoothing = motionSmoothing,
            )
        }

        /**
         * What a player starts with: the viewer's stored choice on every kind of
         * device (the F.6 governor still drops work that runs over budget, and HDR
         * and Dolby Vision still bypass it), else Off on TV (until F.1.6 passes on
         * a real set) and wherever the capability check hasn't passed (F.6.3),
         * else Balanced.
         */
        fun initial(stored: EnhancementSettings?, isTelevision: Boolean, capable: Boolean?): EnhancementSettings =
            stored?.copy(showOriginal = false)
                ?: EnhancementSettings(
                    preset = if (isTelevision || capable != true) EnhancementPreset.OFF else EnhancementPreset.BALANCED,
                )
    }
}

/** A width and height in pixels. */
data class PixelSize(val width: Int, val height: Int) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    override fun toString(): String = "${width}×$height"
}

/** The upscaler's target size (F.3; Apple's `SpatialUpscaler.targetResolution`). */
object UpscaleRules {

    /**
     * - A source at or above the display in both dimensions isn't upscaled.
     * - Below 1080p (width under 1920 and height under 1080), it scales to fit
     *   min(1920, display width) × min(1080, display height).
     * - A 1080p-tier source scales to the display when the display is 4K.
     * Scaled sizes round to even numbers. [display] is the video's on-screen
     * viewport in physical pixels.
     */
    fun targetResolution(source: PixelSize, display: PixelSize, override: PixelSize? = null): PixelSize {
        if (override != null && !override.isEmpty) return override
        if (source.isEmpty) return source
        val isBelow1080p = source.width < 1920 && source.height < 1080
        val isDisplay4K = display.width >= 3840 || display.height >= 2160
        val isAtOrAboveDisplay = source.width >= display.width && source.height >= display.height
        if (isAtOrAboveDisplay) return source
        if (isBelow1080p) {
            val boundingW = min(1920.0, display.width.toDouble())
            val boundingH = min(1080.0, display.height.toDouble())
            val scale = min(boundingW / source.width, boundingH / source.height)
            return if (scale > 1.0) makeEven(source.width * scale, source.height * scale) else source
        }
        if (isDisplay4K) {
            val scale = min(display.width.toDouble() / source.width, display.height.toDouble() / source.height)
            if (scale > 1.0) return makeEven(source.width * scale, source.height * scale)
        }
        return source
    }

    /** Rounds, then up to the next even number: (round(x) + 1) & ~1. */
    private fun makeEven(width: Double, height: Double) =
        PixelSize((Math.round(width).toInt() + 1) and 1.inv(), (Math.round(height).toInt() + 1) and 1.inv())

    /** Apple's label: "1280×720 → 1920×1080", or just the source when nothing is upscaled. */
    fun label(source: PixelSize, target: PixelSize): String =
        if (source == target || target.isEmpty) source.toString() else "$source → $target"
}

/** The enhancement passes that can run, in Apple's order: upscale, color, sharpen, denoise. */
data class EnhancementStages(val upscale: Boolean, val sharpen: Boolean, val denoise: Boolean) {
    companion object {
        fun forPreset(preset: EnhancementPreset, sharpness: Float, denoise: Float) = EnhancementStages(
            upscale = preset.upscales,
            sharpen = preset != EnhancementPreset.OFF && sharpness > 0f,
            denoise = preset == EnhancementPreset.HIGH_QUALITY && denoise > 0f,
        )
    }
}

/**
 * Keeps the enhancement passes inside their GPU budget (F.6; Apple §E.6):
 * under 8 ms per frame for every pass together. Over budget it drops denoise
 * first, then the upscale; sharpening at the source size always stays. A
 * moderate thermal status or battery saver forces one step down. A rolling
 * window and hysteresis keep stages from flapping. Pure: feed it samples.
 *
 * Motion Smoothing (G.5.2) costs more than any enhancement stage, so it goes
 * first: while it's on, the enhancement passes running over budget turn it
 * off before anything else, and so does thermal pressure or battery saver.
 * Its own GPU time has a budget of 40 % of the source's frame interval per
 * real frame ([motionSmoothingBudgetMillis], set for each video from
 * [motionSmoothingBudgetFor]; 13.3 ms
 * at 30 fps, 16.7 ms at 24): over it, the motion search drops to half
 * resolution, then Motion Smoothing turns off. Findings — G: the Pixel 3
 * played 24 → 48 fps with no frame dropped at 13.4 ms a real frame (32 %)
 * and fell behind at 32 ms (77 %); G.1's 8 ms came from a software GPU. It comes back only with [resetMotionSmoothing] (the
 * next video), since nothing measures what it would cost while it's off.
 */
class EnhancementGovernor(
    private val budgetMillis: Double = BUDGET_MILLIS,
    private val window: Int = 30,
    /** Back up only when the window's average is this far under budget. */
    private val recoverFraction: Double = 0.6,
) {
    /** Motion Smoothing's GPU budget per real frame for the playing video; see [motionSmoothingBudgetMillis]. */
    var motionSmoothingBudgetMillis: Double = motionSmoothingBudgetFor(MotionSmoothingRules.MAX_SOURCE_FPS)

    /** 0 = everything allowed, 1 = no denoise, 2 = no denoise and no upscale. */
    var level = 0
        private set

    /** 0 = Motion Smoothing at full resolution, 1 = its search at half resolution, 2 = off. */
    var motionSmoothingLevel = 0
        private set

    /** Motion Smoothing is running (not merely switched on, or paused); set by the player. */
    var motionSmoothingActive = false

    private val samples = ArrayDeque<Double>()
    private val motionSmoothingSamples = ArrayDeque<Double>()

    /** Samples still to ignore after a step: frames timed before the GL thread saw the change. */
    private var motionSmoothingSettle = 0
    private var pressure = false

    val allowsDenoise: Boolean get() = level < 1 && !pressure
    val allowsUpscale: Boolean get() = level < 2 && !(pressure && level >= 1)
    val allowsMotionSmoothing: Boolean get() = motionSmoothingLevel < 2 && !pressure
    val motionSmoothingAtHalfResolution: Boolean get() = motionSmoothingLevel >= 1

    /** The stages to run, given the ones the preset asks for. */
    fun limit(requested: EnhancementStages): EnhancementStages = requested.copy(
        upscale = requested.upscale && allowsUpscale,
        denoise = requested.denoise && allowsDenoise,
    )

    /** Thermal status at or above moderate, or battery saver, forces a step down until it clears. */
    fun setPressure(thermalModerateOrWorse: Boolean, batterySaver: Boolean) {
        pressure = thermalModerateOrWorse || batterySaver
    }

    /**
     * One frame's GPU time for all passes (or an estimate from dropped frames).
     * Returns true when the allowed stages changed.
     */
    fun record(frameMillis: Double): Boolean {
        if (!frameMillis.isFinite() || frameMillis < 0) return false
        samples.addLast(frameMillis)
        while (samples.size > window) samples.removeFirst()
        if (samples.size < window) return false
        val average = samples.average()
        if (average > budgetMillis && motionSmoothingActive && motionSmoothingLevel < 2) {
            // Motion Smoothing goes before any enhancement stage.
            motionSmoothingLevel = 2
            samples.clear()
            motionSmoothingSamples.clear()
            return true
        }
        val before = level
        if (average > budgetMillis && level < 2) {
            level++
            samples.clear()
        } else if (average < budgetMillis * recoverFraction && level > 0) {
            level--
            samples.clear()
        }
        return level != before
    }

    /**
     * Motion Smoothing's GPU time for one real frame. Returns true when it
     * stepped down; the caller drops the rest of that batch, and the next
     * [SETTLE_SAMPLES] are ignored too, so frames timed at full resolution
     * don't count against the half-resolution search.
     */
    fun recordMotionSmoothing(frameMillis: Double): Boolean {
        if (!frameMillis.isFinite() || frameMillis < 0 || motionSmoothingLevel >= 2) return false
        if (motionSmoothingSettle > 0) {
            motionSmoothingSettle--
            return false
        }
        motionSmoothingSamples.addLast(frameMillis)
        while (motionSmoothingSamples.size > window) motionSmoothingSamples.removeFirst()
        if (motionSmoothingSamples.size < window) return false
        if (motionSmoothingSamples.average() <= motionSmoothingBudgetMillis) return false
        motionSmoothingLevel++
        motionSmoothingSamples.clear()
        motionSmoothingSettle = SETTLE_SAMPLES
        return true
    }

    /**
     * The device's Motion Smoothing check passed only with the half-resolution
     * search ([MotionSmoothingCapability]): start there rather than stepping
     * down after a window of late frames.
     */
    fun requireHalfResolution() {
        if (motionSmoothingLevel >= 1) return
        motionSmoothingLevel = 1
        motionSmoothingSamples.clear()
        motionSmoothingSettle = SETTLE_SAMPLES
    }

    /** A new video: Motion Smoothing starts again at full resolution. */
    fun resetMotionSmoothing() {
        motionSmoothingLevel = 0
        motionSmoothingSamples.clear()
        motionSmoothingSettle = 0
    }

    fun reset() {
        level = 0
        samples.clear()
        resetMotionSmoothing()
    }

    companion object {
        const val BUDGET_MILLIS = 8.0

        /** The share of a source frame's interval Motion Smoothing may spend on the GPU (Findings — G). */
        const val MOTION_SMOOTHING_BUDGET_FRACTION = 0.4

        /** Motion Smoothing's budget per real frame for a source at [sourceFps]. */
        fun motionSmoothingBudgetFor(sourceFps: Float): Double = MOTION_SMOOTHING_BUDGET_FRACTION * 1000.0 / sourceFps

        /** GPU timer results arrive a few frames late (`GpuTimer` keeps four queries in flight). */
        const val SETTLE_SAMPLES = 8
    }
}
