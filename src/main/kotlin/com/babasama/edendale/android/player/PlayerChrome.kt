package com.babasama.edendale.android.player

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player

/** Which side panel is open on the trailing edge, if any. */
internal enum class PlayerPanel { PLAYLIST, SETTINGS }

/** Transient gesture-feedback pill shown at the top of the player. */
internal sealed interface PlayerHud {
    val sticky: Boolean get() = false

    data class Seek(val seconds: Int) : PlayerHud
    data class Speed(val rate: Float, override val sticky: Boolean = false) : PlayerHud
    data class Volume(val level: Float) : PlayerHud
    data class Brightness(val level: Float) : PlayerHud
    data class Scrub(
        val target: String,
        val offset: String,
        override val sticky: Boolean,
    ) : PlayerHud
}

/**
 * Controls-overlay state for one playback session, mirroring Apple's
 * `PlayerChromeModel`: visibility, side panels, playback speed (base rate +
 * temporary hold override), loop, auto-skip preferences, aspect fill, the
 * transient HUD, and the D-pad's accumulated seek preview. Timers (auto-hide,
 * HUD fade, remote-seek commit) live in `PlayerScreen`'s LaunchedEffects,
 * which react to this state, so the class itself stays coroutine-free.
 */
internal class PlayerChromeState(private val prefs: SharedPreferences) {

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    var controlsVisible by mutableStateOf(true)
        private set

    var activePanel by mutableStateOf<PlayerPanel?>(null)
        private set

    /**
     * The D-pad can reveal a timeline without mounting the full controls;
     * see the reveal catcher in `PlayerScreen`.
     */
    var timelineVisible by mutableStateOf(false)
        private set

    /**
     * Bumped by every interaction that should keep the chrome up. The
     * auto-hide and timeline-hide effects key on it, so a bump restarts
     * their countdowns.
     */
    var interactionTick by mutableLongStateOf(0L)
        private set

    fun showControls() {
        controlsVisible = true
        // The full controls carry their own timeline; drop the lightweight
        // one so a stale hide countdown can't flash it later.
        timelineVisible = false
        interactionTick++
    }

    fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    fun hideControls() {
        controlsVisible = false
    }

    fun showTimeline() {
        timelineVisible = true
        interactionTick++
    }

    fun openPanel(panel: PlayerPanel) {
        activePanel = if (activePanel == panel) null else panel
        showControls()
    }

    fun closePanel() {
        activePanel = null
        showControls()
    }

    fun noteInteraction() {
        interactionTick++
    }

    // ------------------------------------------------------------------
    // Playback options
    // ------------------------------------------------------------------

    /** User-chosen playback rate; survives media switches inside the session. */
    var baseRate by mutableStateOf(1f)
        private set

    /** Temporary rate while a press-and-hold speed gesture is active. */
    var holdRate by mutableStateOf<Float?>(null)
        private set

    var loopEnabled by mutableStateOf(false)
        private set

    var aspectFill by mutableStateOf(false)

    var skipRecap by mutableStateOf(prefs.getBoolean(KEY_SKIP_RECAP, false))
        private set

    var skipCredits by mutableStateOf(prefs.getBoolean(KEY_SKIP_CREDITS, false))
        private set

    /** Enter Picture in Picture on its own when the user leaves mid-play. */
    var autoPip by mutableStateOf(prefs.getBoolean(KEY_AUTO_PIP, true))
        private set

    fun setRate(player: Player, rate: Float) {
        baseRate = PlayerLogic.normalizedRate(rate)
        if (holdRate == null) player.setPlaybackSpeed(baseRate)
        noteInteraction()
    }

    /** Press-and-hold speed override; reverts in [endHoldRate]. */
    fun beginHoldRate(player: Player, rate: Float) {
        holdRate = rate
        player.setPlaybackSpeed(rate)
        showHud(PlayerHud.Speed(rate, sticky = true))
    }

    fun endHoldRate(player: Player) {
        if (holdRate == null) return
        holdRate = null
        player.setPlaybackSpeed(baseRate)
        dismissHud()
    }

    fun setLoop(player: Player, enabled: Boolean) {
        loopEnabled = enabled
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setSkipRecapEnabled(enabled: Boolean) {
        skipRecap = enabled
        prefs.edit().putBoolean(KEY_SKIP_RECAP, enabled).apply()
    }

    fun setSkipCreditsEnabled(enabled: Boolean) {
        skipCredits = enabled
        prefs.edit().putBoolean(KEY_SKIP_CREDITS, enabled).apply()
    }

    fun setAutoPipEnabled(enabled: Boolean) {
        autoPip = enabled
        prefs.edit().putBoolean(KEY_AUTO_PIP, enabled).apply()
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    var hud by mutableStateOf<PlayerHud?>(null)
        private set

    fun showHud(content: PlayerHud) {
        hud = content
    }

    fun dismissHud() {
        hud = null
    }

    // ------------------------------------------------------------------
    // Scrubbing
    // ------------------------------------------------------------------

    /** Touch drag on the timeline, as a 0…1 preview position. */
    var dragFraction by mutableStateOf<Float?>(null)

    /**
     * Accumulated D-pad seek preview, in milliseconds. Committed by
     * `PlayerScreen` after [PlayerLogic.REMOTE_SEEK_COMMIT_MILLIS] of remote
     * silence.
     */
    var remotePreviewMillis by mutableStateOf<Long?>(null)
        private set

    private var remoteOriginMillis = 0L

    val isScrubbing: Boolean
        get() = dragFraction != null || remotePreviewMillis != null

    /**
     * Folds repeated left/right presses into one preview. The first press
     * latches the origin so the HUD can show the total applied offset.
     */
    fun remoteSeek(player: Player, deltaMillis: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (duration == null) {
            // No timeline to preview against — seek immediately instead.
            player.seekTo(PlayerLogic.seekTargetMillis(player.currentPosition, deltaMillis, 0))
            showHud(PlayerHud.Seek((deltaMillis / 1_000).toInt()))
            return
        }
        val base = remotePreviewMillis
            ?: player.currentPosition.coerceIn(0, duration).also { remoteOriginMillis = it }
        val target = (base + deltaMillis).coerceIn(0, duration)
        remotePreviewMillis = target
        timelineVisible = true
        showHud(
            PlayerHud.Scrub(
                target = PlayerLogic.timestamp(target),
                offset = PlayerLogic.signedTimestamp(target - remoteOriginMillis),
                sticky = true,
            ),
        )
    }

    fun commitRemoteSeek(player: Player) {
        val target = remotePreviewMillis ?: return
        player.seekTo(target)
        showHud(
            PlayerHud.Scrub(
                target = PlayerLogic.timestamp(target),
                offset = PlayerLogic.signedTimestamp(target - remoteOriginMillis),
                sticky = false,
            ),
        )
        remotePreviewMillis = null
        noteInteraction()
    }

    fun cancelRemoteSeek() {
        remotePreviewMillis = null
        timelineVisible = false
        dismissHud()
    }

    /**
     * Back while the lightweight timeline/HUD is presented. Returns true
     * when something was dismissed instead of exiting.
     */
    fun dismissRemotePresentation(): Boolean {
        if (remotePreviewMillis != null) {
            cancelRemoteSeek()
            return true
        }
        if (timelineVisible || hud != null) {
            timelineVisible = false
            dismissHud()
            return true
        }
        return false
    }

    private companion object {
        const val KEY_SKIP_RECAP = "player.skipRecap"
        const val KEY_SKIP_CREDITS = "player.skipCredits"
        const val KEY_AUTO_PIP = "player.autoPiP"
    }
}

/** Relative seek with HUD feedback — the ±10 s buttons, D-pad, and media keys. */
internal fun seekBy(player: Player, chrome: PlayerChromeState, offsetMillis: Long) {
    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    player.seekTo(PlayerLogic.seekTargetMillis(player.currentPosition, offsetMillis, duration))
    chrome.showHud(PlayerHud.Seek((offsetMillis / 1_000).toInt()))
}
