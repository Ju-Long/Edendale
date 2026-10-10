package com.babasama.edendale.android.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * E.2.3: DTS and TrueHD fixtures (src/test/fixtures/generate.sh) play to the
 * end through the player's own renderers. On a device without platform
 * decoders for them — the emulator has neither — FFmpeg decodes them, and the
 * decoded audio passes through the equalizer.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FfmpegAudioInstrumentedTest {

    @Test
    fun dtsPlaysWithSound() = playToEnd("fixtures/audio_dts_5_1.mka", MimeTypes.AUDIO_DTS)

    @Test
    fun trueHdPlaysWithSound() = playToEnd("fixtures/audio_truehd_5_1.mka", MimeTypes.AUDIO_TRUEHD)

    private fun playToEnd(asset: String, mimeType: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // The fixtures are assets of the test APK; the app plays them as files.
        val file = File(context.cacheDir, asset.substringAfterLast('/'))
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        val equalizer = EqAudioProcessor().apply { update(AudioEnhancementSettings(AudioEnhancementProfile.MOVIES)) }
        val decoders = Collections.synchronizedList(mutableListOf<String>())
        val sinkFormats = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        var failure: PlaybackException? = null
        lateinit var player: ExoPlayer

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context, EdendaleRenderersFactory(context, equalizer)).build()
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoders += decoderName
                }

                override fun onAudioTrackInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                ) {
                    sinkFormats += "enc=${audioTrackConfig.encoding} ch=${audioTrackConfig.channelConfig} rate=${audioTrackConfig.sampleRate} offload=${audioTrackConfig.offload}"
                }
            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure = error
                    done.countDown()
                }
            })
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }

        try {
            assertTrue("$asset didn't finish", done.await(30, TimeUnit.SECONDS))
            assertNull(failure?.let { "${it.message}: ${it.cause}" }, failure)
            assertTrue("no audio decoder for $asset", decoders.isNotEmpty())
            val platformDecoders = MediaCodecUtil.getDecoderInfos(mimeType, false, false)
            if (platformDecoders.isEmpty()) {
                assertTrue("decoded by ${decoders.joinToString()}", decoders.any { it.contains("ffmpeg", ignoreCase = true) })
            }
            // About a second at 48 kHz reached the equalizer.
            assertTrue(
                "only ${equalizer.framesSeen} frames; decoders ${decoders.joinToString()}; formats ${sinkFormats.joinToString()}",
                equalizer.framesSeen > 40_000,
            )
        } finally {
            instrumentation.runOnMainSync { player.release() }
            file.delete()
        }
    }
}
