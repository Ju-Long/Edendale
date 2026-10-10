package com.babasama.edendale.android.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Audio Enhancement (E.1; Apple's `AudioEnhancementProfile`): a tuned
 * 10-band equalizer curve per profile. Each preamp offsets its curve's peak
 * boost, so the presets leave headroom with the booster off. Night Mode is
 * equalization only, not a compressor. Raw values are persisted.
 */
enum class AudioEnhancementProfile(val raw: String, val preamp: Float, val bands: List<Float>) {
    FLAT("flat", 0f, listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    MOVIES("movies", -8f, listOf(8f, 5f, 3f, 0f, 0f, 2f, 3f, 2f, 1f, 0f)),
    MUSIC("music", -4f, listOf(4f, 2f, 0f, -1f, -1f, 2f, 3f, 3f, 2f, 1f)),
    DIALOGUE("dialogue", -6f, listOf(-3f, -1f, 0f, 5f, 6f, 5f, 3f, 1f, 0f, -1f)),
    NIGHT_MODE("nightMode", -5f, listOf(-5f, -2f, 1f, 4f, 5f, 5f, 3f, 1f, 0f, -1f)),
    ;

    val peakBandBoost: Float get() = bands.max()

    companion object {
        val DEFAULT = MOVIES

        fun fromRaw(raw: String?): AudioEnhancementProfile = entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

object AudioEnhancementRules {
    const val BAND_COUNT = 10

    /** Center frequencies in Hz. */
    val BAND_FREQUENCIES = listOf(60.0, 170.0, 310.0, 600.0, 1_000.0, 3_000.0, 6_000.0, 12_000.0, 14_000.0, 16_000.0)
    val BAND_LABELS = listOf("60", "170", "310", "600", "1k", "3k", "6k", "12k", "14k", "16k")

    const val MIN_DB = -20f
    const val MAX_DB = 20f

    /** The Audio Booster's gain, added to the preamp through the EQ rather than the volume. */
    const val BOOSTER_GAIN_DB = 10f

    /** Clamps to −20…+20 dB; a non-finite value reads as 0. */
    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN_DB, MAX_DB) else 0f
}

/**
 * The stored settings and what the equalizer applies. The user's preamp and
 * band adjustments are kept apart from the profile and added to it; changing
 * the profile resets them.
 */
data class AudioEnhancementSettings(
    val profile: AudioEnhancementProfile = AudioEnhancementProfile.DEFAULT,
    val userPreamp: Float = 0f,
    val userBands: List<Float> = List(AudioEnhancementRules.BAND_COUNT) { 0f },
    val boosterEnabled: Boolean = false,
) {
    val effectivePreamp: Float
        get() {
            val boost = if (boosterEnabled) AudioEnhancementRules.BOOSTER_GAIN_DB else 0f
            return AudioEnhancementRules.clamp(profile.preamp + userPreamp + boost)
        }

    val effectiveBands: List<Float>
        get() = profile.bands.zip(userBands) { base, user -> AudioEnhancementRules.clamp(base + user) }

    /** True when the equalizer would change nothing: audio passes through bit-exact. */
    val isFlat: Boolean get() = effectivePreamp == 0f && effectiveBands.all { it == 0f }

    val hasUserAdjustments: Boolean get() = userPreamp != 0f || userBands.any { it != 0f }

    /** A different profile starts without adjustments; the same one keeps them. */
    fun selecting(newProfile: AudioEnhancementProfile): AudioEnhancementSettings =
        if (newProfile == profile) this else copy(profile = newProfile, userPreamp = 0f, userBands = List(AudioEnhancementRules.BAND_COUNT) { 0f })

    fun withUserPreamp(value: Float) = copy(userPreamp = AudioEnhancementRules.clamp(value))

    fun withUserBand(index: Int, value: Float): AudioEnhancementSettings {
        if (index !in 0 until AudioEnhancementRules.BAND_COUNT) return this
        return copy(userBands = userBands.toMutableList().also { it[index] = AudioEnhancementRules.clamp(value) })
    }

    fun resettingAdjustments() = copy(userPreamp = 0f, userBands = List(AudioEnhancementRules.BAND_COUNT) { 0f })

    companion object {
        /** Settings as stored: an unknown profile reads as Movies, a band list of the wrong length as zeros. */
        fun fromStored(profileRaw: String?, preamp: Float?, bands: List<Float>?, booster: Boolean): AudioEnhancementSettings =
            AudioEnhancementSettings(
                profile = AudioEnhancementProfile.fromRaw(profileRaw),
                userPreamp = preamp?.let(AudioEnhancementRules::clamp) ?: 0f,
                userBands = bands?.takeIf { it.size == AudioEnhancementRules.BAND_COUNT }
                    ?.map(AudioEnhancementRules::clamp)
                    ?: List(AudioEnhancementRules.BAND_COUNT) { 0f },
                boosterEnabled = booster,
            )
    }
}

/** Normalized biquad coefficients (a0 = 1). */
data class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

object EqualizerMath {
    private const val BANDWIDTH_OCTAVES = 1.0

    /**
     * An RBJ peaking filter with a one-octave bandwidth, as Apple's
     * `AudioEQProcessor` builds it; null (pass through) at or above Nyquist or
     * for a gain under 0.01 dB.
     */
    fun peaking(frequencyHz: Double, gainDb: Double, sampleRate: Double): BiquadCoefficients? {
        if (frequencyHz >= sampleRate / 2 || abs(gainDb) < 0.01) return null
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * frequencyHz / sampleRate
        val sinW0 = sin(w0)
        val alpha = sinW0 * sinh(ln(2.0) / 2 * BANDWIDTH_OCTAVES * w0 / sinW0)
        val cosW0 = cos(w0)
        val a0 = 1 + alpha / a
        return BiquadCoefficients(
            b0 = (1 + alpha * a) / a0,
            b1 = (-2 * cosW0) / a0,
            b2 = (1 - alpha * a) / a0,
            a1 = (-2 * cosW0) / a0,
            a2 = (1 - alpha / a) / a0,
        )
    }

    fun gain(preampDb: Float): Double = 10.0.pow(preampDb / 20.0)
}

/**
 * The equalizer's signal path: one cascade of ten peaking biquads per channel
 * (transposed direct form II, in double precision), then the preamp gain.
 * Interleaved float samples are processed in place. New settings apply from
 * the next call; filter state carries over, so a change never clicks.
 */
class EqualizerDsp(private val channelCount: Int, private val sampleRate: Int) {

    private val sections = arrayOfNulls<BiquadCoefficients>(AudioEnhancementRules.BAND_COUNT)

    /** z1, z2 per channel per band. */
    private val state = DoubleArray(channelCount * AudioEnhancementRules.BAND_COUNT * 2)
    private var gain = 1.0

    var isFlat = true
        private set

    fun configure(preampDb: Float, bandsDb: List<Float>) {
        for (band in 0 until AudioEnhancementRules.BAND_COUNT) {
            val coefficients = EqualizerMath.peaking(
                AudioEnhancementRules.BAND_FREQUENCIES[band],
                bandsDb.getOrElse(band) { 0f }.toDouble(),
                sampleRate.toDouble(),
            )
            // A band that turns off forgets its state, so turning it back on starts clean.
            if (coefficients == null) clearBand(band)
            sections[band] = coefficients
        }
        gain = EqualizerMath.gain(preampDb)
        isFlat = preampDb == 0f && bandsDb.all { it == 0f }
        // Flat audio bypasses the filters, so their state would be stale on return.
        if (isFlat) state.fill(0.0)
    }

    private fun clearBand(band: Int) {
        for (channel in 0 until channelCount) {
            val base = (channel * AudioEnhancementRules.BAND_COUNT + band) * 2
            state[base] = 0.0
            state[base + 1] = 0.0
        }
    }

    /** Filters [frameCount] interleaved frames of [samples] in place. */
    fun process(samples: FloatArray, frameCount: Int) {
        if (isFlat) return
        for (frame in 0 until frameCount) {
            for (channel in 0 until channelCount) {
                val index = frame * channelCount + channel
                var x = samples[index].toDouble()
                for (band in 0 until AudioEnhancementRules.BAND_COUNT) {
                    val c = sections[band] ?: continue
                    val base = (channel * AudioEnhancementRules.BAND_COUNT + band) * 2
                    val y = c.b0 * x + state[base]
                    state[base] = c.b1 * x - c.a1 * y + state[base + 1]
                    state[base + 1] = c.b2 * x - c.a2 * y
                    x = y
                }
                samples[index] = (x * gain).toFloat()
            }
        }
    }

    companion object {
        /** A float sample as 16-bit PCM, clamped so a boost can never wrap around. */
        fun toPcm16(sample: Float): Short =
            (sample * 32768f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        fun fromPcm16(sample: Short): Float = sample / 32768f
    }
}
