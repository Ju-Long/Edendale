package com.babasama.edendale.android.theater

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleTheme
import com.babasama.edendale.android.R
import com.babasama.edendale.android.player.PlayerChromeState
import com.babasama.edendale.android.player.PlayerSubtitleOverlay
import com.babasama.edendale.android.player.PlayerTrackOption
import com.babasama.edendale.android.player.SkipInterval
import com.babasama.edendale.android.player.TrackLabels
import java.util.Locale

/** Which track list the controls card shows in place of the transport. */
internal enum class TheaterMenu { AUDIO, SUBTITLES }

/**
 * What the controls panel draws (HORIZON.md G.8), set by [TheaterActivity]
 * from its player's callbacks and position ticker. Compose state, so the
 * panel redraws on its own.
 */
internal class TheaterControlsState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMillis by mutableLongStateOf(0L)
    var durationMillis by mutableLongStateOf(0L)
    var audioOptions by mutableStateOf<List<PlayerTrackOption>>(emptyList())
    var textOptions by mutableStateOf<List<PlayerTrackOption>>(emptyList())
    var subtitlesOff by mutableStateOf(false)
    var menu by mutableStateOf<TheaterMenu?>(null)
    /** A playback error's message; the card then offers only Exit. */
    var failure by mutableStateOf<String?>(null)
}

/** What the controls panel asks the activity to do. */
internal interface TheaterControlsActions {
    fun togglePlayPause()
    fun skip(forward: Boolean)
    fun seekToFraction(fraction: Float)
    fun selectAudio(option: PlayerTrackOption)
    fun selectSubtitles(option: PlayerTrackOption?)
    fun exit()
    /** Any press keeps the panel up a while longer. */
    fun noteInteraction()
}

/**
 * The small controls card under the screen (G.8): title, skip, play and
 * pause, the timeline, the audio and subtitle tracks, and Exit. It is a
 * Compose panel the SDK renders to a compositor layer; the ray clicks it
 * with a trigger or a pinch. Popups don't render in a panel, so the track
 * lists open in place of the transport.
 */
@Composable
internal fun TheaterControlsPanel(state: TheaterControlsState, chrome: PlayerChromeState, actions: TheaterControlsActions) {
    EdendaleTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = EdendaleColors.Surface.copy(alpha = .94f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                val failure = state.failure
                when {
                    failure != null -> FailureCard(failure, actions)
                    state.menu == TheaterMenu.AUDIO -> TrackList(
                        title = stringResource(R.string.player_audio_track),
                        options = state.audioOptions,
                        offLabel = null,
                        offSelected = false,
                        label = { audioLabel(it) },
                        onPick = { option -> if (option != null) actions.selectAudio(option) },
                        onClose = { actions.noteInteraction(); state.menu = null },
                    )
                    state.menu == TheaterMenu.SUBTITLES -> TrackList(
                        title = stringResource(R.string.player_subtitles),
                        options = state.textOptions,
                        offLabel = stringResource(R.string.player_subtitles_off),
                        offSelected = state.subtitlesOff,
                        label = { textLabel(it) },
                        onPick = { option -> actions.selectSubtitles(option) },
                        onClose = { actions.noteInteraction(); state.menu = null },
                    )
                    else -> Transport(state, chrome, actions)
                }
            }
        }
    }
}

@Composable
private fun Transport(state: TheaterControlsState, chrome: PlayerChromeState, actions: TheaterControlsActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                state.subtitle?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.audioOptions.size > 1) {
                MenuButton(R.drawable.ic_file_music, stringResource(R.string.player_audio_track)) {
                    actions.noteInteraction(); state.menu = TheaterMenu.AUDIO
                }
            }
            if (state.textOptions.isNotEmpty()) {
                MenuButton(R.drawable.ic_subtitles, stringResource(R.string.player_subtitles)) {
                    actions.noteInteraction(); state.menu = TheaterMenu.SUBTITLES
                }
            }
            ExitButton(actions)
        }

        Spacer(Modifier.weight(1f))

        val (backIcon, backString) = when (chrome.skipBackwardInterval) {
            SkipInterval.TEN -> R.drawable.ic_arrow_rotate_left_10 to R.string.player_back_10
            SkipInterval.FIFTEEN -> R.drawable.ic_arrow_rotate_left_15 to R.string.player_back_15
            SkipInterval.THIRTY -> R.drawable.ic_arrow_rotate_left_30 to R.string.player_back_30
        }
        val (forwardIcon, forwardString) = when (chrome.skipForwardInterval) {
            SkipInterval.TEN -> R.drawable.ic_arrow_rotate_right_10 to R.string.player_forward_10
            SkipInterval.FIFTEEN -> R.drawable.ic_arrow_rotate_right_15 to R.string.player_forward_15
            SkipInterval.THIRTY -> R.drawable.ic_arrow_rotate_right_30 to R.string.player_forward_30
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { actions.skip(forward = false) }, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(backIcon), stringResource(backString), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
            Surface(
                onClick = { actions.togglePlayPause() },
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            IconButton(onClick = { actions.skip(forward = true) }, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(forwardIcon), stringResource(forwardString), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        Spacer(Modifier.weight(1f))

        Timeline(state, actions)
    }
}

/** The position as a slider: dragging previews, letting go seeks. */
@Composable
private fun Timeline(state: TheaterControlsState, actions: TheaterControlsActions) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMillis
    val playedFraction = if (duration > 0) (state.positionMillis.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownFraction = if (dragging) dragFraction else playedFraction
    val shownMillis = if (dragging && duration > 0) (dragFraction * duration).toLong() else state.positionMillis
    val positionLabel = stringResource(R.string.theater_position)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(formatClock(shownMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shownFraction,
            onValueChange = {
                dragging = true
                dragFraction = it
                actions.noteInteraction()
            },
            onValueChangeFinished = {
                dragging = false
                actions.seekToFraction(dragFraction)
            },
            enabled = duration > 0,
            modifier = Modifier.weight(1f).semantics { contentDescription = positionLabel },
            colors = SliderDefaults.colors(
                thumbColor = EdendaleColors.Gold,
                activeTrackColor = EdendaleColors.Gold,
                inactiveTrackColor = EdendaleColors.SurfaceHigh,
            ),
        )
        Text(formatClock(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MenuButton(iconRes: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(iconRes), label, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun ExitButton(actions: TheaterControlsActions) {
    TextButton(onClick = { actions.exit() }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(painterResource(R.drawable.ic_xmark), null, Modifier.size(20.dp), tint = EdendaleColors.Gold)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.theater_exit), color = EdendaleColors.Gold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FailureCard(message: String, actions: TheaterControlsActions) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.theater_playback_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            message,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.size(12.dp))
        ExitButton(actions)
    }
}

/** The track rows, with Off first for subtitles; a row's press picks it and closes the list. */
@Composable
private fun TrackList(
    title: String,
    options: List<PlayerTrackOption>,
    offLabel: String?,
    offSelected: Boolean,
    label: @Composable (PlayerTrackOption) -> String,
    onPick: (PlayerTrackOption?) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (offLabel != null) {
                TrackRow(offLabel, offSelected) { onPick(null); onClose() }
            }
            options.forEach { option ->
                TrackRow(label(option), option.isSelected && !offSelected) { onPick(option); onClose() }
            }
        }
    }
}

@Composable
private fun TrackRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) EdendaleColors.SurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) EdendaleColors.Gold else MaterialTheme.colorScheme.onBackground,
        )
        if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = EdendaleColors.Gold)
    }
}

@Composable
private fun audioLabel(option: PlayerTrackOption): String {
    val locale = Locale.getDefault()
    val otherTemplate = stringResource(R.string.player_audio_channels)
    val channels = TrackLabels.channels(
        option.channelCount,
        mono = stringResource(R.string.player_audio_mono),
        stereo = stringResource(R.string.player_audio_stereo),
        other = { otherTemplate.format(it) },
    )
    return TrackLabels.audio(
        label = option.label,
        languageCode = option.language,
        languageName = TrackLabels.languageName(option.language, locale),
        channels = channels,
        fallback = stringResource(R.string.player_track_number, option.trackIndex + 1),
    )
}

@Composable
private fun textLabel(option: PlayerTrackOption): String = TrackLabels.named(
    label = option.label,
    languageCode = option.language,
    languageName = TrackLabels.languageName(option.language, Locale.getDefault()),
    fallback = stringResource(R.string.player_track_number, option.trackIndex + 1),
)

/** h:mm:ss past an hour, else m:ss. */
internal fun formatClock(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * The subtitles, in a transparent panel just in front of the screen (G.8).
 * The 2D player draws its cues in Compose over its SurfaceView, which a
 * surface panel doesn't have, so the same overlay draws into this panel.
 */
@Composable
internal fun TheaterSubtitlePanel(player: Player, chrome: PlayerChromeState) {
    EdendaleTheme {
        Box(Modifier.fillMaxSize()) {
            PlayerSubtitleOverlay(
                player = player,
                chrome = chrome,
                controlsVisible = false,
                effectsVideoSize = null,
            )
        }
    }
}
