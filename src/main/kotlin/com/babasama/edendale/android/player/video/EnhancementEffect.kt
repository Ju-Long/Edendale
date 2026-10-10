package com.babasama.edendale.android.player.video

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * What the effect draws, shared between the player (main thread) and Media3's
 * GL thread (F.1.2). The effect reads it on every frame, so a change shows on
 * the next frame — or, while paused, after a REDRAW.
 */
class VideoEffectsHolder {
    @Volatile var adjustments: VideoAdjustmentValues = VideoAdjustmentValues.NEUTRAL
    @Volatile var enhancement: EnhancementSettings = EnhancementSettings(preset = EnhancementPreset.OFF)

    /** The video's on-screen viewport in physical pixels (F.3). */
    @Volatile var display: PixelSize = PixelSize(1920, 1080)

    /** HDR and Dolby Vision bypass every effect (F.1.4). */
    @Volatile var isHdr: Boolean = false

    /** The governor's limits (F.6). */
    @Volatile var allowsUpscale: Boolean = true
    @Volatile var allowsDenoise: Boolean = true

    /** Bumped on a preset change or item switch; the denoise history resets when it moves. */
    @Volatile var historyGeneration: Int = 0

    /** GPU time per frame for every pass together, from timer queries; read by the governor. */
    private val gpuSamples = ArrayDeque<Double>()

    @Synchronized
    fun recordGpuMillis(millis: Double) {
        gpuSamples.addLast(millis)
        while (gpuSamples.size > 120) gpuSamples.removeFirst()
    }

    @Synchronized
    fun drainGpuMillis(): List<Double> = gpuSamples.toList().also { gpuSamples.clear() }

    /** The output size for a source, decided once per configure. */
    fun outputSizeFor(source: PixelSize): PixelSize {
        val settings = enhancement
        return if (!isHdr && settings.preset.upscales && allowsUpscale) {
            UpscaleRules.targetResolution(source, display)
        } else {
            source
        }
    }

    /** Whether anything needs the effects pipeline right now. */
    val needsEffects: Boolean
        get() = !isHdr && (!adjustments.isNeutral || enhancement.preset != EnhancementPreset.OFF)
}

/**
 * Edendale's video effect for ExoPlayer (F.1): one GL program running, in
 * Apple's order, the upscale, the picture adjustments, sharpening, and
 * temporal denoise, each only when it does something. Its output size is
 * fixed when Media3 configures it, so the player installs a new instance
 * when the target size changes.
 */
@OptIn(UnstableApi::class)
class EnhancementEffect(private val holder: VideoEffectsHolder) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhancementShaderProgram(context, holder, useHdr)
}

@OptIn(UnstableApi::class)
internal class EnhancementShaderProgram(
    context: Context,
    private val holder: VideoEffectsHolder,
    private val useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val appContext = context.applicationContext
    private var copy: GlPass? = null
    private var color: ColorPass? = null
    private var upscale: UpscalePass? = null
    private var sharpen: SharpenPass? = null
    private var denoise: DenoisePass? = null
    private var targets = arrayOfNulls<RenderTarget>(2)
    private var input = PixelSize(0, 0)
    private var output = PixelSize(0, 0)
    private var lastHistoryGeneration = -1
    private val timer = GpuTimer()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        input = PixelSize(inputWidth, inputHeight)
        output = holder.outputSizeFor(input)
        denoise?.reset()
        return Size(output.width, output.height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val outputFbo = currentFramebuffer()
            val enhancement = holder.enhancement
            val original = enhancement.showOriginal
            val bypass = useHdr || holder.isHdr
            val adjustments = if (original || bypass) VideoAdjustmentValues.NEUTRAL else holder.adjustments
            val stages = if (original || bypass) {
                EnhancementStages(false, false, false)
            } else {
                EnhancementStages.forPreset(enhancement.preset, enhancement.sharpness, enhancement.denoise).let {
                    it.copy(denoise = it.denoise && holder.allowsDenoise)
                }
            }
            if (holder.historyGeneration != lastHistoryGeneration) {
                lastHistoryGeneration = holder.historyGeneration
                denoise?.reset()
            }

            // The passes this frame, in order; the last one draws into Media3's output.
            val steps = buildList<(Int, Int) -> Unit> {
                if (output != input) {
                    // The configured size wins even when Show Original or the governor
                    // skips the upscaler: a bilinear stretch keeps the frame filled.
                    if (stages.upscale) add { tex, _ -> upscalePass().draw(tex, input, output) } else add { tex, _ -> copyPass().draw(tex) }
                }
                if (!adjustments.isNeutral) add { tex, _ -> colorPass().draw(tex, adjustments) }
                if (stages.sharpen) add { tex, _ -> sharpenPass().draw(tex, enhancement.sharpness) }
            }
            val denoiseStep = stages.denoise

            timer.begin()
            var texture = inputTexId
            val totalSteps = steps.size + if (denoiseStep) 1 else 0
            if (totalSteps == 0) {
                GlUtil.focusFramebufferUsingCurrentContext(outputFbo, output.width, output.height)
                copyPass().draw(texture)
            } else {
                steps.forEachIndexed { index, step ->
                    val last = index == totalSteps - 1
                    if (last) {
                        GlUtil.focusFramebufferUsingCurrentContext(outputFbo, output.width, output.height)
                        step(texture, outputFbo)
                    } else {
                        val target = target(index % 2)
                        target.focus()
                        step(texture, target.fboId)
                        texture = target.texId
                    }
                }
                if (denoiseStep) {
                    GlUtil.focusFramebufferUsingCurrentContext(outputFbo, output.width, output.height)
                    denoisePass().draw(texture, output, outputFbo, enhancement.denoise)
                } else {
                    denoise?.reset()
                }
            }
            timer.end()
            timer.poll()?.let(holder::recordGpuMillis)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun flush() {
        super.flush()
        // A seek: the next frame isn't the last one's neighbor.
        denoise?.reset()
    }

    override fun release() {
        super.release()
        copy?.release()
        color?.release()
        upscale?.release()
        sharpen?.release()
        denoise?.release()
        targets.forEach { it?.release() }
        timer.release()
    }

    private fun target(index: Int): RenderTarget {
        val existing = targets[index]
        if (existing != null && existing.width == output.width && existing.height == output.height) return existing
        existing?.release()
        return RenderTarget(output.width, output.height).also { targets[index] = it }
    }

    private fun copyPass() = copy ?: GlPass(appContext, GlPass.COPY).also { copy = it }
    private fun colorPass() = color ?: ColorPass(appContext).also { color = it }
    private fun upscalePass() = upscale ?: UpscalePass(appContext, useEasu = true).also { upscale = it }
    private fun sharpenPass() = sharpen ?: SharpenPass(appContext).also { sharpen = it }
    private fun denoisePass() = denoise ?: DenoisePass(appContext).also { denoise = it }

    private fun currentFramebuffer(): Int {
        val binding = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, binding, 0)
        return binding[0]
    }
}

/**
 * GPU time for the enhancement passes, from `GL_EXT_disjoint_timer_query`
 * where the driver has it (F.6.2). Results are read a few frames later, so
 * nothing stalls; a disjoint event discards them. Without the extension it
 * measures nothing, and the governor falls back to dropped frames.
 */
internal class GpuTimer {
    private var supported: Boolean? = null
    private val queries = IntArray(QUERY_COUNT)
    private val pending = ArrayDeque<Int>()
    private var next = 0
    private var active = false

    fun begin() {
        if (!isSupported()) return
        if (pending.size >= QUERY_COUNT) return
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[next])
        active = true
    }

    fun end() {
        if (!active) return
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        pending.addLast(queries[next])
        next = (next + 1) % QUERY_COUNT
        active = false
    }

    /** The oldest finished measurement in milliseconds, or null. */
    fun poll(): Double? {
        val query = pending.firstOrNull() ?: return null
        val available = IntArray(1)
        GLES30.glGetQueryObjectuiv(query, GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0)
        if (available[0] == 0) return null
        pending.removeFirst()
        val disjoint = IntArray(1)
        GLES20.glGetIntegerv(GL_GPU_DISJOINT_EXT, disjoint, 0)
        val nanos = IntArray(1)
        GLES30.glGetQueryObjectuiv(query, GLES30.GL_QUERY_RESULT, nanos, 0)
        if (disjoint[0] != 0) return null
        return (nanos[0].toLong() and 0xFFFFFFFFL) / 1_000_000.0
    }

    fun release() {
        if (supported == true) GLES30.glDeleteQueries(QUERY_COUNT, queries, 0)
    }

    private fun isSupported(): Boolean {
        supported?.let { return it }
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val ok = "GL_EXT_disjoint_timer_query" in extensions && isGles3()
        if (ok) GLES30.glGenQueries(QUERY_COUNT, queries, 0)
        supported = ok
        return ok
    }

    private companion object {
        const val QUERY_COUNT = 4
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
    }
}
