package com.babasama.edendale.android.player.video

import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.util.Size
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import com.babasama.edendale.android.player.EdendaleRenderersFactory
import com.babasama.edendale.android.player.EqAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * F.1: the effects pipeline end to end — ExoPlayer decoding a clip through
 * Edendale's effect into a surface, installed before prepare, and installed
 * mid-play by the controller (which re-prepares at the current position).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoEffectsPipelineInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var file: File
    private val frames = AtomicInteger()
    private val thread = HandlerThread("frames").apply { start() }
    private lateinit var reader: ImageReader

    @Before
    fun setUp() {
        file = File(context.cacheDir, "video_640x360.mp4")
        instrumentation.context.assets.open("fixtures/video_640x360.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        // PRIVATE takes whatever the producer writes: YUV straight from the decoder, RGBA from the effects pipeline.
        reader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 4)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            frames.incrementAndGet()
        }, Handler(thread.looper))
    }

    @After
    fun tearDown() {
        reader.close()
        thread.quitSafely()
        file.delete()
    }

    private class Run(val player: ExoPlayer, val done: CountDownLatch) {
        @Volatile var error: PlaybackException? = null
    }

    private fun start(configure: (ExoPlayer) -> Unit): Run {
        lateinit var run: Run
        instrumentation.runOnMainSync {
            // The app's renderers: the platform video renderer with the replay cache (F.1.2).
            val player = ExoPlayer.Builder(context, EdendaleRenderersFactory(context, EqAudioProcessor())).build()
            val done = CountDownLatch(1)
            run = Run(player, done)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    run.error = error
                    done.countDown()
                }
            })
            player.volume = 0f
            player.setVideoSurface(reader.surface)
            // A bare Surface has no size ExoPlayer can learn (a SurfaceView reports
            // its own); the effects pipeline needs one to render into it.
            for (index in 0 until player.rendererCount) {
                if (player.getRendererType(index) == C.TRACK_TYPE_VIDEO) {
                    player.createMessage(player.getRenderer(index))
                        .setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
                        .setPayload(Size(1280, 720))
                        .send()
                }
            }
            configure(player)
        }
        return run
    }

    @Test
    fun anEffectInstalledBeforePrepareRendersEveryFrame() {
        val holder = VideoEffectsHolder().apply {
            display = PixelSize(1280, 720)
            enhancement = EnhancementSettings(preset = EnhancementPreset.HIGH_QUALITY)
            adjustments = VideoAdjustmentValues(saturation = 1.3f, hue = 20f)
        }
        val run = start { player ->
            player.setVideoEffects(listOf(EnhancementEffect(holder)))
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
        try {
            assertTrue("playback didn't end", run.done.await(30, TimeUnit.SECONDS))
            assertNull(run.error?.let { "${it.errorCodeName}: ${it.cause}" })
            // 640×360 on a 1280×720 viewport upscales to 1280×720.
            assertEquals(PixelSize(1280, 720), holder.outputSizeFor(PixelSize(640, 360)))
            assertTrue("only ${frames.get()} frames", frames.get() >= 24)
        } finally {
            instrumentation.runOnMainSync { run.player.release() }
        }
    }

    @Test
    fun theControllerInstallsEffectsMidPlayAndKeepsPlaying() {
        lateinit var controller: VideoEffectsController
        val reprepared = CountDownLatch(1)
        val run = start { player ->
            controller = VideoEffectsController(
                context = context,
                isTelevision = false,
                reprepare = {
                    val position = player.currentPosition
                    player.stop()
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), position)
                    player.prepare()
                    player.play()
                    reprepared.countDown()
                },
                saveAdjustments = {},
            )
            controller.attach(player, VideoAdjustmentValues.NEUTRAL)
            // Nothing needs effects yet: no pipeline, the decoder draws straight to the surface.
            controller.setPreset(EnhancementPreset.OFF)
            controller.beforePrepare()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
        try {
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                controller.onDisplaySizeChanged(PixelSize(1280, 720))
                controller.setPreset(EnhancementPreset.BALANCED)
            }
            assertTrue("never re-prepared", reprepared.await(5, TimeUnit.SECONDS))
            val before = frames.get()
            assertTrue("playback didn't end", run.done.await(30, TimeUnit.SECONDS))
            assertNull(run.error?.let { "${it.errorCodeName}: ${it.cause}" })
            assertTrue("no frames after installing (${frames.get()} total, $before before)", frames.get() > before)
            // While paused, a change redraws through REDRAW without failing.
            instrumentation.runOnMainSync {
                run.player.pause()
                controller.setSharpness(0.9f)
                controller.showEnhancementOriginal(true)
            }
            Thread.sleep(300)
            assertNull(run.error)
        } finally {
            instrumentation.runOnMainSync { run.player.release() }
        }
    }
}
