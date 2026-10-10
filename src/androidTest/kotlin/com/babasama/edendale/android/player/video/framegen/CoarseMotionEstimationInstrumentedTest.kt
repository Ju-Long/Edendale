package com.babasama.edendale.android.player.video.framegen

import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babasama.edendale.android.player.video.GlPass
import com.babasama.edendale.android.player.video.GpuTimer
import com.babasama.edendale.android.player.video.RenderTarget
import com.babasama.edendale.android.player.video.isGles3
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * G.1.3: what Apple's coarse motion search costs on this device's GPU at
 * 1080p, ported three ways: a GLSL ES 3.0 fragment pass reading RGBA (the
 * exact port), the same reading a one-byte luma texture made by a prepass,
 * and a GLSL ES 3.1 compute shader with Apple's atomic unmatched-block
 * count. Also at half resolution (G.2.3's path for wide sources). Each run
 * checks the vectors on a known pan, so a fast wrong port can't pass.
 *
 * Opt-in: run with `-e g1 true` (optionally `-e g1Iterations 10`). The report
 * is the instrumentation status `g1` and `cache/g1-motion.txt` (`run-as`).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CoarseMotionEstimationInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private lateinit var display: android.opengl.EGLDisplay
    private lateinit var eglContext: android.opengl.EGLContext
    private lateinit var surface: android.opengl.EGLSurface

    @Before
    fun setUp() {
        assumeTrue("G.1 probe: run with -e g1 true", arguments.getString("g1") == "true")
        display = GlUtil.getDefaultEglDisplay()
        eglContext = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        surface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        assertTrue("needs OpenGL ES 3", isGles3())
    }

    @After
    fun tearDown() {
        if (!::display.isInitialized) return
        GlUtil.destroyEglContext(display, eglContext)
        GlUtil.destroyEglSurface(display, surface)
    }

    private val vertexSource by lazy { context.assets.open(GlPass.VERTEX_SHADER).bufferedReader().use { it.readText() } }

    private fun testAsset(name: String) =
        instrumentation.context.assets.open("shaders/framegen/$name").bufferedReader().use { it.readText() }

    private fun program(fragment: String, defines: String = ""): GlProgram =
        GlProgram(vertexSource, fragment.replaceFirst("\n", "\n$defines")).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

    @Test
    fun coarseMotionSearchCostAt1080p() {
        val iterations = arguments.getString("g1Iterations")?.toInt() ?: 6
        val width = 1920
        val height = 1080
        val shift = intArrayOf(6, -4)
        val report = StringBuilder()
        report.appendLine("G.1.3 coarse motion search, ${android.os.Build.MODEL} (${socModel()}), API ${android.os.Build.VERSION.SDK_INT}")
        report.appendLine("GL: ${GLES20.glGetString(GLES20.GL_RENDERER)} / ${GLES20.glGetString(GLES20.GL_VERSION)}")

        val previous = patternTexture(width, height, 0, 0, seed = 1)
        val current = patternTexture(width, height, shift[0], shift[1], seed = 1)
        val timer = GpuTimer()
        val timing = if ("GL_EXT_disjoint_timer_query" in GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()) "GPU timer queries" else "glFinish wall clock"
        report.appendLine("timing: $timing; $iterations runs after 2 warm-up runs; frame N is N−1 panned by (${shift[0]}, ${shift[1]}) px")

        val coarse = testAsset("motion_coarse_es3.glsl")
        val rgbaSearch = program(coarse)
        val lumaSearch = program(coarse, "#define LUMA_INPUT\n")
        val lumaPass = program(testAsset("luma_es3.glsl"))
        val copyPass = GlPass(context, GlPass.COPY)

        // 1. The exact port: RGBA in, luma per sample.
        val full = MotionTarget(width, height)
        val rgba = measure(iterations, timer) { full.search(rgbaSearch, previous, current) }
        report.appendLine(line("fragment, RGBA input, 1080p", rgba, full.check(shift), full.unmatchedFraction()))

        // 2. A luma prepass (one byte per sample), then the search.
        val lumaPrevious = LumaTarget(width, height)
        val lumaCurrent = LumaTarget(width, height)
        lumaPrevious.draw(lumaPass, previous)
        val luma = measure(iterations, timer) {
            lumaCurrent.draw(lumaPass, current)
            full.search(lumaSearch, lumaPrevious.texId, lumaCurrent.texId)
        }
        report.appendLine(line("fragment, luma prepass + search, 1080p", luma, full.check(shift), full.unmatchedFraction()))

        // 3. Half resolution (960×540): a 2:1 box downscale, luma, then the search.
        val halfW = width / 2
        val halfH = height / 2
        val halfPrevious = RenderTarget(halfW, halfH)
        val halfCurrent = RenderTarget(halfW, halfH)
        val halfLumaPrevious = LumaTarget(halfW, halfH)
        val halfLumaCurrent = LumaTarget(halfW, halfH)
        halfPrevious.focus()
        copyPass.draw(previous)
        halfLumaPrevious.draw(lumaPass, halfPrevious.texId)
        val half = MotionTarget(halfW, halfH)
        val halfTimes = measure(iterations, timer) {
            halfCurrent.focus()
            copyPass.draw(current)
            halfLumaCurrent.draw(lumaPass, halfCurrent.texId)
            half.search(lumaSearch, halfLumaPrevious.texId, halfLumaCurrent.texId)
        }
        report.appendLine(line("fragment, half resolution (downscale + luma + search)", halfTimes, half.check(intArrayOf(shift[0] / 2, shift[1] / 2)), half.unmatchedFraction()))

        // 4. The compute port (GLSL ES 3.1), where the context has it.
        val version = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 1)
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 1)) {
            val compute = ComputeSearch(testAsset("motion_coarse_es31.comp"), width, height)
            val times = measure(iterations, timer) { compute.search(previous, current) }
            report.appendLine(line("compute (ES 3.1), RGBA input, 1080p", times, compute.check(shift), compute.unmatchedFraction()))
            compute.release()
        } else {
            report.appendLine("compute: not run (OpenGL ES ${version[0]}.${version[1]})")
        }

        val text = report.toString()
        File(context.cacheDir, "g1-motion.txt").writeText(text)
        instrumentation.sendStatus(0, Bundle().apply { putString("g1", "\n" + text) })

        assertTrue("the RGBA search missed the pan", full.check(shift) > 0.9)
        listOf(rgbaSearch, lumaSearch, lumaPass).forEach { it.delete() }
        copyPass.release()
        timer.release()
    }

    // MARK: - Targets

    /** One RGBA16F texel per 16×16 block: Apple's coarse motion texture. */
    private inner class MotionTarget(val frameWidth: Int, val frameHeight: Int) {
        val width = (frameWidth + BLOCK - 1) / BLOCK
        val height = (frameHeight + BLOCK - 1) / BLOCK
        val texId = GlUtil.createTexture(width, height, true)
        val fboId = GlUtil.createFboForTexture(texId)

        fun search(program: GlProgram, previous: Int, current: Int) {
            GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)
            program.use()
            program.setSamplerTexIdUniform("uPrevSampler", previous, 0)
            program.setSamplerTexIdUniform("uCurrSampler", current, 1)
            program.setIntUniform("uBlockSize", BLOCK)
            program.setIntUniform("uSearchRadius", RADIUS)
            program.setFloatUniform("uUnmatchedError", UNMATCHED_ERROR)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        fun read(): FloatArray {
            GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)
            val buffer = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, buffer)
            GlUtil.checkGlError()
            return FloatArray(width * height * 4).also { buffer.asFloatBuffer().get(it) }
        }

        /** The share of interior blocks whose vector is exactly the pan. */
        fun check(shift: IntArray): Double = matchShare(read(), width, height, frameWidth, frameHeight, shift)

        fun unmatchedFraction(): Double = read().let { mv -> (0 until width * height).count { mv[it * 4 + 3] > 0.5f }.toDouble() / (width * height) }
    }

    /** A one-channel luma texture (R8) with its framebuffer. */
    private class LumaTarget(val width: Int, val height: Int) {
        val texId: Int
        val fboId: Int

        init {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R8, width, height)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            fboId = GlUtil.createFboForTexture(texId)
        }

        fun draw(program: GlProgram, source: Int) {
            GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", source, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }
    }

    /** The compute port: an immutable RGBA16F image and an atomic counter. */
    private inner class ComputeSearch(source: String, val frameWidth: Int, val frameHeight: Int) {
        val width = (frameWidth + BLOCK - 1) / BLOCK
        val height = (frameHeight + BLOCK - 1) / BLOCK
        private val program: Int
        private val image: Int
        private val counter: Int
        private val fbo: Int

        init {
            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(shader, source)
            GLES31.glCompileShader(shader)
            val ok = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES31.GL_TRUE) { "compute shader: " + GLES31.glGetShaderInfoLog(shader) }
            program = GLES31.glCreateProgram()
            GLES31.glAttachShader(program, shader)
            GLES31.glLinkProgram(program)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES31.GL_TRUE) { "compute program: " + GLES31.glGetProgramInfoLog(program) }
            GLES31.glDeleteShader(shader)
            val ids = IntArray(1)
            GLES31.glGenTextures(1, ids, 0)
            image = ids[0]
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, image)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA16F, width, height)
            GLES31.glGenBuffers(1, ids, 0)
            counter = ids[0]
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counter)
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4, null, GLES31.GL_DYNAMIC_READ)
            fbo = GlUtil.createFboForTexture(image)
            GlUtil.checkGlError()
        }

        fun search(previous: Int, current: Int) {
            GLES31.glUseProgram(program)
            // Cleared in the same command stream, as Apple clears its counter per command buffer.
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, counter)
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()))
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, previous)
            GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "uPrevSampler"), 0)
            GLES31.glActiveTexture(GLES31.GL_TEXTURE1)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, current)
            GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "uCurrSampler"), 1)
            GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "uBlockSize"), BLOCK)
            GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "uSearchRadius"), RADIUS)
            GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "uUnmatchedError"), UNMATCHED_ERROR)
            GLES31.glBindImageTexture(0, image, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F)
            GLES31.glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_FRAMEBUFFER_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
            GlUtil.checkGlError()
        }

        fun check(shift: IntArray): Double {
            GlUtil.focusFramebufferUsingCurrentContext(fbo, width, height)
            val buffer = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, buffer)
            val mv = FloatArray(width * height * 4).also { buffer.asFloatBuffer().get(it) }
            return matchShare(mv, width, height, frameWidth, frameHeight, shift)
        }

        fun unmatchedFraction(): Double {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counter)
            val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer
            val count = mapped.order(ByteOrder.nativeOrder()).getInt(0)
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            return count.toDouble() / (width * height)
        }

        fun release() {
            GLES31.glDeleteProgram(program)
            GlUtil.deleteFbo(fbo)
            GLES31.glDeleteTextures(1, intArrayOf(image), 0)
            GLES31.glDeleteBuffers(1, intArrayOf(counter), 0)
        }
    }

    // MARK: - Helpers

    /**
     * Times [work] with GPU timer queries where the driver has them (each read
     * right after the run, which is fine offscreen), else with glFinish.
     */
    private fun measure(iterations: Int, timer: GpuTimer, work: () -> Unit): List<Double> {
        repeat(2) {
            work()
            GLES20.glFinish()
        }
        return (0 until iterations).map {
            val start = SystemClock.elapsedRealtimeNanos()
            timer.begin()
            work()
            timer.end()
            GLES20.glFinish()
            val wall = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            var gpu: Double? = null
            repeat(50) { if (gpu == null) gpu = timer.poll() }
            gpu ?: wall
        }
    }

    private fun line(name: String, times: List<Double>, matched: Double, unmatched: Double): String {
        val sorted = times.sorted()
        return "$name: median ${"%.2f".format(sorted[sorted.size / 2])} ms (min ${"%.2f".format(sorted.first())}, max ${"%.2f".format(sorted.last())}); " +
            "pan found in ${pct(matched)} of interior blocks; unmatched ${pct(unmatched)}"
    }

    private fun pct(value: Double) = "${(value * 1000).roundToInt() / 10.0} %"

    /**
     * Interior blocks (away from the search's clamped edges) whose vector is
     * exactly the inverse of the pan: frame N at c matches N−1 at c − shift.
     */
    private fun matchShare(mv: FloatArray, width: Int, height: Int, frameWidth: Int, frameHeight: Int, shift: IntArray): Double {
        var total = 0
        var hits = 0
        for (by in 2 until height - 2) for (bx in 2 until width - 2) {
            val i = (by * width + bx) * 4
            val dx = (mv[i] * frameWidth).roundToInt()
            val dy = (mv[i + 1] * frameHeight).roundToInt()
            total++
            if (dx == -shift[0] && dy == -shift[1]) hits++
        }
        return hits.toDouble() / total
    }

    /**
     * An RGBA8 frame of value noise at a few scales, panned by (dx, dy): the
     * pixel at (x, y) shows the pattern at (x − dx, y − dy). Detailed enough
     * that every 16×16 block has one clear match.
     */
    private fun patternTexture(width: Int, height: Int, dx: Int, dy: Int, seed: Int): Int {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until height) for (x in 0 until width) {
            val px = (x - dx).toDouble()
            val py = (y - dy).toDouble()
            val v = 0.45 * noise(px / 23.0, py / 23.0, seed) + 0.35 * noise(px / 7.0, py / 7.0, seed + 1) +
                0.2 * (0.5 + 0.5 * sin(px / 31.0 + py / 47.0 + seed))
            val r = (v * 255).roundToInt().coerceIn(0, 255)
            val g = ((0.6 * v + 0.4 * noise(px / 11.0, py / 11.0, seed + 2)) * 255).roundToInt().coerceIn(0, 255)
            val b = ((1 - v) * 255).roundToInt().coerceIn(0, 255)
            buffer.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte())
        }
        buffer.flip()
        val tex = GlUtil.createTexture(width, height, false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GlUtil.checkGlError()
        return tex
    }

    /** Smooth value noise in 0…1. */
    private fun noise(x: Double, y: Double, seed: Int): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        fun h(ix: Int, iy: Int): Double {
            var n = ix * 374761393 + iy * 668265263 + seed * 1274126177
            n = (n xor (n ushr 13)) * 1274126177
            return ((n xor (n ushr 16)) and 0xFFFF) / 65535.0
        }
        val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * fx
        val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * fx
        return a + (b - a) * fy
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private companion object {
        /** Apple's coarse block size, search radius, and unmatched-block threshold. */
        const val BLOCK = 16
        const val RADIUS = 16
        const val UNMATCHED_ERROR = 0.06f
    }
}

/** The SoC name where the platform reports it (API 31+). */
private fun socModel(): String =
    if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "unknown"
