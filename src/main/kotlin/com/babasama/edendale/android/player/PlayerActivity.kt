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
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.WindowManager
import android.widget.Toast
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
import androidx.media3.common.PlaybackException
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
import com.babasama.edendale.android.KeyboardShortcuts
import com.babasama.edendale.android.PlaybackSources
import com.babasama.edendale.android.PlayerKeyCommand
import com.babasama.edendale.android.R
import com.babasama.edendale.android.YoungAudienceFilter
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.data.WyzieKeyStore
import com.babasama.edendale.android.isTelevisionDevice
import com.babasama.edendale.android.parental.ParentalControls
import com.babasama.edendale.android.parental.awaitReady
import com.babasama.edendale.android.parental.parentalControls
import com.babasama.edendale.android.parental.restrictedMessageRes
import com.babasama.edendale.android.youngAudienceFilter
import com.babasama.edendale.parental.ParentalGateRules
import com.babasama.edendale.android.xr.XrTheater
import com.babasama.edendale.android.xr.XrTheaterHandover
import com.babasama.edendale.android.xr.XrTheaterRules
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.introdb.IntroDbMedia
import com.babasama.edendale.introdb.PlaybackSegment
import com.babasama.edendale.introdb.PlayerSegmentController
import com.babasama.edendale.introdb.SkipAction
import com.babasama.edendale.android.player.video.EnhancementCapability
import com.babasama.edendale.android.player.video.MotionSmoothingCapability
import com.babasama.edendale.android.player.video.PixelSize
import com.babasama.edendale.android.player.video.VideoEffectsController
import com.babasama.edendale.wyzie.WyzieException
import com.babasama.edendale.wyzie.WyzieSubtitle
import com.babasama.edendale.wyzie.WyzieSubtitleQuery
import com.babasama.edendale.wyzie.WyzieSubtitleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    private var mediaSession: PlayerMediaSession? = null

    /** Audio Enhancement's equalizer and booster, in every audio sink this player builds (E.1). */
    private val equalizer = EqAudioProcessor()

    /** Picture and Enhancement (F): the effects pipeline, installed only when needed. */
    private lateinit var videoEffects: VideoEffectsController
    private var effectsTick = 0
    private var dataStore: LocalDataStore? = null
    private var progressKey: ProgressKey? = null
    private var resumeFraction: Double? = null
    private var resumeApplied = false
    private var completedWritten = false

    /**
     * Whether the item presented now has reached READY, its stored resume
     * point applied: only then does its position mean anything. Until then it
     * reads 0, so a progress write would file 0 over the resume point (and 27.1
     * sync would carry it), and a Play From pick of another copy resumes from
     * the stored progress instead of carrying the position.
     */
    private var itemStarted = false

    private var isTelevision = false
    private lateinit var chrome: PlayerChromeState
    private lateinit var contentPreferencesStore: ContentPlayerPreferencesStore
    private var currentTmdbId: Int? = null
    private var currentIsEpisode: Boolean = false
    private var currentShowTmdbId: Int? = null
    private var hasRestoredContentPreferences = false
    private var isRestoringContentPreferences = false
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
    private val activeSegmentState = mutableStateOf<PlaybackSegment?>(null)
    private val upcomingEpisodeState = mutableStateOf<PlaylistEntry?>(null)
    private val playbackFailureState = mutableStateOf<PlaybackFailure?>(null)

    private val transitions = PlaybackTransitions()

    private val segmentController by lazy {
        PlayerSegmentController(
            preferences = chrome.preferences,
            onActiveSegmentChanged = { activeSegmentState.value = it },
            lookup = { request -> AndroidEdendaleCore.introDbService().segments(request) },
        )
    }

    private data class AttachedSubtitle(
        val subtitle: WyzieSubtitle,
        val label: String,
        val configuration: MediaItem.SubtitleConfiguration,
    )

    /** Sideloaded tracks survive switches within this player session only. */
    private val attachedSubtitles = mutableMapOf<String, MutableList<AttachedSubtitle>>()
    private var pendingSubtitleSelection: AttachedSubtitle? = null

    private var prefsSubscription: AutoCloseable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaKeyHoldRunnable: Runnable? = null
    private var mediaKeyHoldActive = false
    private var activeHoldKeyCode: Int? = null

    /** Absent on handhelds where the OEM dropped PiP; present on TV from API 34. */
    private val supportsPip: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /** An Android XR headset with the theater split installed (ENHANCEMENT.md N-D2). */
    private val offersTheater: Boolean by lazy { XrTheater.isAvailable(this) }

    /** A position the theater sent the viewer back to; applied once, in place of stored progress. */
    private var pendingPositionMillis: Long? = null

    /** Android XR: no brightness swipe (as on visionOS) and no Rotation Lock there. */
    private val isSpatialDevice: Boolean by lazy { packageManager.hasSystemFeature(XrTheaterRules.FEATURE_SPATIAL) }

    /** The left swipe's level: the screen's brightness while the player is in front. Null on TV and Android XR. */
    private var screenBrightness: PlayerScreenBrightness? = null

    /** The right swipe's level: the device's media volume. Null on TV, which has no swipes. */
    private var deviceVolume: DeviceVolume? = null

    /** The activity the viewer is using: top-resumed from API 29, resumed before that. */
    private var isInFront = false

    /** Whether the Rotation Lock chip is offered right now (handhelds, full screen). */
    private val rotationLockAvailable = mutableStateOf(false)

    /** Learned from a lock that landed a half turn away: this device maps the odd rotations the other way. */
    private var rotationMappingReversed = false
    private var rotationLockGuard: DisplayManager.DisplayListener? = null
    private val endRotationLockGuard = Runnable { stopRotationLockGuard() }

    /**
     * Whether the item loading becomes its show's or movie's remembered source
     * once it plays (27.1): a Play From pick does, and so does the launch item
     * unless its launcher fell back from the remembered source
     * ([EXTRA_REMEMBER_SOURCE]); an advance, Up Next, or a panel row follows
     * the list, whose fallbacks mustn't overwrite the viewer's choice.
     */
    private var rememberSourceOnReady = true

    /** Drives the play/pause and skip buttons drawn inside the PiP window. */
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            val exoPlayer = player ?: return
            when (intent.getIntExtra(EXTRA_PIP_CONTROL, -1)) {
                PIP_CONTROL_PLAY -> exoPlayer.play()
                PIP_CONTROL_PAUSE -> exoPlayer.pause()
                PIP_CONTROL_REWIND ->
                    exoPlayer.seekTo((exoPlayer.currentPosition - chrome.skipBackwardInterval.millis).coerceAtLeast(0))
                PIP_CONTROL_FORWARD -> {
                    val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    val target = exoPlayer.currentPosition + chrome.skipForwardInterval.millis
                    exoPlayer.seekTo(duration?.let { target.coerceAtMost(it) } ?: target)
                }
            }
        }
    }
    private var pipReceiverRegistered = false

    /** Survives activity teardown long enough to land the final write. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The parental policy at the player (O.5): the process's controls and this
    // activity's own filter, on a main-thread scope that ends with the activity.
    private val parentalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var parental: ParentalControls
    private lateinit var audienceFilter: YoungAudienceFilter

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
        // The hardware volume keys move media volume even while paused, the same stream the swipe moves.
        volumeControlStream = AudioManager.STREAM_MUSIC

        isTelevision = isTelevisionDevice()
        if (!isTelevision && !isSpatialDevice) screenBrightness = PlayerScreenBrightness(this)
        parental = parentalControls(this)
        audienceFilter = youngAudienceFilter(this, parentalScope)
        val playerPreferences = getSharedPreferences("player", MODE_PRIVATE)
        val contentPreferences = getSharedPreferences(ContentPlayerPreferencesRules.PREFS_NAME, MODE_PRIVATE)
        contentPreferencesStore = SharedPreferencesContentStore(contentPreferences)
        chrome = PlayerChromeState(playerPreferences)
        // A relaunch (a keyboard, font, or language change) keeps the system's
        // requested orientation, so a Rotation Lock taken before it still
        // holds; the chip starts from it. A fresh player always starts unlocked.
        if (savedInstanceState != null && !isTelevision && !isSpatialDevice &&
            OrientationLockRules.isLocked(requestedOrientation)
        ) {
            chrome.setOrientationLocked(true)
        }
        chrome.onSpeedChanged = {
            saveContentPreferences()
        }
        equalizer.update(chrome.preferences.audioEnhancement)
        prefsSubscription = chrome.preferences.addChangeListener {
            updatePipParams()
            refreshMediaSession()
            // Settings and the panel's booster apply live, from the next buffer.
            equalizer.update(chrome.preferences.audioEnhancement)
        }
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
        // Shown once the parental check lets the item start, so a refused title never flashes.
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: displayName(uri)
            ?: uri.lastPathSegment
            ?: getString(R.string.default_video_name)
        currentUriState.value = uri.toString()

        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1).takeIf { it > 0 }
        val isEpisode = intent.getBooleanExtra(EXTRA_IS_EPISODE, false)
        val showTmdbId = intent.getIntExtra(EXTRA_SHOW_TMDB_ID, -1).takeIf { it > 0 }
        val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
        // The theater sends the viewer back to the exact position (Section N);
        // everything else resumes from stored progress.
        pendingPositionMillis = intent.getLongExtra(EXTRA_POSITION, -1L).takeIf { it >= 0L }
        rememberSourceOnReady = intent.getBooleanExtra(EXTRA_REMEMBER_SOURCE, true)
        if (savedInstanceState != null) {
            // A relaunch (a keyboard, font, or language change): the intent is
            // the item playing (switchTo keeps it so), which carries on where
            // it was, and its source is recorded only if it wasn't yet.
            savedInstanceState.getLong(STATE_POSITION, -1L).takeIf { it >= 0L }?.let { pendingPositionMillis = it }
            rememberSourceOnReady = savedInstanceState.getBoolean(STATE_REMEMBER_SOURCE, rememberSourceOnReady)
        }
        currentTmdbId = tmdbId
        currentIsEpisode = isEpisode
        currentShowTmdbId = showTmdbId
        hasRestoredContentPreferences = false
        wyzieLookupState.value = subtitleLookup(
            tmdbId = tmdbId,
            isEpisode = isEpisode,
            showTmdbId = showTmdbId,
            season = season,
            episode = episode,
        )
        segmentController.begin(
            itemId = uri.toString(),
            media = introDbMedia(tmdbId, isEpisode, showTmdbId, season, episode),
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

        // One factory for the whole session, resolved per request: the item
        // playing can change mid-session via the playlist panel, so the
        // scheme must not be captured once at onCreate. DefaultDataSource
        // routes any scheme it doesn't recognise (smb and the storage
        // providers' schemes, H.2) to the base source.
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DataSource.Factory { DefaultDataSource(this, EdendaleDataSource(this)) },
        )

        val exoPlayer = ExoPlayer.Builder(this, EdendaleRenderersFactory(this, equalizer))
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
        if (!isTelevision) deviceVolume = DeviceVolume(this, exoPlayer)
        exoPlayer.repeatMode = if (chrome.loopEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // The item a switch replaces can still turn READY before the
                // new one is set; that mustn't take the new item's resume
                // point or count as the new item starting.
                if (playbackState == Player.STATE_READY && isPresented(exoPlayer)) {
                    applyPendingResume()
                    itemStarted = true
                    rememberPlayingSource()
                }
                if (playbackState == Player.STATE_ENDED) {
                    handlePlaybackEnded()
                }
            }

            // The PiP window mirrors playback state: its buttons and its
            // auto-enter arming both live in the params, so refresh them
            // whenever what they describe changes.
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePipParams()

            override fun onPlayerError(error: PlaybackException) {
                playbackFailureState.value = PlaybackFailure.of(error)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()

            // A new item first reports empty tracks, before it's prepared.
            // Restoring against those would match nothing and use up the
            // item's only restore, so wait for the report that lists them.
            override fun onTracksChanged(tracks: Tracks) {
                tracksState.value = tracks
                videoEffects.onTracksChanged(tracks)
                updatePipParams()
                if (!hasRestoredContentPreferences && !tracks.isEmpty) {
                    hasRestoredContentPreferences = true
                    restoreContentPreferences(exoPlayer, tracks)
                }
                selectPendingOnlineSubtitle(exoPlayer, tracks)
            }
        })

        videoEffects = VideoEffectsController(
            context = this,
            isTelevision = isTelevision,
            reprepare = ::reprepareAtCurrentPosition,
            saveAdjustments = { chrome.preferences.videoAdjustments = it },
            saveEnhancement = { chrome.preferences.videoEnhancement = it },
        )
        videoEffects.attach(exoPlayer, chrome.preferences.videoAdjustments, chrome.preferences.videoEnhancement)
        updateVideoDisplaySize()
        // Once per app version, off the main thread: can this device run enhancement in budget (F.6.3),
        // and then Motion Smoothing (G.5)?
        writeScope.launch {
            EnhancementCapability.evaluateIfNeeded(applicationContext)
            MotionSmoothingCapability.evaluateIfNeeded(applicationContext, isTelevision)
        }

        mediaSession = PlayerMediaSession(
            context = this,
            player = exoPlayer,
            onSeekBy = { offset -> player?.let { seekBy(it, chrome, offset) } },
            onNeighbor = ::switchToNeighbor,
        )
        refreshMediaSession()

        refreshWyzieConfigured()

        transitions.present()
        startWhenPermitted(exoPlayer, uri, title, tmdbId, isEpisode, showTmdbId)
        observeParentalPolicy()

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
                    activeSegment = activeSegmentState,
                    upcomingEpisode = upcomingEpisodeState,
                    playbackFailure = playbackFailureState,
                    video = videoEffects,
                    brightness = screenBrightness,
                    volume = deviceVolume,
                    rotationLockAvailable = rotationLockAvailable,
                    onToggleRotationLock = ::toggleRotationLock,
                    inPipMode = inPipMode,
                    supportsPip = supportsPip,
                    onEnterPip = if (supportsPip) ::enterPictureInPicture else null,
                    onEnterTheater = if (offersTheater) ::enterTheater else null,
                    onAutoPipChanged = ::updatePipParams,
                    onSelectEntry = ::switchTo,
                    onSelectCopy = ::switchToCopy,
                    onSearchOnlineSubtitles = ::searchOnlineSubtitles,
                    onDownloadOnlineSubtitle = ::downloadOnlineSubtitle,
                    onSkip = { performSkip() },
                    onTrackSelected = { saveContentPreferences() },
                    onClose = { finish() },
                )
            }
        }
        refreshRotationLockAvailability()
    }

    /**
     * O.5: with no title rule active this is the plain start. Otherwise
     * playback waits for the controls to read their store and for this
     * title's verdict, and a restricted or id-less item (O-D8) is refused
     * with a notice; the Watch Next row's intent and an "Open with" file
     * arrive here like any other launch, so they are covered too.
     */
    private fun startWhenPermitted(
        exoPlayer: ExoPlayer,
        uri: Uri,
        title: String,
        tmdbId: Int?,
        isEpisode: Boolean,
        showTmdbId: Int?,
    ) {
        fun start() {
            titleState.value = title
            refreshMediaSession()
            lifecycleScopedStart(exoPlayer, uri)
            loadPlaylist(uri.toString(), showTmdbId)
        }
        if (parental.ready.value && !parental.policy.value.isActive) {
            start()
            return
        }
        parentalScope.launch {
            parental.awaitReady()
            val ref = ParentalGateRules.playbackRef(tmdbId, isEpisode, showTmdbId)
            if (ref != null && audienceFilter.isEnabled) audienceFilter.verify(listOf(ref))
            if (isDestroyed) return@launch
            if (ParentalGateRules.permitsPlayback(audienceFilter.isEnabled, ref, audienceFilter::allows)) {
                start()
            } else {
                refuseRestricted(Toast.LENGTH_LONG)
                finish()
            }
        }
    }

    private fun refuseRestricted(duration: Int) {
        Toast.makeText(this, getString(restrictedMessageRes(parental.policy.value)), duration).show()
    }

    /** A policy or list change while playing re-filters the rows, so a new ceiling reaches the panel, Up Next, and the media session. */
    private fun observeParentalPolicy() {
        parentalScope.launch {
            combine(parental.policy, parental.lists) { policy, lists -> policy.key to lists }
                .distinctUntilChanged()
                .drop(1)
                .collect { loadPlaylist(currentUriState.value, currentShowTmdbId) }
        }
    }

    /** Reads stored progress first (fast Room lookup), then starts playback. */
    private fun lifecycleScopedStart(exoPlayer: ExoPlayer, uri: Uri) {
        val key = progressKey
        val startAt = pendingPositionMillis
        pendingPositionMillis = null
        writeScope.launch {
            resumeFraction = if (startAt != null) {
                null
            } else {
                PlayerLogic.resumeFraction(key?.let { dataStore?.getWatchProgress(it.storageKey) })
            }
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                playbackFailureState.value = null
                videoEffects.beforePrepare()
                if (startAt != null) {
                    exoPlayer.setMediaItem(mediaItem(uri), startAt)
                } else {
                    exoPlayer.setMediaItem(mediaItem(uri))
                }
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                applyPendingResume()
            }
        }
    }

    /** Whether the player holds the item presented now, not the one a switch is about to replace. */
    private fun isPresented(exoPlayer: Player): Boolean =
        exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() == currentUriState.value

    private fun applyPendingResume() {
        val exoPlayer = player ?: return
        val fraction = resumeFraction ?: return
        if (resumeApplied) return
        val duration = exoPlayer.duration
        if (duration != C.TIME_UNSET && duration > 0) {
            resumeApplied = true
            val target = (duration * fraction).toLong()
            exoPlayer.seekTo(target)
        }
    }

    // ------------------------------------------------------------------
    // Playlist switching
    // ------------------------------------------------------------------

    private fun loadPlaylist(uriString: String, showTmdbId: Int?) {
        val app = application as EdendaleApplication
        writeScope.launch {
            val loaded = runCatching {
                loadPlayerPlaylist(
                    dao = app.database.libraryDao(),
                    uriString = uriString,
                    showTmdbIdExtra = showTmdbId,
                    remembered = app.libraryRepository.sourceMemory.sourceFor(PlaybackSources.showMemoryKey(showTmdbId, null)),
                )
            }.getOrNull() ?: return@launch
            // O.5: only the rows the parental policy permits, verified first so the filter can judge them.
            if (audienceFilter.isEnabled) audienceFilter.verify(playlistAudienceRefs(loaded))
            val playlist = permittedPlaylist(loaded, audienceFilter.isEnabled, audienceFilter::allows)
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                playlistState.value = playlist
                // The launch intent carries no episode code; backfill the
                // subtitle line once the library row is known.
                playlist?.entries?.firstOrNull { it.uri == currentUriState.value }
                    ?.let { subtitleState.value = it.detail }
                refreshMediaSession()
            }
        }
    }

    /**
     * Plays another playlist entry in place. Flushing the outgoing item's
     * progress must precede the re-key — writeProgress reads the player's
     * live position, so reversing the order files the old position under
     * the new key. [explicitSource] marks a Play From pick, whose source the
     * show or movie then remembers once it plays (27.1). Returns whether it
     * switched.
     */
    internal fun switchTo(entry: PlaylistEntry, explicitSource: Boolean = false): Boolean {
        val exoPlayer = player ?: return false
        // O.5: the rows are already filtered; this keeps a row from a list loaded under an older policy from playing.
        if (!ParentalGateRules.permitsPlayback(audienceFilter.isEnabled, entry.audienceRef(), audienceFilter::allows)) {
            refuseRestricted(Toast.LENGTH_SHORT)
            return false
        }
        saveContentPreferences()
        currentTmdbId = entry.tmdbId?.takeIf { it > 0 }
        currentIsEpisode = entry.isEpisode
        currentShowTmdbId = entry.showTmdbId?.takeIf { it > 0 }
        hasRestoredContentPreferences = false
        writeProgress()
        transitions.present()
        searchJob?.cancel()
        downloadJob?.cancel()
        searchJob = null
        downloadJob = null
        pendingSubtitleSelection = null
        onlineSubtitles.reset()
        resumeFraction = null
        resumeApplied = false
        itemStarted = false
        completedWritten = false
        rememberSourceOnReady = explicitSource
        upcomingEpisodeState.value = null
        videoEffects.onItemChanged()
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
        segmentController.begin(
            itemId = entry.uri,
            media = introDbMedia(
                tmdbId = entry.tmdbId,
                isEpisode = entry.isEpisode,
                showTmdbId = entry.showTmdbId,
                season = entry.season,
                episode = entry.episode,
            ),
        )
        titleState.value = entry.title
        subtitleState.value = entry.detail
        currentUriState.value = entry.uri
        // A relaunch builds the player again from its intent, so the intent
        // follows the item playing: the next episode or a picked copy, not
        // the one the player opened with, and no stale start position.
        setIntent(
            PlayerActivity.intent(
                context = this,
                uri = entry.uri,
                title = entry.title,
                tmdbId = entry.tmdbId,
                isEpisode = entry.isEpisode,
                showTmdbId = entry.showTmdbId,
                season = entry.season,
                episode = entry.episode,
            ),
        )
        lifecycleScopedStart(exoPlayer, Uri.parse(entry.uri))
        chrome.showControls()
        refreshMediaSession()
        return true
    }

    /**
     * A Play From pick in the episodes panel (27.1): plays [copy] and makes
     * its source the show's remembered one. Another copy of the episode
     * playing now carries on from the same moment once this copy has started
     * ([PlayerLogic.copySwitchStartMillis]); before that, from the stored
     * progress the copies share, which the outgoing write leaves alone. The
     * list then reloads around the new copy, so Up Next and the next advance
     * follow its source.
     */
    internal fun switchToCopy(copy: PlaylistEntry) {
        val exoPlayer = player ?: return
        val currentUri = currentUriState.value
        if (copy.uri == currentUri) return
        val playlist = playlistState.value
        val sameEpisode = playlist?.entries?.firstOrNull { it.uri == currentUri }?.copies.orEmpty().any { it.uri == copy.uri }
        pendingPositionMillis = PlayerLogic.copySwitchStartMillis(sameEpisode, itemStarted, exoPlayer.currentPosition)
        if (!switchTo(copy, explicitSource = true)) {
            pendingPositionMillis = null
            return
        }
        playlist?.let { playlistState.value = it.choosing(copy) }
        loadPlaylist(copy.uri, copy.showTmdbId)
    }

    /** Remembers where the item that just became ready plays from, once per item ([rememberSourceOnReady]). */
    private fun rememberPlayingSource() {
        if (!rememberSourceOnReady) return
        rememberSourceOnReady = false
        val app = application as EdendaleApplication
        val uri = currentUriState.value
        val tmdbId = currentTmdbId.takeUnless { currentIsEpisode }
        writeScope.launch {
            runCatching { app.libraryRepository.sourceMemory.recordPlayed(app.database.libraryDao(), uri, tmdbId) }
        }
    }

    /**
     * Tells the system's media surfaces what's playing, which skip lengths
     * App Controls set, and whether next and previous have somewhere to go.
     */
    private fun refreshMediaSession() {
        val session = mediaSession ?: return
        val playlist = playlistState.value
        val entries = playlist?.entries.orEmpty()
        val currentUri = currentUriState.value
        session.update(
            backMillis = chrome.preferences.skipBackwardInterval.millis,
            forwardMillis = chrome.preferences.skipForwardInterval.millis,
            neighbors = PlaylistNeighbors.of(entries.map { it.uri }, currentUri),
            nowPlaying = NowPlayingInfo.of(
                entry = entries.firstOrNull { it.uri == currentUri },
                title = titleState.value,
                subtitle = subtitleState.value,
                showName = playlist?.showName,
                isEpisode = currentIsEpisode,
                tmdbId = currentTmdbId,
            ),
        )
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
            .setId("ext-${subtitle.id}")
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
        val targetId = pending.configuration.id ?: "ext-${pending.subtitle.id}"
        val option = options.firstOrNull { it.id == targetId }
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
    // Playback position tick
    // ------------------------------------------------------------------

    /**
     * Driven by the UI's position ticker.
     */
    internal fun onPlaybackTick(positionMillis: Long, durationMillis: Long) {
        val exoPlayer = player ?: return
        segmentController.update(
            positionMillis = positionMillis,
            durationMillis = durationMillis.takeIf { it > 0 },
            isSeekable = exoPlayer.isCurrentMediaItemSeekable,
        )
        activeSegmentState.value = segmentController.activeSegment
        updateUpcomingEpisode(positionMillis, durationMillis)
        // The tick runs every half second; the effects governor wants about one sample a second.
        if (++effectsTick % 2 == 0) videoEffects.onTick()
    }

    /**
     * Stops and prepares the playing item again at its position. Media3 sets
     * up the effects pipeline only when the video renderer is enabled, so
     * turning Picture or Enhancement on mid-play needs this once (F.1.1).
     */
    private fun reprepareAtCurrentPosition() {
        val exoPlayer = player ?: return
        val position = exoPlayer.currentPosition.coerceAtLeast(0)
        val playWhenReady = exoPlayer.playWhenReady
        resumeApplied = true
        exoPlayer.stop()
        exoPlayer.setMediaItem(mediaItem(Uri.parse(currentUriState.value)), position)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
    }

    /**
     * The upscaler's "display" (F.1.3, F.3): on TV the panel's own resolution
     * from the display mode, since boxes often draw their interface at 1080p
     * on a 4K panel; elsewhere the window, the largest the video can be.
     */
    private fun updateVideoDisplaySize() {
        val size = if (isTelevision) {
            @Suppress("DEPRECATION")
            val mode = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay)?.mode
            mode?.let { PixelSize(maxOf(it.physicalWidth, it.physicalHeight), minOf(it.physicalWidth, it.physicalHeight)) }
        } else {
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.let { PixelSize(it.width(), it.height()) }
            } else {
                resources.displayMetrics.let { PixelSize(it.widthPixels, it.heightPixels) }
            }
            bounds
        }
        size?.let(videoEffects::onDisplaySizeChanged)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::videoEffects.isInitialized) updateVideoDisplaySize()
        refreshRotationLockAvailability()
    }

    private fun updateUpcomingEpisode(positionMillis: Long, durationMillis: Long) {
        if (!currentIsEpisode || chrome.loopEnabled || durationMillis <= 0) {
            if (upcomingEpisodeState.value != null) upcomingEpisodeState.value = null
            return
        }
        val playlist = playlistState.value
        if (playlist == null || !playlist.isEpisodeList) {
            if (upcomingEpisodeState.value != null) upcomingEpisodeState.value = null
            return
        }
        val currentUri = currentUriState.value
        val entries = playlist.entries
        val currentEntry = entries.firstOrNull { it.uri == currentUri }
        if (currentEntry == null || currentEntry.season == null || currentEntry.episode == null) {
            if (upcomingEpisodeState.value != null) upcomingEpisodeState.value = null
            return
        }
        val currentCandidate = EpisodeCandidate(
            id = currentEntry.uri,
            season = currentEntry.season,
            episode = currentEntry.episode,
            title = currentEntry.title,
        )
        val candidates = entries.mapNotNull { entry ->
            if (entry.season != null && entry.episode != null) {
                EpisodeCandidate(
                    id = entry.uri,
                    season = entry.season,
                    episode = entry.episode,
                    title = entry.title,
                )
            } else null
        }
        val nextCandidate = EpisodeProgression.upcomingEpisode(
            timeMillis = positionMillis,
            durationMillis = durationMillis,
            loopEnabled = chrome.loopEnabled,
            current = currentCandidate,
            episodes = candidates,
        )
        val nextEntry = nextCandidate?.let { next ->
            entries.firstOrNull { it.uri == next.id }
        }
        if (upcomingEpisodeState.value != nextEntry) {
            upcomingEpisodeState.value = nextEntry
        }
    }

    // ------------------------------------------------------------------
    // Screen brightness (handheld swipe gesture)
    // ------------------------------------------------------------------

    /**
     * The swipe's brightness holds only while the player is the activity the
     * viewer is using and isn't floating: in Picture in Picture, or while the
     * other app in split screen is in use, the window override would keep
     * setting the whole display's brightness. Top-resumed rather than window
     * focus, because the player's own menus take the focus.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        isInFront = isTopResumedActivity
        updateBrightnessScope()
    }

    override fun onResume() {
        super.onResume()
        // Before API 29 there is no top-resumed state; only the focused activity is resumed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            isInFront = true
            updateBrightnessScope()
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            isInFront = false
            updateBrightnessScope()
        }
        super.onPause()
    }

    private fun updateBrightnessScope() {
        val brightness = screenBrightness ?: return
        if (isInFront && !isInPictureInPictureMode) brightness.resume() else brightness.suspend()
    }

    // ------------------------------------------------------------------
    // Rotation Lock (handhelds)
    // ------------------------------------------------------------------

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshRotationLockAvailability()
    }

    private fun refreshRotationLockAvailability() {
        rotationLockAvailable.value = OrientationLockRules.isAvailable(
            isTelevision = isTelevision,
            isXr = isSpatialDevice,
            inMultiWindow = isInMultiWindowMode,
            inPip = isInPictureInPictureMode,
            sdkInt = Build.VERSION.SDK_INT,
            targetSdk = applicationInfo.targetSdkVersion,
            smallestDisplayWidthDp = resources.configuration.smallestScreenWidthDp,
        )
    }

    /**
     * Locks to the exact orientation on screen, or goes back to the
     * manifest's sensor landscape. The lock is the session's: the requested
     * orientation lasts through episode switches, Picture in Picture, and a
     * relaunch (which [onCreate] reads back), and ends when the player closes.
     */
    private fun toggleRotationLock() {
        if (chrome.orientationLocked) {
            stopRotationLockGuard()
            requestedOrientation = OrientationLockRules.UNLOCKED
            chrome.setOrientationLocked(false)
            return
        }
        val rotation = displayRotation() ?: return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val mapped = OrientationLockRules.lockedOrientation(rotation, isLandscape)
        val locked = if (rotationMappingReversed && rotation % 2 == 1) mapped.opposite() else mapped
        requestedOrientation = locked.activityInfoValue
        chrome.setOrientationLocked(true)
        guardRotationLock(rotation, locked)
    }

    /**
     * Devices built with `config_reverseDefaultRotation` turn the odd
     * rotations the other way round, so a lock can land a half turn from the
     * picture the viewer was looking at. A 180° turn changes no
     * configuration, so a display listener watches briefly after each lock and
     * turns the lock round; later locks in this session start from the turned
     * mapping.
     */
    private fun guardRotationLock(fromRotation: Int, locked: LockedOrientation) {
        stopRotationLockGuard()
        val displays = getSystemService(DisplayManager::class.java) ?: return
        val displayId = currentDisplayId() ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(addedId: Int) = Unit
            override fun onDisplayRemoved(removedId: Int) = Unit
            override fun onDisplayChanged(changedId: Int) {
                if (changedId != displayId || !chrome.orientationLocked) return
                val now = displayRotation() ?: return
                if (!OrientationLockRules.isHalfTurn(fromRotation, now)) return
                stopRotationLockGuard()
                if (fromRotation % 2 == 1) rotationMappingReversed = !rotationMappingReversed
                requestedOrientation = locked.opposite().activityInfoValue
            }
        }
        runCatching { displays.registerDisplayListener(listener, mainHandler) }.onSuccess {
            rotationLockGuard = listener
            mainHandler.postDelayed(endRotationLockGuard, ROTATION_LOCK_GUARD_MILLIS)
        }
    }

    private fun stopRotationLockGuard() {
        mainHandler.removeCallbacks(endRotationLockGuard)
        val listener = rotationLockGuard ?: return
        rotationLockGuard = null
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener) }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation else windowManager.defaultDisplay.rotation
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId else windowManager.defaultDisplay.displayId
    }.getOrNull()

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
        updatePipParams()
        if (::wyzieKeyStore.isInitialized) refreshWyzieConfigured()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // A relaunch carries on from here rather than from the intent's start.
        player?.currentPosition?.takeIf { itemStarted && it >= 0L }?.let { outState.putLong(STATE_POSITION, it) }
        outState.putBoolean(STATE_REMEMBER_SOURCE, rememberSourceOnReady)
    }

    override fun onStop() {
        cancelMediaKeyHold()
        saveContentPreferences()
        writeProgress()
        // 27.1 sync writes the device file at once when playback stops (SYNC-PROTOCOL.md §8.1).
        (applicationContext as? EdendaleApplication)?.sync?.flushSoon()
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        parentalScope.cancel()
        prefsSubscription?.close()
        segmentController.end()
        transitions.end()
        writeProgress()
        searchJob?.cancel()
        downloadJob?.cancel()
        unregisterPipReceiver()
        screenBrightness?.release()
        stopRotationLockGuard()
        // The session's player wraps ExoPlayer and releases it with itself.
        val session = mediaSession
        if (session != null) session.release() else player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Per-content preferences
    // ------------------------------------------------------------------

    private fun restoreContentPreferences(exoPlayer: ExoPlayer, tracks: Tracks) {
        val key = ContentPlayerPreferencesRules.contentKey(
            tmdbId = currentTmdbId,
            isEpisode = currentIsEpisode,
            showTmdbId = currentShowTmdbId,
        ) ?: return
        val prefs = contentPreferencesStore.get(key) ?: return
        isRestoringContentPreferences = true
        try {
            applyContentPreferences(
                preferences = prefs,
                player = exoPlayer,
                chrome = chrome,
                tracks = tracks,
            )
        } finally {
            isRestoringContentPreferences = false
        }
    }

    private fun saveContentPreferences() {
        if (isRestoringContentPreferences) return
        val key = ContentPlayerPreferencesRules.contentKey(
            tmdbId = currentTmdbId,
            isEpisode = currentIsEpisode,
            showTmdbId = currentShowTmdbId,
        ) ?: return
        val exoPlayer = player ?: return
        val existing = contentPreferencesStore.get(key)
        val prefs = snapshotPreferences(
            baseRate = chrome.baseRate,
            tracks = exoPlayer.currentTracks,
            trackSelectionParameters = exoPlayer.trackSelectionParameters,
            existing = existing,
        )
        contentPreferencesStore.save(key, prefs)
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
        // The floating window must not set the whole display's brightness.
        updateBrightnessScope()
        refreshRotationLockAvailability()
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
        // ExoPlayer reports no size while the effects pipeline is installed.
        val effectsSize = if (::videoEffects.isInitialized) videoEffects.effectsVideoSize else null
        val size = effectsSize ?: player?.videoSize
        val width = size?.let { (it.width * it.pixelWidthHeightRatio).roundToInt() } ?: 0
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

        val backInterval = chrome.skipBackwardInterval
        val forwardInterval = chrome.skipForwardInterval
        val (backIcon, backString) = when (backInterval) {
            SkipInterval.TEN -> Pair(R.drawable.ic_arrow_rotate_left_10, R.string.player_back_10)
            SkipInterval.FIFTEEN -> Pair(R.drawable.ic_arrow_rotate_left_15, R.string.player_back_15)
            SkipInterval.THIRTY -> Pair(R.drawable.ic_arrow_rotate_left_30, R.string.player_back_30)
        }
        val (forwardIcon, forwardString) = when (forwardInterval) {
            SkipInterval.TEN -> Pair(R.drawable.ic_arrow_rotate_right_10, R.string.player_forward_10)
            SkipInterval.FIFTEEN -> Pair(R.drawable.ic_arrow_rotate_right_15, R.string.player_forward_15)
            SkipInterval.THIRTY -> Pair(R.drawable.ic_arrow_rotate_right_30, R.string.player_forward_30)
        }
        return listOf(
            pipAction(backIcon, getString(backString), PIP_CONTROL_REWIND),
            playPause,
            pipAction(forwardIcon, getString(forwardString), PIP_CONTROL_FORWARD),
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

    // ------------------------------------------------------------------
    // Android XR theater (ENHANCEMENT.md Section N)
    // ------------------------------------------------------------------

    /**
     * Hands playback to the immersive theater: the item, its position, the
     * speed, the audio and subtitle choices, and the episode context, then
     * closes this player. The theater writes progress itself; the write here
     * only covers a handover that fails before it starts.
     */
    private fun enterTheater() {
        val exoPlayer = player ?: return
        // Only an item that has started here is handed over. Until then the
        // parental check (O.5) may still be deciding whether it plays at all,
        // and its position reads 0 over the stored resume point.
        if (!itemStarted) return
        saveContentPreferences()
        writeProgress()
        val tracks = exoPlayer.currentTracks
        val audio = audioTrackOptions(tracks).firstOrNull { it.isSelected }
        val text = textTrackOptions(tracks).firstOrNull { it.isSelected }
        val handover = XrTheaterHandover(
            uri = currentUriState.value,
            title = titleState.value,
            subtitle = subtitleState.value,
            positionMillis = exoPlayer.currentPosition.coerceAtLeast(0L),
            playWhenReady = exoPlayer.playWhenReady,
            tmdbId = currentTmdbId,
            isEpisode = currentIsEpisode,
            showTmdbId = currentShowTmdbId,
            season = progressKey?.season,
            episode = progressKey?.episode,
            audioTrackId = audio?.id,
            audioLanguage = audio?.language,
            subtitlesOff = text == null,
            textTrackId = text?.id,
            textLanguage = text?.language,
            speed = chrome.baseRate,
        )
        // The theater's player takes over; this one stops first, so the file is
        // never decoded twice while the theater opens at the same position.
        exoPlayer.stop()
        startActivity(XrTheater.intent(this, handover))
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Never intercept volume keys — the system (or the TV/AVR) owns them.
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            -> return super.dispatchKeyEvent(event)
        }

        if (!::chrome.isInitialized) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_S) {
            if (activeSegmentState.value != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    performSkip()
                }
                return true
            }
        }

        val exoPlayer = player
        if (exoPlayer != null && event.keyCode in TRANSPORT_KEYS) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    handleFastForwardRewindKey(exoPlayer, event)
                    return true
                }
                else -> {
                    // Consume both halves so no orphan ACTION_UP reaches a child.
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        handleTransportKey(exoPlayer, event.keyCode)
                    }
                    return true
                }
            }
        }

        // Keyboard keys on handhelds (J.3): Space, ← and → while no panel is
        // open (a panel's sliders and switches keep their own keys), and Esc
        // like Back. The TV's D-pad sends the same arrow codes and keeps its
        // reveal-and-seek handling.
        when (KeyboardShortcuts.playerCommand(event.keyCode, hasModifiers = !event.hasNoModifiers())) {
            PlayerKeyCommand.PLAY_PAUSE, PlayerKeyCommand.SKIP_BACK, PlayerKeyCommand.SKIP_FORWARD ->
                if (exoPlayer != null && !isTelevision && chrome.activePanel == null) {
                    handleKeyboardTransport(exoPlayer, event)
                    return true
                }
            PlayerKeyCommand.CLOSE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
                return true
            }
            null -> Unit
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

    /** Space plays and pauses; ← and → skip by the App Controls lengths, repeating while held. */
    private fun handleKeyboardTransport(exoPlayer: ExoPlayer, event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_SPACE -> if (event.repeatCount == 0) {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                chrome.showControls()
            }
            // A held key repeats every ~50 ms; every fourth repeat keeps the seeks followable.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount % 4 == 0) {
                seekBy(exoPlayer, chrome, -chrome.skipBackwardInterval.millis)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount % 4 == 0) {
                seekBy(exoPlayer, chrome, chrome.skipForwardInterval.millis)
            }
        }
    }

    /** Lists the player's keys in the system's keyboard shortcuts list (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (!::chrome.isInitialized) return
        data.add(
            KeyboardShortcuts.playerGroup(
                context = this,
                backSeconds = chrome.skipBackwardInterval.seconds,
                forwardSeconds = chrome.skipForwardInterval.seconds,
            ),
        )
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

    private fun handleFastForwardRewindKey(exoPlayer: ExoPlayer, event: KeyEvent) {
        val isForward = event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    cancelMediaKeyHold()
                    mediaKeyHoldActive = false
                    activeHoldKeyCode = event.keyCode
                    val runnable = Runnable {
                        mediaKeyHoldActive = true
                        val rate = if (isForward) chrome.holdRightRate else chrome.holdLeftRate
                        chrome.beginHoldRate(exoPlayer, rate)
                    }
                    mediaKeyHoldRunnable = runnable
                    mainHandler.postDelayed(runnable, 400L)
                }
                // Key repeats while the key is held must not also seek.
            }
            KeyEvent.ACTION_UP -> {
                val runnable = mediaKeyHoldRunnable
                if (runnable != null) {
                    mainHandler.removeCallbacks(runnable)
                    mediaKeyHoldRunnable = null
                }
                if (mediaKeyHoldActive) {
                    mediaKeyHoldActive = false
                    activeHoldKeyCode = null
                    chrome.endHoldRate(exoPlayer)
                } else if (!event.isCanceled && activeHoldKeyCode == event.keyCode) {
                    activeHoldKeyCode = null
                    if (isForward) {
                        seekBy(exoPlayer, chrome, chrome.skipForwardInterval.millis)
                    } else {
                        seekBy(exoPlayer, chrome, -chrome.skipBackwardInterval.millis)
                    }
                } else {
                    activeHoldKeyCode = null
                }
            }
        }
    }

    private fun cancelMediaKeyHold() {
        mediaKeyHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        mediaKeyHoldRunnable = null
        if (mediaKeyHoldActive) {
            mediaKeyHoldActive = false
            activeHoldKeyCode = null
            player?.let { chrome.endHoldRate(it) }
        } else {
            activeHoldKeyCode = null
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
                seekBy(exoPlayer, chrome, chrome.skipForwardInterval.millis)
            KeyEvent.KEYCODE_MEDIA_REWIND ->
                seekBy(exoPlayer, chrome, -chrome.skipBackwardInterval.millis)
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

    /**
     * Files the presented item's position under its TMDB id. Short of a
     * completion, an item that hasn't started ([itemStarted]) writes nothing:
     * it still reads 0:00, and its stored resume point must survive a close or
     * a switch while it buffers.
     */
    internal fun writeProgress(completed: Boolean = false) {
        if (!completed && (!itemStarted || !transitions.shouldWriteProgress)) return
        val key = progressKey ?: return
        val store = dataStore ?: return
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exoPlayer.currentPosition.coerceIn(0, duration)
        val fraction = position.toDouble() / duration
        val isCompleted = completed || fraction >= PlayerLogic.COMPLETE_FRACTION
        if (completedWritten && !isCompleted) return
        if (isCompleted) {
            completedWritten = true
            transitions.markCurrentCompleted()
        }
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
                    durationSeconds = duration / 1000.0,
                ),
            )
        }
    }

    internal fun performSkip(): Boolean {
        val exoPlayer = player ?: return false
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val action = segmentController.consumeSkip(
            positionMillis = exoPlayer.currentPosition,
            durationMillis = duration,
            isSeekable = exoPlayer.isCurrentMediaItemSeekable,
        ) ?: return false

        when (action) {
            is SkipAction.Seek -> {
                exoPlayer.seekTo(action.positionMillis)
                chrome.showControls()
            }
            is SkipAction.Finish -> {
                handlePlaybackEnded()
            }
        }
        return true
    }

    private fun handlePlaybackEnded() {
        val exoPlayer = player ?: return
        writeProgress(completed = true)
        val entries = playlistState.value?.entries.orEmpty()
        val currentUri = currentUriState.value
        val currentEntry = entries.firstOrNull { it.uri == currentUri }
        val currentCandidate = currentEntry?.let { entry ->
            if (entry.season != null && entry.episode != null) {
                EpisodeCandidate(
                    id = entry.uri,
                    season = entry.season,
                    episode = entry.episode,
                    title = entry.title,
                )
            } else null
        }
        val episodeCandidates = entries.mapNotNull { entry ->
            if (entry.season != null && entry.episode != null) {
                EpisodeCandidate(
                    id = entry.uri,
                    season = entry.season,
                    episode = entry.episode,
                    title = entry.title,
                )
            } else null
        }
        val action = transitions.onNaturalEnd(
            loopEnabled = chrome.loopEnabled,
            currentEpisode = currentCandidate,
            episodes = episodeCandidates,
        )
        when (action) {
            is NaturalEndAction.LoopRestart -> {
                exoPlayer.seekTo(0)
                exoPlayer.play()
            }
            is NaturalEndAction.Advance -> {
                if (transitions.claim(action.ticket)) {
                    val nextEntry = entries.firstOrNull { it.uri == action.nextEpisode.id }
                    if (nextEntry != null) {
                        switchTo(nextEntry)
                    } else {
                        finish()
                    }
                }
            }
            is NaturalEndAction.Finish -> {
                finish()
            }
        }
    }

    private fun introDbMedia(
        tmdbId: Int?,
        isEpisode: Boolean,
        showTmdbId: Int?,
        season: Int?,
        episode: Int?,
    ): IntroDbMedia? = if (isEpisode) {
        val showId = showTmdbId?.takeIf { it in 1..10_000_000 } ?: return null
        val s = season?.takeIf { it > 0 } ?: return null
        val e = episode?.takeIf { it > 0 } ?: return null
        IntroDbMedia.create(tmdbId = showId, season = s, episode = e)
    } else {
        val movieId = tmdbId?.takeIf { it in 1..10_000_000 } ?: return null
        IntroDbMedia.create(tmdbId = movieId)
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
        /** Where to start, in milliseconds; the theater's way back (Section N). */
        private const val EXTRA_POSITION = "edendale.positionMillis"

        /**
         * False when the launcher fell back from the source the title last
         * played from (`PlaybackSources.remembersSource`) or follows a list,
         * so playing the item mustn't make its source the remembered one
         * (27.1). Absent means true.
         */
        private const val EXTRA_REMEMBER_SOURCE = "edendale.rememberSource"

        /** Saved state across a relaunch: where the item was, and whether its source is still to be recorded. */
        private const val STATE_POSITION = "edendale.state.positionMillis"
        private const val STATE_REMEMBER_SOURCE = "edendale.state.rememberSource"

        private const val ACTION_PIP_CONTROL = "com.babasama.edendale.PIP_CONTROL"
        private const val EXTRA_PIP_CONTROL = "edendale.pipControl"
        private const val PIP_CONTROL_PLAY = 1
        private const val PIP_CONTROL_PAUSE = 2
        private const val PIP_CONTROL_REWIND = 3
        private const val PIP_CONTROL_FORWARD = 4

        /** The system rejects PiP aspect ratios outside 1:2.39 … 2.39:1. */
        private const val MIN_PIP_ASPECT = 1.0 / 2.39
        private const val MAX_PIP_ASPECT = 2.39

        /** How long after a lock a half turn of the display counts as the lock's doing. */
        private const val ROTATION_LOCK_GUARD_MILLIS = 1_500L

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
            rememberSource: Boolean = true,
        ) {
            context.startActivity(
                intent(context, uri, title, tmdbId, isEpisode, showTmdbId, season, episode, rememberSource = rememberSource),
            )
        }

        /** The request [play] sends, also written into the TV home screen's Watch Next rows (I.3). */
        fun intent(
            context: Context,
            uri: String,
            title: String,
            tmdbId: Int? = null,
            isEpisode: Boolean = false,
            showTmdbId: Int? = null,
            season: Int? = null,
            episode: Int? = null,
            positionMillis: Long? = null,
            rememberSource: Boolean = true,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URI, uri)
            putExtra(EXTRA_TITLE, title)
            tmdbId?.let { putExtra(EXTRA_TMDB_ID, it) }
            putExtra(EXTRA_IS_EPISODE, isEpisode)
            showTmdbId?.let { putExtra(EXTRA_SHOW_TMDB_ID, it) }
            season?.let { putExtra(EXTRA_SEASON, it) }
            episode?.let { putExtra(EXTRA_EPISODE, it) }
            positionMillis?.let { putExtra(EXTRA_POSITION, it) }
            if (!rememberSource) putExtra(EXTRA_REMEMBER_SOURCE, false)
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
