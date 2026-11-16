package com.babasama.edendale.android.player.video

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babasama.edendale.android.MainActivity
import com.babasama.edendale.android.player.EdendaleRenderersFactory
import com.babasama.edendale.android.player.EqAudioProcessor
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * G.6 on a device: plays a clip through the app's own chain, Enhancement
 * then [MotionSmoothingEffect], into a real SurfaceView, and checks what
 * ExoPlayer released: about two frames per source frame while it runs, one
 * while it's paused, and playback that survives seeks, a pause with a
 * redraw, and pausing Motion Smoothing mid-play. It reports the effect's GPU
 * time per real frame (timer queries, where the driver has them) and what
 * SurfaceFlinger presented.
 *
 * Opt-in, because it needs a clip with audio on the device: `adb push clip
 * /data/local/tmp/` and run with `-e motionSmoothingClip /data/local/tmp/clip
 * -e motionSmoothingFps 24`, optionally `-e motionSmoothingHalf true` for
 * the half-resolution search and `-e motionSmoothingSpeed 0.25` to play
 * slower (for a software GPU such as the emulator's, which can't make the
 * frames in real time; the effect itself runs at any speed here). The
 * report is the instrumentation status `motionSmoothing` and logcat
 * `MotionSmoothingPlay`.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MotionSmoothingPlaybackInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun motionSmoothingThroughExoPlayer() {
        val clipPath = arguments.getString("motionSmoothingClip")
        assumeTrue("run with -e motionSmoothingClip <a clip with audio on the device>", clipPath != null)
        val fps = arguments.getString("motionSmoothingFps")?.toFloat() ?: 24f
        val half = arguments.getString("motionSmoothingHalf") == "true"
        val speed = arguments.getString("motionSmoothingSpeed")?.toFloat() ?: 1f
        val frameUs = (1_000_000 / fps).roundToLong()
        val clip = copyFromShell(clipPath!!)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val surfaceView = attachSurfaceView(activity)
        val holder = VideoEffectsHolder().apply {
            enhancement = EnhancementSettings(preset = EnhancementPreset.OFF)
            motionSmoothing = MotionSmoothingRun(running = true, frameDurationUs = frameUs, halfResolution = half)
        }
        val released = ConcurrentLinkedQueue<Pair<Long, Long>>()
        var error: PlaybackException? = null
        lateinit var player: ExoPlayer
        val report = StringBuilder()
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context, EdendaleRenderersFactory(context, EqAudioProcessor())).build()
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) {
                        error = e
                    }
                })
                player.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                    released.add(presentationTimeUs to releaseTimeNs)
                }
                player.setVideoSurfaceView(surfaceView)
                player.setVideoEffects(listOf(EnhancementEffect(holder), MotionSmoothingEffect(holder)))
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(clip)))
                player.setPlaybackSpeed(speed)
                player.prepare()
                player.play()
            }
            waitForPosition(player, 2_000)
            holder.drainMotionSmoothingMillis()
            shell("dumpsys SurfaceFlinger --timestats -clear -enable")
            val runningFrom = positionUs(player)
            waitForPosition(player, 7_000)
            val runningTo = positionUs(player)
            val stats = timeStats()
            val gpu = holder.drainMotionSmoothingMillis().sorted()
            val running = released.count { (pts, release) -> release != C.TIME_UNSET && pts in runningFrom until runningTo }
            val runningRatio = running / ((runningTo - runningFrom) / frameUs.toDouble())

            // Paused Motion Smoothing: one frame per source frame.
            holder.motionSmoothing = MotionSmoothingRun.OFF
            waitForPosition(player, 8_000)
            val pausedFrom = positionUs(player)
            waitForPosition(player, 10_000)
            val pausedTo = positionUs(player)
            val paused = released.count { (pts, release) -> release != C.TIME_UNSET && pts in pausedFrom until pausedTo }
            val pausedRatio = paused / ((pausedTo - pausedFrom) / frameUs.toDouble())

            // Back on; a seek, a pause with a redraw, and a backwards seek.
            holder.motionSmoothing = MotionSmoothingRun(running = true, frameDurationUs = frameUs, halfResolution = half)
            instrumentation.runOnMainSync { player.seekTo(15_000) }
            waitForPosition(player, 17_000)
            instrumentation.runOnMainSync { player.pause() }
            Thread.sleep(400)
            instrumentation.runOnMainSync { player.setVideoEffects(VideoFrameProcessor.REDRAW) }
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                player.play()
                player.seekTo(4_000)
            }
            waitForPosition(player, 6_000)

            report.appendLine("Motion Smoothing playback: ${clip.name}, $fps fps at ${speed}×, ${if (half) "half" else "full"}-resolution search, ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
            report.appendLine("running: %d frames released in %.1f s, %.2f per source frame".format(running, (runningTo - runningFrom) / 1e6, runningRatio))
            report.appendLine("paused: %d frames released in %.1f s, %.2f per source frame".format(paused, (pausedTo - pausedFrom) / 1e6, pausedRatio))
            report.appendLine(
                if (gpu.isEmpty()) "GPU time: no timer queries on this driver"
                else "GPU time per real frame: median %.2f ms, 90th %.2f ms (%d samples)".format(gpu[gpu.size / 2], gpu[gpu.size * 9 / 10], gpu.size),
            )
            report.append(stats)
            Log.i(TAG, report.toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("motionSmoothing", "\n" + report) })

            assertNull("playback error: ${error?.errorCodeName}", error)
            assertTrue("about two frames per source frame while running, got $runningRatio", runningRatio > 1.6)
            assertTrue("one frame per source frame while paused, got $pausedRatio", pausedRatio in 0.8..1.2)
        } finally {
            instrumentation.runOnMainSync { player.release() }
            shell("dumpsys SurfaceFlinger --timestats -disable -clear")
            activity.finish()
            clip.delete()
        }
    }

    // MARK: - Helpers

    private fun positionUs(player: ExoPlayer): Long {
        var position = 0L
        instrumentation.runOnMainSync { position = player.currentPosition }
        return position * 1000
    }

    private fun waitForPosition(player: ExoPlayer, positionMs: Long, timeoutMs: Long = 120_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var position = 0L
            var state = Player.STATE_IDLE
            instrumentation.runOnMainSync {
                position = player.currentPosition
                state = player.playbackState
            }
            if (position >= positionMs || state == Player.STATE_ENDED) return
            Thread.sleep(50)
        }
        throw AssertionError("playback never reached $positionMs ms")
    }

    private fun attachSurfaceView(activity: Activity): SurfaceView {
        val created = CountDownLatch(1)
        lateinit var view: SurfaceView
        instrumentation.runOnMainSync {
            view = SurfaceView(activity)
            view.setZOrderOnTop(true)
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = created.countDown()
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
            activity.addContentView(view, FrameLayout.LayoutParams(1280, 720, Gravity.CENTER))
        }
        assertTrue("no surface", created.await(10, TimeUnit.SECONDS))
        return view
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun copyFromShell(path: String): File {
        val target = File(context.cacheDir, "motion-smoothing-" + File(path).name)
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> target.outputStream().use { input.copyTo(it) } }
        assertTrue("couldn't read $path", target.length() > 0)
        return target
    }

    /** SurfaceFlinger's frame counts and average rate for the test's SurfaceView. */
    private fun timeStats(): String {
        val dump = shell("dumpsys SurfaceFlinger --timestats -dump -maxlayers 40")
        val ours = dump.split(Regex("(?=layerName = )")).filter { it.startsWith("layerName = ") && "SurfaceView" in it && "MainActivity" in it }
        if (ours.isEmpty()) return "timestats: no SurfaceView layer\n"
        return ours.joinToString("") { section ->
            section.lines().filter { line -> listOf("totalFrames", "droppedFrames", "averageFPS").any { it in line } }
                .joinToString(", ", prefix = "timestats: ", postfix = "\n") { it.trim() }
        }
    }

    private companion object {
        const val TAG = "MotionSmoothingPlay"
    }
}
