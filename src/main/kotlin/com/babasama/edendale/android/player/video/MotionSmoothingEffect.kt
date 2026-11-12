package com.babasama.edendale.android.player.video

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor

/**
 * Motion Smoothing for ExoPlayer (G.4.3): the presentation path G.1.1 proved.
 * It runs after [EnhancementEffect], so it blends enhanced frames, as Apple
 * does. For each frame whose predecessor is a direct neighbour it outputs a
 * synthetic frame at the midpoint timestamp and then the frame itself;
 * ExoPlayer releases each by its own timestamp, so A/V sync holds and late
 * midpoints are simply dropped. Media3 documents effects that change frame
 * timestamps as unsupported during playback: they work in 1.9.0 (Findings —
 * G.1), and the G.1 probe should run again after every Media3 upgrade.
 */
@OptIn(UnstableApi::class)
class MotionSmoothingEffect(private val holder: VideoEffectsHolder) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MotionSmoothingShaderProgram(context, holder, useHdr)
}

@OptIn(UnstableApi::class)
internal class MotionSmoothingShaderProgram(
    context: Context,
    private val holder: VideoEffectsHolder,
    private val useHdr: Boolean,
) : GlShaderProgram {
    private val appContext = context.applicationContext
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorExecutor = Executor { it.run() }
    private var errorListener = GlShaderProgram.ErrorListener { }

    private var passes: MotionSmoothingPasses? = null
    private var copyOnly: GlPass? = null
    private val schedule = MotionSmoothingSchedule()
    private val timer = GpuTimer()

    private val free = ArrayDeque<GlTextureInfo>()
    private val inUse = mutableListOf<GlTextureInfo>()

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
        try {
            val size = PixelSize(inputTexture.width, inputTexture.height)
            val run = holder.motionSmoothing
            val passes = if (run.running && !useHdr) passes() else null
            if (passes == null) {
                // Off, paused, or unavailable: the frame goes through as it is, and the next start has no history.
                schedule.reset()
                this.passes?.reset()
                val output = take(size)
                copyPass().let {
                    GlUtil.focusFramebufferUsingCurrentContext(output.fboId, size.width, size.height)
                    it.draw(inputTexture.texId)
                }
                outputListener.onOutputFrameAvailable(output, presentationTimeUs)
            } else {
                timer.begin()
                passes.ingest(inputTexture.texId, size, run.halfResolution)
                val shape = Triple(size, passes.searchSize, passes.searchScale)
                val midpointUs = schedule.midpointBefore(presentationTimeUs, shape, run.frameDurationUs)
                if (midpointUs != null && passes.hasPrevious) {
                    val midpoint = take(size)
                    passes.synthesize(midpoint.fboId)
                    outputListener.onOutputFrameAvailable(midpoint, midpointUs)
                }
                val output = take(size)
                passes.drawCurrent(output.fboId)
                outputListener.onOutputFrameAvailable(output, presentationTimeUs)
                // History moves on only after the midpoint is drawn (G.4.2).
                passes.commit()
                schedule.commit(presentationTimeUs, shape)
                timer.end()
            }
            timer.poll()?.let(holder::recordMotionSmoothingMillis)
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
        // The next stream's first frame isn't this one's neighbour.
        schedule.reset()
        passes?.reset()
        outputListener.onCurrentOutputStreamEnded()
    }

    override fun flush() {
        free.addAll(inUse)
        inUse.clear()
        schedule.reset()
        passes?.reset()
        promisedInputs = 0
        inputListener.onFlush()
        offerCapacity()
    }

    override fun release() {
        try {
            passes?.release()
            copyOnly?.release()
            (free + inUse).forEach { it.release() }
            timer.release()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        passes = null
        copyOnly = null
        free.clear()
        inUse.clear()
    }

    /**
     * The passes, built on first use. A driver that can't compile them marks
     * Motion Smoothing unavailable, and the player stops offering it; the
     * video keeps playing.
     */
    private fun passes(): MotionSmoothingPasses? {
        passes?.let { return it }
        if (holder.motionSmoothingUnavailable) return null
        return try {
            MotionSmoothingPasses(appContext).also { passes = it }
        } catch (e: GlUtil.GlException) {
            Log.w(TAG, "Motion Smoothing is unavailable on this GPU", e)
            holder.motionSmoothingUnavailable = true
            null
        }
    }

    private fun copyPass(): GlPass = copyOnly ?: GlPass(appContext, GlPass.COPY).also { copyOnly = it }

    /** Two output textures per promised input: a midpoint and the frame itself. */
    private fun offerCapacity() {
        while (POOL_SIZE - inUse.size - 2 * promisedInputs >= 2) {
            promisedInputs++
            inputListener.onReadyToAcceptInputFrame()
        }
    }

    private fun take(size: PixelSize): GlTextureInfo {
        var texture = free.removeFirstOrNull()
        if (texture != null && (texture.width != size.width || texture.height != size.height)) {
            texture.release()
            texture = null
        }
        return (texture ?: newTexture(size)).also { inUse.add(it) }
    }

    private fun newTexture(size: PixelSize): GlTextureInfo {
        val texId = GlUtil.createTexture(size.width, size.height, false)
        return GlTextureInfo(texId, GlUtil.createFboForTexture(texId), C.INDEX_UNSET, size.width, size.height)
    }

    private companion object {
        const val TAG = "MotionSmoothing"

        /**
         * Output textures: what the final stage holds until each frame's
         * release time (up to 50 ms ahead), plus the next input's two. G.1.1
         * found more didn't help.
         */
        const val POOL_SIZE = 6
    }
}
