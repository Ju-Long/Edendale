package com.babasama.edendale.android.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleTheme
import com.babasama.edendale.android.R
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

/**
 * Full-screen in-app player. Resumes from stored watch progress, writes
 * progress every five seconds and on dismiss, and marks titles completed near
 * the end (~95%) or at STATE_ENDED. Files without a TMDB match play fine but
 * record no progress because watch state is keyed by TMDB id.
 * Also the target of "Open with" VIEW intents for video mime types.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var dataStore: LocalDataStore? = null
    private var progressKey: ProgressKey? = null
    private var resumeFraction: Double? = null
    private var resumeApplied = false
    private var completedWritten = false
    private val controlsVisible = mutableStateOf(true)
    private val inPipMode = mutableStateOf(false)

    /** Absent on Android TV and on handhelds where the OEM dropped PiP. */
    private val supportsPip: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /** Drives the play/pause and skip buttons drawn inside the PiP window. */
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            val exoPlayer = player ?: return
            when (intent.getIntExtra(EXTRA_PIP_CONTROL, -1)) {
                PIP_CONTROL_PLAY -> exoPlayer.play()
                PIP_CONTROL_PAUSE -> exoPlayer.pause()
                PIP_CONTROL_REWIND ->
                    exoPlayer.seekTo((exoPlayer.currentPosition - 10_000).coerceAtLeast(0))
                PIP_CONTROL_FORWARD -> {
                    val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    val target = exoPlayer.currentPosition + 10_000
                    exoPlayer.seekTo(duration?.let { target.coerceAtMost(it) } ?: target)
                }
            }
        }
    }
    private var pipReceiverRegistered = false

    /** Survives activity teardown long enough to land the final write. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ProgressKey(
        val tmdbId: Int,
        val mediaType: WatchMediaType,
        val showTmdbId: Int?,
        val season: Int?,
        val episode: Int?,
    ) {
        val storageKey: String get() = "${mediaType.name.lowercase()}:$tmdbId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val uri = intent.data ?: intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: displayName(uri)
            ?: uri.lastPathSegment
            ?: "Video"

        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        if (tmdbId > 0) {
            progressKey = ProgressKey(
                tmdbId = tmdbId,
                mediaType = if (intent.getBooleanExtra(EXTRA_IS_EPISODE, false)) {
                    WatchMediaType.EPISODE
                } else {
                    WatchMediaType.MOVIE
                },
                showTmdbId = intent.getIntExtra(EXTRA_SHOW_TMDB_ID, -1).takeIf { it > 0 },
                season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 },
                episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 },
            )
        }
        dataStore = LocalDataStore((application as EdendaleApplication).database)

        val customDataSourceFactory = androidx.media3.datasource.DataSource.Factory {
            if (uri.scheme == "smb") {
                SmbDataSource(this)
            } else {
                androidx.media3.datasource.DefaultDataSource(this, false)
            }
        }
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(customDataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        player = exoPlayer
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) applyPendingResume()
                if (playbackState == Player.STATE_ENDED) {
                    writeProgress(completed = true)
                    finish()
                }
            }

            // The PiP window mirrors playback state: its buttons and its
            // auto-enter arming both live in the params, so refresh them
            // whenever what they describe changes.
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePipParams()

            override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()
        })

        lifecycleScopedStart(exoPlayer, uri)

        setContent {
            EdendaleTheme {
                PlayerScreen(
                    player = exoPlayer,
                    title = title,
                    controlsVisible = controlsVisible,
                    inPipMode = inPipMode,
                    onEnterPip = if (supportsPip) ::enterPictureInPicture else null,
                    onClose = { finish() },
                )
            }
        }
    }

    /** Reads stored progress first (fast Room lookup), then starts playback. */
    private fun lifecycleScopedStart(exoPlayer: ExoPlayer, uri: Uri) {
        val key = progressKey
        writeScope.launch {
            resumeFraction = key?.let { dataStore?.getWatchProgress(it.storageKey) }
                ?.takeIf { !it.isCompleted }
                ?.normalizedPosition
                ?.takeIf { it in 0.02..0.94 }
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                exoPlayer.setMediaItem(MediaItem.fromUri(uri))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                applyPendingResume()
            }
        }
    }

    private fun applyPendingResume() {
        val exoPlayer = player ?: return
        val fraction = resumeFraction ?: return
        if (resumeApplied) return
        val duration = exoPlayer.duration
        if (duration != C.TIME_UNSET && duration > 0) {
            resumeApplied = true
            exoPlayer.seekTo((duration * fraction).toLong())
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
        updatePipParams()
    }

    override fun onStop() {
        writeProgress()
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        writeProgress()
        unregisterPipReceiver()
        player?.release()
        player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Picture in Picture
    // ------------------------------------------------------------------

    /**
     * The user leaving mid-play floats the video instead of stopping it.
     * Android 12+ enters on its own from the auto-enter flag in the params
     * (which also gives the smooth gesture transition), so only older
     * releases need the explicit call here.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            supportsPip && player?.isPlaying == true
        ) {
            enterPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode.value = isInPictureInPictureMode
        // The floating window is too small for the app's own chrome — it uses
        // the system's remote actions instead.
        controlsVisible.value = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            registerPipReceiver()
        } else {
            unregisterPipReceiver()
            // Leaving PiP without coming back to the foreground means the user
            // closed the floating window; end the session rather than leave a
            // stopped player behind in the back stack.
            if (lifecycle.currentState == Lifecycle.State.CREATED) finish()
        }
    }

    private fun enterPictureInPicture() {
        if (!supportsPip) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun updatePipParams() {
        if (!supportsPip) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(videoAspectRatio())
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player?.isPlaying == true)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Clamped to the range the system accepts; unknown sizes fall back to 16:9. */
    private fun videoAspectRatio(): Rational {
        val size = player?.videoSize
        val width = size?.width ?: 0
        val height = size?.height ?: 0
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toDouble() / height
        return when {
            ratio < MIN_PIP_ASPECT -> Rational(419, 1000)
            ratio > MAX_PIP_ASPECT -> Rational(239, 100)
            else -> Rational(width, height)
        }
    }

    private fun pipActions(): List<RemoteAction> {
        val playing = player?.isPlaying == true
        val playPause = if (playing) {
            pipAction(R.drawable.ic_pause, "Pause", PIP_CONTROL_PAUSE)
        } else {
            pipAction(R.drawable.ic_play, "Play", PIP_CONTROL_PLAY)
        }
        if (maxNumPictureInPictureActions < 3) return listOf(playPause)
        return listOf(
            pipAction(R.drawable.ic_arrow_rotate_left_10, getString(R.string.player_back_10), PIP_CONTROL_REWIND),
            playPause,
            pipAction(R.drawable.ic_arrow_rotate_right_10, getString(R.string.player_forward_10), PIP_CONTROL_FORWARD),
        )
    }

    private fun pipAction(iconRes: Int, title: String, control: Int): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_PIP_CONTROL, control)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            control,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pendingIntent)
    }

    private fun registerPipReceiver() {
        if (pipReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        pipReceiverRegistered = true
    }

    private fun unregisterPipReceiver() {
        if (!pipReceiverRegistered) return
        runCatching { unregisterReceiver(pipActionReceiver) }
        pipReceiverRegistered = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any remote/keyboard key first brings the controls back on TV.
        if (event.action == KeyEvent.ACTION_DOWN && !controlsVisible.value &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            controlsVisible.value = true
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    internal fun writeProgress(completed: Boolean = false) {
        val key = progressKey ?: return
        val store = dataStore ?: return
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exoPlayer.currentPosition.coerceIn(0, duration)
        val fraction = position.toDouble() / duration
        val isCompleted = completed || fraction >= COMPLETE_FRACTION
        if (completedWritten && !isCompleted) return
        completedWritten = isCompleted
        writeScope.launch {
            store.updateWatchProgress(
                WatchProgress(
                    tmdbId = key.tmdbId,
                    mediaType = key.mediaType,
                    position = if (isCompleted) 1.0 else fraction,
                    watchedSeconds = position / 1000.0,
                    lastWatchedEpochMillis = System.currentTimeMillis(),
                    isCompleted = isCompleted,
                    showTmdbId = key.showTmdbId,
                    seasonNumber = key.season,
                    episodeNumber = key.episode,
                ),
            )
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    companion object {
        private const val EXTRA_URI = "edendale.uri"
        private const val EXTRA_TITLE = "edendale.title"
        private const val EXTRA_TMDB_ID = "edendale.tmdbId"
        private const val EXTRA_IS_EPISODE = "edendale.isEpisode"
        private const val EXTRA_SHOW_TMDB_ID = "edendale.showTmdbId"
        private const val EXTRA_SEASON = "edendale.season"
        private const val EXTRA_EPISODE = "edendale.episode"
        private const val COMPLETE_FRACTION = 0.95

        private const val ACTION_PIP_CONTROL = "com.babasama.edendale.PIP_CONTROL"
        private const val EXTRA_PIP_CONTROL = "edendale.pipControl"
        private const val PIP_CONTROL_PLAY = 1
        private const val PIP_CONTROL_PAUSE = 2
        private const val PIP_CONTROL_REWIND = 3
        private const val PIP_CONTROL_FORWARD = 4

        /** The system rejects PiP aspect ratios outside 1:2.39 … 2.39:1. */
        private const val MIN_PIP_ASPECT = 1.0 / 2.39
        private const val MAX_PIP_ASPECT = 2.39

        fun play(
            context: Context,
            uri: String,
            title: String,
            tmdbId: Int? = null,
            isEpisode: Boolean = false,
            showTmdbId: Int? = null,
            season: Int? = null,
            episode: Int? = null,
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java).apply {
                    putExtra(EXTRA_URI, uri)
                    putExtra(EXTRA_TITLE, title)
                    tmdbId?.let { putExtra(EXTRA_TMDB_ID, it) }
                    putExtra(EXTRA_IS_EPISODE, isEpisode)
                    showTmdbId?.let { putExtra(EXTRA_SHOW_TMDB_ID, it) }
                    season?.let { putExtra(EXTRA_SEASON, it) }
                    episode?.let { putExtra(EXTRA_EPISODE, it) }
                },
            )
        }
    }
}

@Composable
private fun PlayerScreen(
    player: ExoPlayer,
    title: String,
    controlsVisible: androidx.compose.runtime.MutableState<Boolean>,
    inPipMode: androidx.compose.runtime.State<Boolean>,
    onEnterPip: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMillis by remember { mutableLongStateOf(0L) }
    var durationMillis by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? PlayerActivity

    DisposableListener(player) { state, playing ->
        isBuffering = state == Player.STATE_BUFFERING
        isPlaying = playing
    }

    LaunchedEffect(player) {
        var lastWrite = 0L
        while (isActive) {
            positionMillis = player.currentPosition
            durationMillis = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val now = System.currentTimeMillis()
            if (player.isPlaying && now - lastWrite >= 5_000) {
                lastWrite = now
                activity?.writeProgress()
            }
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible.value, isPlaying, inPipMode.value) {
        if (controlsVisible.value && isPlaying && !inPipMode.value) {
            delay(4_000)
            controlsVisible.value = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !inPipMode.value,
            ) { controlsVisible.value = !controlsVisible.value },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (isBuffering && !inPipMode.value) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible.value && !inPipMode.value,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControls(
                title = title,
                isPlaying = isPlaying,
                positionMillis = dragFraction
                    ?.let { (it * durationMillis).toLong() }
                    ?: positionMillis,
                durationMillis = durationMillis,
                onPlayPause = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                onSeekBack = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) },
                onSeekForward = {
                    val target = player.currentPosition + 10_000
                    player.seekTo(if (durationMillis > 0) target.coerceAtMost(durationMillis) else target)
                },
                onScrub = { fraction -> dragFraction = fraction },
                onScrubFinished = {
                    dragFraction?.let { fraction ->
                        if (durationMillis > 0) player.seekTo((durationMillis * fraction).toLong())
                    }
                    dragFraction = null
                },
                onEnterPip = onEnterPip,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun DisposableListener(
    player: ExoPlayer,
    onUpdate: (state: Int, playing: Boolean) -> Unit,
) {
    androidx.compose.runtime.DisposableEffect(player) {
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

@Composable
private fun PlayerControls(
    title: String,
    isPlaying: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onEnterPip: (() -> Unit)?,
    onClose: () -> Unit,
) {
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_xmark),
                    contentDescription = stringResource(R.string.player_close),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (onEnterPip != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onEnterPip) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_picture_in_picture),
                        contentDescription = stringResource(R.string.player_picture_in_picture),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSeekBack, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_rotate_left_10),
                    contentDescription = stringResource(R.string.player_back_10),
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Surface(
                onClick = onPlayPause,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        contentDescription = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_rotate_right_10),
                    contentDescription = stringResource(R.string.player_forward_10),
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Slider(
                value = if (durationMillis > 0) {
                    (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onValueChange = onScrub,
                onValueChangeFinished = onScrubFinished,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = EdendaleColors.SurfaceHigh,
                ),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(positionMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(durationMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
