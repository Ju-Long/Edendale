package com.babasama.edendale.android.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** E.1: the processor's Media3 glue — formats, bit-exact flat audio, live updates, clamping. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class EqAudioProcessorInstrumentedTest {

    private fun pcm16(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    private fun shorts(buffer: ByteBuffer): ShortArray {
        val out = ShortArray(buffer.remaining() / 2)
        val view = buffer.order(ByteOrder.nativeOrder())
        for (i in out.indices) out[i] = view.getShort()
        return out
    }

    private fun sine(frames: Int, amplitude: Double): ShortArray =
        ShortArray(frames * 2) { i -> (amplitude * 32767 * sin(2 * PI * 1_000 * (i / 2) / 48_000.0)).toInt().toShort() }

    private fun configured(settings: AudioEnhancementSettings): EqAudioProcessor =
        EqAudioProcessor().apply {
            update(settings)
            configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            flush(StreamMetadata.DEFAULT)
        }

    private fun run(processor: EqAudioProcessor, input: ShortArray): ShortArray {
        processor.queueInput(pcm16(input))
        return shorts(processor.output)
    }

    @Test
    fun acceptsSixteenBitAndFloatAndIgnoresOtherEncodings() {
        val processor = EqAudioProcessor()
        assertEquals(C.ENCODING_PCM_16BIT, processor.configure(AudioFormat(44_100, 6, C.ENCODING_PCM_16BIT)).encoding)
        assertTrue(processor.isActive)
        assertEquals(C.ENCODING_PCM_FLOAT, processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)).encoding)
        assertEquals(AudioFormat.NOT_SET, processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)))
        assertFalse(processor.isActive)
    }

    @Test
    fun flatSettingsPassThroughBitExactThenUpdatesApplyLive() {
        val processor = configured(AudioEnhancementSettings(AudioEnhancementProfile.FLAT))
        val input = sine(2_048, 0.2)
        assertArrayEquals(input, run(processor, input))

        // Movies, without reconfiguring: the next buffer is equalized.
        processor.update(AudioEnhancementSettings(AudioEnhancementProfile.MOVIES))
        val equalized = run(processor, input)
        assertEquals(input.size, equalized.size)
        assertFalse(input.contentEquals(equalized))

        // And back to flat: bit-exact again.
        processor.update(AudioEnhancementSettings(AudioEnhancementProfile.FLAT))
        assertArrayEquals(input, run(processor, input))
    }

    @Test
    fun boostedSixteenBitOutputClampsInsteadOfWrapping() {
        val loud = AudioEnhancementSettings(AudioEnhancementProfile.FLAT).withUserPreamp(20f).copy(boosterEnabled = true)
        val processor = configured(loud)
        val input = sine(4_800, 0.9)
        val output = run(processor, input)
        assertTrue(output.any { it == Short.MAX_VALUE })
        input.indices.filter { input[it] > 16_000 }.forEach { assertTrue(output[it] > 0) }
    }
}
