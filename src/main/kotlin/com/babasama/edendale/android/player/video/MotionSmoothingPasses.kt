package com.babasama.edendale.android.player.video

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.ceil

/**
 * Motion Smoothing's GPU work (G.2, G.3; Apple's MotionEstimation.metal and
 * FrameInterpolation.metal), as OpenGL ES 3.0 fragment passes: Findings — G.1
 * measured a compute port at the same cost, and ES 3.0 reaches more phones.
 * A plain class on the GL thread with no Media3 effect types, so instrumented
 * tests drive it directly.
 *
 * For each real frame: [ingest] copies it into the history and makes its luma
 * at the search's resolution; while the previous frame is a neighbour of the
 * same size, [synthesize] draws the frame halfway between them; [drawCurrent]
 * draws the real frame; [commit] makes it the previous frame. Committing
 * comes last, never before synthesizing (G.4.2).
 *
 * The search runs at half resolution for sources wider than 1920 px (G.2.3)
 * and whenever the governor asks for it (G.5.2).
 */
@OptIn(UnstableApi::class)
class MotionSmoothingPasses(context: Context) {
    private val appContext = context.applicationContext

    /** All or nothing: a shader that doesn't build releases the ones built before it. */
    private val programs: List<FrameGenProgram> = buildList {
        try {
            for (asset in listOf(LUMA, COARSE, REFINE, CUT, WARP)) add(FrameGenProgram(appContext, asset))
        } catch (e: Exception) {
            forEach { it.release() }
            throw e
        }
    }
    private val luma = programs[0]
    private val coarse = programs[1]
    private val refine = programs[2]
    private val cut = programs[3]
    private val warp = programs[4]
    private val copy = try {
        GlPass(appContext, GlPass.COPY)
    } catch (e: Exception) {
        programs.forEach { it.release() }
        throw e
    }

    /** Two slots each, swapped by [commit]: the previous frame and the current one. */
    private val colors = arrayOfNulls<RenderTarget>(2)
    private val lumas = arrayOfNulls<GlTarget>(2)
    private var currentSlot = 0
    private var coarseVectors: GlTarget? = null
    private var refinedVectors: GlTarget? = null
    private var rowCounts: GlTarget? = null
    private var cutVerdict: GlTarget? = null

    /** The real frames' size, from the last [ingest]. */
    var frameSize = PixelSize(0, 0)
        private set

    /** The motion search's size: the frame's, or half of it. */
    var searchSize = PixelSize(0, 0)
        private set

    /** Output pixels per search pixel: 1 or 2. */
    var searchScale = 1
        private set

    /** The previous slot holds the last committed frame, at the current sizes. */
    var hasPrevious = false
        private set

    /** Called after each pass with its name; the benchmark times passes with it. Null in the app. */
    internal var afterPass: ((String) -> Unit)? = null

    /** Takes the real frame [inputTexId] of [size]; doesn't change the history until [commit]. */
    fun ingest(inputTexId: Int, size: PixelSize, halfResolution: Boolean) {
        val scale = if (halfResolution || size.width > HALF_RESOLUTION_WIDTH) 2 else 1
        val search = PixelSize((size.width / scale).coerceAtLeast(1), (size.height / scale).coerceAtLeast(1))
        if (size != frameSize || search != searchSize) {
            // A new size, or the governor's half-resolution step: the old history doesn't compare.
            hasPrevious = false
            frameSize = size
            searchSize = search
            searchScale = scale
        }
        val color = colorTarget(currentSlot)
        color.focus()
        copy.draw(inputTexId)
        afterPass?.invoke("copy in")
        val lumaTarget = lumaTarget(currentSlot)
        lumaTarget.focus()
        luma.use()
        luma.texture("uTexSampler", 0, inputTexId)
        luma.int("uScale", scale)
        luma.draw()
        afterPass?.invoke("luma")
    }

    /**
     * Draws the frame halfway between the previous and the current one into
     * [outputFbo] (the frame's size): the motion search, the scene-cut count,
     * then the warp. Needs [hasPrevious].
     */
    fun synthesize(outputFbo: Int, blendTime: Float = 0.5f) {
        check(hasPrevious) { "No previous frame to blend with" }
        estimateMotion()
        GlUtil.focusFramebufferUsingCurrentContext(outputFbo, frameSize.width, frameSize.height)
        warp.use()
        warp.texture("uPrevSampler", 0, colorTarget(1 - currentSlot).texId)
        warp.texture("uCurrSampler", 1, colorTarget(currentSlot).texId)
        warp.texture("uMotionSampler", 2, refinedVectors!!.texId)
        warp.texture("uCutSampler", 3, cutVerdict!!.texId)
        warp.int("uBlockSize", REFINE_BLOCK)
        warp.float("uMotionScale", searchScale.toFloat())
        warp.float("uBlendTime", blendTime)
        warp.draw()
        afterPass?.invoke("warp")
    }

    /** Draws the current real frame into [outputFbo]. */
    fun drawCurrent(outputFbo: Int) {
        GlUtil.focusFramebufferUsingCurrentContext(outputFbo, frameSize.width, frameSize.height)
        copy.draw(colorTarget(currentSlot).texId)
        afterPass?.invoke("copy out")
    }

    /** The frame just ingested becomes the previous one. */
    fun commit() {
        currentSlot = 1 - currentSlot
        hasPrevious = true
    }

    /** Forgets the previous frame: a seek, the end of a stream, or Motion Smoothing pausing. */
    fun reset() {
        hasPrevious = false
    }

    fun release() {
        copy.release()
        programs.forEach { it.release() }
        (colors.toList() + lumas + listOf(coarseVectors, refinedVectors, rowCounts, cutVerdict)).forEach {
            when (it) {
                is RenderTarget -> it.release()
                is GlTarget -> it.release()
            }
        }
        colors.fill(null)
        lumas.fill(null)
        coarseVectors = null
        refinedVectors = null
        rowCounts = null
        cutVerdict = null
    }

    // MARK: - Motion estimation (G.2)

    /** The coarse and refined searches and the scene-cut count, previous luma against current. */
    private fun estimateMotion() {
        val previous = lumaTarget(1 - currentSlot).texId
        val current = lumaTarget(currentSlot).texId
        // Integer targets can't be blended.
        GLES20.glDisable(GLES20.GL_BLEND)

        val coarseGrid = grid(searchSize, COARSE_BLOCK)
        val coarseTarget = intTarget(coarseVectors, coarseGrid, GLES30.GL_RGBA16I).also { coarseVectors = it }
        coarseTarget.focus()
        coarse.use()
        coarse.texture("uPrevSampler", 0, previous)
        coarse.texture("uCurrSampler", 1, current)
        coarse.int("uBlockSize", COARSE_BLOCK)
        coarse.int("uSearchRadius", COARSE_SEARCH_RADIUS)
        coarse.float("uUnmatchedError", SCENE_CUT_BLOCK_ERROR)
        coarse.draw()
        afterPass?.invoke("coarse")

        val refinedTarget = intTarget(refinedVectors, grid(searchSize, REFINE_BLOCK), GLES30.GL_RGBA16I).also { refinedVectors = it }
        refinedTarget.focus()
        refine.use()
        refine.texture("uPrevSampler", 0, previous)
        refine.texture("uCurrSampler", 1, current)
        refine.texture("uCoarseSampler", 2, coarseTarget.texId)
        refine.int("uBlockSize", REFINE_BLOCK)
        refine.int("uCoarseBlockSize", COARSE_BLOCK)
        refine.draw()
        afterPass?.invoke("refine")

        val rows = intTarget(rowCounts, PixelSize(1, coarseGrid.height), GLES30.GL_R32I).also { rowCounts = it }
        rows.focus()
        cut.use()
        cut.texture("uCountSampler", 0, coarseTarget.texId)
        cut.int("uMode", 0)
        cut.int("uCutCount", 0)
        cut.draw()
        afterPass?.invoke("cut rows")

        val verdict = intTarget(cutVerdict, PixelSize(1, 1), GLES30.GL_RGBA32I).also { cutVerdict = it }
        verdict.focus()
        cut.use()
        cut.texture("uCountSampler", 0, rows.texId)
        cut.int("uMode", 1)
        cut.int("uCutCount", cutBlockCount(coarseGrid))
        cut.draw()
        afterPass?.invoke("cut total")
    }

    // MARK: - Readback for tests (G.6.T2)

    /** The refined vectors after the last [synthesize]: (dx, dy) per 4×4 block, row by row, in search pixels. */
    fun readRefinedVectors(): IntArray {
        val target = refinedVectors ?: return IntArray(0)
        val texels = readInts(target)
        return IntArray(target.width * target.height * 2) { i -> texels[(i / 2) * 4 + i % 2] }
    }

    /** The refined vector grid's size. */
    val refinedGrid: PixelSize get() = refinedVectors?.let { PixelSize(it.width, it.height) } ?: PixelSize(0, 0)

    /** After the last [synthesize]: whether it was a scene cut, and how many coarse blocks were unmatched. */
    fun readSceneCut(): Pair<Boolean, Int> {
        val target = cutVerdict ?: return false to 0
        val texel = readInts(target)
        return (texel[0] != 0) to texel[1]
    }

    private fun readInts(target: GlTarget): IntArray {
        target.focus()
        val buffer = ByteBuffer.allocateDirect(target.width * target.height * 4 * 4).order(ByteOrder.nativeOrder())
        // RGBA_INTEGER with INT is the read every signed integer buffer supports.
        GLES30.glReadPixels(0, 0, target.width, target.height, GLES30.GL_RGBA_INTEGER, GLES20.GL_INT, buffer)
        GlUtil.checkGlError()
        val ints: IntBuffer = buffer.asIntBuffer()
        return IntArray(ints.remaining()).also { ints.get(it) }
    }

    // MARK: - Targets

    private fun colorTarget(slot: Int): RenderTarget {
        val existing = colors[slot]
        if (existing != null && existing.width == frameSize.width && existing.height == frameSize.height) return existing
        existing?.release()
        return RenderTarget(frameSize.width, frameSize.height).also { colors[slot] = it }
    }

    private fun lumaTarget(slot: Int): GlTarget {
        val existing = lumas[slot]
        if (existing != null && existing.width == searchSize.width && existing.height == searchSize.height) return existing
        existing?.release()
        return GlTarget(searchSize.width, searchSize.height, GLES30.GL_R8).also { lumas[slot] = it }
    }

    private fun intTarget(existing: GlTarget?, size: PixelSize, format: Int): GlTarget {
        if (existing != null && existing.width == size.width && existing.height == size.height) return existing
        existing?.release()
        return GlTarget(size.width, size.height, format)
    }

    companion object {
        const val LUMA = "shaders/edendale_fg_luma_es3.glsl"
        const val COARSE = "shaders/edendale_fg_coarse_es3.glsl"
        const val REFINE = "shaders/edendale_fg_refine_es3.glsl"
        const val CUT = "shaders/edendale_fg_cut_es3.glsl"
        const val WARP = "shaders/edendale_fg_warp_es3.glsl"

        /** Apple's constants (FrameInterpolator.swift). */
        const val COARSE_BLOCK = 16
        const val REFINE_BLOCK = 4
        const val COARSE_SEARCH_RADIUS = 16

        /** A 16×16 block is unmatched when even its best match differs by more than this mean luma (0–1). */
        const val SCENE_CUT_BLOCK_ERROR = 0.06f

        /** A pair with at least this share of unmatched blocks is a scene cut: frame N−1 is shown instead of a blend. */
        const val SCENE_CUT_BLOCK_FRACTION = 0.3f

        /** Sources wider than this run the search at half resolution (G.2.3). */
        const val HALF_RESOLUTION_WIDTH = 1920

        /** Blocks of [block] pixels covering [size], partial blocks included. */
        fun grid(size: PixelSize, block: Int) = PixelSize((size.width + block - 1) / block, (size.height + block - 1) / block)

        /** 30 % of the blocks rounded up, in single precision as Apple counts them, and at least one. */
        fun cutBlockCount(coarseGrid: PixelSize): Int =
            maxOf(1, ceil(SCENE_CUT_BLOCK_FRACTION * (coarseGrid.width * coarseGrid.height).toFloat()).toInt())
    }
}

/**
 * A texture with immutable storage in [internalFormat] and a framebuffer,
 * sampled with texelFetch only: nearest filtering, which integer formats
 * need to be complete.
 */
@OptIn(UnstableApi::class)
internal class GlTarget(val width: Int, val height: Int, internalFormat: Int) {
    val texId: Int
    val fboId: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, width, height)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GlUtil.checkGlError()
        fboId = GlUtil.createFboForTexture(texId)
    }

    fun focus() = GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)

    fun release() {
        GlUtil.deleteFbo(fboId)
        GlUtil.deleteTexture(texId)
    }
}

/**
 * A full-screen pass compiled straight from Edendale's GLSL, for the passes
 * that read or write integer textures: Media3's `GlProgram` binds every
 * sampler it finds as a float `sampler2D` and refuses `isampler2D`.
 */
@OptIn(UnstableApi::class)
internal class FrameGenProgram(context: Context, fragmentAsset: String) {
    private val programId: Int
    private val positionLocation: Int
    private val locations = HashMap<String, Int>()

    init {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, context.readAsset(GlPass.VERTEX_SHADER))
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, context.readAsset(fragmentAsset))
        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertex)
        GLES30.glAttachShader(programId, fragment)
        GLES30.glLinkProgram(programId)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linked, 0)
        // Flagged for deletion; they go with the program.
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (linked[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(programId)
            GLES30.glDeleteProgram(programId)
            throw GlUtil.GlException("Linking $fragmentAsset failed: $log")
        }
        positionLocation = GLES30.glGetAttribLocation(programId, "aFramePosition")
    }

    fun use() {
        GLES30.glUseProgram(programId)
    }

    /** Binds [texId] to [unit] for the sampler [name]; the texture keeps its own filtering. */
    fun texture(name: String, unit: Int, texId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(location(name), unit)
    }

    fun int(name: String, value: Int) = GLES30.glUniform1i(location(name), value)

    fun float(name: String, value: Float) = GLES30.glUniform1f(location(name), value)

    /** Draws the quad into the focused framebuffer. */
    fun draw() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glVertexAttribPointer(positionLocation, 4, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    private fun location(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(programId, name) }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw GlUtil.GlException("Compiling a Motion Smoothing shader failed: $log")
        }
        return shader
    }

    private companion object {
        val QUAD: FloatBuffer = GlUtil.createBuffer(GlUtil.getNormalizedCoordinateBounds())

        fun Context.readAsset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
    }
}
