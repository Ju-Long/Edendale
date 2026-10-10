package com.babasama.edendale.android.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E.1.T1–T3, ported from Apple's AudioEnhancementTests and extended to the DSP. */
class AudioEnhancementTest {

    private fun preferences() = PlayerPreferences(InMemoryPlayerPreferencesStore())

    // MARK: - Profiles and settings (E.1.T1)

    @Test
    fun theProfileTable() {
        assertEquals(listOf("flat", "movies", "music", "dialogue", "nightMode"), AudioEnhancementProfile.entries.map { it.raw })
        assertEquals(listOf(0f, -8f, -4f, -6f, -5f), AudioEnhancementProfile.entries.map { it.preamp })
        assertEquals(listOf(8f, 5f, 3f, 0f, 0f, 2f, 3f, 2f, 1f, 0f), AudioEnhancementProfile.MOVIES.bands)
        assertEquals(listOf(4f, 2f, 0f, -1f, -1f, 2f, 3f, 3f, 2f, 1f), AudioEnhancementProfile.MUSIC.bands)
        assertEquals(listOf(-3f, -1f, 0f, 5f, 6f, 5f, 3f, 1f, 0f, -1f), AudioEnhancementProfile.DIALOGUE.bands)
        assertEquals(listOf(-5f, -2f, 1f, 4f, 5f, 5f, 3f, 1f, 0f, -1f), AudioEnhancementProfile.NIGHT_MODE.bands)
        AudioEnhancementProfile.entries.forEach {
            assertEquals(AudioEnhancementRules.BAND_COUNT, it.bands.size)
            // Each preset leaves headroom with the booster off.
            assertTrue(it.preamp + it.peakBandBoost <= 0f, it.raw)
        }
        assertEquals(listOf(60.0, 170.0, 310.0, 600.0, 1_000.0, 3_000.0, 6_000.0, 12_000.0, 14_000.0, 16_000.0), AudioEnhancementRules.BAND_FREQUENCIES)
    }

    @Test
    fun moviesIsTheDefaultAndUnknownValuesReadAsIt() {
        assertEquals(AudioEnhancementProfile.MOVIES, preferences().audioEnhancement.profile)
        val store = InMemoryPlayerPreferencesStore()
        store.putString(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PROFILE, "nonexistent")
        assertEquals(AudioEnhancementProfile.MOVIES, PlayerPreferences(store).audioEnhancement.profile)
        store.putString(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PROFILE, "nightMode")
        assertEquals(AudioEnhancementProfile.NIGHT_MODE, PlayerPreferences(store).audioEnhancement.profile)
    }

    @Test
    fun clamping() {
        assertEquals(-20f, AudioEnhancementRules.clamp(-25f))
        assertEquals(20f, AudioEnhancementRules.clamp(25f))
        assertEquals(5f, AudioEnhancementRules.clamp(5f))
        assertEquals(0f, AudioEnhancementRules.clamp(Float.NaN))
        val movies = AudioEnhancementSettings(AudioEnhancementProfile.MOVIES).withUserPreamp(20f).withUserBand(0, 20f)
        assertEquals(12f, movies.effectivePreamp)
        assertEquals(20f, movies.effectiveBands[0])
    }

    @Test
    fun adjustmentsPersistAndARelaunchRestoresThem() {
        val store = InMemoryPlayerPreferencesStore()
        PlayerPreferences(store).audioEnhancement = AudioEnhancementSettings()
            .withUserPreamp(5f).withUserBand(0, 3f).withUserBand(9, -2f)
        val restored = PlayerPreferences(store).audioEnhancement
        assertEquals(5f, restored.userPreamp)
        assertEquals(3f, restored.userBands[0])
        assertEquals(-2f, restored.userBands[9])
        assertEquals("[3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,-2.0]", store.getString(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_BANDS, null))
    }

    @Test
    fun changingTheProfileResetsAdjustments() {
        val adjusted = AudioEnhancementSettings().withUserPreamp(5f).withUserBand(0, 3f)
        val music = adjusted.selecting(AudioEnhancementProfile.MUSIC)
        assertEquals(0f, music.userPreamp)
        assertTrue(music.userBands.all { it == 0f })
        // Choosing the same profile keeps them.
        assertEquals(adjusted, adjusted.selecting(AudioEnhancementProfile.MOVIES))
    }

    @Test
    fun theBoosterAddsTenDecibelsAndRestoresOnOff() {
        val flat = AudioEnhancementSettings(AudioEnhancementProfile.FLAT).withUserPreamp(3f).withUserBand(2, -2f)
        val boosted = flat.copy(boosterEnabled = true)
        assertEquals(13f, boosted.effectivePreamp)
        assertEquals(flat.effectiveBands, boosted.effectiveBands)
        assertEquals(3f, boosted.copy(boosterEnabled = false).effectivePreamp)
        // Clamped at +20 while boosted, and the adjustment survives.
        val loud = AudioEnhancementSettings(AudioEnhancementProfile.FLAT).withUserPreamp(17f).copy(boosterEnabled = true)
        assertEquals(20f, loud.effectivePreamp)
        assertEquals(17f, loud.copy(boosterEnabled = false).effectivePreamp)
    }

    @Test
    fun aWrongBandCountOrCorruptValuesReadAsZeros() {
        val store = InMemoryPlayerPreferencesStore()
        store.putString(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_BANDS, "[5,5,5,5,5]")
        assertEquals(List(10) { 0f }, PlayerPreferences(store).audioEnhancement.userBands)
        store.putString(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_BANDS, "not json")
        assertEquals(List(10) { 0f }, PlayerPreferences(store).audioEnhancement.userBands)
        store.putString(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_BANDS, "[NaN,Infinity,0,0,0,0,0,0,0,30]")
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 20f), PlayerPreferences(store).audioEnhancement.userBands)
        store.putFloat(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PREAMP, Float.NaN)
        assertEquals(0f, PlayerPreferences(store).audioEnhancement.userPreamp)
        // An out-of-range band index is a no-op.
        val settings = AudioEnhancementSettings()
        assertEquals(settings, settings.withUserBand(-1, 5f))
        assertEquals(settings, settings.withUserBand(10, 5f))
    }

    @Test
    fun flatnessAndAdjustmentTracking() {
        val flat = AudioEnhancementSettings(AudioEnhancementProfile.FLAT)
        assertTrue(flat.isFlat)
        assertFalse(flat.hasUserAdjustments)
        assertFalse(flat.withUserBand(0, 1f).isFlat)
        assertTrue(flat.withUserBand(0, 1f).resettingAdjustments().isFlat)
        assertFalse(flat.copy(boosterEnabled = true).isFlat)
        assertFalse(AudioEnhancementSettings(AudioEnhancementProfile.MOVIES).isFlat)
        assertTrue(flat.withUserBand(5, -1f).hasUserAdjustments)
    }

    // MARK: - Coefficients (E.1.T2)

    private fun rbj(f: Double, gain: Double, fs: Double): BiquadCoefficients {
        val a = 10.0.pow(gain / 40)
        val w0 = 2 * PI * f / fs
        val alpha = sin(w0) * sinh(ln(2.0) / 2 * 1 * w0 / sin(w0))
        val a0 = 1 + alpha / a
        return BiquadCoefficients((1 + alpha * a) / a0, -2 * cos(w0) / a0, (1 - alpha * a) / a0, -2 * cos(w0) / a0, (1 - alpha / a) / a0)
    }

    private fun assertClose(expected: BiquadCoefficients, actual: BiquadCoefficients?) {
        assertNotNull(actual)
        listOf(
            expected.b0 to actual.b0, expected.b1 to actual.b1, expected.b2 to actual.b2,
            expected.a1 to actual.a1, expected.a2 to actual.a2,
        ).forEach { (e, a) -> assertEquals(e, a, 1e-12) }
    }

    @Test
    fun coefficientsFollowTheRbjFormula() {
        assertClose(rbj(1_000.0, 6.0, 48_000.0), EqualizerMath.peaking(1_000.0, 6.0, 48_000.0))
        assertClose(rbj(60.0, -5.0, 44_100.0), EqualizerMath.peaking(60.0, -5.0, 44_100.0))
        // A boost's numerator exceeds its denominator at the center: b0 > 1.
        assertTrue(EqualizerMath.peaking(1_000.0, 6.0, 48_000.0)!!.b0 > 1)
    }

    @Test
    fun bandsPassThroughAtNyquistOrBelowAHundredthOfADecibel() {
        // At 22.05 kHz, Nyquist is 11.025 kHz: the 12, 14, and 16 kHz bands pass through.
        listOf(12_000.0, 14_000.0, 16_000.0).forEach { assertNull(EqualizerMath.peaking(it, 6.0, 22_050.0)) }
        assertNotNull(EqualizerMath.peaking(6_000.0, 6.0, 22_050.0))
        assertNull(EqualizerMath.peaking(1_000.0, 0.009, 48_000.0))
        assertNotNull(EqualizerMath.peaking(1_000.0, 0.011, 48_000.0))
    }

    // MARK: - DSP (E.1.T3)

    private fun sine(frequency: Double, sampleRate: Int, frames: Int, amplitude: Double, channels: Int = 2): FloatArray =
        FloatArray(frames * channels) { i -> (amplitude * sin(2 * PI * frequency * (i / channels) / sampleRate)).toFloat() }

    private fun rmsDb(samples: FloatArray, channel: Int, channels: Int, skipFrames: Int): Double {
        var sum = 0.0
        var count = 0
        for (frame in skipFrames until samples.size / channels) {
            val s = samples[frame * channels + channel].toDouble()
            sum += s * s
            count++
        }
        return 20 * log10(sqrt(sum / count))
    }

    @Test
    fun aSineAtABandCenterGainsThatBandsBoost() {
        val rate = 48_000
        listOf(4 to 1_000.0, 5 to 3_000.0, 0 to 60.0).forEach { (band, frequency) ->
            val input = sine(frequency, rate, rate, 0.1)
            val output = input.copyOf()
            val dsp = EqualizerDsp(channelCount = 2, sampleRate = rate)
            dsp.configure(0f, List(10) { if (it == band) 6f else 0f })
            dsp.process(output, output.size / 2)
            val gain = rmsDb(output, 0, 2, skipFrames = rate / 4) - rmsDb(input, 0, 2, skipFrames = rate / 4)
            // Neighbouring bands are flat, so the center gains the full boost.
            assertTrue(abs(gain - 6.0) <= 0.5, "band at $frequency Hz gained $gain dB")
        }
    }

    @Test
    fun thePreampScalesTheOutput() {
        val input = sine(440.0, 48_000, 4_800, 0.1)
        val output = input.copyOf()
        val dsp = EqualizerDsp(2, 48_000)
        dsp.configure(-6f, List(10) { 0f })
        dsp.process(output, output.size / 2)
        val ratio = output[101] / input[101]
        assertEquals(10.0.pow(-6.0 / 20).toFloat(), ratio, 1e-4f)
    }

    @Test
    fun flatSettingsAreBitExact() {
        val input = sine(440.0, 48_000, 1_000, 0.7)
        val output = input.copyOf()
        val dsp = EqualizerDsp(2, 48_000)
        dsp.configure(0f, List(10) { 0f })
        assertTrue(dsp.isFlat)
        dsp.process(output, output.size / 2)
        assertContentEquals(input, output)
        // And 16-bit samples round-trip exactly.
        listOf(Short.MIN_VALUE, -1, 0, 1, 12_345, Short.MAX_VALUE).forEach {
            assertEquals(it.toShort(), EqualizerDsp.toPcm16(EqualizerDsp.fromPcm16(it.toShort())))
        }
    }

    @Test
    fun sixteenBitOutputClampsAtFullScale() {
        assertEquals(Short.MAX_VALUE, EqualizerDsp.toPcm16(1.7f))
        assertEquals(Short.MIN_VALUE, EqualizerDsp.toPcm16(-2.5f))
        // A +20 dB preamp on a loud sine never wraps around.
        val samples = sine(440.0, 48_000, 4_800, 0.9)
        val dsp = EqualizerDsp(2, 48_000)
        dsp.configure(20f, List(10) { 0f })
        dsp.process(samples, samples.size / 2)
        val pcm = samples.map(EqualizerDsp::toPcm16)
        assertTrue(pcm.any { it == Short.MAX_VALUE } && pcm.any { it == Short.MIN_VALUE })
        // Where the input is positive, the output never flips negative.
        val positive = sine(440.0, 48_000, 4_800, 0.9)
        positive.indices.filter { positive[it] > 0.5f }.forEach { assertTrue(pcm[it] > 0) }
    }

    @Test
    fun aSettingsChangeKeepsFilterState() {
        // Changing a band mid-stream must not reset the other bands' state (no click).
        val dsp = EqualizerDsp(1, 48_000)
        val first = sine(1_000.0, 48_000, 4_800, 0.1, channels = 1)
        dsp.configure(0f, List(10) { if (it == 4) 6f else 0f })
        dsp.process(first, first.size)
        val tail = first.last()
        dsp.configure(0f, List(10) { if (it == 4) 6f else if (it == 0) 1f else 0f })
        val next = sine(1_000.0, 48_000, 4_801, 0.1, channels = 1).copyOfRange(4_800, 4_801)
        dsp.process(next, 1)
        assertTrue(abs(next[0] - tail) < 0.05f, "jumped from $tail to ${next[0]}")
    }
}
