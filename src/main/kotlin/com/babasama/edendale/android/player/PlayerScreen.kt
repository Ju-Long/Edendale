package com.babasama.edendale.android.player

import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.focusable
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleRadii
import com.babasama.edendale.android.R
import com.babasama.edendale.android.ArchiveIconButton
import com.babasama.edendale.android.tvFocusLift
import com.babasama.edendale.wyzie.WyzieSubtitle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The full-screen playback surface: video underneath, a touch gesture layer
 * on handhelds, a D-pad reveal catcher on TV, the auto-hiding controls
 * overlay, trailing side panels, and the transient HUD. The Android
 * counterpart of Apple's `PlayerScreen` + `PlayerControlsOverlay`.
 */
@Composable
internal fun PlayerScreen(
    player: ExoPlayer,
    chrome: PlayerChromeState,
    isTelevision: Boolean,
    title: State<String>,
    subtitle: State<String?>,
    currentUri: State<String>,
    playlist: State<PlayerPlaylist?>,
    tracks: State<Tracks>,
    onlineSubtitles: OnlineSubtitlesState,
    wyzieConfigured: State<Boolean>,
    wyzieLookup: State<WyzieLookup?>,
    inPipMode: State<Boolean>,
    supportsPip: Boolean,
    onEnterPip: (() -> Unit)?,
    onAutoPipChanged: () -> Unit,
    onSelectEntry: (PlaylistEntry) -> Unit,
    onSearchOnlineSubtitles: () -> Unit,
    onDownloadOnlineSubtitle: (WyzieSubtitle) -> Unit,
    onClose: () -> Unit,
) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMillis by remember { mutableLongStateOf(0L) }
    var durationMillis by remember { mutableLongStateOf(0L) }
    val activity = LocalContext.current as? PlayerActivity

    // Every FocusRequester here is attached to a node that stays composed
    // for the whole session (the catcher) or is requested only while its
    // subtree is mounted, so requestFocus can never hit an unattached node.
    val catcherFocus = remember { FocusRequester() }
    val topFocus = remember { FocusRequester() }
    val centerFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    val controlsActive = chrome.controlsVisible && !inPipMode.value

    DisposableListener(player) { state, playing ->
        isBuffering = state == Player.STATE_BUFFERING
        isPlaying = playing
    }

    LaunchedEffect(player) {
        var lastWrite = 0L
        while (isActive) {
            positionMillis = player.currentPosition
            durationMillis = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            activity?.onPlaybackTick(positionMillis, durationMillis)
            val now = System.currentTimeMillis()
            if (player.isPlaying && now - lastWrite >= 5_000) {
                lastWrite = now
                activity?.writeProgress()
            }
            delay(500)
        }
    }

    // Auto-hide pauses while a panel is open, mid-scrub, or paused; any
    // interaction bumps the tick and restarts the countdown.
    LaunchedEffect(
        chrome.controlsVisible, isPlaying, inPipMode.value,
        chrome.activePanel, chrome.isScrubbing, chrome.interactionTick,
    ) {
        if (chrome.controlsVisible && isPlaying && !inPipMode.value &&
            chrome.activePanel == null && !chrome.isScrubbing
        ) {
            delay(PlayerLogic.AUTO_HIDE_MILLIS)
            chrome.hideControls()
        }
    }

    // Non-sticky HUDs fade shortly after their last update.
    LaunchedEffect(chrome.hud) {
        val current = chrome.hud ?: return@LaunchedEffect
        if (current.sticky) return@LaunchedEffect
        delay(PlayerLogic.HUD_DISMISS_MILLIS)
        if (chrome.hud === current) chrome.dismissHud()
    }

    // A pause in remote input commits the accumulated seek preview.
    LaunchedEffect(chrome.remotePreviewMillis) {
        if (chrome.remotePreviewMillis == null) return@LaunchedEffect
        delay(PlayerLogic.REMOTE_SEEK_COMMIT_MILLIS)
        chrome.commitRemoteSeek(player)
    }

    LaunchedEffect(chrome.timelineVisible, chrome.interactionTick, chrome.isScrubbing) {
        if (chrome.timelineVisible && !chrome.isScrubbing) {
            delay(PlayerLogic.TIMELINE_HIDE_MILLIS)
            chrome.cancelRemoteSeek()
        }
    }

    // Focus never lands anywhere on its own: seed it wherever the remote
    // should act. Both flips run after the frame that (un)mounted the
    // target subtree, so the nodes are attached; runCatching covers the
    // teardown race when the activity is finishing.
    if (isTelevision) {
        LaunchedEffect(controlsActive, chrome.activePanel) {
            runCatching {
                when {
                    !controlsActive -> catcherFocus.requestFocus()
                    chrome.activePanel != null -> panelFocus.requestFocus()
                    else -> centerFocus.requestFocus()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    // The hidden built-in controller is full of focusable
                    // buttons; keep the D-pad out of the View world entirely.
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    isFocusable = false
                    this.player = player
                }
            },
            update = { view ->
                view.resizeMode = if (chrome.aspectFill) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!isTelevision && !inPipMode.value) {
            PlayerGestureLayer(player, chrome, activity)
        }

        if (isTelevision) {
            RevealCatcher(
                active = isTelevision && !controlsActive && !inPipMode.value,
                focusRequester = catcherFocus,
                chrome = chrome,
                player = player,
            )
        }

        if (isBuffering && !inPipMode.value && !controlsActive) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Lightweight remote-seek timeline; the full controls carry their
        // own timeline, so the two are never shown together.
        if (isTelevision && !chrome.controlsVisible &&
            (chrome.timelineVisible || chrome.remotePreviewMillis != null) &&
            !inPipMode.value
        ) {
            TvTimelineOverlay(
                positionMillis = chrome.remotePreviewMillis ?: positionMillis,
                durationMillis = durationMillis,
            )
        }

        AnimatedVisibility(
            visible = controlsActive,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControlsOverlay(
                player = player,
                chrome = chrome,
                isTelevision = isTelevision,
                title = title.value,
                subtitle = subtitle.value,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                hasPlaylist = playlist.value != null,
                topFocus = topFocus,
                centerFocus = centerFocus,
                timelineFocus = timelineFocus,
                onEnterPip = onEnterPip,
                onClose = onClose,
            )
        }

        if (!inPipMode.value) {
            PlayerPanels(
                player = player,
                chrome = chrome,
                isTelevision = isTelevision,
                currentUri = currentUri.value,
                playlist = playlist.value,
                tracks = tracks.value,
                onlineSubtitles = onlineSubtitles,
                wyzieConfigured = wyzieConfigured.value,
                wyzieLookup = wyzieLookup.value,
                supportsPip = supportsPip,
                panelFocus = panelFocus,
                onAutoPipChanged = onAutoPipChanged,
                onSelectEntry = onSelectEntry,
                onSearchOnlineSubtitles = onSearchOnlineSubtitles,
                onDownloadOnlineSubtitle = onDownloadOnlineSubtitle,
            )

            PlayerHudView(chrome)
        }
    }
}

// ------------------------------------------------------------------
// Player listener
// ------------------------------------------------------------------

@Composable
private fun DisposableListener(
    player: ExoPlayer,
    onUpdate: (state: Int, playing: Boolean) -> Unit,
) {
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) =
                onUpdate(playbackState, player.isPlaying)

            override fun onIsPlayingChanged(isPlaying: Boolean) =
                onUpdate(player.playbackState, isPlaying)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
}

// ------------------------------------------------------------------
// TV reveal catcher
// ------------------------------------------------------------------

/**
 * Invisible focused surface that owns the remote while the chrome is
 * hidden — the reason the D-pad works at all with nothing on screen. It
 * handles every direction itself instead of relying on focus search (a
 * full-screen focus target is a dead end for directional search): left and
 * right accumulate a seek preview, down peeks the timeline, and up or
 * select reveal the controls.
 */
@Composable
private fun BoxScope.RevealCatcher(
    active: Boolean,
    focusRequester: FocusRequester,
    chrome: PlayerChromeState,
    player: ExoPlayer,
) {
    Box(
        Modifier
            .matchParentSize()
            .onKeyEvent { event ->
                if (!active || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // A held key repeats every ~50 ms; acting on every fourth
                // repeat scrubs at a fast-but-followable ~5 steps a second.
                val repeat = event.nativeKeyEvent.repeatCount
                val act = repeat == 0 || repeat % 4 == 0
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (act) chrome.remoteSeek(player, -PlayerLogic.SEEK_STEP_MILLIS)
                        true
                    }
                    Key.DirectionRight -> {
                        if (act) chrome.remoteSeek(player, PlayerLogic.SEEK_STEP_MILLIS)
                        true
                    }
                    Key.DirectionDown -> {
                        chrome.showTimeline()
                        true
                    }
                    Key.DirectionUp, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        chrome.showControls()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusProperties { canFocus = active }
            .focusable(),
    )
}

// ------------------------------------------------------------------
// Controls overlay
// ------------------------------------------------------------------

@Composable
private fun PlayerControlsOverlay(
    player: ExoPlayer,
    chrome: PlayerChromeState,
    isTelevision: Boolean,
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    hasPlaylist: Boolean,
    topFocus: FocusRequester,
    centerFocus: FocusRequester,
    timelineFocus: FocusRequester,
    onEnterPip: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val edgeMargin = if (isTelevision) 48.dp else 20.dp
    // Focus moving between controls counts as activity: keep the chrome up
    // while the user is navigating.
    val chipDidFocus = { chrome.showControls() }
    // An open panel owns the remote. Leaving these focusable lets a press that
    // runs out of panel walk into transport the viewer never aimed at — and it
    // is what stops the panel from telling a sideways press apart from "leave".
    // Touch is unaffected: the panel's scrim already swallows taps out here.
    val controlsFocusable = chrome.activePanel == null

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        EdendaleColors.Background.copy(alpha = .82f),
                        Color.Transparent,
                        Color.Transparent,
                        EdendaleColors.Background.copy(alpha = .9f),
                    ),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = edgeMargin, vertical = 16.dp)
                .focusGroup()
                .focusProperties { if (isTelevision) down = centerFocus },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerChip(
                iconRes = R.drawable.ic_xmark,
                contentDescription = stringResource(R.string.player_close),
                isTelevision = isTelevision,
                modifier = Modifier.focusRequester(topFocus),
                canFocus = controlsFocusable,
                onFocus = chipDidFocus,
                onClick = onClose,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onEnterPip != null) {
                PlayerChip(
                    iconRes = R.drawable.ic_picture_in_picture,
                    contentDescription = stringResource(R.string.player_picture_in_picture),
                    isTelevision = isTelevision,
                    canFocus = controlsFocusable,
                    onFocus = chipDidFocus,
                    onClick = onEnterPip,
                )
            }
            if (hasPlaylist) {
                PlayerChip(
                    iconRes = R.drawable.ic_list_tree,
                    contentDescription = stringResource(R.string.player_episodes),
                    isTelevision = isTelevision,
                    isActive = chrome.activePanel == PlayerPanel.PLAYLIST,
                    canFocus = controlsFocusable,
                    onFocus = chipDidFocus,
                    onClick = { chrome.openPanel(PlayerPanel.PLAYLIST) },
                )
            }
            PlayerChip(
                iconRes = R.drawable.ic_sidebar_right,
                contentDescription = stringResource(R.string.player_adjustments),
                isTelevision = isTelevision,
                isActive = chrome.activePanel == PlayerPanel.SETTINGS,
                canFocus = controlsFocusable,
                onFocus = chipDidFocus,
                onClick = { chrome.openPanel(PlayerPanel.SETTINGS) },
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .focusGroup()
                .focusProperties {
                    if (isTelevision) {
                        up = topFocus
                        down = timelineFocus
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArchiveIconButton(
                onClick = { seekBy(player, chrome, -PlayerLogic.SEEK_STEP_MILLIS) },
                modifier = Modifier
                    .size(56.dp)
                    .focusProperties { canFocus = controlsFocusable }
                    .onFocusChanged { if (it.isFocused) chipDidFocus() },
                isTelevision = isTelevision,
            ) { focused ->
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_rotate_left_10),
                    contentDescription = stringResource(R.string.player_back_10),
                    modifier = Modifier.size(34.dp),
                    tint = if (focused) EdendaleColors.OnGold
                    else MaterialTheme.colorScheme.onBackground,
                )
            }
            Surface(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                    chrome.showControls()
                },
                modifier = Modifier
                    .size(76.dp)
                    .focusRequester(centerFocus)
                    .focusProperties { canFocus = controlsFocusable }
                    .onFocusChanged { if (it.isFocused) chipDidFocus() }
                    .tvFocusLift(isTelevision, CircleShape),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                            ),
                            contentDescription = stringResource(
                                if (isPlaying) R.string.action_pause else R.string.action_play,
                            ),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            ArchiveIconButton(
                onClick = { seekBy(player, chrome, PlayerLogic.SEEK_STEP_MILLIS) },
                modifier = Modifier
                    .size(56.dp)
                    .focusProperties { canFocus = controlsFocusable }
                    .onFocusChanged { if (it.isFocused) chipDidFocus() },
                isTelevision = isTelevision,
            ) { focused ->
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_rotate_right_10),
                    contentDescription = stringResource(R.string.player_forward_10),
                    modifier = Modifier.size(34.dp),
                    tint = if (focused) EdendaleColors.OnGold
                    else MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        val displayedMillis = chrome.remotePreviewMillis
            ?: chrome.dragFraction?.let { (it * durationMillis).toLong() }
            ?: positionMillis
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = edgeMargin, vertical = 18.dp)
                .focusGroup()
                .focusProperties { if (isTelevision) up = centerFocus },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PlayerLogic.timestamp(displayedMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TimelineBar(
                player = player,
                chrome = chrome,
                displayedFraction = if (durationMillis > 0) {
                    (displayedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
                } else {
                    0f
                },
                durationMillis = durationMillis,
                isTelevision = isTelevision,
                canFocus = controlsFocusable,
                focusRequester = timelineFocus,
                upFocus = centerFocus,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = PlayerLogic.timestamp(durationMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Circular icon button used across the player chrome. */
@Composable
private fun PlayerChip(
    iconRes: Int,
    contentDescription: String?,
    isTelevision: Boolean,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    canFocus: Boolean = true,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    ArchiveIconButton(
        onClick = onClick,
        modifier = modifier
            .focusProperties { this.canFocus = canFocus }
            .onFocusChanged { if (it.isFocused) onFocus() },
        isTelevision = isTelevision,
    ) { focused ->
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            // An active chip is already gold, which is also the focus fill, so
            // focus has to take the glyph with it or it vanishes.
            tint = when {
                focused -> EdendaleColors.OnGold
                isActive -> EdendaleColors.Gold
                else -> MaterialTheme.colorScheme.onBackground
            },
        )
    }
}

// ------------------------------------------------------------------
// Timeline
// ------------------------------------------------------------------

/**
 * Progress bar with a knob, drawn by hand: Material's Slider has no key
 * handling at all, so a focused Slider ignores D-pad left/right and the
 * focus system moves away instead of seeking. Touch taps and drags scrub;
 * on TV, focusing it turns left/right into immediate ±10 s seeks.
 */
@Composable
private fun TimelineBar(
    player: ExoPlayer,
    chrome: PlayerChromeState,
    displayedFraction: Float,
    durationMillis: Long,
    isTelevision: Boolean,
    canFocus: Boolean,
    focusRequester: FocusRequester,
    upFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val engaged = focused || chrome.dragFraction != null

    Canvas(
        modifier
            .height(32.dp)
            .graphicsLayer {
                scaleX = if (focused) 1.02f else 1f
                scaleY = if (focused) 1.02f else 1f
            }
            .onKeyEvent { event ->
                if (!isTelevision || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount
                val act = repeat == 0 || repeat % 4 == 0
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (act) seekBy(player, chrome, -PlayerLogic.SEEK_STEP_MILLIS)
                        chrome.showControls()
                        true
                    }
                    Key.DirectionRight -> {
                        if (act) seekBy(player, chrome, PlayerLogic.SEEK_STEP_MILLIS)
                        chrome.showControls()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusProperties {
                this.canFocus = isTelevision && canFocus
                up = upFocus
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) chrome.showControls()
            }
            .focusable()
            .pointerInput(durationMillis) {
                detectTapGestures { offset ->
                    if (durationMillis > 0 && size.width > 0) {
                        player.seekTo((offset.x / size.width * durationMillis).toLong())
                        chrome.showControls()
                    }
                }
            }
            .pointerInput(durationMillis) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (size.width > 0) {
                            chrome.dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        if (size.width > 0) {
                            chrome.dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        chrome.dragFraction?.let { fraction ->
                            if (durationMillis > 0) player.seekTo((fraction * durationMillis).toLong())
                        }
                        chrome.dragFraction = null
                        chrome.showControls()
                    },
                    onDragCancel = { chrome.dragFraction = null },
                )
            },
    ) {
        val trackHeight = (if (engaged) 8.dp else 5.dp).toPx()
        val knobRadius = (if (engaged) 9.dp else 6.dp).toPx()
        val centerY = size.height / 2
        val fraction = displayedFraction.coerceIn(0f, 1f)
        drawLine(
            color = EdendaleColors.SurfaceHigh,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = EdendaleColors.Gold,
            start = Offset(0f, centerY),
            end = Offset(size.width * fraction, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = EdendaleColors.Gold,
            radius = knobRadius,
            center = Offset(size.width * fraction, centerY),
        )
    }
}

/**
 * Non-focusable timeline the remote can peek without mounting the full
 * chrome; also the live preview surface for accumulated D-pad seeks.
 */
@Composable
private fun BoxScope.TvTimelineOverlay(
    positionMillis: Long,
    durationMillis: Long,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, EdendaleColors.Background),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 64.dp, vertical = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PlayerLogic.timestamp(positionMillis),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val fraction = if (durationMillis > 0) {
                (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
            } else {
                0f
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(8.dp),
            ) {
                drawLine(
                    color = EdendaleColors.SurfaceHigh,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = EdendaleColors.Gold,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width * fraction, size.height / 2),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
            Text(
                text = PlayerLogic.timestamp(durationMillis),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------
// HUD
// ------------------------------------------------------------------

/** Transient gesture-feedback pill at the top of the screen. */
@Composable
private fun BoxScope.PlayerHudView(chrome: PlayerChromeState) {
    val hud = chrome.hud ?: return
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 72.dp),
        shape = RoundedCornerShape(50),
        color = EdendaleColors.SurfaceLow.copy(alpha = .92f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (hud) {
                is PlayerHud.Seek -> {
                    Icon(
                        painter = painterResource(
                            id = if (hud.seconds < 0) R.drawable.ic_backward else R.drawable.ic_forward,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = EdendaleColors.Gold,
                    )
                    HudValue(stringResource(R.string.player_seek_seconds, kotlin.math.abs(hud.seconds)))
                }
                is PlayerHud.Speed -> {
                    HudCaption(stringResource(R.string.player_speed))
                    HudValue(PlayerLogic.rateLabel(hud.rate))
                }
                is PlayerHud.Volume -> {
                    HudCaption(stringResource(R.string.player_volume))
                    HudLevelBar(hud.level)
                }
                is PlayerHud.Brightness -> {
                    HudCaption(stringResource(R.string.player_brightness))
                    HudLevelBar(hud.level)
                }
                is PlayerHud.Scrub -> {
                    HudValue(hud.target)
                    HudCaption(hud.offset)
                }
            }
        }
    }
}

@Composable
private fun HudCaption(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HudValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun HudLevelBar(level: Float) {
    Canvas(Modifier.size(width = 120.dp, height = 5.dp)) {
        drawLine(
            color = EdendaleColors.SurfaceHigh,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = EdendaleColors.Gold,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width * level.coerceIn(0f, 1f), size.height / 2),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
    }
}

// ------------------------------------------------------------------
// Side panels
// ------------------------------------------------------------------

@Composable
private fun BoxScope.PlayerPanels(
    player: ExoPlayer,
    chrome: PlayerChromeState,
    isTelevision: Boolean,
    currentUri: String,
    playlist: PlayerPlaylist?,
    tracks: Tracks,
    onlineSubtitles: OnlineSubtitlesState,
    wyzieConfigured: Boolean,
    wyzieLookup: WyzieLookup?,
    supportsPip: Boolean,
    panelFocus: FocusRequester,
    onAutoPipChanged: () -> Unit,
    onSelectEntry: (PlaylistEntry) -> Unit,
    onSearchOnlineSubtitles: () -> Unit,
    onDownloadOnlineSubtitle: (WyzieSubtitle) -> Unit,
) {
    // Tap anywhere outside the panel to dismiss it. Never a focus target —
    // a full-screen focusable would trap the D-pad.
    if (chrome.activePanel != null) {
        Box(
            Modifier
                .matchParentSize()
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { chrome.closePanel() },
        )
    }

    val panelWidth = if (isTelevision) 460.dp else 340.dp

    AnimatedVisibility(
        visible = chrome.activePanel == PlayerPanel.PLAYLIST,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        PlaylistPanel(
            chrome = chrome,
            isTelevision = isTelevision,
            currentUri = currentUri,
            playlist = playlist,
            panelWidth = panelWidth,
            panelFocus = panelFocus,
            onSelectEntry = onSelectEntry,
        )
    }

    AnimatedVisibility(
        visible = chrome.activePanel == PlayerPanel.SETTINGS,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        SettingsPanel(
            player = player,
            chrome = chrome,
            isTelevision = isTelevision,
            tracks = tracks,
            onlineSubtitles = onlineSubtitles,
            wyzieConfigured = wyzieConfigured,
            wyzieLookup = wyzieLookup,
            supportsPip = supportsPip,
            panelWidth = panelWidth,
            panelFocus = panelFocus,
            onAutoPipChanged = onAutoPipChanged,
            onSearchOnlineSubtitles = onSearchOnlineSubtitles,
            onDownloadOnlineSubtitle = onDownloadOnlineSubtitle,
        )
    }
}

@Composable
private fun PanelSurface(
    panelWidth: androidx.compose.ui.unit.Dp,
    panelFocus: FocusRequester,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // The panel hugs the trailing edge, so leaving it means moving toward the
    // leading one — left under LTR, right under RTL.
    val exitDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
        FocusDirection.Left
    } else {
        FocusDirection.Right
    }
    val exitKey = if (exitDirection == FocusDirection.Left) {
        Key.DirectionLeft
    } else {
        Key.DirectionRight
    }
    Surface(
        modifier = Modifier
            .width(panelWidth)
            .fillMaxHeight()
            // Speed and Aspect Ratio lay their controls out in a row, so a
            // sideways press has to try the panel's own focus search first and
            // only means "leave" when nothing is there. The controls behind are
            // deactivated while a panel is open, so that search can never
            // wander out into the transport.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != exitKey) {
                    return@onKeyEvent false
                }
                if (!focusManager.moveFocus(exitDirection)) onDismiss()
                true
            }
            .focusRequester(panelFocus)
            .focusGroup(),
        color = EdendaleColors.SurfaceLow.copy(alpha = .97f),
    ) {
        content()
    }
}

@Composable
private fun PanelHeader(title: String, isTelevision: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        PlayerChip(
            iconRes = R.drawable.ic_sidebar_right,
            contentDescription = stringResource(R.string.action_close),
            isTelevision = isTelevision,
            isActive = true,
            onClick = onClose,
        )
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Full-width focusable row shared by the panels. The gold focus ring is
 * drawn here rather than with [tvFocusLift] because a scale lift would
 * distort a block spanning the panel.
 */
@Composable
private fun PanelRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(EdendaleRadii.Group.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .background(
                when {
                    focused -> EdendaleColors.SurfaceHigh
                    selected -> EdendaleColors.Surface
                    else -> Color.Transparent
                },
                shape,
            )
            // Inert rows stay clickable so they stay focusable: a row that
            // drops its focus target the moment it disables (Search while a
            // search runs, results while a download runs) strands the D-pad
            // with nothing focused. Same idiom as the playing PlaylistRow.
            .clickable { if (enabled) onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    selected || focused -> EdendaleColors.Gold
                    enabled -> MaterialTheme.colorScheme.onBackground
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

// ------------------------------------------------------------------
// Settings panel
// ------------------------------------------------------------------

@Composable
private fun SettingsPanel(
    player: ExoPlayer,
    chrome: PlayerChromeState,
    isTelevision: Boolean,
    tracks: Tracks,
    onlineSubtitles: OnlineSubtitlesState,
    wyzieConfigured: Boolean,
    wyzieLookup: WyzieLookup?,
    supportsPip: Boolean,
    panelWidth: androidx.compose.ui.unit.Dp,
    panelFocus: FocusRequester,
    onAutoPipChanged: () -> Unit,
    onSearchOnlineSubtitles: () -> Unit,
    onDownloadOnlineSubtitle: (WyzieSubtitle) -> Unit,
) {
    PanelSurface(panelWidth, panelFocus, onDismiss = { chrome.closePanel() }) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            PanelHeader(
                title = stringResource(R.string.player_adjustments),
                isTelevision = isTelevision,
                onClose = { chrome.closePanel() },
            )

            SpeedSection(player, chrome, isTelevision)
            SubtitleSection(player, chrome, tracks)
            OnlineSubtitlesSection(
                state = onlineSubtitles,
                chrome = chrome,
                isConfigured = wyzieConfigured,
                lookup = wyzieLookup,
                onSearch = onSearchOnlineSubtitles,
                onDownload = onDownloadOnlineSubtitle,
            )
            PlaybackSection(player, chrome, supportsPip, onAutoPipChanged)
            AspectSection(chrome, isTelevision)
        }
    }
}

@Composable
private fun SpeedSection(player: ExoPlayer, chrome: PlayerChromeState, isTelevision: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelLabel(stringResource(R.string.player_speed))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedChip("−", isTelevision) {
                chrome.setRate(player, PlayerLogic.decrementedRate(chrome.baseRate))
            }
            Text(
                text = PlayerLogic.rateLabel(chrome.baseRate),
                modifier = Modifier.widthIn(min = 72.dp),
                style = MaterialTheme.typography.titleLarge,
                color = if (chrome.baseRate == 1f) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    EdendaleColors.Gold
                },
            )
            SpeedChip("+", isTelevision) {
                chrome.setRate(player, PlayerLogic.incrementedRate(chrome.baseRate))
            }
            Spacer(Modifier.weight(1f))
            if (chrome.baseRate != 1f) {
                Surface(
                    onClick = { chrome.setRate(player, 1f) },
                    modifier = Modifier.tvFocusLift(isTelevision, RoundedCornerShape(50)),
                    shape = RoundedCornerShape(50),
                    color = EdendaleColors.Surface,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Text(
                        text = stringResource(R.string.player_reset).uppercase(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedChip(label: String, isTelevision: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .tvFocusLift(isTelevision, CircleShape),
        shape = CircleShape,
        color = EdendaleColors.Surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SubtitleSection(player: ExoPlayer, chrome: PlayerChromeState, tracks: Tracks) {
    val options = remember(tracks) { textTrackOptions(tracks) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelLabel(stringResource(R.string.player_subtitles))
        if (options.isEmpty()) {
            Text(
                text = stringResource(R.string.player_subtitles_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val noneSelected = options.none { it.isSelected }
                PanelRow(
                    title = stringResource(R.string.player_subtitles_off),
                    selected = noneSelected,
                    trailing = if (noneSelected) {
                        { SelectedCheck() }
                    } else {
                        null
                    },
                    onClick = {
                        selectTextTrack(player, null)
                        chrome.noteInteraction()
                    },
                )
                options.forEachIndexed { index, option ->
                    PanelRow(
                        title = trackOptionLabel(option, index),
                        selected = option.isSelected,
                        trailing = if (option.isSelected) {
                            { SelectedCheck() }
                        } else {
                            null
                        },
                        onClick = {
                            selectTextTrack(player, option)
                            chrome.noteInteraction()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedCheck() {
    Icon(
        painter = painterResource(id = R.drawable.ic_check),
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = EdendaleColors.Gold,
    )
}

@Composable
private fun OnlineSubtitlesSection(
    state: OnlineSubtitlesState,
    chrome: PlayerChromeState,
    isConfigured: Boolean,
    lookup: WyzieLookup?,
    onSearch: () -> Unit,
    onDownload: (WyzieSubtitle) -> Unit,
) {
    var languagesExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(lookup) {
        languagesExpanded = false
    }
    val selectedLanguage = state.language.takeIf { it.isNotBlank() }
        ?.let(::localizedLanguageName)
        ?: stringResource(R.string.player_online_all_languages)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelLabel(stringResource(R.string.player_online_subtitles))
        when {
            !isConfigured -> SecondaryPanelCopy(
                stringResource(R.string.player_online_needs_key),
            )

            lookup == null -> SecondaryPanelCopy(
                stringResource(R.string.player_online_needs_tmdb),
            )

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PanelRow(
                        title = stringResource(R.string.player_online_language),
                        detail = selectedLanguage,
                        onClick = {
                            languagesExpanded = !languagesExpanded
                            chrome.showControls()
                        },
                    )
                    if (languagesExpanded) {
                        ONLINE_SUBTITLE_LANGUAGES.forEach { code ->
                            val selected = state.language == code
                            PanelRow(
                                title = if (code.isEmpty()) {
                                    stringResource(R.string.player_online_all_languages)
                                } else {
                                    localizedLanguageName(code)
                                },
                                selected = selected,
                                trailing = if (selected) {
                                    { SelectedCheck() }
                                } else {
                                    null
                                },
                                modifier = Modifier.padding(start = 12.dp),
                                onClick = {
                                    state.updateLanguage(code)
                                    languagesExpanded = false
                                    chrome.showControls()
                                },
                            )
                        }
                    }
                    ToggleRow(
                        title = stringResource(R.string.player_online_hearing_impaired),
                        detail = stringResource(R.string.player_online_hearing_impaired_detail),
                        checked = state.hearingImpaired,
                    ) {
                        state.updateHearingImpaired(it)
                        chrome.showControls()
                    }
                    PanelRow(
                        title = stringResource(R.string.player_online_search),
                        enabled = state.phase !is OnlineSubtitlePhase.Searching &&
                            state.downloadingId == null,
                        onClick = {
                            chrome.showControls()
                            onSearch()
                        },
                    )
                }

                when (val phase = state.phase) {
                    OnlineSubtitlePhase.Idle -> Unit
                    OnlineSubtitlePhase.Searching -> OnlineSubtitleProgress()
                    OnlineSubtitlePhase.Results -> {
                        if (state.results.isEmpty()) {
                            SecondaryPanelCopy(stringResource(R.string.player_online_none_found))
                        }
                    }
                    is OnlineSubtitlePhase.Failed -> Text(
                        text = phase.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = EdendaleColors.Gold,
                    )
                }

                if (state.results.isNotEmpty()) {
                    OnlineSubtitleResults(state, chrome, onDownload)
                }
            }
        }
    }
}

@Composable
private fun OnlineSubtitleProgress() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = EdendaleColors.Gold,
        )
        SecondaryPanelCopy(stringResource(R.string.player_online_searching))
    }
}

@Composable
private fun OnlineSubtitleResults(
    state: OnlineSubtitlesState,
    chrome: PlayerChromeState,
    onDownload: (WyzieSubtitle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.results.take(MAX_ONLINE_SUBTITLE_ROWS).forEach { subtitle ->
            val downloaded = subtitle.id in state.downloadedIds
            val downloading = state.downloadingId == subtitle.id
            val enabled = !downloaded && state.downloadingId == null
            PanelRow(
                title = subtitle.display,
                detail = onlineSubtitleDetail(subtitle),
                selected = downloaded,
                enabled = enabled,
                trailing = {
                    when {
                        downloading -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = EdendaleColors.Gold,
                        )
                        downloaded -> SelectedCheck()
                        else -> Text(
                            text = stringResource(R.string.player_online_download).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = EdendaleColors.Gold,
                        )
                    }
                },
                onClick = {
                    chrome.showControls()
                    onDownload(subtitle)
                },
            )
        }
        val remaining = state.results.size - MAX_ONLINE_SUBTITLE_ROWS
        if (remaining > 0) {
            SecondaryPanelCopy(
                pluralStringResource(
                    R.plurals.player_online_more_matched,
                    remaining,
                    remaining,
                ),
            )
        }
    }
}

@Composable
private fun SecondaryPanelCopy(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun localizedLanguageName(code: String): String =
    Locale.forLanguageTag(code).displayLanguage
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }

@Composable
private fun onlineSubtitleDetail(subtitle: WyzieSubtitle): String? =
    listOfNotNull(
        subtitle.release?.takeIf { it.isNotBlank() }
            ?: subtitle.fileName?.takeIf { it.isNotBlank() },
        subtitle.format.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
        stringResource(R.string.player_online_hi).takeIf { subtitle.isHearingImpaired },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

private val ONLINE_SUBTITLE_LANGUAGES = listOf(
    "",
    "en", "es", "fr", "de", "it", "pt", "nl", "pl", "ru", "tr", "ar", "hi", "id",
    "ja", "ko", "zh", "sv", "da", "no", "fi", "cs", "el", "he", "th", "vi", "ro",
    "hu", "uk",
)

private const val MAX_ONLINE_SUBTITLE_ROWS = 25

@Composable
private fun PlaybackSection(
    player: ExoPlayer,
    chrome: PlayerChromeState,
    supportsPip: Boolean,
    onAutoPipChanged: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelLabel(stringResource(R.string.player_playback))
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = stringResource(R.string.player_skip_recap),
            detail = stringResource(R.string.player_skip_recap_detail),
            checked = chrome.skipRecap,
        ) { chrome.setSkipRecapEnabled(it) }
        ToggleRow(
            title = stringResource(R.string.player_skip_credits),
            detail = stringResource(R.string.player_skip_credits_detail),
            checked = chrome.skipCredits,
        ) { chrome.setSkipCreditsEnabled(it) }
        ToggleRow(
            title = stringResource(R.string.player_loop),
            detail = stringResource(R.string.player_loop_detail),
            checked = chrome.loopEnabled,
        ) { chrome.setLoop(player, it) }
        if (supportsPip) {
            ToggleRow(
                title = stringResource(R.string.player_auto_pip),
                detail = stringResource(R.string.player_auto_pip_detail),
                checked = chrome.autoPip,
            ) {
                chrome.setAutoPipEnabled(it)
                onAutoPipChanged()
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    PanelRow(
        title = title,
        detail = detail,
        onClick = { onToggle(!checked) },
        trailing = {
            // Display-only: the row itself is the click and focus target, so
            // the D-pad has one stop per option instead of two.
            Switch(checked = checked, onCheckedChange = null)
        },
    )
}

@Composable
private fun AspectSection(chrome: PlayerChromeState, isTelevision: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelLabel(stringResource(R.string.player_aspect_ratio))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentChip(
                label = stringResource(R.string.player_aspect_fit),
                selected = !chrome.aspectFill,
                isTelevision = isTelevision,
            ) { chrome.aspectFill = false }
            SegmentChip(
                label = stringResource(R.string.player_aspect_fill),
                selected = chrome.aspectFill,
                isTelevision = isTelevision,
            ) { chrome.aspectFill = true }
        }
    }
}

@Composable
private fun SegmentChip(
    label: String,
    selected: Boolean,
    isTelevision: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.tvFocusLift(isTelevision, RoundedCornerShape(EdendaleRadii.Soft.dp)),
        shape = RoundedCornerShape(EdendaleRadii.Soft.dp),
        color = if (selected) EdendaleColors.Gold else EdendaleColors.Surface,
        contentColor = if (selected) EdendaleColors.OnGold else MaterialTheme.colorScheme.onBackground,
    ) {
        Text(
            text = label.uppercase(),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// ------------------------------------------------------------------
// Playlist panel
// ------------------------------------------------------------------

@Composable
private fun PlaylistPanel(
    chrome: PlayerChromeState,
    isTelevision: Boolean,
    currentUri: String,
    playlist: PlayerPlaylist?,
    panelWidth: androidx.compose.ui.unit.Dp,
    panelFocus: FocusRequester,
    onSelectEntry: (PlaylistEntry) -> Unit,
) {
    PanelSurface(panelWidth, panelFocus, onDismiss = { chrome.closePanel() }) {
        Column(Modifier.padding(24.dp)) {
            PanelHeader(
                title = stringResource(
                    if (playlist?.isEpisodeList == true) {
                        R.string.player_episodes
                    } else {
                        R.string.player_in_this_folder
                    },
                ),
                isTelevision = isTelevision,
                onClose = { chrome.closePanel() },
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val entries = playlist?.entries.orEmpty()
                if (playlist?.isEpisodeList == true) {
                    entries.groupBy { it.season ?: 0 }.forEach { (season, seasonEntries) ->
                        item("season-$season") {
                            Text(
                                text = if (season == 0) {
                                    stringResource(R.string.season_specials)
                                } else {
                                    stringResource(R.string.season_number, season)
                                }.uppercase(),
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(seasonEntries.size, key = { seasonEntries[it].uri }) { index ->
                            PlaylistRow(seasonEntries[index], currentUri, onSelectEntry)
                        }
                    }
                } else {
                    items(entries.size, key = { entries[it].uri }) { index ->
                        PlaylistRow(entries[index], currentUri, onSelectEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    entry: PlaylistEntry,
    currentUri: String,
    onSelectEntry: (PlaylistEntry) -> Unit,
) {
    val isCurrent = entry.uri == currentUri
    PanelRow(
        title = entry.title,
        detail = entry.detail,
        selected = isCurrent,
        trailing = if (isCurrent) {
            {
                Icon(
                    painter = painterResource(id = R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = EdendaleColors.Gold,
                )
            }
        } else {
            null
        },
        onClick = { if (!isCurrent) onSelectEntry(entry) },
    )
}

// ------------------------------------------------------------------
// Touch gestures (handhelds)
// ------------------------------------------------------------------

private enum class GestureMode { PENDING, HOLD_SPEED, SCRUB, ADJUST_LEFT, ADJUST_RIGHT, IGNORED }

private const val HOLD_DWELL_MILLIS = 400L

/**
 * Touch-first hidden controls, mirroring Apple's gesture layer:
 *  - single tap             show/hide the controls
 *  - double tap L / C / R   seek −10 s / play-pause / +10 s
 *  - vertical swipe L / R   screen brightness / player volume
 *  - press-and-hold L / R   0.5× / 1.5× until released
 *  - hold + horizontal drag scrub the timeline (full width = 5 minutes)
 */
@Composable
private fun BoxScope.PlayerGestureLayer(
    player: ExoPlayer,
    chrome: PlayerChromeState,
    activity: PlayerActivity?,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { chrome.toggleControls() },
                    onDoubleTap = { offset ->
                        when ((offset.x / size.width * 3).toInt().coerceIn(0, 2)) {
                            0 -> seekBy(player, chrome, -PlayerLogic.SEEK_STEP_MILLIS)
                            1 -> {
                                if (player.isPlaying) player.pause() else player.play()
                                chrome.showControls()
                            }
                            else -> seekBy(player, chrome, PlayerLogic.SEEK_STEP_MILLIS)
                        }
                    },
                )
            }
            .pointerInput(player) {
                val swipeSlop = 14.dp.toPx()
                val scrubSlop = 30.dp.toPx()
                val adjustTravel = 280.dp.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val holdDeadline = down.uptimeMillis + HOLD_DWELL_MILLIS
                    var mode = GestureMode.PENDING
                    var baseline = 0f
                    var scrubBase = 0.0
                    var scrubDuration = 0L
                    var translation = Offset.Zero

                    gesture@ while (true) {
                        val event = if (mode == GestureMode.PENDING) {
                            val remaining = holdDeadline - SystemClock.uptimeMillis()
                            if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            // Dwell elapsed with the finger still down and
                            // (nearly) still: press-and-hold speed.
                            mode = if (translation.getDistance() < swipeSlop) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                chrome.beginHoldRate(
                                    player,
                                    PlayerLogic.holdRate(down.position.x, size.width.toFloat()),
                                )
                                GestureMode.HOLD_SPEED
                            } else {
                                GestureMode.IGNORED
                            }
                            continue@gesture
                        }

                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.first()
                        if (change.changedToUp()) break@gesture
                        translation = change.position - down.position

                        when (mode) {
                            GestureMode.PENDING -> {
                                if (translation.getDistance() > swipeSlop) {
                                    mode = if (kotlin.math.abs(translation.y) > kotlin.math.abs(translation.x)) {
                                        if (down.position.x < size.width / 2f) {
                                            baseline = activity?.windowBrightnessBaseline() ?: 0.5f
                                            GestureMode.ADJUST_LEFT
                                        } else {
                                            baseline = player.volume
                                            GestureMode.ADJUST_RIGHT
                                        }
                                    } else {
                                        // Horizontal drags only scrub after a
                                        // hold — a stray swipe never seeks.
                                        GestureMode.IGNORED
                                    }
                                }
                            }
                            GestureMode.HOLD_SPEED -> {
                                if (kotlin.math.abs(translation.x) > scrubSlop) {
                                    chrome.endHoldRate(player)
                                    scrubDuration = player.duration
                                        .takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                                    if (scrubDuration > 0) {
                                        scrubBase = player.currentPosition.toDouble() / scrubDuration
                                        chrome.dragFraction = scrubBase.toFloat()
                                        mode = GestureMode.SCRUB
                                    } else {
                                        mode = GestureMode.IGNORED
                                    }
                                }
                                change.consume()
                            }
                            GestureMode.SCRUB -> {
                                val target = PlayerLogic.scrubTarget(
                                    basePosition = scrubBase,
                                    translation = translation.x,
                                    width = size.width.toFloat(),
                                    durationMillis = scrubDuration,
                                )
                                chrome.dragFraction = target.toFloat()
                                val targetMillis = (target * scrubDuration).toLong()
                                chrome.showHud(
                                    PlayerHud.Scrub(
                                        target = PlayerLogic.timestamp(targetMillis),
                                        offset = PlayerLogic.signedTimestamp(
                                            targetMillis - (scrubBase * scrubDuration).toLong(),
                                        ),
                                        sticky = true,
                                    ),
                                )
                                change.consume()
                            }
                            GestureMode.ADJUST_LEFT -> {
                                val level = (baseline - translation.y / adjustTravel).coerceIn(0f, 1f)
                                activity?.setWindowBrightness(level)
                                chrome.showHud(PlayerHud.Brightness(level))
                                change.consume()
                            }
                            GestureMode.ADJUST_RIGHT -> {
                                val level = (baseline - translation.y / adjustTravel).coerceIn(0f, 1f)
                                player.volume = level
                                chrome.showHud(PlayerHud.Volume(level))
                                change.consume()
                            }
                            GestureMode.IGNORED -> Unit
                        }
                    }

                    when (mode) {
                        GestureMode.HOLD_SPEED -> chrome.endHoldRate(player)
                        GestureMode.SCRUB -> {
                            chrome.dragFraction?.let { fraction ->
                                if (scrubDuration > 0) {
                                    player.seekTo((fraction * scrubDuration).toLong())
                                }
                            }
                            chrome.dragFraction = null
                            chrome.dismissHud()
                        }
                        else -> Unit
                    }
                }
            },
    )
}

// ------------------------------------------------------------------
// Track selection
// ------------------------------------------------------------------

internal data class PlayerTrackOption(
    val group: TrackGroup,
    val trackIndex: Int,
    val id: String?,
    val label: String?,
    val language: String?,
    val isSelected: Boolean,
)

/** Selectable subtitle tracks in the current media, in declaration order. */
internal fun textTrackOptions(tracks: Tracks): List<PlayerTrackOption> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) return@mapNotNull null
            val format = group.getTrackFormat(index)
            PlayerTrackOption(
                group = group.mediaTrackGroup,
                trackIndex = index,
                id = format.id,
                label = format.label,
                language = format.language,
                isSelected = group.isTrackSelected(index),
            )
        }
    }

/**
 * Applies a subtitle choice. Off must both disable the text type and clear
 * any override — a stale override would otherwise resurface on the next
 * selection; picking a track must re-enable the type or a previous Off
 * silently suppresses the override.
 */
internal fun selectTextTrack(player: Player, option: PlayerTrackOption?) {
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .apply {
            if (option == null) {
                clearOverridesOfType(C.TRACK_TYPE_TEXT)
                setPreferredTextLanguage(null)
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
                option.language?.let { setPreferredTextLanguage(it) }
            }
        }
        .build()
}

@Composable
private fun trackOptionLabel(option: PlayerTrackOption, index: Int): String {
    option.label?.takeIf { it.isNotBlank() }?.let { return it }
    val language = option.language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { tag -> Locale.forLanguageTag(tag).displayLanguage }
        ?.takeIf { it.isNotBlank() }
    return language?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        ?: stringResource(R.string.player_track_number, index + 1)
}
