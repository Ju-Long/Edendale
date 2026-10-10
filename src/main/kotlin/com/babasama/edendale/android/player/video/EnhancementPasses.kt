package com.babasama.edendale.android.player.video

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * One full-screen GLSL ES 3.0 pass (F.1): Edendale's shaders under
 * `assets/shaders/`, drawn into whatever framebuffer is focused. Plain classes
 * with no Media3 effect types, so instrumented tests drive them directly and
 * they could move into a custom renderer (G.1).
 */
@OptIn(UnstableApi::class)
class GlPass(context: Context, fragmentAsset: String) {
    private val program = GlProgram(context, VERTEX_SHADER, fragmentAsset).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    /** Draws into the focused framebuffer with [inputTexId] as `uTexSampler`, after [uniforms]. */
    fun draw(inputTexId: Int, uniforms: GlProgram.() -> Unit = {}) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.uniforms()
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    fun release() = program.delete()

    companion object {
        const val VERTEX_SHADER = "shaders/edendale_vertex_es3.glsl"
        const val COPY = "shaders/edendale_copy_es3.glsl"
        const val COLOR = "shaders/edendale_color_es3.glsl"
        const val CAS = "shaders/edendale_cas_es3.glsl"
        const val DENOISE = "shaders/edendale_denoise_es3.glsl"
        const val LANCZOS = "shaders/edendale_lanczos_es3.glsl"
        const val EASU = "shaders/edendale_easu_es3.glsl"
    }
}

/** An RGBA8 texture with a framebuffer, for a pass's output. */
@OptIn(UnstableApi::class)
class RenderTarget(val width: Int, val height: Int) {
    val texId: Int = GlUtil.createTexture(width, height, false)
    val fboId: Int = GlUtil.createFboForTexture(texId)

    fun focus() = GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)

    fun release() {
        GlUtil.deleteFbo(fboId)
        GlUtil.deleteTexture(texId)
    }
}

/** Picture adjustments: Apple's color math in one pass (F.2.2). */
class ColorPass(context: Context) {
    private val pass = GlPass(context, GlPass.COLOR)

    fun draw(inputTexId: Int, values: VideoAdjustmentValues) = pass.draw(inputTexId) {
        setFloatUniform("uBrightness", values.brightness)
        setFloatUniform("uContrast", values.contrast)
        setFloatUniform("uGamma", values.gamma)
        setFloatUniform("uSaturation", values.saturation)
        setFloatUniform("uHue", values.hue)
    }

    fun release() = pass.release()
}

/** The upscaler: FSR 1 EASU, or Apple's Lanczos-2 fallback (F.3). */
class UpscalePass(context: Context, val useEasu: Boolean = true) {
    private val pass = GlPass(context, if (useEasu) GlPass.EASU else GlPass.LANCZOS)

    fun draw(inputTexId: Int, input: PixelSize, output: PixelSize) = pass.draw(inputTexId) {
        setFloatsUniformIfPresent("uInputSize", floatArrayOf(input.width.toFloat(), input.height.toFloat()))
        setFloatsUniform("uOutputSize", floatArrayOf(output.width.toFloat(), output.height.toFloat()))
    }

    fun release() = pass.release()
}

/** Contrast Adaptive Sharpening (F.4.1). */
class SharpenPass(context: Context) {
    private val pass = GlPass(context, GlPass.CAS)

    fun draw(inputTexId: Int, sharpness: Float) = pass.draw(inputTexId) {
        setFloatUniform("uSharpness", sharpness)
    }

    fun release() = pass.release()
}

/**
 * Temporal denoise with its two history textures (F.5.1). The first frame
 * after a [reset] primes the history and passes through.
 */
@OptIn(UnstableApi::class)
class DenoisePass(context: Context) {
    private val pass = GlPass(context, GlPass.DENOISE)
    private val copy = GlPass(context, GlPass.COPY)
    private var historyA: RenderTarget? = null
    private var historyB: RenderTarget? = null
    private var readA = true
    private var hasHistory = false

    /** Forgets the history: after a seek, a preset change, an item switch, or a size change. */
    fun reset() {
        hasHistory = false
    }

    /**
     * Denoises [inputTexId] into the framebuffer that's focused when called
     * ([outputFbo], [size]). The result also becomes the next frame's history.
     */
    fun draw(inputTexId: Int, size: PixelSize, outputFbo: Int, strength: Float, motionThreshold: Float = MOTION_THRESHOLD) {
        val a = ensure(historyA, size).also { historyA = it }
        val b = ensure(historyB, size).also { historyB = it }
        if (!hasHistory) {
            // Prime the history with this frame, and pass it through.
            a.focus()
            copy.draw(inputTexId)
            readA = true
            hasHistory = true
            GlUtil.focusFramebufferUsingCurrentContext(outputFbo, size.width, size.height)
            copy.draw(inputTexId)
            return
        }
        val read = if (readA) a else b
        val write = if (readA) b else a
        write.focus()
        drawBlend(inputTexId, read.texId, strength, motionThreshold)
        readA = !readA
        GlUtil.focusFramebufferUsingCurrentContext(outputFbo, size.width, size.height)
        copy.draw(write.texId)
    }

    private fun drawBlend(inputTexId: Int, historyTexId: Int, strength: Float, threshold: Float) =
        pass.draw(inputTexId) {
            setSamplerTexIdUniform("uHistorySampler", historyTexId, 1)
            setFloatUniform("uStrength", strength)
            setFloatUniform("uMotionThreshold", threshold)
        }

    private fun ensure(target: RenderTarget?, size: PixelSize): RenderTarget {
        if (target != null && target.width == size.width && target.height == size.height) return target
        target?.release()
        hasHistory = false
        return RenderTarget(size.width, size.height)
    }

    fun release() {
        pass.release()
        copy.release()
        historyA?.release()
        historyB?.release()
        historyA = null
        historyB = null
    }

    companion object {
        /** Apple's motion threshold. */
        const val MOTION_THRESHOLD = 0.08f
    }
}

/** Whether the bound context is OpenGL ES 3.0 or later (the passes' GLSL needs it). */
fun isGles3(): Boolean {
    val version = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
    return GLES20.glGetError() == GLES20.GL_NO_ERROR && version[0] >= 3
}
