package com.babasama.edendale.android.player.video

import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * G.6.T2: Motion Smoothing's passes on a real OpenGL ES 3.0 context,
 * offscreen, ported from Apple's `FrameInterpolationTests` GPU cases with
 * their frames: smooth gray noise that block matching can follow.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MotionSmoothingPassesInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var display: android.opengl.EGLDisplay
    private lateinit var eglContext: android.opengl.EGLContext
    private lateinit var surface: android.opengl.EGLSurface
    private lateinit var passes: MotionSmoothingPasses
    private val textures = mutableListOf<Int>()
    private val targets = mutableListOf<RenderTarget>()

    @Before
    fun setUp() {
        display = GlUtil.getDefaultEglDisplay()
        eglContext = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        surface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        assertTrue("needs OpenGL ES 3", isGles3())
        passes = MotionSmoothingPasses(context)
    }

    @After
    fun tearDown() {
        passes.release()
        textures.forEach { GlUtil.deleteTexture(it) }
        targets.forEach { it.release() }
        GlUtil.destroyEglContext(display, eglContext)
        GlUtil.destroyEglSurface(display, surface)
    }

    // MARK: - Motion vectors

    @Test
    fun aHorizontalPanGivesVectorsOfTheRightSign() {
        val shift = 8
        synthesize(textured(shift = 0), textured(shift = shift))
        val vectors = interiorVectors()
        // The content moved right, so each block of frame N matches N−1 to its left.
        val onTarget = vectors.count { (dx, dy) -> dx == -shift && dy == 0 }
        assertTrue("$onTarget of ${vectors.size} interior blocks found (−$shift, 0)", onTarget >= vectors.size * 9 / 10)
        assertFalse(passes.readSceneCut().first)
    }

    @Test
    fun aStaticSceneGivesZeroVectors() {
        val frame = textured(shift = 0)
        synthesize(frame, frame)
        val vectors = interiorVectors()
        assertTrue("every block stays put", vectors.all { (dx, dy) -> dx == 0 && dy == 0 })
        assertEquals(0, passes.readSceneCut().second)
    }

    // MARK: - The synthetic frame

    @Test
    fun theInterpolatedPanLandsOnTheMidpoint() {
        val shift = 8
        val output = synthesize(textured(shift = 0), textured(shift = shift))
        val midpointError = meanAbsoluteError(output, textured(shift = shift / 2))
        assertTrue("synthetic frame vs the pan's midpoint: $midpointError", midpointError < 2.0)
        val copyError = meanAbsoluteError(output, textured(shift = shift))
        assertTrue("synthetic frame vs the new frame: $copyError", copyError > 5.0)
    }

    @Test
    fun unrelatedFramesAreASceneCutAndRepeatThePreviousFrame() {
        val previous = textured(shift = 0)
        val output = synthesize(previous, textured(shift = 0, seed = 7))
        assertTrue(passes.readSceneCut().first)
        assertTrue(meanAbsoluteError(output, previous, margin = 0) < 0.5)
    }

    @Test
    fun letterboxBarsAndFlatAreasStayInPlace() {
        val frame = textured(shift = 0)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            if (y < 20 || y >= HEIGHT - 20) frame[y * WIDTH + x] = 0
            if (y in 45 until 75 && x in 60 until 100) frame[y * WIDTH + x] = 128
        }
        val output = synthesize(frame, frame)
        val worst = output.indices.maxOf { abs(output[it] - frame[it]) }
        assertTrue("largest change in a still frame: $worst levels", worst <= 2)
    }

    @Test
    fun aFadeOfAboutFourPercentBlendsRatherThanCuts() {
        val previous = textured(shift = 0)
        val brighter = IntArray(previous.size) { minOf(255, previous[it] + 10) }
        val halfway = IntArray(previous.size) { minOf(255, previous[it] + 5) }
        val output = synthesize(previous, brighter)
        assertFalse(passes.readSceneCut().first)
        val error = meanAbsoluteError(output, halfway)
        assertTrue("fade vs halfway: $error", error < 1.5)
    }

    // MARK: - History and half resolution

    @Test
    fun theHistoryNeedsACommitAndASize() {
        val first = texture(textured(shift = 0))
        passes.ingest(first, PixelSize(WIDTH, HEIGHT), halfResolution = false)
        assertFalse("nothing committed yet", passes.hasPrevious)
        passes.commit()
        passes.ingest(texture(textured(shift = 2)), PixelSize(WIDTH, HEIGHT), halfResolution = false)
        assertTrue(passes.hasPrevious)
        // The governor's half-resolution step changes the search size: the history no longer compares.
        passes.ingest(texture(textured(shift = 4)), PixelSize(WIDTH, HEIGHT), halfResolution = true)
        assertFalse(passes.hasPrevious)
        assertEquals(PixelSize(WIDTH / 2, HEIGHT / 2), passes.searchSize)
        passes.commit()
        passes.reset()
        assertFalse(passes.hasPrevious)
    }

    @Test
    fun theHalfResolutionSearchStillFindsTheMidpoint() {
        val shift = 8
        val output = synthesize(textured(shift = 0), textured(shift = shift), halfResolution = true)
        assertEquals(2, passes.searchScale)
        val midpointError = meanAbsoluteError(output, textured(shift = shift / 2))
        assertTrue("half-resolution synthetic frame vs the midpoint: $midpointError", midpointError < 3.0)
    }

    // MARK: - Phone timings (opt-in)

    /**
     * Not a pass/fail check: times every pass of one synthetic frame at
     * 1080p, at full and half resolution, for G.1.3 and G.6.D1 on a phone.
     * Run with `-e motionSmoothingBenchmark true`; results go to logcat
     * (`MotionSmoothingBench`) and the instrumentation status.
     */
    @Test
    fun benchmark1080p() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionSmoothingBenchmark") == "true")
        val size = PixelSize(1920, 1080)
        val frames = (0 until 4).map { shift -> texture(textured(shift = shift * 6, width = size.width, height = size.height), size) }
        val output = RenderTarget(size.width, size.height).also { targets.add(it) }
        val results = Bundle()
        for (half in listOf(false, true)) {
            passes.reset()
            val times = (0 until 40).map { i ->
                val start = SystemClock.elapsedRealtimeNanos()
                passes.ingest(frames[i % frames.size], size, half)
                if (passes.hasPrevious) passes.synthesize(output.fboId)
                passes.drawCurrent(output.fboId)
                passes.commit()
                GLES20.glFinish()
                (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }.drop(10).sorted()
            val median = times[times.size / 2]
            val label = if (half) "half" else "full"
            Log.i("MotionSmoothingBench", "1080p $label resolution: median %.2f ms, best %.2f ms, worst %.2f ms".format(median, times.first(), times.last()))
            results.putDouble("${label}MedianMs", median)
            // Each pass on its own, finished before the next starts.
            val perPass = LinkedHashMap<String, MutableList<Double>>()
            var mark = 0L
            passes.afterPass = { name ->
                GLES20.glFinish()
                val now = SystemClock.elapsedRealtimeNanos()
                perPass.getOrPut(name) { mutableListOf() }.add((now - mark) / 1_000_000.0)
                mark = now
            }
            repeat(12) { i ->
                GLES20.glFinish()
                mark = SystemClock.elapsedRealtimeNanos()
                passes.ingest(frames[i % frames.size], size, half)
                if (passes.hasPrevious) passes.synthesize(output.fboId)
                passes.drawCurrent(output.fboId)
                passes.commit()
            }
            passes.afterPass = null
            val breakdown = perPass.entries.joinToString(", ") { (name, ms) -> "%s %.2f".format(name, ms.drop(2).sorted().let { it[it.size / 2] }) }
            Log.i("MotionSmoothingBench", "1080p $label resolution by pass (ms): $breakdown")
            results.putString("${label}Passes", breakdown)
        }
        instrumentation.sendStatus(0, results)
    }

    // MARK: - Helpers

    /** Ingests [previous], commits it, ingests [current], and returns the synthetic frame as gray levels. */
    private fun synthesize(previous: IntArray, current: IntArray, halfResolution: Boolean = false): IntArray {
        val size = PixelSize(WIDTH, HEIGHT)
        passes.ingest(texture(previous), size, halfResolution)
        passes.commit()
        passes.ingest(texture(current), size, halfResolution)
        val output = RenderTarget(WIDTH, HEIGHT).also { targets.add(it) }
        passes.synthesize(output.fboId)
        return readGray(output)
    }

    /** Refined vectors away from the frame's edges, where a pan uncovers new content. */
    private fun interiorVectors(): List<Pair<Int, Int>> {
        val grid = passes.refinedGrid
        val raw = passes.readRefinedVectors()
        val margin = 24 / MotionSmoothingPasses.REFINE_BLOCK
        return buildList {
            for (y in margin until grid.height - margin) for (x in margin until grid.width - margin) {
                val i = (y * grid.width + x) * 2
                add(raw[i] to raw[i + 1])
            }
        }
    }

    private fun texture(gray: IntArray, size: PixelSize = PixelSize(WIDTH, HEIGHT)): Int {
        val buffer = ByteBuffer.allocateDirect(size.width * size.height * 4).order(ByteOrder.nativeOrder())
        gray.forEach { value ->
            repeat(3) { buffer.put(value.toByte()) }
            buffer.put(255.toByte())
        }
        buffer.flip()
        val tex = GlUtil.createTexture(size.width, size.height, false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, size.width, size.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GlUtil.checkGlError()
        textures.add(tex)
        return tex
    }

    /** The green channel of [target] (the frames are gray). */
    private fun readGray(target: RenderTarget): IntArray {
        target.focus()
        val buffer = ByteBuffer.allocateDirect(target.width * target.height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, target.width, target.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GlUtil.checkGlError()
        return IntArray(target.width * target.height) { buffer.get(it * 4 + 1).toInt() and 0xFF }
    }

    /** Mean absolute difference over the interior; a pan uncovers new content at the edges. */
    private fun meanAbsoluteError(a: IntArray, b: IntArray, margin: Int = 24): Double {
        var total = 0.0
        var count = 0
        for (y in margin until HEIGHT - margin) for (x in margin until WIDTH - margin) {
            total += abs(a[y * WIDTH + x] - b[y * WIDTH + x])
            count++
        }
        return total / count
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 120

        /**
         * Apple's test frame: deterministic smooth noise moved right by
         * [shift] pixels, as gray levels; different seeds give unrelated images.
         */
        fun textured(shift: Int, seed: Int = 1, width: Int = WIDTH, height: Int = HEIGHT): IntArray {
            fun lattice(x: Int, y: Int, seed: Int): Double {
                var v = (x * 73_856_093) xor (y * 19_349_663) xor seed
                v = (v xor (v ushr 13)) * 1_274_126_177
                return ((v xor (v ushr 16)) and 0xFFFF) / 65_535.0
            }
            fun noise(x: Double, y: Double, cell: Double, seed: Int): Double {
                val fx = x / cell
                val fy = y / cell
                val x0 = Math.floor(fx).toInt()
                val y0 = Math.floor(fy).toInt()
                val tx = fx - x0
                val ty = fy - y0
                val top = lattice(x0, y0, seed) * (1 - tx) + lattice(x0 + 1, y0, seed) * tx
                val bottom = lattice(x0, y0 + 1, seed) * (1 - tx) + lattice(x0 + 1, y0 + 1, seed) * tx
                return top * (1 - ty) + bottom * ty
            }
            return IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                val sx = (x - shift + 100).toDouble()
                val v = 0.65 * noise(sx, y.toDouble(), 12.0, seed) + 0.35 * noise(sx, y.toDouble(), 4.0, seed + 1)
                (30 + v * 200).toInt()
            }
        }
    }
}
