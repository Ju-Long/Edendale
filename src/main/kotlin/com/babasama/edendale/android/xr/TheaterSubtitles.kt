package com.babasama.edendale.android.xr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.babasama.edendale.android.player.PlayerSubtitleOverlay

/**
 * The 2D player's subtitle overlay, drawn in the theater's transparent
 * subtitle panel 2 cm in front of the picture (ENHANCEMENT.md N-D11): the
 * same cues, the same Settings → Subtitles styling and layout rules, with no
 * transport controls to stay above and no effects pipeline to size against.
 * The panel is sized to the picture's shape, so the cues keep their place.
 */
@Composable
fun TheaterSubtitleOverlay(playback: TheaterPlayback, modifier: Modifier = Modifier) {
    PlayerSubtitleOverlay(
        player = playback.player,
        chrome = playback.chrome,
        controlsVisible = false,
        effectsVideoSize = null,
        modifier = modifier,
    )
}
