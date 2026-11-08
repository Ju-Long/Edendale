package com.babasama.edendale.android.xr.theater

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleTheme
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterPlayback
import com.babasama.edendale.android.xr.TheaterLights
import com.babasama.edendale.android.xr.TheaterRoom
import com.babasama.edendale.android.xr.TheaterSeatMap
import com.babasama.edendale.android.xr.TheaterSubtitleOverlay
import com.babasama.edendale.android.xr.R
import com.babasama.edendale.android.xr.TheaterTrack
import com.babasama.edendale.introdb.SegmentKind
import com.babasama.edendale.android.R as BaseR

/*
 * The two floating panels of the theater (ENHANCEMENT.md N.7): the controls
 * dock below the line of sight and the seat panel to its left. They are
 * ordinary Compose, hosted in Jetpack XR panels; the DESIGN.md tokens come
 * from the base's EdendaleColors and EdendaleTheme. Popups don't render in a
 * panel, so a list replaces the transport in place.
 */

internal enum class TheaterMenu { AUDIO, SUBTITLES, ROOM }

/** What the panels show that isn't playback: the room, its seats, and which list is open. */
internal class TheaterUiState {
    var room by mutableStateOf(TheaterRoom.DEFAULT)
    var seatMap by mutableStateOf<TheaterSeatMap?>(null)
    var screenCenterX by mutableFloatStateOf(0f)
    var screenWidth by mutableFloatStateOf(4f)
    var seatedSeat by mutableStateOf<SeatID?>(null)
    var pointedSeat by mutableStateOf<SeatID?>(null)
    var menu by mutableStateOf<TheaterMenu?>(null)
    var lights by mutableStateOf(TheaterLights.NORMAL)
}

internal interface TheaterActions {
    fun exit()
    fun togglePlayPause()
    fun skip(forward: Boolean)
    fun seekToFraction(fraction: Float)
    fun selectAudio(id: String)
    fun selectSubtitles(id: String?)
    fun skipSegment()
    fun playUpNext()
    fun chooseRoom(room: TheaterRoom)
    fun chooseSeat(seat: SeatID?)
    fun recentre()
    fun chooseLights(level: TheaterLights)
}

private val PanelShape = RoundedCornerShape(24.dp)

@Composable
private fun TheaterPanelSurface(content: @Composable ColumnScope.() -> Unit) {
    EdendaleTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .border(1.dp, Color.White.copy(alpha = 0.06f), PanelShape),
                shape = PanelShape,
                color = EdendaleColors.Background.copy(alpha = 0.82f),
                contentColor = EdendaleColors.TextPrimary,
            ) {
                Column(Modifier.padding(24.dp), content = content)
            }
        }
    }
}

// ------------------------------------------------------------------
// The dock
// ------------------------------------------------------------------

@Composable
internal fun TheaterDockPanel(playback: TheaterPlayback, ui: TheaterUiState, actions: TheaterActions) {
    TheaterPanelSurface {
        val failure = playback.failure
        when {
            failure != null -> FailureCard(failure, actions)
            ui.menu == TheaterMenu.AUDIO -> TrackList(
                title = stringResource(BaseR.string.player_audio_track),
                options = playback.audioTracks,
                offLabel = null,
                offSelected = false,
                onPick = { it?.let(actions::selectAudio) },
                onClose = { ui.menu = null },
            )
            ui.menu == TheaterMenu.SUBTITLES -> TrackList(
                title = stringResource(BaseR.string.player_subtitles),
                options = playback.textTracks,
                offLabel = stringResource(BaseR.string.player_subtitles_off),
                offSelected = playback.subtitlesOff,
                onPick = actions::selectSubtitles,
                onClose = { ui.menu = null },
            )
            ui.menu == TheaterMenu.ROOM -> RoomList(ui.room, onPick = actions::chooseRoom, onClose = { ui.menu = null })
            else -> Transport(playback, ui, actions)
        }
    }
}

@Composable
private fun ColumnScope.Transport(playback: TheaterPlayback, ui: TheaterUiState, actions: TheaterActions) {
    playback.upcomingEpisode?.let { next ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                onClick = actions::playUpNext,
                shape = RoundedCornerShape(8.dp),
                color = EdendaleColors.Surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, EdendaleColors.Outline),
                modifier = Modifier.semantics { contentDescription = next.title },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        stringResource(BaseR.string.player_up_next).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = EdendaleColors.Gold,
                    )
                    next.code?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = EdendaleColors.TextSecondary) }
                    Text(next.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // Header: Exit, the title, and the tools.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ExitButton(actions)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(playback.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            playback.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = EdendaleColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (playback.audioTracks.size > 1) {
            MenuButton(BaseR.drawable.ic_file_music, stringResource(BaseR.string.player_audio_track)) { ui.menu = TheaterMenu.AUDIO }
        }
        if (playback.textTracks.isNotEmpty()) {
            MenuButton(BaseR.drawable.ic_subtitles, stringResource(BaseR.string.player_subtitles)) { ui.menu = TheaterMenu.SUBTITLES }
        }
        MenuButton(BaseR.drawable.ic_theater, stringResource(R.string.theater_rooms)) { ui.menu = TheaterMenu.ROOM }
    }

    Spacer(Modifier.height(16.dp))
    Timeline(playback, actions)
    Spacer(Modifier.height(16.dp))

    // Transport: skips around play/pause, the segment prompt at the trailing edge.
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipButton(forward = false, seconds = playback.skipBackwardSeconds, actions)
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                if (playback.isBuffering && !playback.isPlaying) {
                    CircularProgressIndicator(color = EdendaleColors.Gold, modifier = Modifier.size(40.dp))
                } else {
                    Surface(
                        onClick = actions::togglePlayPause,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = EdendaleColors.Gold,
                        contentColor = EdendaleColors.OnGold,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(if (playback.isPlaying) BaseR.drawable.ic_pause else BaseR.drawable.ic_play),
                                contentDescription = stringResource(if (playback.isPlaying) BaseR.string.action_pause else BaseR.string.action_play),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
            }
            SkipButton(forward = true, seconds = playback.skipForwardSeconds, actions)
        }
        playback.activeSegment?.let { segment ->
            val label = when (segment.kind) {
                SegmentKind.INTRO -> BaseR.string.player_skip_intro
                SegmentKind.RECAP -> BaseR.string.player_skip_recap
                SegmentKind.CREDITS -> BaseR.string.player_skip_credits
            }
            Surface(
                onClick = actions::skipSegment,
                modifier = Modifier.align(Alignment.CenterEnd),
                shape = RoundedCornerShape(8.dp),
                color = EdendaleColors.Surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, EdendaleColors.Outline),
            ) {
                Text(
                    stringResource(label),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun Timeline(playback: TheaterPlayback, actions: TheaterActions) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = playback.durationMillis
    val played = if (duration > 0) (playback.positionMillis.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragFraction else played
    val shownMillis = if (dragging && duration > 0) (dragFraction * duration).toLong() else playback.positionMillis
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(formatClock(shownMillis), style = MaterialTheme.typography.bodySmall, color = EdendaleColors.TextSecondary)
        val description = stringResource(R.string.theater_position)
        Slider(
            value = shown,
            onValueChange = { dragging = true; dragFraction = it },
            onValueChangeFinished = { dragging = false; actions.seekToFraction(dragFraction) },
            enabled = duration > 0,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
            colors = SliderDefaults.colors(
                thumbColor = EdendaleColors.Gold,
                activeTrackColor = EdendaleColors.Gold,
                inactiveTrackColor = EdendaleColors.SurfaceHigh,
            ),
        )
        Text(formatClock(duration), style = MaterialTheme.typography.bodySmall, color = EdendaleColors.TextSecondary)
    }
}

@Composable
private fun SkipButton(forward: Boolean, seconds: Int, actions: TheaterActions) {
    val icon = when {
        forward && seconds == 15 -> BaseR.drawable.ic_arrow_rotate_right_15
        forward && seconds == 30 -> BaseR.drawable.ic_arrow_rotate_right_30
        forward -> BaseR.drawable.ic_arrow_rotate_right_10
        seconds == 15 -> BaseR.drawable.ic_arrow_rotate_left_15
        seconds == 30 -> BaseR.drawable.ic_arrow_rotate_left_30
        else -> BaseR.drawable.ic_arrow_rotate_left_10
    }
    val label = when {
        forward && seconds == 15 -> BaseR.string.player_forward_15
        forward && seconds == 30 -> BaseR.string.player_forward_30
        forward -> BaseR.string.player_forward_10
        seconds == 15 -> BaseR.string.player_back_15
        seconds == 30 -> BaseR.string.player_back_30
        else -> BaseR.string.player_back_10
    }
    IconButton(onClick = { actions.skip(forward) }, modifier = Modifier.size(60.dp)) {
        Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(32.dp), tint = EdendaleColors.TextPrimary)
    }
}

@Composable
private fun MenuButton(iconRes: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(iconRes), contentDescription = label, modifier = Modifier.size(24.dp), tint = EdendaleColors.TextPrimary)
    }
}

@Composable
private fun ExitButton(actions: TheaterActions) {
    TextButton(onClick = actions::exit, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(painterResource(BaseR.drawable.ic_xmark), contentDescription = null, modifier = Modifier.size(20.dp), tint = EdendaleColors.Gold)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.theater_exit), color = EdendaleColors.Gold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ColumnScope.FailureCard(message: String, actions: TheaterActions) {
    Text(stringResource(R.string.theater_playback_failed), style = MaterialTheme.typography.bodyLarge)
    Text(message, style = MaterialTheme.typography.bodySmall, color = EdendaleColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(12.dp))
    ExitButton(actions)
}

@Composable
private fun ColumnScope.ListHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(BaseR.drawable.ic_chevron_down), contentDescription = null, modifier = Modifier.size(24.dp), tint = EdendaleColors.TextPrimary)
        }
    }
}

@Composable
private fun ColumnScope.TrackList(
    title: String,
    options: List<TheaterTrack>,
    offLabel: String?,
    offSelected: Boolean,
    onPick: (String?) -> Unit,
    onClose: () -> Unit,
) {
    ListHeader(title, onClose)
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        if (offLabel != null) {
            ListRow(offLabel, selected = offSelected) { onPick(null); onClose() }
        }
        options.forEach { option ->
            ListRow(option.label, selected = option.isSelected && !offSelected) { onPick(option.id); onClose() }
        }
    }
}

@Composable
private fun ColumnScope.RoomList(selected: TheaterRoom, onPick: (TheaterRoom) -> Unit, onClose: () -> Unit) {
    ListHeader(stringResource(R.string.theater_rooms), onClose)
    TheaterRoom.entries.forEach { room ->
        ListRow(stringResource(room.titleRes), selected = room == selected) { onPick(room); onClose() }
    }
}

@Composable
private fun ListRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
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
            color = if (selected) EdendaleColors.Gold else EdendaleColors.TextPrimary,
        )
        if (selected) {
            Icon(painterResource(BaseR.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp), tint = EdendaleColors.Gold)
        }
    }
}

internal val TheaterRoom.titleRes: Int
    get() = when (this) {
        TheaterRoom.MOVIE_THEATRE -> R.string.theater_room_movie_theatre
        TheaterRoom.PROJECTOR_ROOM -> R.string.theater_room_projector_room
        TheaterRoom.PERFORMANCE_THEATRE -> R.string.theater_room_performance_theatre
    }

internal fun formatClock(millis: Long): String {
    val total = (millis.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}

// ------------------------------------------------------------------
// The seat panel
// ------------------------------------------------------------------

@Composable
internal fun TheaterSeatPanel(ui: TheaterUiState, actions: TheaterActions) {
    TheaterPanelSurface {
        val map = ui.seatMap
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.theater_seats), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            val shown = ui.pointedSeat ?: ui.seatedSeat
            if (shown != null) {
                Text(
                    stringResource(R.string.theater_seat_name, shown.row, shown.number),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.pointedSeat != null) EdendaleColors.Gold else EdendaleColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (map != null && map.seats.isNotEmpty()) {
            SeatMapCanvas(map, ui, actions)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(EdendaleColors.Gold))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.theater_your_seat), style = MaterialTheme.typography.bodySmall, color = EdendaleColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                val atBest = map.best == null || ui.seatedSeat == map.best?.id
                Surface(
                    onClick = { actions.chooseSeat(null) },
                    enabled = !atBest,
                    shape = CircleShape,
                    color = EdendaleColors.Surface,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).border(2.dp, EdendaleColors.Gold, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.theater_best_seat),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (atBest) EdendaleColors.TextSecondary else EdendaleColors.TextPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = EdendaleColors.Outline)
            Spacer(Modifier.height(16.dp))
        }
        LightsRow(ui, actions)
        Spacer(Modifier.height(16.dp))
        Surface(onClick = actions::recentre, shape = RoundedCornerShape(8.dp), color = EdendaleColors.Surface) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(BaseR.drawable.ic_theater), contentDescription = null, modifier = Modifier.size(24.dp), tint = EdendaleColors.Gold)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.theater_recentre), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.theater_recentre_caption), style = MaterialTheme.typography.bodySmall, color = EdendaleColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SeatMapCanvas(map: TheaterSeatMap, ui: TheaterUiState, actions: TheaterActions) {
    val seatsLabel = stringResource(R.string.theater_seats)
    val screenLabel = stringResource(R.string.theater_screen)
    var layout by remember { mutableStateOf(TheaterSeatMap.Layout.EMPTY) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(color = EdendaleColors.TextSecondary)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .semantics { contentDescription = seatsLabel }
            .pointerInput(map) {
                // A pinch is a press: hold and slide to sweep, release to sit.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    ui.pointedSeat = map.nearestSeat(down.position.x, down.position.y, layout)?.id
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        last = change.position
                        ui.pointedSeat = map.nearestSeat(last.x, last.y, layout)?.id
                        if (!change.pressed) break
                    }
                    val seat = map.nearestSeat(last.x, last.y, layout)?.id
                    ui.pointedSeat = null
                    seat?.let { actions.chooseSeat(it) }
                }
            },
    ) {
        layout = map.layout(size.width, size.height, ui.screenCenterX, ui.screenWidth)
        if (layout.isEmpty) return@Canvas
        val screen = layout.screen
        drawRoundRect(
            color = EdendaleColors.TextPrimary.copy(alpha = 0.85f),
            topLeft = Offset(screen.x, screen.y),
            size = Size(screen.width, screen.height),
            cornerRadius = CornerRadius(4f, 4f),
        )
        val measured = textMeasurer.measure(screenLabel.uppercase(), labelStyle)
        drawText(
            textMeasurer,
            screenLabel.uppercase(),
            topLeft = Offset(screen.midX - measured.size.width / 2f, screen.maxY + 6f),
            style = labelStyle,
        )
        val seatSize = layout.seatSize
        val radius = CornerRadius(seatSize * 0.3f, seatSize * 0.3f)
        map.seats.forEach { seat ->
            val point = layout.points[seat.id] ?: return@forEach
            val topLeft = Offset(point.first - seatSize / 2f, point.second - seatSize / 2f)
            val color = when {
                seat.id == ui.pointedSeat -> EdendaleColors.TextPrimary
                seat.id == ui.seatedSeat -> EdendaleColors.Gold
                else -> EdendaleColors.TextSecondary.copy(alpha = 0.35f)
            }
            drawRoundRect(color = color, topLeft = topLeft, size = Size(seatSize, seatSize), cornerRadius = radius)
            if (seat.id == map.best?.id && seat.id != ui.seatedSeat) {
                drawRoundRect(
                    color = EdendaleColors.Gold,
                    topLeft = topLeft,
                    size = Size(seatSize, seatSize),
                    cornerRadius = radius,
                    style = Stroke(width = maxOf(seatSize * 0.14f, 1f)),
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Subtitles
// ------------------------------------------------------------------

/** The 2D player's cues in a transparent panel just in front of the picture. */
@Composable
internal fun TheaterSubtitlePanel(playback: TheaterPlayback) {
    EdendaleTheme {
        Box(Modifier.fillMaxSize()) {
            TheaterSubtitleOverlay(playback, Modifier.fillMaxSize())
        }
    }
}

/** The house lights (N.9): Normal, Dim, Dark, the current level in gold. */
@Composable
private fun LightsRow(ui: TheaterUiState, actions: TheaterActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.theater_lights), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TheaterLights.entries.forEach { level ->
            val selected = ui.lights == level
            Surface(
                onClick = { actions.chooseLights(level) },
                shape = CircleShape,
                color = if (selected) EdendaleColors.Gold else EdendaleColors.Surface,
                modifier = Modifier.padding(start = 6.dp),
            ) {
                Text(
                    stringResource(level.labelRes),
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) EdendaleColors.Background else EdendaleColors.TextPrimary,
                )
            }
        }
    }
}

private val TheaterLights.labelRes: Int
    get() = when (this) {
        TheaterLights.NORMAL -> R.string.theater_lights_normal
        TheaterLights.DIM -> R.string.theater_lights_dim
        TheaterLights.DARK -> R.string.theater_lights_dark
    }
