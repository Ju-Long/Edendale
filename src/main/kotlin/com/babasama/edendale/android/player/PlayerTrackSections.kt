package com.babasama.edendale.android.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.babasama.edendale.android.R

/**
 * Video Track and Audio Track (B.3): shown only when the file has more than
 * one track of that kind. A choice applies at once through a track override
 * and is remembered for the title (B.2) via [onTrackSelected].
 */
@Composable
internal fun VideoTrackSection(
    player: Player,
    chrome: PlayerChromeState,
    tracks: Tracks,
    onTrackSelected: () -> Unit,
) {
    val options = remember(tracks) { videoTrackOptions(tracks) }
    if (options.size < 2) return
    val locale = LocalConfiguration.current.locales[0]
    TrackSection(title = stringResource(R.string.player_video_track)) {
        options.forEachIndexed { index, option ->
            TrackRow(
                title = TrackLabels.video(
                    label = option.label,
                    languageCode = option.language,
                    languageName = TrackLabels.languageName(option.language, locale),
                    width = option.width,
                    height = option.height,
                    fallback = stringResource(R.string.player_track_number, index + 1),
                ),
                selected = option.isSelected,
                onClick = {
                    selectVideoTrack(player, option)
                    chrome.noteInteraction()
                    onTrackSelected()
                },
            )
        }
    }
}

@Composable
internal fun AudioTrackSection(
    player: Player,
    chrome: PlayerChromeState,
    tracks: Tracks,
    onTrackSelected: () -> Unit,
) {
    val options = remember(tracks) { audioTrackOptions(tracks) }
    if (options.size < 2) return
    val locale = LocalConfiguration.current.locales[0]
    val mono = stringResource(R.string.player_audio_mono)
    val stereo = stringResource(R.string.player_audio_stereo)
    val otherTemplate = stringResource(R.string.player_audio_channels)
    TrackSection(title = stringResource(R.string.player_audio_track)) {
        options.forEachIndexed { index, option ->
            TrackRow(
                title = TrackLabels.audio(
                    label = option.label,
                    languageCode = option.language,
                    languageName = TrackLabels.languageName(option.language, locale),
                    channels = TrackLabels.channels(option.channelCount, mono, stereo) { otherTemplate.format(it) },
                    fallback = stringResource(R.string.player_track_number, index + 1),
                ),
                selected = option.isSelected,
                onClick = {
                    selectAudioTrack(player, option)
                    chrome.noteInteraction()
                    onTrackSelected()
                },
            )
        }
    }
}

@Composable
private fun TrackSection(title: String, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelLabel(title)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { rows() }
    }
}

@Composable
private fun TrackRow(title: String, selected: Boolean, onClick: () -> Unit) {
    PanelRow(
        title = title,
        selected = selected,
        trailing = if (selected) {
            { SelectedCheck() }
        } else {
            null
        },
        onClick = onClick,
    )
}
