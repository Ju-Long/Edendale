package com.babasama.edendale.android.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Audio Enhancement equalizer and booster in Media3's audio path (E.1.2).
 * It takes 16-bit and float PCM at any rate and channel count, filters in
 * floating point with state per channel, and clamps 16-bit output so a boost
 * never wraps around. Media3 checks [isActive] only when the sink
 * reconfigures, so the processor stays active for those formats: flat
 * settings copy the audio through bit-exact, and new settings from [update]
 * apply at the next buffer. Passthrough and offload (an AC-3, E-AC-3, or DTS
 * stream sent undecoded to a receiver) never reach audio processors.
 */
@OptIn(UnstableApi::class)
class EqAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var settings = AudioEnhancementSettings(profile = AudioEnhancementProfile.FLAT)
    private var appliedSettings: AudioEnhancementSettings? = null
    private var dsp: EqualizerDsp? = null
    private var scratch = FloatArray(0)

    /** New settings; they apply from the next buffer, without reconfiguring the sink. */
    fun update(settings: AudioEnhancementSettings) {
        this.settings = settings
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            // Same format out: the equalizer never changes the layout.
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            // Anything else stays inactive and passes untouched.
            else -> AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: StreamMetadata) {
        dsp = EqualizerDsp(inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
        appliedSettings = null
    }

    override fun onReset() {
        dsp = null
        appliedSettings = null
        scratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val equalizer = dsp ?: EqualizerDsp(inputAudioFormat.channelCount, inputAudioFormat.sampleRate).also { dsp = it }
        val current = settings
        if (current != appliedSettings) {
            equalizer.configure(current.effectivePreamp, current.effectiveBands)
            appliedSettings = current
        }
        val output = replaceOutputBuffer(remaining)
        if (equalizer.isFlat) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val channels = inputAudioFormat.channelCount
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                val samples = remaining / 2
                val floats = scratchOf(samples)
                for (i in 0 until samples) floats[i] = EqualizerDsp.fromPcm16(input.getShort())
                equalizer.process(floats, samples / channels)
                for (i in 0 until samples) output.putShort(EqualizerDsp.toPcm16(floats[i]))
            }
            else -> {
                val samples = remaining / 4
                val floats = scratchOf(samples)
                for (i in 0 until samples) floats[i] = input.getFloat()
                equalizer.process(floats, samples / channels)
                // Float output may exceed full scale; the sink (or the device) clips it.
                for (i in 0 until samples) output.putFloat(floats[i])
            }
        }
        output.flip()
    }

    private fun scratchOf(size: Int): FloatArray {
        if (scratch.size < size) scratch = FloatArray(size)
        return scratch
    }
}

/** Installs [eq] in every audio sink the player builds (E.1.2). */
@OptIn(UnstableApi::class)
class EdendaleRenderersFactory(context: Context, private val eq: EqAudioProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        .setAudioProcessors(arrayOf(eq))
        .build()
}
