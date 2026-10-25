package com.babasama.edendale.android.theater

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.MainActivity
import com.babasama.edendale.android.R
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.player.ContentPlayerPreferencesRules
import com.babasama.edendale.android.player.EdendaleDataSource
import com.babasama.edendale.android.player.EdendaleRenderersFactory
import com.babasama.edendale.android.player.EqAudioProcessor
import com.babasama.edendale.android.player.PlayerChromeState
import com.babasama.edendale.android.player.PlayerLogic
import com.babasama.edendale.android.player.PlayerTrackOption
import com.babasama.edendale.android.player.SharedPreferencesContentStore
import com.babasama.edendale.android.player.applyContentPreferences
import com.babasama.edendale.android.player.audioTrackOptions
import com.babasama.edendale.android.player.seekBy
import com.babasama.edendale.android.player.selectAudioTrack
import com.babasama.edendale.android.player.selectTextTrack
import com.babasama.edendale.android.player.snapshotPreferences
import com.babasama.edendale.android.player.textTrackOptions
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SessionState
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.io.FileNotFoundException
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The immersive theater (HORIZON.md Section G): the video on a large screen
 * inside an environment, through Meta's Spatial SDK. The library and the
 * player stay 2D panels; this activity opens from the player's Theater
 * button with a [TheaterHandover] and owns its own ExoPlayer, built with the
 * same renderers and data source as the panel player, so every source type
 * plays unchanged (G.5).
 *
 * Scene (G.7): `theater.glb` from the assets when the `scenes` branch has
 * exported one, placed so its `Seat` is under the viewer and its `Screen`
 * takes the picture; otherwise the spike's sky and a 4 m screen 5 m ahead.
 * The screen is placed from the head pose once tracking reports one, and
 * again after a recenter. Controls and subtitles are Compose panels (G.8).
 * Exit (G.6) returns to Home with the panel focused, through Meta's hybrid
 * pattern, and progress is saved on exit, on stop, and every five seconds.
 */
class TheaterActivity : AppSystemActivity() {

    private var handover: TheaterHandover? = null
    private var player: ExoPlayer? = null

    /** Audio Enhancement's equalizer, in the audio sink as in the 2D player (E.1). */
    private val equalizer = EqAudioProcessor()
    private lateinit var chrome: PlayerChromeState
    private lateinit var contentStore: SharedPreferencesContentStore
    private var dataStore: LocalDataStore? = null
    private val controls = TheaterControlsState()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var authoredScene: TheaterScene? = null
    private var roomEntity: Entity? = null
    private var screenEntity: Entity? = null
    private var subtitlesEntity: Entity? = null
    private var controlsEntity: Entity? = null

    private var lastHead: HeadPose? = null
    private var placed = false
    private var aspectRatio: Float? = null
    private var controlsVisible = false
    private var hasRestoredPreferences = false
    private var handoverTracksApplied = false
    private var completedWritten = false
    private var exiting = false
    private var released = false
    private var resumeWhenFocused = false
    private var ticksSinceWrite = 0

    private val hideControls = Runnable { setControlsVisible(false) }
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            mainHandler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handover = TheaterHandover.from(intent)
        if (handover == null) {
            // Only the player starts the theater; anything else has nothing to show.
            Log.w(TAG, "started without a handover")
            finish()
            return
        }
        this.handover = handover
        chrome = PlayerChromeState(getSharedPreferences("player", MODE_PRIVATE))
        contentStore = SharedPreferencesContentStore(
            getSharedPreferences(ContentPlayerPreferencesRules.PREFS_NAME, MODE_PRIVATE),
        )
        equalizer.update(chrome.preferences.audioEnhancement)
        dataStore = LocalDataStore((application as EdendaleApplication).database)
        controls.title = handover.title
        controls.subtitle = handover.subtitle
        controls.subtitlesOff = handover.subtitlesOff
        authoredScene = loadAuthoredScene()

        // A seated theater has no locomotion (G.9); Meta's media sample drops the system the same way.
        if (systemManager.tryFindSystem<LocomotionSystem>() != null) systemManager.unregisterSystem<LocomotionSystem>()
        systemManager.registerSystem(
            TheaterInputSystem(onActivate = { setControlsVisible(true) }, onBack = { exitTheater() }),
        )
        systemManager.registerSystem(PlacementSystem())

        player = buildPlayer(handover)
        mainHandler.post(tick)
    }

    // ------------------------------------------------------------------
    // The player
    // ------------------------------------------------------------------

    private fun buildPlayer(handover: TheaterHandover): ExoPlayer {
        // The same factory as PlayerActivity: DefaultDataSource routes smb and
        // the storage providers' schemes to EdendaleDataSource (H.2).
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DataSource.Factory { DefaultDataSource(this, EdendaleDataSource(this)) },
        )
        val exoPlayer = ExoPlayer.Builder(this, EdendaleRenderersFactory(this, equalizer))
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // The headset keeps the app awake while worn, and this branch
            // declares no WAKE_LOCK (A.2), so don't ask for one.
            .setWakeMode(C.WAKE_MODE_NONE)
            .build()
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    writeProgress(completed = true)
                    exitTheater()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controls.isPlaying = isPlaying
            }

            /** The screen takes the picture's shape (G.7): a wider or taller video resizes it. */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width * videoSize.pixelWidthHeightRatio
                if (width > 0f && videoSize.height > 0) {
                    aspectRatio = width / videoSize.height
                    placeAgain()
                }
            }

            // A new item first reports empty tracks; restore against the report that lists them.
            override fun onTracksChanged(tracks: Tracks) {
                controls.audioOptions = audioTrackOptions(tracks)
                controls.textOptions = textTrackOptions(tracks)
                if (tracks.isEmpty) return
                if (!hasRestoredPreferences) {
                    hasRestoredPreferences = true
                    restoreContentPreferences(exoPlayer, tracks)
                }
                if (!handoverTracksApplied) {
                    handoverTracksApplied = true
                    applyHandoverChoices(exoPlayer, tracks)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                controls.failure = error.message ?: error.errorCodeName
                setControlsVisible(true)
            }
        })
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(handover.uri)), handover.positionMillis)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = handover.playWhenReady
        return exoPlayer
    }

    /** The per-title speed and track choices, the way the 2D player restores them. */
    private fun restoreContentPreferences(exoPlayer: ExoPlayer, tracks: Tracks) {
        val handover = handover ?: return
        val key = ContentPlayerPreferencesRules.contentKey(
            tmdbId = handover.tmdbId,
            isEpisode = handover.isEpisode,
            showTmdbId = handover.showTmdbId,
        ) ?: return
        val preferences = contentStore.get(key) ?: return
        applyContentPreferences(preferences = preferences, player = exoPlayer, chrome = chrome, tracks = tracks)
    }

    /** What the viewer was hearing and reading when they pressed Theater wins over the stored match. */
    private fun applyHandoverChoices(exoPlayer: ExoPlayer, tracks: Tracks) {
        val handover = handover ?: return
        matching(audioTrackOptions(tracks), handover.audioTrackId, handover.audioLanguage)
            ?.let { selectAudioTrack(exoPlayer, it) }
        if (handover.subtitlesOff) {
            selectTextTrack(exoPlayer, null)
        } else {
            matching(textTrackOptions(tracks), handover.textTrackId, handover.textLanguage)
                ?.let { selectTextTrack(exoPlayer, it) }
        }
        chrome.setRate(exoPlayer, handover.speed)
    }

    private fun matching(options: List<PlayerTrackOption>, id: String?, language: String?): PlayerTrackOption? =
        options.firstOrNull { id != null && it.id == id }
            ?: options.firstOrNull { language != null && it.language.equals(language, ignoreCase = true) }

    private fun saveContentPreferences() {
        val handover = handover ?: return
        val exoPlayer = player ?: return
        val key = ContentPlayerPreferencesRules.contentKey(
            tmdbId = handover.tmdbId,
            isEpisode = handover.isEpisode,
            showTmdbId = handover.showTmdbId,
        ) ?: return
        contentStore.save(
            key,
            snapshotPreferences(
                baseRate = chrome.baseRate,
                tracks = exoPlayer.currentTracks,
                trackSelectionParameters = exoPlayer.trackSelectionParameters,
                existing = contentStore.get(key),
            ),
        )
    }

    /** Continue Watching picks up where the theater left off (G.6, Functional.4), as PlayerActivity writes it. */
    private fun writeProgress(completed: Boolean = false) {
        val handover = handover ?: return
        val tmdbId = handover.tmdbId ?: return
        val store = dataStore ?: return
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exoPlayer.currentPosition.coerceIn(0, duration)
        val fraction = position.toDouble() / duration
        val isCompleted = completed || fraction >= PlayerLogic.COMPLETE_FRACTION
        if (completedWritten && !isCompleted) return
        if (isCompleted) completedWritten = true
        writeScope.launch {
            store.updateWatchProgress(
                WatchProgress(
                    tmdbId = tmdbId,
                    mediaType = if (handover.isEpisode) WatchMediaType.EPISODE else WatchMediaType.MOVIE,
                    position = if (isCompleted) 1.0 else fraction,
                    watchedSeconds = position / 1000.0,
                    lastWatchedEpochMillis = System.currentTimeMillis(),
                    isCompleted = isCompleted,
                    showTmdbId = handover.showTmdbId,
                    seasonNumber = handover.season,
                    episodeNumber = handover.episode,
                ),
            )
        }
    }

    private fun onTick() {
        val exoPlayer = player ?: return
        controls.positionMillis = exoPlayer.currentPosition.coerceAtLeast(0)
        controls.durationMillis = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        controls.isPlaying = exoPlayer.isPlaying
        if (++ticksSinceWrite >= WRITE_EVERY_TICKS) {
            ticksSinceWrite = 0
            if (exoPlayer.isPlaying) writeProgress()
        }
    }

    // ------------------------------------------------------------------
    // The scene (G.7)
    // ------------------------------------------------------------------

    /** The `scenes` branch's export, when one ships; null means the sky. */
    private fun loadAuthoredScene(): TheaterScene? = try {
        assets.open(SCENE_ASSET).use { TheaterScene.parse(it) }
    } catch (missing: FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.w(TAG, "$SCENE_ASSET is unusable; showing the sky instead", error)
        null
    }

    override fun onSceneReady() {
        super.onSceneReady()
        // The floor is the origin's height, which is what a seat and a recenter need.
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        // Nothing is lit: the sky is unlit and an authored room ships baked textures.
        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(0f),
            sunDirection = Vector3(0f, -1f, 0f),
        )

        roomEntity = if (authoredScene != null) {
            Entity.create(
                listOf(
                    Mesh(Uri.parse(SCENE_ASSET), hittable = MeshCollision.NoCollision).apply {
                        defaultShaderOverride = SceneMaterial.UNLIT_SHADER
                    },
                    Transform(),
                    Visible(false),
                ),
            )
        } else {
            Entity.create(
                listOf(
                    Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
                    Material().apply {
                        baseTextureAndroidResourceId = R.drawable.theater_sky
                        unlit = true
                    },
                    Transform(),
                ),
            )
        }
        // Hidden until the head is found and the screen has a place (G.7).
        screenEntity = Entity.create(listOf(Panel(R.id.theater_screen), Transform(), Visible(false)))
        subtitlesEntity = Entity.create(listOf(Panel(R.id.theater_subtitles), Transform(), Visible(false)))
        controlsEntity = Entity.create(listOf(Panel(R.id.theater_controls), Transform(), Visible(false)))
    }

    override fun registerPanels(): List<PanelRegistration> = listOf(
        // The picture, direct to a compositor layer; the quad is 16:9 and a
        // Scale component fits it to the video (G.7).
        VideoSurfacePanelRegistration(
            R.id.theater_screen,
            surfaceConsumer = { _, surface -> player?.setVideoSurface(surface) },
            settingsCreator = {
                MediaPanelSettings(
                    shape = QuadShapeOptions(width = BASE_SCREEN_WIDTH, height = BASE_SCREEN_HEIGHT),
                    // Ignored for surface panels: the video's own size rules.
                    display = PixelDisplayOptions(width = 1920, height = 1080),
                    rendering = MediaPanelRenderOptions(zIndex = 0),
                )
            },
        ),
        // The subtitles, the screen's size, 2 cm in front of it (G.8).
        ComposeViewPanelRegistration(
            R.id.theater_subtitles,
            composeViewCreator = { _, context ->
                ComposeView(context).apply {
                    player?.let { exoPlayer -> setContent { TheaterSubtitlePanel(exoPlayer, chrome) } }
                }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = BASE_SCREEN_WIDTH, height = BASE_SCREEN_HEIGHT),
                    // 1280 dp at 240 dpi is 1920 px: the same dp width as the panel player, so cues keep their size.
                    display = DpDisplayOptions(width = 1280f, height = 720f, dpi = 240),
                    style = PanelStyleOptions(themeResourceId = R.style.TheaterPanelTheme),
                )
            },
        ),
        // The controls card under the screen (G.8).
        ComposeViewPanelRegistration(
            R.id.theater_controls,
            composeViewCreator = { _, context ->
                ComposeView(context).apply { setContent { TheaterControlsPanel(controls, chrome, controlsActions) } }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = CONTROLS_WIDTH, height = CONTROLS_HEIGHT),
                    display = DpDisplayOptions(width = 800f, height = 250f, dpi = 240),
                    style = PanelStyleOptions(themeResourceId = R.style.TheaterPanelTheme),
                )
            },
        ),
    )

    // ------------------------------------------------------------------
    // Placement (G.7)
    // ------------------------------------------------------------------

    /** Until the screen has a place, looks for the head every frame. */
    private inner class PlacementSystem : SystemBase() {
        override fun execute() {
            if (!placed) tryPlace()
        }
    }

    /** The head's position and yaw from the local avatar, as Meta's samples read it. */
    private fun readHead(): HeadPose? {
        val head = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()
            ?.tryGetLocalPlayerAvatarBody()?.head ?: return null
        val pose = head.tryGetComponent<Transform>()?.transform ?: return null
        val forward = pose.q * Vector3(0f, 0f, 1f)
        return HeadPose(Point3(pose.t.x, pose.t.y, pose.t.z), HeadPose.yawOf(forward.x, forward.z))
    }

    private fun tryPlace() {
        val head = readHead() ?: return
        // (0, 0, 0) until tracking finds the head (Findings, 2026-10-25).
        if (!TheaterLayout.isTracking(head)) return
        lastHead = head
        placed = true
        applyPlacement(TheaterLayout.place(head, authoredScene, aspectRatio))
    }

    /** Keeps the room where it is and refits the screen (a new aspect ratio). */
    private fun placeAgain() {
        val head = lastHead ?: return
        if (placed) applyPlacement(TheaterLayout.place(head, authoredScene, aspectRatio))
    }

    private fun applyPlacement(placement: TheaterPlacement) {
        val forward = Vector3(sin(placement.yawRadians), 0f, cos(placement.yawRadians))
        val screen = placement.screen
        val center = screen.center.toVector3()
        // A panel's front faces the viewer when it looks along viewer − panel (Meta's samples).
        val viewer = Vector3(placement.root.x, screen.center.y, placement.root.z)
        val facing = Quaternion.lookRotationAroundY(viewer - center)
        val fit = Scale(Vector3(screen.width / BASE_SCREEN_WIDTH, screen.height / BASE_SCREEN_HEIGHT, 1f))

        if (authoredScene != null) {
            // The room's Seat under the viewer, its "ahead" along the viewer's yaw. If the
            // SDK's GLB import keeps glTF's -Z as ahead, this turns the room around: G.10.
            roomEntity?.setComponents(
                listOf(Transform(Pose(placement.root.toVector3(), Quaternion.lookRotationAroundY(forward))), Visible(true)),
            )
        }
        screenEntity?.setComponents(listOf(Transform(Pose(center, facing)), fit, Visible(true)))
        subtitlesEntity?.setComponents(listOf(Transform(Pose(center - forward * SUBTITLE_OFFSET, facing)), fit, Visible(true)))
        val controlsCenter = center - forward * CONTROLS_TOWARD_VIEWER - Vector3(0f, screen.height / 2f + CONTROLS_BELOW_SCREEN, 0f)
        controlsEntity?.setComponents(listOf(Transform(Pose(controlsCenter, facing)), Visible(controlsVisible)))
    }

    private fun Point3.toVector3() = Vector3(x, y, z)

    // ------------------------------------------------------------------
    // Controls (G.8)
    // ------------------------------------------------------------------

    /** Shows the card and hides it after five seconds; a failure keeps it up. */
    private fun setControlsVisible(visible: Boolean) {
        mainHandler.removeCallbacks(hideControls)
        controlsVisible = visible
        controlsEntity?.setComponent(Visible(visible && placed))
        if (visible && controls.failure == null) mainHandler.postDelayed(hideControls, CONTROLS_TIMEOUT_MILLIS)
    }

    private val controlsActions = object : TheaterControlsActions {
        override fun togglePlayPause() {
            val exoPlayer = player ?: return
            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            noteInteraction()
        }

        override fun skip(forward: Boolean) {
            val exoPlayer = player ?: return
            seekBy(exoPlayer, chrome, if (forward) chrome.skipForwardInterval.millis else -chrome.skipBackwardInterval.millis)
            noteInteraction()
        }

        override fun seekToFraction(fraction: Float) {
            val exoPlayer = player ?: return
            val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
            exoPlayer.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
            noteInteraction()
        }

        override fun selectAudio(option: PlayerTrackOption) {
            player?.let { selectAudioTrack(it, option) }
            saveContentPreferences()
            noteInteraction()
        }

        override fun selectSubtitles(option: PlayerTrackOption?) {
            player?.let { selectTextTrack(it, option) }
            controls.subtitlesOff = option == null
            saveContentPreferences()
            noteInteraction()
        }

        override fun exit() = exitTheater()

        override fun noteInteraction() {
            if (controlsVisible) setControlsVisible(true)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle and exit (G.6)
    // ------------------------------------------------------------------

    /** A recenter moves the origin; the next frame places the room and the screen again. */
    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        placed = false
    }

    /** Taking the headset off pauses; putting it on resumes (B.4's choice). */
    override fun onHMDUnmounted() {
        super.onHMDUnmounted()
        pauseForTheSystem()
    }

    override fun onHMDMounted() {
        super.onHMDMounted()
        resumeAfterTheSystem()
    }

    override fun onVRPause() {
        super.onVRPause()
        pauseForTheSystem()
    }

    override fun onVRReady() {
        super.onVRReady()
        resumeAfterTheSystem()
    }

    /** The Meta button's menu takes focus without pausing the activity (Meta's media sample). */
    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        when (state) {
            SessionState.VISIBLE -> pauseForTheSystem()
            SessionState.FOCUSED -> resumeAfterTheSystem()
            else -> Unit
        }
    }

    private fun pauseForTheSystem() {
        val exoPlayer = player ?: return
        if (exoPlayer.isPlaying) {
            resumeWhenFocused = true
            exoPlayer.pause()
            writeProgress()
        }
    }

    private fun resumeAfterTheSystem() {
        if (!resumeWhenFocused) return
        resumeWhenFocused = false
        player?.play()
    }

    override fun onStop() {
        saveContentPreferences()
        writeProgress()
        pauseForTheSystem()
        super.onStop()
    }

    /**
     * Leaves for Home with the panel focused: Home starts with a pending
     * intent for the library, Meta's hybrid pattern. A plain finish() also
     * returns to Home but leaves the panel unfocused (Findings, 2026-10-25).
     */
    private fun exitTheater() {
        if (exiting) return
        exiting = true
        mainHandler.removeCallbacks(hideControls)
        saveContentPreferences()
        writeProgress()
        val panel = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingPanel = PendingIntent.getActivity(
            applicationContext,
            0,
            panel,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_LAUNCH_IN_HOME, pendingPanel)
        runCatching { startActivity(home) }.onFailure { Log.w(TAG, "Home refused the return; finishing anyway", it) }
        finish()
    }

    override fun onSpatialShutdown() {
        release()
        super.onSpatialShutdown()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(tick)
        mainHandler.removeCallbacks(hideControls)
        player?.setVideoSurface(null)
        player?.release()
        player = null
    }

    companion object {
        private const val TAG = "TheaterActivity"

        /** The `scenes` branch's export, under src/horizon/assets (G.7, D17). */
        const val SCENE_ASSET = "theater.glb"

        /** The registered quad is 16:9; a Scale component fits it to the video. */
        private const val BASE_SCREEN_WIDTH = 4f
        private const val BASE_SCREEN_HEIGHT = 2.25f
        private const val CONTROLS_WIDTH = 1.6f
        private const val CONTROLS_HEIGHT = 0.5f
        /** The subtitles sit 2 cm in front of the picture. */
        private const val SUBTITLE_OFFSET = 0.02f
        /** The controls card hangs under the screen's bottom edge, a little toward the viewer. */
        private const val CONTROLS_BELOW_SCREEN = 0.35f
        private const val CONTROLS_TOWARD_VIEWER = 0.5f
        private const val CONTROLS_TIMEOUT_MILLIS = 5_000L
        private const val TICK_MILLIS = 500L
        /** Progress every five seconds, as the 2D player writes it. */
        private const val WRITE_EVERY_TICKS = 10

        /** Meta's hybrid-sample extra: Home starts this pending intent after it opens. */
        private const val EXTRA_LAUNCH_IN_HOME = "extra_launch_in_home_pending_intent"
    }
}
