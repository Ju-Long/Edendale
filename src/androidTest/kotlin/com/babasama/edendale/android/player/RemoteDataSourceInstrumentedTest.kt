package com.babasama.edendale.android.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.remote.RemoteFailure
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

/**
 * H.2.2: the player's data source chain. Local files still play through
 * Media3's `DefaultDataSource`, and a provider's item reaches
 * [EdendaleDataSource] and fails with that provider's reason.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RemoteDataSourceInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(context.cacheDir, "remote_chain.mp4")
        instrumentation.context.assets.open("fixtures/video_640x360.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    /** Plays [uri] through the player's factory; returns the error, or null when it played to the end. */
    private fun play(uri: Uri): PlaybackException? {
        val done = CountDownLatch(1)
        var failure: PlaybackException? = null
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            val factory = DefaultMediaSourceFactory(DataSource.Factory { DefaultDataSource(context, EdendaleDataSource(context)) })
            player = ExoPlayer.Builder(context).setMediaSourceFactory(factory).build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure = error
                    done.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
        }
        try {
            assertTrue("playback never finished", done.await(30, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
        return failure
    }

    @Test
    fun aLocalFileStillPlaysThroughTheChain() {
        assertNull(play(Uri.fromFile(file)))
    }

    @Test
    fun aProviderItemWithoutAnAccountAsksForSignIn() {
        val account = SourceUrl.accountKey(MediaSourceKind.GOOGLE_DRIVE, "nobody")
        val item = SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, account, listOf("file1"), "The.Matrix.1999.mkv")
        val error = play(Uri.parse(item))
        assertEquals(
            PlaybackFailure.Provider(MediaSourceKind.GOOGLE_DRIVE, RemoteFailure.SignInRequired),
            PlaybackFailure.of(error!!),
        )
    }
}
