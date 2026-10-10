package com.babasama.edendale.android.player.video.framegen

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.babasama.edendale.android.player.video.GlPass
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * G.1.1 prototype, test-only: a Media3 effect that outputs an extra frame
 * halfway between each pair of neighboring input frames, as a plain 50/50
 * blend. Media3 documents that effects which change frame timestamps aren't
 * supported during playback; this checks what actually happens.
 */
@UnstableApi
class MidpointFrameEffect(private val config: MidpointConfig) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MidpointFrameShaderProgram(context, config)
}

/** The prototype's switch and counters, shared with the test thread. */
class MidpointConfig(
    /** The source's frame spacing; only frames closer than 1.5 of these get a midpoint. */
    val sourceFrameDurationUs: Long,
    @Volatile var enabled: Boolean = true,
    /** Extra time the GL thread spends per midpoint, standing in for motion estimation and warping. */
    val simulatedWorkMs: Long = 0,
    /** Output textures: what the final stage holds until each frame's release time, plus the next input's two. */
    val poolSize: Int = 6,
) {
    val inputFrames = AtomicInteger()
    val flushes = AtomicInteger()
    /** Midpoint timestamps in output order. */
    val synthesizedTimesUs = ConcurrentLinkedQueue<Long>()
    /** GL-thread time for one input (blend and copies issued, plus any simulated work), in nanoseconds. */
    val issueNanos = ConcurrentLinkedQueue<Long>()
}

@UnstableApi
internal class MidpointFrameShaderProgram(context: Context, private val config: MidpointConfig) : GlShaderProgram {
    private val vertexSource = context.assets.open(GlPass.VERTEX_SHADER).bufferedReader().use { it.readText() }
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorExecutor = Executor { it.run() }
    private var errorListener = GlShaderProgram.ErrorListener { }

    private var blend: GlProgram? = null
    private var copy: GlProgram? = null
    private val free = ArrayDeque<GlTextureInfo>()
    private val inUse = mutableListOf<GlTextureInfo>()
    private var history: GlTextureInfo? = null
    private var historyTimeUs = C.TIME_UNSET

    /** Input frames announced as acceptable but not yet queued; each may need two output textures. */
    private var promisedInputs = 0

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        offerCapacity()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) {
        errorExecutor = executor
        this.errorListener = errorListener
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        promisedInputs = (promisedInputs - 1).coerceAtLeast(0)
        config.inputFrames.incrementAndGet()
        try {
            val start = System.nanoTime()
            val width = inputTexture.width
            val height = inputTexture.height
            val previous = history?.takeIf { it.width == width && it.height == height }
            val gapUs = if (historyTimeUs == C.TIME_UNSET) 0 else presentationTimeUs - historyTimeUs
            if (config.enabled && previous != null && gapUs > 0 && gapUs * 2 < config.sourceFrameDurationUs * 3) {
                // Stands in for the GPU time of motion estimation and warping: the midpoint, and frame N behind it, come that much later.
                if (config.simulatedWorkMs > 0) android.os.SystemClock.sleep(config.simulatedWorkMs)
                val midpoint = take(width, height)
                GlUtil.focusFramebufferUsingCurrentContext(midpoint.fboId, width, height)
                draw(blendProgram(), inputTexture.texId) {
                    setSamplerTexIdUniform("uPreviousSampler", previous.texId, 1)
                    setFloatUniform("uBlend", 0.5f)
                }
                val midpointTimeUs = historyTimeUs + gapUs / 2
                config.synthesizedTimesUs.add(midpointTimeUs)
                outputListener.onOutputFrameAvailable(midpoint, midpointTimeUs)
            }
            val output = take(width, height)
            GlUtil.focusFramebufferUsingCurrentContext(output.fboId, width, height)
            draw(copyProgram(), inputTexture.texId)
            outputListener.onOutputFrameAvailable(output, presentationTimeUs)

            val keep = history?.takeIf { it.width == width && it.height == height }
                ?: newTexture(width, height).also { history?.release(); history = it }
            GlUtil.focusFramebufferUsingCurrentContext(keep.fboId, width, height)
            draw(copyProgram(), inputTexture.texId)
            historyTimeUs = presentationTimeUs
            config.issueNanos.add(System.nanoTime() - start)
            inputListener.onInputFrameProcessed(inputTexture)
        } catch (e: GlUtil.GlException) {
            errorExecutor.execute { errorListener.onError(VideoFrameProcessingException(e, presentationTimeUs)) }
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (!inUse.remove(outputTexture)) return
        free.addLast(outputTexture)
        offerCapacity()
    }

    override fun signalEndOfCurrentInputStream() {
        // The next stream's first frame isn't this one's neighbor.
        historyTimeUs = C.TIME_UNSET
        outputListener.onCurrentOutputStreamEnded()
    }

    override fun flush() {
        free.addAll(inUse)
        inUse.clear()
        historyTimeUs = C.TIME_UNSET
        promisedInputs = 0
        config.flushes.incrementAndGet()
        inputListener.onFlush()
        offerCapacity()
    }

    override fun release() {
        try {
            blend?.delete()
            copy?.delete()
            (free + inUse).forEach { it.release() }
            history?.release()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        free.clear()
        inUse.clear()
        history = null
    }

    private fun offerCapacity() {
        while (config.poolSize - inUse.size - 2 * promisedInputs >= 2) {
            promisedInputs++
            inputListener.onReadyToAcceptInputFrame()
        }
    }

    private fun take(width: Int, height: Int): GlTextureInfo {
        var texture = free.removeFirstOrNull()
        if (texture != null && (texture.width != width || texture.height != height)) {
            texture.release()
            texture = null
        }
        return (texture ?: newTexture(width, height)).also { inUse.add(it) }
    }

    private fun newTexture(width: Int, height: Int): GlTextureInfo {
        val texId = GlUtil.createTexture(width, height, false)
        return GlTextureInfo(texId, GlUtil.createFboForTexture(texId), C.INDEX_UNSET, width, height)
    }

    private fun blendProgram() = blend ?: program(BLEND_SHADER).also { blend = it }
    private fun copyProgram() = copy ?: program(COPY_SHADER).also { copy = it }

    private fun program(fragment: String) = GlProgram(vertexSource, fragment).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    private fun draw(program: GlProgram, inputTexId: Int, uniforms: GlProgram.() -> Unit = {}) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.uniforms()
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    private companion object {
        val COPY_SHADER = """
            #version 300 es
            precision highp float;
            uniform sampler2D uTexSampler;
            in vec2 vTexCoord;
            out vec4 outColor;
            void main() { outColor = texture(uTexSampler, vTexCoord); }
        """.trimIndent()

        // G.1.1 starts with a plain blend; G.3 would warp along the motion vectors instead.
        val BLEND_SHADER = """
            #version 300 es
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uPreviousSampler;
            uniform float uBlend;
            in vec2 vTexCoord;
            out vec4 outColor;
            void main() { outColor = mix(texture(uPreviousSampler, vTexCoord), texture(uTexSampler, vTexCoord), uBlend); }
        """.trimIndent()
    }
}
