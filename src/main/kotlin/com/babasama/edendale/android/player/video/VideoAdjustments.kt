package com.babasama.edendale.android.player.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Picture adjustments (F.2; Apple's `VideoAdjustment`): brightness, contrast,
 * gamma, saturation, and hue, stored on the device as one JSON object under
 * `video.adjustments`. Pure, so the JVM suite covers it.
 */
enum class VideoAdjustment(
    val key: String,
    val min: Float,
    val max: Float,
    val neutral: Float,
    val step: Float,
) {
    BRIGHTNESS("brightness", 0f, 2f, 1f, 0.05f),
    CONTRAST("contrast", 0f, 2f, 1f, 0.05f),
    GAMMA("gamma", 0.25f, 3f, 1f, 0.05f),
    SATURATION("saturation", 0f, 3f, 1f, 0.05f),
    HUE("hue", 0f, 360f, 0f, 5f),
    ;

    /** A non-finite value becomes neutral; then the value clamps and snaps to its step. */
    fun normalize(value: Float): Float {
        if (!value.isFinite()) return neutral
        val clamped = value.coerceIn(min, max)
        val steps = ((clamped - min) / step).toDouble().roundToLong()
        // Rounded to six decimals so repeated steps never drift.
        val snapped = (min + steps * step).coerceIn(min, max)
        return (snapped * 1_000_000f).roundToLong() / 1_000_000f
    }
}

data class VideoAdjustmentValues(
    val brightness: Float = VideoAdjustment.BRIGHTNESS.neutral,
    val contrast: Float = VideoAdjustment.CONTRAST.neutral,
    val gamma: Float = VideoAdjustment.GAMMA.neutral,
    val saturation: Float = VideoAdjustment.SATURATION.neutral,
    val hue: Float = VideoAdjustment.HUE.neutral,
) {
    operator fun get(adjustment: VideoAdjustment): Float = when (adjustment) {
        VideoAdjustment.BRIGHTNESS -> brightness
        VideoAdjustment.CONTRAST -> contrast
        VideoAdjustment.GAMMA -> gamma
        VideoAdjustment.SATURATION -> saturation
        VideoAdjustment.HUE -> hue
    }

    fun with(adjustment: VideoAdjustment, value: Float): VideoAdjustmentValues {
        val v = adjustment.normalize(value)
        return when (adjustment) {
            VideoAdjustment.BRIGHTNESS -> copy(brightness = v)
            VideoAdjustment.CONTRAST -> copy(contrast = v)
            VideoAdjustment.GAMMA -> copy(gamma = v)
            VideoAdjustment.SATURATION -> copy(saturation = v)
            VideoAdjustment.HUE -> copy(hue = v)
        }
    }

    /** Neutral values skip the effect entirely. */
    val isNeutral: Boolean get() = this == NEUTRAL

    fun normalized(): VideoAdjustmentValues = VideoAdjustment.entries.fold(NEUTRAL) { acc, a -> acc.with(a, this[a]) }

    fun toJson(): String = JsonObject(VideoAdjustment.entries.associate { it.key to JsonPrimitive(this[it]) }).toString()

    companion object {
        val NEUTRAL = VideoAdjustmentValues()

        /** Missing fields read as neutral; anything that isn't a JSON object reads as all neutral. */
        fun fromJson(raw: String?): VideoAdjustmentValues {
            val obj = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return NEUTRAL
            return VideoAdjustment.entries.fold(NEUTRAL) { acc, a ->
                val value = obj[a.key]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }?.toFloat()
                acc.with(a, value ?: a.neutral)
            }
        }
    }
}

/**
 * The color math of Apple's `ColorAdjustment.metal`, in Kotlin: the reference
 * the GLSL pass is tested against (F.2.T2, F.2.T3).
 */
object ColorMath {
    private const val AXIS = 0.57735026919

    fun apply(rgb: DoubleArray, a: VideoAdjustmentValues): DoubleArray {
        var r = rgb[0] * a.brightness
        var g = rgb[1] * a.brightness
        var b = rgb[2] * a.brightness
        r = (r - 0.5) * a.contrast + 0.5
        g = (g - 0.5) * a.contrast + 0.5
        b = (b - 0.5) * a.contrast + 0.5
        if (a.gamma > 0.01f && abs(a.gamma - 1f) > 1e-3f) {
            val inv = 1.0 / a.gamma
            r = max(r, 0.0).pow(inv)
            g = max(g, 0.0).pow(inv)
            b = max(b, 0.0).pow(inv)
        }
        val luma = r * 0.2126 + g * 0.7152 + b * 0.0722
        r = luma + (r - luma) * a.saturation
        g = luma + (g - luma) * a.saturation
        b = luma + (b - luma) * a.saturation
        if (abs(a.hue) >= 1e-3f) {
            val angle = a.hue * PI / 180
            val cosA = cos(angle)
            val sinA = sin(angle)
            // rgb·cos + (axis × rgb)·sin + axis·(axis·rgb)·(1 − cos)
            val crossR = AXIS * b - AXIS * g
            val crossG = AXIS * r - AXIS * b
            val crossB = AXIS * g - AXIS * r
            val dot = AXIS * (r + g + b)
            val nr = r * cosA + crossR * sinA + AXIS * dot * (1 - cosA)
            val ng = g * cosA + crossG * sinA + AXIS * dot * (1 - cosA)
            val nb = b * cosA + crossB * sinA + AXIS * dot * (1 - cosA)
            r = nr
            g = ng
            b = nb
        }
        return doubleArrayOf(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
    }
}
