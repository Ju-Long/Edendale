package com.babasama.edendale.android.player.video

import android.opengl.EGL14
import android.opengl.GLES20
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * F.2.T3, F.3.T2, F.4.T1, F.5.T1: the enhancement passes on a real GL ES 3.0
 * context, offscreen, with the pixels read back.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class EnhancementPassesInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var display: android.opengl.EGLDisplay
    private lateinit var eglContext: android.opengl.EGLContext
    private lateinit var surface: android.opengl.EGLSurface

    @Before
    fun setUp() {
        display = GlUtil.getDefaultEglDisplay()
        eglContext = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        surface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        assertTrue("needs OpenGL ES 3", isGles3())
    }

    @After
    fun tearDown() {
        GlUtil.destroyEglContext(display, eglContext)
        GlUtil.destroyEglSurface(display, surface)
    }

    // MARK: - Helpers

    /** An RGBA8 texture from [pixels] (0…1 floats, rgb per pixel, row by row from the bottom). */
    private fun texture(width: Int, height: Int, rgb: (x: Int, y: Int) -> FloatArray): Int {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until height) for (x in 0 until width) {
            val c = rgb(x, y)
            for (i in 0 until 3) buffer.put((c[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buffer.put(255.toByte())
        }
        buffer.flip()
        val tex = GlUtil.createTexture(width, height, false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GlUtil.checkGlError()
        return tex
    }

    /** The target's pixels as 0…1 rgb floats, indexed [y][x]. */
    private fun read(target: RenderTarget): Array<Array<FloatArray>> {
        target.focus()
        val buffer = ByteBuffer.allocateDirect(target.width * target.height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, target.width, target.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GlUtil.checkGlError()
        return Array(target.height) { y ->
            Array(target.width) { x ->
                val i = (y * target.width + x) * 4
                FloatArray(3) { c -> (buffer.get(i + c).toInt() and 0xFF) / 255f }
            }
        }
    }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    // MARK: - F.2.T3

    @Test
    fun colorPassMatchesTheKotlinReference() {
        val colors = listOf(
            floatArrayOf(0.8f, 0.4f, 0.1f),
            floatArrayOf(0.2f, 0.5f, 0.9f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        val settings = listOf(
            VideoAdjustmentValues(),
            VideoAdjustmentValues(brightness = 1.2f, contrast = 1.1f),
            VideoAdjustmentValues(gamma = 1.8f, saturation = 0.4f),
            VideoAdjustmentValues(hue = 120f),
            VideoAdjustmentValues(brightness = 0.7f, contrast = 1.6f, gamma = 0.6f, saturation = 2.2f, hue = 275f),
        )
        val pass = ColorPass(context)
        val target = RenderTarget(4, 1)
        try {
            for (adjustment in settings) {
                val input = texture(4, 1) { x, _ -> colors[x] }
                target.focus()
                pass.draw(input, adjustment)
                val out = read(target)[0]
                colors.forEachIndexed { x, color ->
                    // The reference works on the 8-bit value the texture actually holds.
                    val stored = DoubleArray(3) { ((color[it] * 255f + 0.5f).toInt()) / 255.0 }
                    val expected = ColorMath.apply(stored, adjustment)
                    for (c in 0 until 3) {
                        assertEquals("$adjustment color $x channel $c", expected[c], out[x][c].toDouble(), 1.5 / 255)
                    }
                }
                GlUtil.deleteTexture(input)
            }
        } finally {
            pass.release()
            target.release()
        }
    }

    // MARK: - F.3.T2

    @Test
    fun easuKeepsAStepEdgeMonotonicWithoutRinging() {
        val source = PixelSize(64, 32)
        val output = PixelSize(144, 72)
        val input = texture(source.width, source.height) { x, _ -> if (x < 32) floatArrayOf(0.2f, 0.2f, 0.2f) else floatArrayOf(0.8f, 0.8f, 0.8f) }
        val pass = UpscalePass(context, useEasu = true)
        val target = RenderTarget(output.width, output.height)
        try {
            target.focus()
            pass.draw(input, source, output)
            val pixels = read(target)
            assertEquals(output.height, pixels.size)
            assertEquals(output.width, pixels[0].size)
            val tolerance = 2f / 255
            for (row in pixels) {
                var previous = -1f
                for (pixel in row) {
                    val v = pixel[1]
                    assertTrue("no ringing below the dark side: $v", v >= 0.2f - tolerance)
                    assertTrue("no ringing above the light side: $v", v <= 0.8f + tolerance)
                    assertTrue("monotonic across the edge: $previous then $v", v >= previous - tolerance)
                    previous = v
                }
                assertEquals(0.2f, row.first()[1], tolerance)
                assertEquals(0.8f, row.last()[1], tolerance)
            }
        } finally {
            pass.release()
            target.release()
            GlUtil.deleteTexture(input)
        }
    }

    @Test
    fun lanczosFallbackFillsTheTargetSize() {
        val input = texture(8, 8) { _, _ -> floatArrayOf(0.5f, 0.25f, 0.75f) }
        val pass = UpscalePass(context, useEasu = false)
        val target = RenderTarget(16, 16)
        try {
            target.focus()
            pass.draw(input, PixelSize(8, 8), PixelSize(16, 16))
            val pixels = read(target)
            pixels.flatten().forEach { assertEquals(0.5f, it[0], 1.5f / 255) }
        } finally {
            pass.release()
            target.release()
            GlUtil.deleteTexture(input)
        }
    }

    // MARK: - F.4.T1

    @Test
    fun sharpnessZeroIsTheIdentityAndContrastGrowsWithSharpness() {
        val random = Random(7)
        val noise = texture(16, 16) { _, _ -> floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat()) }
        val pass = SharpenPass(context)
        val target = RenderTarget(16, 16)
        val identityRef = RenderTarget(16, 16)
        try {
            // Identity: compare the pass at 0 with a straight read of the input through a copy.
            identityRef.focus()
            GlPass(context, GlPass.COPY).also { it.draw(noise) }.release()
            target.focus()
            pass.draw(noise, 0f)
            assertEquals(read(identityRef).map { r -> r.map { it.toList() } }, read(target).map { r -> r.map { it.toList() } })

            // A blurred edge: a ramp from 0.3 to 0.7 across four pixels.
            val ramp = floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f)
            val edge = texture(16, 4) { x, _ -> FloatArray(3) { ramp[x] } }
            val edgeTarget = RenderTarget(16, 4)
            fun contrastAt(sharpness: Float): Float {
                edgeTarget.focus()
                pass.draw(edge, sharpness)
                val row = read(edgeTarget)[1]
                // Local contrast across the ramp's neighbours.
                return (4..9).maxOf { abs(row[it + 1][0] - row[it][0]) }
            }
            val soft = contrastAt(0.1f)
            val sharp = contrastAt(1f)
            assertTrue("contrast $soft then $sharp", sharp > soft)
            // Flat areas don't change.
            edgeTarget.focus()
            pass.draw(edge, 1f)
            val row = read(edgeTarget)[1]
            assertEquals(0.3f, row[1][0], 1f / 255)
            assertEquals(0.7f, row[13][0], 1f / 255)
            edgeTarget.release()
            GlUtil.deleteTexture(edge)
        } finally {
            pass.release()
            target.release()
            identityRef.release()
            GlUtil.deleteTexture(noise)
        }
    }

    // MARK: - F.5.T1

    @Test
    fun denoiseConvergesOnStaticNoiseKeepsMotionAndResets() {
        val size = PixelSize(16, 16)
        val pass = DenoisePass(context)
        val target = RenderTarget(size.width, size.height)
        try {
            // Static scene, fresh noise each frame: the output's spread shrinks toward the mean.
            var inputSpread = 0f
            var outputSpread = 0f
            repeat(12) { frame ->
                val random = Random(frame)
                val noisy = texture(size.width, size.height) { _, _ -> FloatArray(3) { 0.5f + (random.nextFloat() - 0.5f) * 0.06f } }
                pass.draw(noisy, size, target.fboId, strength = 1f)
                if (frame == 11) {
                    val out = read(target).flatten()
                    outputSpread = out.maxOf { abs(it[1] - 0.5f) }
                    inputSpread = 0.03f
                }
                GlUtil.deleteTexture(noisy)
            }
            assertTrue("spread $outputSpread vs $inputSpread", outputSpread < inputSpread * 0.8f)

            // A moving edge: where the frame changed by far more than the threshold, the current frame wins.
            pass.reset()
            val dark = texture(size.width, size.height) { _, _ -> floatArrayOf(0.1f, 0.1f, 0.1f) }
            pass.draw(dark, size, target.fboId, strength = 1f)
            val moved = texture(size.width, size.height) { x, _ -> if (x < 8) floatArrayOf(0.9f, 0.9f, 0.9f) else floatArrayOf(0.1f, 0.1f, 0.1f) }
            pass.draw(moved, size, target.fboId, strength = 1f)
            val out = read(target)
            assertEquals("no ghost of the old frame", 0.9f, out[4][2][0], 1.5f / 255)
            assertEquals(0.1f, out[4][12][0], 1.5f / 255)

            // reset() clears the history: the next frame passes through untouched.
            pass.reset()
            val gray = texture(size.width, size.height) { _, _ -> floatArrayOf(0.45f, 0.45f, 0.45f) }
            pass.draw(gray, size, target.fboId, strength = 1f)
            read(target).flatten().forEach { assertEquals(0.45f, it[0], 1.5f / 255) }
            listOf(dark, moved, gray).forEach(GlUtil::deleteTexture)
        } finally {
            pass.release()
            target.release()
        }
    }
}
