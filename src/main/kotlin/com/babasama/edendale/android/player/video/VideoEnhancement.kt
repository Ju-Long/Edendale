package com.babasama.edendale.android.player.video

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
}

/**
 * Enhancement as the viewer set it (D11): it lives in memory for the app
 * process and is never saved. Sharpness and denoise range 0–1 in steps of 0.05.
 */
data class EnhancementSettings(
    val preset: EnhancementPreset = EnhancementPreset.BALANCED,
    val sharpness: Float = 0.5f,
    val denoise: Float = 0.5f,
    val showOriginal: Boolean = false,
) {
    fun withSharpness(value: Float) = copy(sharpness = normalizeUnit(value))
    fun withDenoise(value: Float) = copy(denoise = normalizeUnit(value))

    companion object {
        const val STEP = 0.05f

        fun normalizeUnit(value: Float): Float {
            if (!value.isFinite()) return 0.5f
            return ((value.coerceIn(0f, 1f) / STEP).roundToInt() * STEP).coerceIn(0f, 1f)
        }
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
 */
class EnhancementGovernor(
    private val budgetMillis: Double = BUDGET_MILLIS,
    private val window: Int = 30,
    /** Back up only when the window's average is this far under budget. */
    private val recoverFraction: Double = 0.6,
) {
    /** 0 = everything allowed, 1 = no denoise, 2 = no denoise and no upscale. */
    var level = 0
        private set

    private val samples = ArrayDeque<Double>()
    private var pressure = false

    val allowsDenoise: Boolean get() = level < 1 && !pressure
    val allowsUpscale: Boolean get() = level < 2 && !(pressure && level >= 1)

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

    fun reset() {
        level = 0
        samples.clear()
    }

    companion object {
        const val BUDGET_MILLIS = 8.0
    }
}
