package com.babasama.edendale.android.xr.theater

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleTheme
import com.babasama.edendale.android.xr.R
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterLights
import com.babasama.edendale.android.xr.TheaterPlayback
import com.babasama.edendale.android.xr.TheaterRoom
import com.babasama.edendale.android.xr.XrTheater
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The immersive theater (ENHANCEMENT.md Section N): the player hands a video
 * over, this activity plays it on a room's screen in Full Space and sends
 * the viewer back to the player when they leave, or to the library when the
 * video ends. Everything but the 3D scene is the base's `TheaterPlayback`.
 */
class XrTheaterActivity : ComponentActivity(), TheaterStage.Listener, TheaterActions {

    private lateinit var playback: TheaterPlayback
    private lateinit var stage: TheaterStage
    private lateinit var preferences: TheaterPreferences
    private val ui = TheaterUiState()
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handover = XrTheater.handover(intent)
        if (handover == null) {
            Log.w(TAG, "started without a handover; only the player opens the theater")
            finish()
            return
        }
        preferences = TheaterPreferences(this)
        playback = TheaterPlayback(this, handover)
        // The main panel shows only a hint until the room is up; the stage hides it then.
        setContent {
            EdendaleTheme {
                Box(Modifier.fillMaxSize().background(EdendaleColors.Background), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.theater_look_down), style = MaterialTheme.typography.bodyLarge, color = EdendaleColors.TextSecondary)
                }
            }
        }
        val panels = ActivityPanelFactory(
            activity = this,
            dock = { compose { TheaterDockPanel(playback, ui, this) } },
            seats = { compose { TheaterSeatPanel(ui, this) } },
            subtitles = { compose { TheaterSubtitlePanel(playback) } },
        )
        stage = TheaterStage(this, playback, preferences, lifecycleScope, ui, panels, this)
        playback.start()
        lifecycleScope.launch { stage.start() }
        lifecycleScope.launch {
            snapshotFlow { playback.ended }.filter { it }.first()
            leaveToLibrary()
        }
        onBackPressedDispatcher.addCallback(this) {
            Log.i(TAG, "back: leaving to the player")
            exit()
        }
    }

    /**
     * A panel's view. The system's back reaches whichever window has focus,
     * and in Full Space that is a panel's own embedded window rather than the
     * activity's, so each panel answers Back the way the activity does.
     */
    private fun compose(content: @androidx.compose.runtime.Composable () -> Unit): View =
        object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply { addView(ComposeView(context).apply { setContent(content) }) }

    override fun onStart() {
        super.onStart()
        if (::playback.isInitialized) playback.resumeAfterSystem()
    }

    override fun onStop() {
        if (::playback.isInitialized) playback.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (::stage.isInitialized) stage.release()
        if (::playback.isInitialized) playback.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Leaving (N-D7)
    // ------------------------------------------------------------------

    /** Exit: back to the 2D player at this position. */
    override fun exit() = leave(playback.returnToPlayerIntent())

    /** The video ended: its completed progress is written; the library takes it from here. */
    private fun leaveToLibrary() = leave(playback.libraryIntent())

    override fun playUpNext() {
        val next = playback.upNextIntent() ?: return
        leave(next)
    }

    private fun leave(next: Intent) {
        if (leaving) {
            Log.i(TAG, "leave ignored: already leaving")
            return
        }
        leaving = true
        Log.i(TAG, "leaving for ${next.component?.className ?: next.action}")
        playback.saveContentPreferences()
        playback.writeProgress()
        stage.release()
        startActivity(next)
        finish()
    }

    override fun onUnavailable(reason: String) {
        Log.w(TAG, "the theater can't open here ($reason); back to the player")
        exit()
    }

    // ------------------------------------------------------------------
    // The dock's and the seat panel's actions
    // ------------------------------------------------------------------

    override fun togglePlayPause() = playback.togglePlayPause()
    override fun skip(forward: Boolean) = playback.skip(forward)
    override fun seekToFraction(fraction: Float) = playback.seekToFraction(fraction)
    override fun selectAudio(id: String) = playback.selectAudio(id)
    override fun selectSubtitles(id: String?) = playback.selectSubtitles(id)
    override fun skipSegment() = playback.skipSegment()
    override fun chooseRoom(room: TheaterRoom) = stage.chooseRoom(room)
    override fun chooseSeat(seat: SeatID?) = stage.chooseSeat(seat)
    override fun recentre() = stage.recentre()
    override fun chooseLights(level: TheaterLights) = stage.chooseLights(level)

    private companion object {
        const val TAG = "XrTheater"
    }
}
