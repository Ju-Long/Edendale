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
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.cacheWyzieSubtitle
import com.babasama.edendale.android.AppStrings
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.EdendaleTheme
import com.babasama.edendale.android.R
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.data.WyzieKeyStore
import com.babasama.edendale.android.isTelevisionDevice
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.wyzie.WyzieException
import com.babasama.edendale.wyzie.WyzieSubtitle
import com.babasama.edendale.wyzie.WyzieSubtitleQuery
import com.babasama.edendale.wyzie.WyzieSubtitleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen in-app player. Resumes from stored watch progress, writes
 * progress every five seconds and on dismiss, and marks titles completed near
 * the end (~95%) or at STATE_ENDED. Files without a TMDB match play fine but
 * record no progress because watch state is keyed by TMDB id.
 * Also the target of "Open with" VIEW intents for video mime types.
 *
 * Input routing: this activity owns only the keys that must work with
 * nothing focused — transport media keys and Back/Menu. D-pad keys are
 * deliberately left to Compose: consuming their ACTION_DOWN here would
 * strand the ACTION_UP, which Compose discards as an orphan, so no control
 * could ever be clicked. Volume keys pass straight through to the system.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var dataStore: LocalDataStore? = null
    private var progressKey: ProgressKey? = null
    private var resumeFraction: Double? = null
    private var resumeApplied = false
    private var completedWritten = false

    /** One-shot auto-skip latches, reset on every item switch. */
    private var recapPending = false
    private var creditsHandled = false

    private var isTelevision = false
    private lateinit var chrome: PlayerChromeState
    private lateinit var onlineSubtitles: OnlineSubtitlesState
    private lateinit var wyzieKeyStore: WyzieKeyStore
    private lateinit var wyzieService: WyzieSubtitleService
    private val appStrings by lazy { AppStrings(this) }
    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    private val inPipMode = mutableStateOf(false)
    private val titleState = mutableStateOf("")
    private val subtitleState = mutableStateOf<String?>(null)
    private val currentUriState = mutableStateOf("")
    private val playlistState = mutableStateOf<PlayerPlaylist?>(null)
    private val tracksState = mutableStateOf(Tracks.EMPTY)
    private val wyzieConfiguredState = mutableStateOf(false)
    private val wyzieLookupState = mutableStateOf<WyzieLookup?>(null)

    private data class AttachedSubtitle(
        val subtitle: WyzieSubtitle,
        val label: String,
        val configuration: MediaItem.SubtitleConfiguration,
    )

    /** Sideloaded tracks survive switches within this player session only. */
    private val attachedSubtitles = mutableMapOf<String, MutableList<AttachedSubtitle>>()
    private var pendingSubtitleSelection: AttachedSubtitle? = null

    /** Absent on handhelds where the OEM dropped PiP; present on TV from API 34. */
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

        isTelevision = isTelevisionDevice()
        val playerPreferences = getSharedPreferences("player", MODE_PRIVATE)
        chrome = PlayerChromeState(playerPreferences)
        onlineSubtitles = OnlineSubtitlesState(playerPreferences)
        wyzieKeyStore = WyzieKeyStore(this)
        wyzieService = AndroidEdendaleCore.wyzieService()
        // The build key is safe to inspect synchronously; encrypted preference
        // access stays on writeScope below.
        wyzieConfiguredState.value = wyzieKeyStore.buildKey.isNotEmpty()

        val uri = intent.data ?: intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            finish()
            return
        }
        titleState.value = intent.getStringExtra(EXTRA_TITLE)
            ?: displayName(uri)
            ?: uri.lastPathSegment
            ?: getString(R.string.default_video_name)
        currentUriState.value = uri.toString()

        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1).takeIf { it > 0 }
        val isEpisode = intent.getBooleanExtra(EXTRA_IS_EPISODE, false)
        val showTmdbId = intent.getIntExtra(EXTRA_SHOW_TMDB_ID, -1).takeIf { it > 0 }
        val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
        wyzieLookupState.value = subtitleLookup(
            tmdbId = tmdbId,
            isEpisode = isEpisode,
            showTmdbId = showTmdbId,
            season = season,
            episode = episode,
        )
        if (tmdbId != null) {
            progressKey = ProgressKey(
                tmdbId = tmdbId,
                mediaType = if (isEpisode) {
                    WatchMediaType.EPISODE
                } else {
                    WatchMediaType.MOVIE
                },
                showTmdbId = showTmdbId,
                season = season,
                episode = episode,
            )
        }
        val app = application as EdendaleApplication
        dataStore = LocalDataStore(app.database)
        recapPending = chrome.skipRecap

        // One factory for the whole session, resolved per request: the item
        // playing can change mid-session via the playlist panel, so the
        // scheme must not be captured once at onCreate. DefaultDataSource
        // routes any scheme it doesn't recognise (smb) to the base source.
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DataSource.Factory { DefaultDataSource(this, SmbDataSource(this)) },
        )

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Focus handling accepts only USAGE_MEDIA/GAME; MOVIE is
                    // a content type, not a usage.
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            // Pause-free playback through unplugged headphones is never what
            // the viewer wants; media3 registers the receiver itself.
            .setHandleAudioBecomingNoisy(true)
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

            override fun onTracksChanged(tracks: Tracks) {
                tracksState.value = tracks
                selectPendingOnlineSubtitle(exoPlayer, tracks)
            }
        })

        refreshWyzieConfigured()

        lifecycleScopedStart(exoPlayer, uri)
        loadPlaylist(uri.toString(), showTmdbId)

        setContent {
            EdendaleTheme(isTelevision = isTelevision) {
                PlayerScreen(
                    player = exoPlayer,
                    chrome = chrome,
                    isTelevision = isTelevision,
                    title = titleState,
                    subtitle = subtitleState,
                    currentUri = currentUriState,
                    playlist = playlistState,
                    tracks = tracksState,
                    onlineSubtitles = onlineSubtitles,
                    wyzieConfigured = wyzieConfiguredState,
                    wyzieLookup = wyzieLookupState,
                    inPipMode = inPipMode,
                    supportsPip = supportsPip,
                    onEnterPip = if (supportsPip) ::enterPictureInPicture else null,
                    onAutoPipChanged = ::updatePipParams,
                    onSelectEntry = ::switchTo,
                    onSearchOnlineSubtitles = ::searchOnlineSubtitles,
                    onDownloadOnlineSubtitle = ::downloadOnlineSubtitle,
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
                exoPlayer.setMediaItem(mediaItem(uri))
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
            // A resume replaces the recap skip; resuming straight into the
            // credits window must not trip auto-skip and bounce right out.
            recapPending = false
            val target = (duration * fraction).toLong()
            if (chrome.skipCredits) {
                PlayerLogic.creditsStartMillis(duration)?.let { creditsStart ->
                    if (target >= creditsStart) creditsHandled = true
                }
            }
            exoPlayer.seekTo(target)
        }
    }

    // ------------------------------------------------------------------
    // Playlist switching
    // ------------------------------------------------------------------

    private fun loadPlaylist(uriString: String, showTmdbId: Int?) {
        val app = application as EdendaleApplication
        writeScope.launch {
            val playlist = runCatching {
                loadPlayerPlaylist(
                    dao = app.database.libraryDao(),
                    repository = app.libraryRepository,
                    uriString = uriString,
                    showTmdbIdExtra = showTmdbId,
                )
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                playlistState.value = playlist
                // The launch intent carries no episode code; backfill the
                // subtitle line once the library row is known.
                playlist.entries.firstOrNull { it.uri == currentUriState.value }
                    ?.let { subtitleState.value = it.detail }
            }
        }
    }

    /**
     * Plays another playlist entry in place. Flushing the outgoing item's
     * progress must precede the re-key — writeProgress reads the player's
     * live position, so reversing the order files the old position under
     * the new key.
     */
    internal fun switchTo(entry: PlaylistEntry) {
        val exoPlayer = player ?: return
        writeProgress()
        searchJob?.cancel()
        downloadJob?.cancel()
        searchJob = null
        downloadJob = null
        pendingSubtitleSelection = null
        onlineSubtitles.reset()
        resumeFraction = null
        resumeApplied = false
        completedWritten = false
        recapPending = chrome.skipRecap
        creditsHandled = false
        progressKey = entry.tmdbId?.takeIf { it > 0 }?.let { tmdbId ->
            ProgressKey(
                tmdbId = tmdbId,
                mediaType = if (entry.isEpisode) WatchMediaType.EPISODE else WatchMediaType.MOVIE,
                showTmdbId = entry.showTmdbId,
                season = entry.season,
                episode = entry.episode,
            )
        }
        wyzieLookupState.value = subtitleLookup(
            tmdbId = entry.tmdbId,
            isEpisode = entry.isEpisode,
            showTmdbId = entry.showTmdbId,
            season = entry.season,
            episode = entry.episode,
        )
        titleState.value = entry.title
        subtitleState.value = entry.detail
        currentUriState.value = entry.uri
        lifecycleScopedStart(exoPlayer, Uri.parse(entry.uri))
        chrome.showControls()
    }

    // ------------------------------------------------------------------
    // Online subtitles
    // ------------------------------------------------------------------

    /**
     * Re-read on every foreground pass, not just at onCreate: a viewer who
     * floats the player into PiP, saves a key in Settings, and comes back
     * returns to this same instance, and a failed search latches the flag
     * false. Reads the encrypted store off the main thread.
     */
    private fun refreshWyzieConfigured() {
        writeScope.launch {
            val configured = runCatching { wyzieKeyStore.isConfigured() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (!isDestroyed) wyzieConfiguredState.value = configured
            }
        }
    }

    private fun searchOnlineSubtitles() {
        val lookup = wyzieLookupState.value ?: return
        val language = onlineSubtitles.language
        val hearingImpaired = onlineSubtitles.hearingImpaired
        searchJob?.cancel()
        onlineSubtitles.startSearch()
        chrome.noteInteraction()

        // Privacy boundary: a Wyzie request is launched only by this callback,
        // which is wired exclusively to the viewer's explicit Search action.
        searchJob = writeScope.launch {
            try {
                val key = wyzieKeyStore.resolvedKey()
                val results = wyzieService.search(
                    query = WyzieSubtitleQuery(
                        id = lookup.id,
                        season = lookup.season,
                        episode = lookup.episode,
                        language = language.takeIf { it.isNotBlank() },
                        hearingImpaired = hearingImpaired,
                    ),
                    key = key,
                )
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) onlineSubtitles.showResults(results)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = appStrings.wyzieError(error, download = false)
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) {
                        if (error is WyzieException.MissingKey) {
                            wyzieConfiguredState.value = false
                        }
                        onlineSubtitles.fail(message, clearResults = true)
                    }
                }
            }
        }
    }

    private fun downloadOnlineSubtitle(subtitle: WyzieSubtitle) {
        if (downloadJob?.isActive == true || subtitle.id in onlineSubtitles.downloadedIds) return
        onlineSubtitles.startDownload(subtitle.id)
        chrome.noteInteraction()
        downloadJob = writeScope.launch {
            try {
                val file = cacheWyzieSubtitle(this@PlayerActivity, wyzieService, subtitle)
                withContext(Dispatchers.Main) {
                    if (isDestroyed) return@withContext
                    attachOnlineSubtitle(subtitle, Uri.fromFile(file))
                    onlineSubtitles.finishDownload(subtitle.id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = appStrings.wyzieError(error, download = true)
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) onlineSubtitles.fail(message, clearResults = false)
                }
            }
        }
    }

    private fun attachOnlineSubtitle(subtitle: WyzieSubtitle, fileUri: Uri) {
        val exoPlayer = player ?: return
        val playingUri = currentUriState.value
        val label = getString(R.string.player_online_subtitle_label, subtitle.display)
        val configuration = MediaItem.SubtitleConfiguration.Builder(fileUri)
            .setMimeType(subtitleMimeType(subtitle.format))
            .setLanguage(subtitle.language)
            .setLabel(label)
            .setId(subtitle.id)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val attachments = attachedSubtitles.getOrPut(playingUri) { mutableListOf() }
        val attached = AttachedSubtitle(subtitle, label, configuration)
        val existingIndex = attachments.indexOfFirst { it.subtitle.id == subtitle.id }
        if (existingIndex >= 0) {
            attachments[existingIndex] = attached
        } else {
            attachments += attached
        }

        val position = exoPlayer.currentPosition.coerceAtLeast(0)
        val playWhenReady = exoPlayer.playWhenReady
        pendingSubtitleSelection = attached
        resumeApplied = true
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        exoPlayer.setMediaItem(mediaItem(Uri.parse(playingUri)), position)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
    }

    private fun selectPendingOnlineSubtitle(exoPlayer: ExoPlayer, tracks: Tracks) {
        val pending = pendingSubtitleSelection ?: return
        val options = textTrackOptions(tracks)
        val option = options.firstOrNull { it.id == pending.subtitle.id }
            ?: options.firstOrNull { it.label == pending.label }
            ?: options.firstOrNull { it.language == pending.subtitle.language }
            ?: return
        pendingSubtitleSelection = null
        selectTextTrack(exoPlayer, option)
    }

    private fun mediaItem(uri: Uri): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setSubtitleConfigurations(
            attachedSubtitles[uri.toString()].orEmpty().map { it.configuration },
        )
        .build()

    // ------------------------------------------------------------------
    // Auto-skip
    // ------------------------------------------------------------------

    /**
     * Driven by the UI's position ticker. Applies the skip-recap jump once
     * the duration is known and ends (or loops) playback at the credits.
     */
    internal fun onPlaybackTick(positionMillis: Long, durationMillis: Long) {
        val exoPlayer = player ?: return
        if (durationMillis <= 0) return
        // Never skip ahead of a resume seek that hasn't landed yet.
        if (resumeFraction != null && !resumeApplied) return

        if (recapPending) {
            recapPending = false
            val target = PlayerLogic.recapSkipTargetMillis(durationMillis)
            if (target != null && positionMillis < target) exoPlayer.seekTo(target)
        }

        if (chrome.skipCredits && !creditsHandled) {
            val creditsStart = PlayerLogic.creditsStartMillis(durationMillis) ?: return
            if (positionMillis >= creditsStart) {
                creditsHandled = true
                if (chrome.loopEnabled) {
                    // Credits are over as far as the viewer cares — restart.
                    exoPlayer.seekTo(0)
                } else {
                    writeProgress(completed = true)
                    finish()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Window brightness (handheld swipe gesture)
    // ------------------------------------------------------------------

    /** Level in 0…1, or -1 to hand control back to the system. */
    internal fun setWindowBrightness(level: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = if (level < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            level.coerceIn(0f, 1f)
        }
        // The reassignment is what applies it.
        window.attributes = attributes
    }

    /**
     * The window override reads as -1 until set once, so the first gesture
     * seeds its baseline from the system setting instead.
     */
    internal fun windowBrightnessBaseline(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0f) return current
        val system = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128,
        )
        return (system / 255f).coerceIn(0f, 1f)
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
        updatePipParams()
        if (::wyzieKeyStore.isInitialized) refreshWyzieConfigured()
    }

    override fun onStop() {
        writeProgress()
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        writeProgress()
        searchJob?.cancel()
        downloadJob?.cancel()
        unregisterPipReceiver()
        setWindowBrightness(-1f)
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
            supportsPip && chrome.autoPip && player?.isPlaying == true
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
        if (isInPictureInPictureMode) {
            chrome.hideControls()
            registerPipReceiver()
        } else {
            chrome.showControls()
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

    internal fun updatePipParams() {
        if (!supportsPip) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(videoAspectRatio())
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player?.isPlaying == true && chrome.autoPip)
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
            pipAction(R.drawable.ic_pause, getString(R.string.action_pause), PIP_CONTROL_PAUSE)
        } else {
            pipAction(R.drawable.ic_play, getString(R.string.action_play), PIP_CONTROL_PLAY)
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

    // ------------------------------------------------------------------
    // Key routing
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Never intercept volume keys — the system (or the TV/AVR) owns them.
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            -> return super.dispatchKeyEvent(event)
        }

        if (!::chrome.isInitialized) return super.dispatchKeyEvent(event)

        val exoPlayer = player
        if (exoPlayer != null && event.keyCode in TRANSPORT_KEYS) {
            // Consume both halves so no orphan ACTION_UP reaches a child.
            if (event.action == KeyEvent.ACTION_DOWN) {
                handleTransportKey(exoPlayer, event.keyCode)
            }
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
            // Compose maps Back to a focus Exit at its root and silently
            // consumes it whenever focus is nested, so it never reaches the
            // default back handling — handle it before Compose sees it.
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * On TV, Back peels one layer at a time — panel, scrub preview,
     * timeline/HUD, controls, then the player itself. A handheld's back
     * button keeps its platform meaning and exits once panels are gone.
     */
    private fun handleBack() {
        when {
            chrome.activePanel != null -> chrome.closePanel()
            !isTelevision -> finish()
            chrome.dismissRemotePresentation() -> Unit
            chrome.controlsVisible -> chrome.hideControls()
            else -> finish()
        }
    }

    private fun handleTransportKey(exoPlayer: ExoPlayer, keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                chrome.showControls()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exoPlayer.play()
                chrome.showControls()
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exoPlayer.pause()
                chrome.showControls()
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                seekBy(exoPlayer, chrome, PlayerLogic.SEEK_STEP_MILLIS)
            KeyEvent.KEYCODE_MEDIA_REWIND ->
                seekBy(exoPlayer, chrome, -PlayerLogic.SEEK_STEP_MILLIS)
            KeyEvent.KEYCODE_MEDIA_NEXT -> switchToNeighbor(+1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> switchToNeighbor(-1)
            KeyEvent.KEYCODE_MEDIA_STOP -> finish()
        }
    }

    private fun switchToNeighbor(offset: Int) {
        val entries = playlistState.value?.entries ?: return
        val index = entries.indexOfFirst { it.uri == currentUriState.value }
        if (index < 0) return
        entries.getOrNull(index + offset)?.let(::switchTo)
    }

    internal fun writeProgress(completed: Boolean = false) {
        val key = progressKey ?: return
        val store = dataStore ?: return
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exoPlayer.currentPosition.coerceIn(0, duration)
        val fraction = position.toDouble() / duration
        val isCompleted = completed || fraction >= PlayerLogic.COMPLETE_FRACTION
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

        private const val ACTION_PIP_CONTROL = "com.babasama.edendale.PIP_CONTROL"
        private const val EXTRA_PIP_CONTROL = "edendale.pipControl"
        private const val PIP_CONTROL_PLAY = 1
        private const val PIP_CONTROL_PAUSE = 2
        private const val PIP_CONTROL_REWIND = 3
        private const val PIP_CONTROL_FORWARD = 4

        /** The system rejects PiP aspect ratios outside 1:2.39 … 2.39:1. */
        private const val MIN_PIP_ASPECT = 1.0 / 2.39
        private const val MAX_PIP_ASPECT = 2.39

        private val TRANSPORT_KEYS = intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
        )

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

internal fun subtitleMimeType(format: String): String = when (format.trim().lowercase()) {
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
}
