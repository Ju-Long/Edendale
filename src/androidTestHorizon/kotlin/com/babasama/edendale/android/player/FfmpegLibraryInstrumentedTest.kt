package com.babasama.edendale.android.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HORIZON.md G.T1: the LGPL-2.1 FFmpeg decoder that
 * tools/horizon/build-ffmpeg-decoder.sh builds is in the APK and decodes
 * what the Jellyfin package used to (G.1, D13). On a headset run it with
 * `adb shell am instrument`, never `connected…AndroidTest`, which wipes the
 * app's data afterwards.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FfmpegLibraryInstrumentedTest {

    @Test
    fun theNativeLibraryLoads() {
        assertTrue("libffmpegJNI.so is missing or failed to load; run tools/horizon/build-ffmpeg-decoder.sh", FfmpegLibrary.isAvailable())
    }

    @Test
    fun decodesDtsDtsHdAndTrueHd() {
        for (mimeType in listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3)) {
            assertTrue("FFmpeg doesn't decode $mimeType", FfmpegLibrary.supportsFormat(mimeType))
        }
    }

    @Test
    fun itIsTheFfmpegWeBuilt() {
        // The script pins FFmpeg release/6.0, whose libavcodec identifies as Lavc60 (Findings: ffmpegLavc60.3.100-dca).
        val version = FfmpegLibrary.getVersion()
        assertTrue("unexpected FFmpeg version string: $version", version != null && version.contains("Lavc60."))
    }
}
