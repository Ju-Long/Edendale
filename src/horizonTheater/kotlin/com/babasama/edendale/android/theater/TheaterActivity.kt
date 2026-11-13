package com.babasama.edendale.android.theater

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.MainActivity
import com.babasama.edendale.android.PlaybackSources
import com.babasama.edendale.android.R
import com.babasama.edendale.android.YoungAudienceFilter
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.parental.awaitReady
import com.babasama.edendale.android.parental.parentalControls
import com.babasama.edendale.android.parental.restrictedMessageRes
import com.babasama.edendale.android.player.PlayerLogic
import com.babasama.edendale.android.player.PlayerPlaylist
import com.babasama.edendale.android.player.PlaylistEntry
import com.babasama.edendale.android.player.audioTrackOptions
import com.babasama.edendale.android.player.loadPlayerPlaylist
import com.babasama.edendale.android.player.permittedPlaylist
import com.babasama.edendale.android.player.playlistAudienceRefs
import com.babasama.edendale.android.player.textTrackOptions
import com.babasama.edendale.android.xr.DockFollower
import com.babasama.edendale.android.xr.DockPose
import com.babasama.edendale.android.xr.HeadFrame
import com.babasama.edendale.android.xr.Quat
import com.babasama.edendale.android.xr.RoomPose
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterDock
import com.babasama.edendale.android.xr.TheaterGlb
import com.babasama.edendale.android.xr.TheaterLights
import com.babasama.edendale.android.xr.TheaterPlayback
import com.babasama.edendale.android.xr.TheaterPreferencesRules
import com.babasama.edendale.android.xr.TheaterSeatMap
import com.babasama.edendale.android.xr.Vec3
import com.babasama.edendale.android.youngAudienceFilter
import com.babasama.edendale.domain.WatchMediaType
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
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
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.io.FileNotFoundException
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The immersive theater (HORIZON.md Section G): the video on a large screen
 * inside one of the three `scenes` rooms, through Meta's Spatial SDK. The
 * library and the player stay 2D panels; this activity opens from the
 * player's Theater button with a [TheaterHandover].
 *
 * Since G.15 it is android's Android XR theater (ENHANCEMENT.md Section N)
 * on the Quest, through the shared code in `src/main`'s `xr` package:
 * - playback is android's `TheaterPlayback` (built as the 2D player builds
 *   its player, with the TheIntroDB prompts and the per-title memory);
 * - the viewer is seated by android's recentre rule (level, or the whole
 *   room tilted when lying back), in the chosen seat of the room's seat map,
 *   the best seat by default and a remembered one per room;
 * - the controls dock and the seat panel follow the head below the line of
 *   sight (`DockFollower`) and never hide;
 * - the house lights scale the room's baked materials, Recentre re-seats
 *   the viewer where they look, the subtitles are laid out in the picture's
 *   own shape, and Up Next plays the next episode here (the owner's choice).
 *
 * [TheaterStaging] and [TheaterFrames] turn the shared frame into the SDK's.
 * The activity is exported (G.4, Meta's sample), so it runs the 2D player's
 * parental check itself before anything plays ([TheaterAdmission]).
 * Exit (G.6) returns to Home with the panel focused, through Meta's hybrid
 * pattern.
 */
class TheaterActivity : AppSystemActivity(), TheaterActions {

    /** What plays now: the player's handover, or the episode Up Next moved to. */
    private var handover: TheaterHandover? = null
    private var playback: TheaterPlayback? = null
    private val ui = TheaterUiState()
    private lateinit var settings: TheaterSettings
    private val mainHandler = Handler(Looper.getMainLooper())

    // The parental check (G.15) and the episode list, on the main thread as the 2D player runs them.
    private val parentalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audience: YoungAudienceFilter? = null
    private var admissionJob: Job? = null
    private var playlistJob: Job? = null
    private var playlist: PlayerPlaylist? = null
    private var upcoming: PlaylistEntry? = null
    /** Up Next was pressed and the next episode is being checked: the card stays away and a second press does nothing. */
    private var upNextPending = false

    /**
     * The system holds the theater (headset off, the Meta button's menu, the
     * activity stopped): a title the parental check admits meanwhile starts
     * paused and resumes when the hold ends.
     */
    private var systemHeld = false

    /** The screen panel's surface, kept so the next episode's player can draw on it. */
    private var videoSurface: Surface? = null

    /** The current subtitle panel's view; an older one disposes its composition once detached (its composition would outlive the entity). */
    private var subtitleView: ComposeView? = null

    // The room (G.14, G.15).
    private var room = TheaterRoom.DEFAULT
    private var roomFile: TheaterRoomFile? = null
    private var roomEntity: Entity? = null
    private var roomObject: CompletableFuture<SceneObject>? = null
    private var roomMesh: SceneMesh? = null
    /** How the SDK turned the room when it loaded it ([TheaterFrames.importTurn]); null until its nodes are read. */
    private var importTurn: Float? = null
    /** The room is drawn: its turn is known, or waiting for it timed out. */
    private var roomShown = false
    private var roomWaitFrames = 0
    private var roomGaveUp = false
    /** The lights level last written to the current room's materials. */
    private var lightsApplied: TheaterLights? = null
    /** Frames the lights have found none of the room's materials to write, in case the SDK fills them in late. */
    private var lightsMisses = 0

    private var screenEntity: Entity? = null
    private var subtitlesEntity: Entity? = null
    private var dockEntity: Entity? = null
    private var seatsEntity: Entity? = null

    // The stage: where the viewer sits; null until seated, and again after a recentre.
    private var stage: RoomPose? = null
    private var seatedWithoutHead = false
    private var framesWithoutHead = 0
    private var lastFrameNanos = 0L
    private var follower = DockFollower()
    private var lastDock: DockPose? = null
    private var lastSide: DockPose? = null
    private var bodySystem: PlayerBodyAttachmentSystem? = null
    private var placedAspect: Float? = null
    /** The aspect the subtitle panel was laid out for (G.15); its entity is rebuilt when the picture changes shape. */
    private var subtitleAspect: Float? = null
    /** The room and the picture are hidden for a moment while the viewer changes seats. */
    private var blinking = false

    private var exiting = false
    private var released = false

    private val endBlink = Runnable {
        blinking = false
        placeScene()
    }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            mainHandler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A theater the system rebuilds plays what it played last (Up Next may have moved on), and checks it again.
        val saved = TheaterHandover.from(savedInstanceState?.getBundle(STATE_HANDOVER))
        val handover = saved ?: TheaterHandover.from(intent)
        if (handover == null) {
            // Only the player starts the theater; anything else has nothing to show.
            Log.w(TAG, "started without a handover")
            finish()
            return
        }
        settings = TheaterSettings(getSharedPreferences(TheaterPreferencesRules.PREFS_NAME, MODE_PRIVATE))
        room = settings.room
        ui.lights = settings.lights
        loadRoomFile(room)

        // A seated theater has no locomotion (G.9); Meta's media sample drops the system the same way.
        if (systemManager.tryFindSystem<LocomotionSystem>() != null) systemManager.unregisterSystem<LocomotionSystem>()
        systemManager.registerSystem(TheaterInputSystem(onBack = { exit() }))
        systemManager.registerSystem(StageSystem())

        // The player exists from the start, so the panels can bind to it; it loads nothing until admitted.
        this.handover = handover
        attach(TheaterPlayback(this, handover.toPlayback()))
        admit(
            handover,
            ticket = if (saved != null) null else TheaterHandover.ticket(intent),
            onAdmitted = { begin(handover) },
            onRefused = {
                refuse()
                exitTheater()
            },
        )
        mainHandler.post(tick)
    }

    /** The theater is singleTask: a second start while it runs plays nothing (only the player's own handover ever does). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.w(TAG, "a second start while the theater runs was ignored")
    }

    /** What plays now and where, with no ticket, so a rebuilt theater picks up there after its own check. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = handover ?: return
        val position = playback?.takeIf { ui.admitted }?.player?.currentPosition?.coerceAtLeast(0L) ?: current.positionMillis
        outState.putBundle(STATE_HANDOVER, current.copy(positionMillis = position).extras())
    }

    // ------------------------------------------------------------------
    // Playback and the parental check (G.15)
    // ------------------------------------------------------------------

    private fun attach(next: TheaterPlayback) {
        playback = next
        ui.playback = next
        videoSurface?.let { next.player.setVideoSurface(it) }
    }

    /**
     * The 2D player's parental check (O.5), run here because the activity is
     * exported: a handover with the player's [ticket] plays at once, as does
     * any title while no rule is in force; anything else waits for the
     * controls, is verified with TMDB, and plays only if the policy allows it.
     */
    private fun admit(handover: TheaterHandover, ticket: String?, onAdmitted: () -> Unit, onRefused: () -> Unit) {
        val controls = parentalControls(this)
        val look = TheaterAdmission.firstLook(
            ticketRedeemed = TheaterTickets.redeem(ticket, handover),
            controlsReady = controls.ready.value,
            policyActive = controls.policy.value.isActive,
        )
        if (look == TheaterAdmission.FirstLook.PLAY) {
            onAdmitted()
            return
        }
        admissionJob?.cancel()
        admissionJob = parentalScope.launch {
            controls.awaitReady()
            val filter = audience()
            val ref = TheaterAdmission.ref(handover)
            if (ref != null && filter.isEnabled) filter.verify(listOf(ref))
            if (released) return@launch
            if (TheaterAdmission.permits(filter.isEnabled, ref, filter::allows)) onAdmitted() else onRefused()
        }
    }

    private fun audience(): YoungAudienceFilter = audience ?: youngAudienceFilter(this, parentalScope).also { audience = it }

    /** As the player refuses: its words for the policy in force, in a toast. */
    private fun refuse() {
        Log.i(TAG, "the parental policy refused a title")
        Toast.makeText(applicationContext, getString(restrictedMessageRes(parentalControls(this).policy.value)), Toast.LENGTH_LONG).show()
    }

    /** Admitted: the dock shows the title and the transport, and the item loads. */
    private fun begin(handover: TheaterHandover) {
        if (released) return
        ui.admitted = true
        playback?.start()
        // Admitted while the system holds the theater: start paused, and resume when the hold ends.
        if (systemHeld) playback?.pauseForSystem()
        loadPlaylist(handover)
    }

    /**
     * The episode list Up Next reads, as the 2D player loads it, filtered by
     * the parental policy as the player filters it (O.5), so a restricted
     * episode is never offered.
     */
    private fun loadPlaylist(handover: TheaterHandover) {
        playlistJob?.cancel()
        playlist = null
        upcoming = null
        ui.upNext = null
        if (!handover.isEpisode) return
        val app = application as? EdendaleApplication ?: return
        playlistJob = parentalScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    loadPlayerPlaylist(
                        dao = app.database.libraryDao(),
                        uriString = handover.uri,
                        showTmdbIdExtra = handover.showTmdbId,
                        remembered = app.libraryRepository.sourceMemory.sourceFor(PlaybackSources.showMemoryKey(handover.showTmdbId, null)),
                    )
                }.getOrNull()
            } ?: return@launch
            val filter = audience()
            if (filter.isEnabled) filter.verify(playlistAudienceRefs(loaded))
            if (released || this@TheaterActivity.handover !== handover) return@launch
            playlist = permittedPlaylist(loaded, filter.isEnabled, filter::allows)
        }
    }

    private fun onTick() {
        val current = playback ?: return
        val handover = handover ?: return
        if (!ui.admitted || exiting) return
        // A natural end (or a credits skip to the end): the completed progress is written; back to the library (G.6).
        if (current.ended) {
            exitTheater()
            return
        }
        val next = if (upNextPending) null else TheaterUpNextRules.upcoming(playlist, handover.uri, handover.isEpisode, current.positionMillis, current.durationMillis)
        if (next?.uri != upcoming?.uri) {
            upcoming = next
            ui.upNext = next?.let(TheaterUpNextRules::card)
        }
        val aspect = current.videoAspect
        if (aspect != null && aspect != placedAspect) placeScene()
    }

    // ------------------------------------------------------------------
    // The room (G.14, G.15)
    // ------------------------------------------------------------------

    /**
     * The room's `Seat`, `Screen`, seat markers, and dimmable materials from
     * its GLB's JSON chunk (the textures aren't read); null means the sky.
     * Every room ships in a theater build, so null is a broken asset, never a
     * missing choice.
     */
    private fun loadRoomFile(room: TheaterRoom) {
        val file = try {
            assets.open(room.assetPath).use { TheaterRoomFile.read(it) }
        } catch (missing: FileNotFoundException) {
            Log.w(TAG, "${room.assetPath} isn't in the APK; showing the sky instead")
            null
        } catch (error: Exception) {
            Log.w(TAG, "${room.assetPath} is unusable; showing the sky instead", error)
            null
        }
        roomFile = file
        val map = file?.let { TheaterSeatMap(it.glb.seatMarkers, it.glb.seat) }
        val screen = TheaterStaging.screen(file?.glb)
        ui.room = room
        ui.seatMap = map
        ui.screenCenterX = screen.center.x
        ui.screenWidth = screen.width
        ui.seatedSeat = TheaterStaging.seatedSeat(map, settings.seat(room))
        roomObject = null
        roomMesh = null
        importTurn = null
        roomWaitFrames = 0
        roomGaveUp = false
        lightsApplied = null
        lightsMisses = 0
        roomShown = file == null
    }

    override fun onSceneReady() {
        super.onSceneReady()
        // The floor is the origin's height, which is what a seat and a recentre need.
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        // Nothing is lit: the sky is unlit, and the rooms' materials are
        // KHR_materials_unlit, their light baked into the textures (G.14).
        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(0f),
            sunDirection = Vector3(0f, -1f, 0f),
        )
        roomEntity = createRoomEntity()
        // Hidden until the viewer is seated (G.7, G.15).
        screenEntity = Entity.create(listOf(Panel(R.id.theater_screen), Transform(), Visible(false)))
        dockEntity = Entity.create(listOf(Panel(R.id.theater_controls), Transform(), Visible(false)))
        seatsEntity = Entity.create(listOf(Panel(R.id.theater_seats), Transform(), Visible(false)))
    }

    /**
     * The current room's mesh, hidden until the SDK has loaded it and the
     * placement knows which way it turned it; or the sky when the room file
     * is unusable. No shader override: the prepared GLB's materials say unlit
     * themselves (`KHR_materials_unlit`), which keeps the projector beam's
     * blending.
     */
    private fun createRoomEntity(): Entity = if (roomFile != null) {
        Entity.create(
            listOf(
                Mesh(Uri.parse(room.meshUri), hittable = MeshCollision.NoCollision),
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

    /**
     * Until the SDK has loaded the room's mesh: reads where it put `Seat` and
     * `Screen`, which says how it turned the room ([TheaterFrames.importTurn]),
     * and places the room by that. If the mesh hasn't reported them after
     * [ROOM_FRAME_TIMEOUT_FRAMES], the room shows as if the SDK mirrored z
     * (turn 0), and keeps looking until [ROOM_FRAME_GIVE_UP_FRAMES] in case
     * the SDK only loads a mesh it draws.
     */
    private fun pollRoom() {
        val entity = roomEntity ?: return
        val file = roomFile ?: return
        roomWaitFrames++
        if (roomMesh == null) {
            val future = roomObject
                ?: systemManager.tryFindSystem<SceneObjectSystem>()?.getSceneObject(entity)?.also { roomObject = it }
            if (future != null && future.isDone && !future.isCompletedExceptionally) {
                roomMesh = runCatching { future.getNow(null)?.mesh }
                    .onFailure { Log.w(TAG, "couldn't read ${room.id}'s mesh", it) }
                    .getOrNull()
            }
        }
        val mesh = roomMesh
        if (mesh != null && importTurn == null) {
            val turn = runCatching { readImportTurn(mesh, file.glb) }
                .onFailure { Log.w(TAG, "couldn't read ${room.id}'s nodes", it) }
                .getOrNull()
            if (turn != null) {
                importTurn = turn
                roomShown = true
                placeScene()
                return
            }
        }
        when {
            !roomShown && roomWaitFrames > ROOM_FRAME_TIMEOUT_FRAMES -> {
                Log.w(TAG, "${room.id} reported no Seat and Screen yet; showing it as if the SDK mirrored z")
                roomShown = true
                placeScene()
            }
            roomWaitFrames >= ROOM_FRAME_GIVE_UP_FRAMES -> {
                roomGaveUp = true
                Log.w(TAG, "${room.id} never reported Seat and Screen; it stays as if the SDK mirrored z")
            }
        }
    }

    /**
     * How the SDK turned the room, from `Seat` and `Screen` in the loaded
     * mesh's frame, or null while it has neither. G.10 reads this line in
     * `logcat`; the off-centre seat marker it names checks that the room was
     * not also mirrored left to right (the seat map would then be too).
     */
    private fun readImportTurn(mesh: SceneMesh, glb: TheaterGlb): Float? {
        if (mesh.getNodeCount() == 0) return null
        val seatNode = mesh.getNodeIndex(TheaterGlb.SEAT).takeIf { it >= 0 } ?: return null
        val screenNode = mesh.getNodeIndex(TheaterGlb.SCREEN).takeIf { it >= 0 } ?: return null
        val loadedSeat = nodePosition(mesh, seatNode)
        val loadedScreen = nodePosition(mesh, screenNode)
        val fileScreen = (glb.screenBoundsMin + glb.screenBoundsMax) * 0.5f
        val turn = TheaterFrames.importTurn(glb.seat, fileScreen, loadedSeat, loadedScreen) ?: return null
        Log.i(TAG, "${room.id} loaded with Seat at $loadedSeat and Screen at $loadedScreen; import turn ${Math.toDegrees(turn.toDouble()).toInt()}°")
        glb.seatMarkers.maxByOrNull { (_, position) -> abs(position.x) }?.let { (name, position) ->
            val node = mesh.getNodeIndex(name)
            if (node >= 0) {
                val loaded = nodePosition(mesh, node)
                val predicted = TheaterFrames.loadedPoint(position, turn)
                val off = Vec3.distance(loaded, predicted)
                val line = "$name loaded at $loaded, expected $predicted (${"%.3f".format(off)} m)"
                if (off > MIRROR_CHECK_METRES) Log.w(TAG, "$line: the SDK's glTF import isn't the mirror this theater expects") else Log.i(TAG, line)
            } else {
                Log.i(TAG, "$name isn't in the loaded mesh; the import's left and right are unchecked")
            }
        }
        return turn
    }

    private fun nodePosition(mesh: SceneMesh, node: Int): Vec3 {
        val t = mesh.calculateNodeGlobalMatrix(node).decompose().first.t
        return Vec3(t.x, t.y, t.z)
    }

    /**
     * The house lights (G.15): every baked material of the loaded room at the
     * level's brightness (its base colour factor, which an unlit material
     * multiplies), by the material's name in the GLB and else by its index.
     * The screen and the projector's beam stay as they are. Written in full
     * every time, Normal included, because the SDK may hand a revisited room
     * the same mesh with the old level still in it.
     */
    private fun applyLights(mesh: SceneMesh) {
        val file = roomFile ?: return
        val level = ui.lights
        lightsApplied = level
        val count = runCatching { mesh.materials?.size ?: 0 }.getOrDefault(0)
        var written = 0
        var byIndex = 0
        for (material in file.dimmable) {
            val named = material.name?.let { name -> runCatching { mesh.getMaterialIndex(name) }.getOrDefault(-1) } ?: -1
            val index = if (named >= 0) named else material.index.also { byIndex++ }
            if (index !in 0 until count) continue
            val albedo = material.albedo(level)
            runCatching {
                val target = mesh.getMaterial(index) ?: error("no material at $index")
                val attribute = target.getAttributeIndex(ALBEDO_FACTOR)
                if (attribute >= 0) {
                    target.setAttribute(attribute, Vector4(albedo[0], albedo[1], albedo[2], albedo[3]))
                } else {
                    target.setAlbedoColor(Color.valueOf(albedo[0], albedo[1], albedo[2], albedo[3]))
                }
            }.onSuccess { written++ }
                .onFailure { Log.w(TAG, "lights: material $index of ${room.id} refused", it) }
        }
        if (written == 0 && file.dimmable.isNotEmpty() && ++lightsMisses < LIGHTS_MISS_FRAMES) {
            // Nothing to write yet: try again next frame, and say so only once it gives up.
            lightsApplied = null
            return
        }
        lightsMisses = 0
        Log.i(TAG, "lights ${level.raw}: $written of ${file.dimmable.size} materials of ${room.id} ($count loaded, $byIndex by index)")
    }

    /**
     * Moves the viewer to [next] (G.14). The video keeps playing on the same
     * panel; the old room goes, the new one loads hidden in the seat last
     * chosen there, and the picture moves to its screen.
     */
    private fun switchRoom(next: TheaterRoom) {
        if (next == room) return
        room = next
        settings.room = next
        loadRoomFile(next)
        roomEntity?.destroy()
        roomEntity = createRoomEntity()
        placeScene()
    }

    override fun registerPanels(): List<PanelRegistration> = listOf(
        // The picture, direct to a compositor layer; the quad is 16:9 and a
        // Scale component fits it to the video (G.7).
        VideoSurfacePanelRegistration(
            R.id.theater_screen,
            surfaceConsumer = { _, surface ->
                videoSurface = surface
                playback?.player?.setVideoSurface(surface)
            },
            settingsCreator = {
                MediaPanelSettings(
                    shape = QuadShapeOptions(width = BASE_SCREEN_WIDTH, height = BASE_SCREEN_HEIGHT),
                    // Ignored for surface panels: the video's own size rules.
                    display = PixelDisplayOptions(width = 1920, height = 1080),
                    rendering = MediaPanelRenderOptions(zIndex = 0),
                )
            },
        ),
        // The subtitles, in the picture's own shape, 2 cm in front of it (G.8, G.15).
        ComposeViewPanelRegistration(
            R.id.theater_subtitles,
            composeViewCreator = { _, context ->
                ComposeView(context).apply {
                    setContent { TheaterSubtitlePanel(ui) }
                    // The SDK keeps every panel's composition until the activity ends; a subtitle panel
                    // made again for another shape lets its old one go once the SDK has taken it down.
                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) = Unit
                        override fun onViewDetachedFromWindow(view: View) {
                            if (view !== subtitleView) (view as ComposeView).disposeComposition()
                        }
                    })
                }.also { subtitleView = it }
            },
            settingsCreator = {
                val shape = TheaterSubtitleShape.of(subtitleAspect ?: 0f)
                UIPanelSettings(
                    shape = QuadShapeOptions(width = TheaterSubtitleShape.WIDTH_METRES, height = shape.heightMetres),
                    // 1280 dp at 240 dpi is 1920 px: the panel player's dp width, so cues keep their size against the picture.
                    display = DpDisplayOptions(width = shape.widthDp, height = shape.heightDp, dpi = PANEL_DPI),
                    style = PanelStyleOptions(themeResourceId = R.style.TheaterPanelTheme),
                )
            },
        ),
        // The controls dock (G.15): 680 dp across 0.4 m, android's and visionOS's size.
        ComposeViewPanelRegistration(
            R.id.theater_controls,
            composeViewCreator = { _, context -> ComposeView(context).apply { setContent { TheaterDockPanel(ui, this@TheaterActivity) } } },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = TheaterDock.WIDTH, height = TheaterDock.WIDTH * DOCK_DP_HEIGHT / DOCK_DP_WIDTH),
                    display = DpDisplayOptions(width = DOCK_DP_WIDTH, height = DOCK_DP_HEIGHT, dpi = PANEL_DPI),
                    style = PanelStyleOptions(themeResourceId = R.style.TheaterPanelTheme),
                )
            },
        ),
        // The seat panel to the dock's left (G.15), at the dock's scale.
        ComposeViewPanelRegistration(
            R.id.theater_seats,
            composeViewCreator = { _, context -> ComposeView(context).apply { setContent { TheaterSeatPanel(ui, this@TheaterActivity) } } },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = TheaterDock.SIDE_WIDTH, height = TheaterDock.SIDE_WIDTH * SEATS_DP_HEIGHT / SEATS_DP_WIDTH),
                    display = DpDisplayOptions(width = SEATS_DP_WIDTH, height = SEATS_DP_HEIGHT, dpi = PANEL_DPI),
                    style = PanelStyleOptions(themeResourceId = R.style.TheaterPanelTheme),
                )
            },
        ),
    )

    // ------------------------------------------------------------------
    // Seating, the dock, and placement (G.15)
    // ------------------------------------------------------------------

    /** Once a frame: seats the viewer, reads the loaded room, keeps its lights, and moves the dock after the head. */
    private inner class StageSystem : SystemBase() {
        override fun execute() {
            if (released) return
            val now = System.nanoTime()
            val deltaTime = if (lastFrameNanos == 0L) 1f / 90f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastFrameNanos = now

            val head = readHead()
            if (head == null) framesWithoutHead++ else framesWithoutHead = 0
            // Seated before tracking found the head: seat again as soon as it does.
            if (seatedWithoutHead && head != null) stage = null

            var seated = this@TheaterActivity.stage
            if (seated == null) {
                seated = head?.let(TheaterStaging::stage)
                    ?: if (head == null && framesWithoutHead > TheaterDock.HEAD_WAIT_FRAMES * 2) TheaterStaging.UNTRACKED_STAGE else null
                if (seated == null) return
                seatedWithoutHead = head == null
                Log.i(TAG, if (head != null) "seated from the head at ${head.position}, gaze ${head.forward}" else "seated without tracking after $framesWithoutHead frames")
                this@TheaterActivity.stage = seated
                follower = DockFollower()
                lastDock = null
                lastSide = null
                placeScene()
            }

            if (roomFile != null && !roomGaveUp && (roomMesh == null || importTurn == null)) pollRoom()
            roomMesh?.let { if (lightsApplied != ui.lights) applyLights(it) }

            // The follower works in the stage's frame, so the panels sit below the line of sight as the viewer lies.
            val (eye, yaw) = when {
                head != null -> TheaterStaging.dockInput(seated, head)
                follower.pose == null && framesWithoutHead > TheaterDock.HEAD_WAIT_FRAMES -> TheaterDock.RESTING_EYE to 0f
                else -> return
            }
            follower.update(yaw, eye, deltaTime)
            follower.pose?.let { pose ->
                if (pose != lastDock) {
                    lastDock = pose
                    dockEntity?.setComponents(listOf(Transform(TheaterStaging.sdkPanelPose(seated, pose).toPose()), Visible(true)))
                }
            }
            follower.sidePose?.let { pose ->
                if (pose != lastSide) {
                    lastSide = pose
                    seatsEntity?.setComponents(listOf(Transform(TheaterStaging.sdkPanelPose(seated, pose).toPose()), Visible(true)))
                }
            }
        }
    }

    /** The head from the local avatar, as Meta's samples read it; null until tracking reports a position (it starts at (0, 0, 0)). */
    private fun readHead(): HeadFrame? {
        val body = bodySystem ?: systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.also { bodySystem = it } ?: return null
        val pose = body.tryGetLocalPlayerAvatarBody()?.head?.tryGetComponent<Transform>()?.transform ?: return null
        val position = Vec3(pose.t.x, pose.t.y, pose.t.z)
        if (!position.isFinite || position.length < TRACKING_EPSILON) return null
        return TheaterFrames.head(position, Quat(pose.q.x, pose.q.y, pose.q.z, pose.q.w))
    }

    /**
     * Puts the room, the picture, and the subtitles where the stage, the
     * seat, the video's shape, and the room's import turn say. Nothing moves
     * until the viewer is seated.
     */
    private fun placeScene() {
        val stage = stage ?: return
        val file = roomFile
        // Until the item reports its shape (a new episode, say), keep the last one rather than fall back to 16:9 and back.
        val aspect = playback?.videoAspect ?: placedAspect
        placedAspect = aspect
        val placement = TheaterStaging.place(
            stage = stage,
            seat = TheaterStaging.seatPosition(file?.glb, ui.seatMap, settings.seat(room)),
            screen = TheaterStaging.screen(file?.glb),
            aspect = aspect,
            importTurn = importTurn ?: 0f,
        )
        if (file != null) {
            roomEntity?.setComponents(listOf(Transform(placement.sdkRoom.toPose()), Visible(roomShown && !blinking)))
        }
        val fit = Scale(Vector3(placement.pictureWidth / BASE_SCREEN_WIDTH, placement.pictureHeight / BASE_SCREEN_HEIGHT, 1f))
        screenEntity?.setComponents(listOf(Transform(placement.sdkPicture.toPose()), fit, Visible(!blinking)))
        val subtitles = subtitlesFor(placement.pictureAspect)
        val even = TheaterSubtitleShape.scale(placement.pictureWidth)
        subtitles?.setComponents(listOf(Transform(placement.sdkSubtitles.toPose()), Scale(Vector3(even, even, 1f)), Visible(!blinking)))
    }

    /**
     * The subtitle panel for a picture of [aspect]: laid out in that shape,
     * and made again when the picture changes shape, since a panel's layout
     * size is fixed when it is created (G.15).
     */
    private fun subtitlesFor(aspect: Float): Entity? {
        val current = subtitlesEntity
        if (current != null && TheaterSubtitleShape.fits(subtitleAspect, aspect)) return current
        subtitleAspect = aspect
        // The old view disposes its composition when the SDK detaches it (composeViewCreator).
        subtitleView = null
        current?.destroy()
        return Entity.create(listOf(Panel(R.id.theater_subtitles), Transform(), Visible(false))).also { subtitlesEntity = it }
    }

    private fun RoomPose.toPose() = Pose(
        Vector3(translation.x, translation.y, translation.z),
        Quaternion(rotation.w, rotation.x, rotation.y, rotation.z),
    )

    // ------------------------------------------------------------------
    // The dock's and the seat panel's actions
    // ------------------------------------------------------------------

    override fun togglePlayPause() {
        playback?.togglePlayPause()
    }

    override fun skip(forward: Boolean) {
        playback?.skip(forward)
    }

    override fun seekToFraction(fraction: Float) {
        playback?.seekToFraction(fraction)
    }

    override fun selectAudio(id: String) {
        playback?.selectAudio(id)
    }

    override fun selectSubtitles(id: String?) {
        playback?.selectSubtitles(id)
    }

    override fun skipSegment() {
        playback?.skipSegment()
    }

    override fun chooseRoom(room: TheaterRoom) = switchRoom(room)

    /** Sits the viewer in [seat] (null: the best seat), remembered for the room; the room blinks out and back as it moves. */
    override fun chooseSeat(seat: SeatID?) {
        val map = ui.seatMap ?: return
        val choice = TheaterStaging.storedChoice(map, seat)
        settings.setSeat(room, choice)
        ui.seatedSeat = TheaterStaging.seatedSeat(map, choice)
        // Sliding a whole room past someone sitting still makes them queasy: hide, move, show.
        mainHandler.removeCallbacks(endBlink)
        blinking = true
        placeScene()
        mainHandler.postDelayed(endBlink, SEAT_BLINK_MILLIS)
    }

    /** Re-seats the viewer where they look now, lying down included (android's recentre rule). */
    override fun recentre() {
        stage = null
    }

    override fun chooseLights(level: TheaterLights) {
        settings.lights = level
        // The next frame writes it to the room's materials (StageSystem).
        ui.lights = level
    }

    /**
     * Up Next (G.15): the next episode on this screen, in this room and seat,
     * after the same parental check as any title the theater didn't get from
     * the player. It starts where its stored progress left off, at the
     * viewer's speed, with the audio and subtitle languages on now.
     */
    override fun playUpNext() {
        if (upNextPending) return
        val entry = upcoming ?: return
        val current = playback ?: return
        val currentHandover = handover ?: return
        upNextPending = true
        // The languages on now, read as the 2D player reads them for its own handover.
        val tracks = current.player.currentTracks
        val audioLanguage = audioTrackOptions(tracks).firstOrNull { it.isSelected }?.language
        val textLanguage = textTrackOptions(tracks).firstOrNull { it.isSelected }?.language
        ui.upNext = null
        upcoming = null
        parentalScope.launch {
            val app = application as? EdendaleApplication
            val stored = entry.tmdbId?.takeIf { it > 0 }?.let { tmdbId ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        app?.let { LocalDataStore(it.database).getWatchProgress("${WatchMediaType.EPISODE.name.lowercase()}:$tmdbId") }
                    }.getOrNull()
                }
            }
            if (released || handover !== currentHandover) {
                upNextPending = false
                return@launch
            }
            val next = TheaterUpNextRules.handover(
                entry = entry,
                resumeMillis = TheaterUpNextRules.resumeMillis(stored?.watchedSeconds, PlayerLogic.resumeFraction(stored)),
                audioLanguage = audioLanguage,
                subtitlesOff = current.subtitlesOff,
                textLanguage = textLanguage,
                speed = current.chrome.baseRate,
            )
            admit(
                next,
                ticket = null,
                onAdmitted = {
                    upNextPending = false
                    playNext(next)
                },
                onRefused = {
                    upNextPending = false
                    refuse()
                },
            )
        }
    }

    /** Swaps the playing episode for [next]: the old one's choices and progress are saved, the new one plays on the same surface. */
    private fun playNext(next: TheaterHandover) {
        if (released || exiting) return
        val old = playback
        old?.saveContentPreferences()
        old?.writeProgress()
        old?.release()
        handover = next
        // As the 2D player's switchTo: a relaunch rebuilds from the intent, which now names this episode (and has no ticket).
        setIntent(next.intent(this, withTicket = false))
        ui.menu = null
        Log.i(TAG, "Up Next: playing the next episode in the theater")
        attach(TheaterPlayback(this, next.toPlayback()))
        begin(next)
        placeScene()
    }

    override fun exit() = exitTheater()

    // ------------------------------------------------------------------
    // Lifecycle and exit (G.6)
    // ------------------------------------------------------------------

    /** A recenter moves the origin; the next frame seats the viewer again where they look. */
    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        stage = null
    }

    /** Taking the headset off pauses; putting it on resumes (B.4's choice). */
    override fun onHMDUnmounted() {
        super.onHMDUnmounted()
        holdForTheSystem()
    }

    override fun onHMDMounted() {
        super.onHMDMounted()
        releaseTheSystemHold()
    }

    override fun onVRPause() {
        super.onVRPause()
        holdForTheSystem()
    }

    override fun onVRReady() {
        super.onVRReady()
        releaseTheSystemHold()
    }

    /** The Meta button's menu takes focus without pausing the activity (Meta's media sample). */
    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        when (state) {
            SessionState.VISIBLE -> holdForTheSystem()
            SessionState.FOCUSED -> releaseTheSystemHold()
            else -> Unit
        }
    }

    private fun holdForTheSystem() {
        systemHeld = true
        playback?.pauseForSystem()
    }

    private fun releaseTheSystemHold() {
        systemHeld = false
        playback?.resumeAfterSystem()
    }

    override fun onStart() {
        super.onStart()
        systemHeld = false
    }

    override fun onStop() {
        systemHeld = true
        // Only an admitted title has choices and progress to keep: a refused one never played (G.15).
        if (ui.admitted) playback?.onStop() else playback?.pauseForSystem()
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
        mainHandler.removeCallbacks(endBlink)
        if (ui.admitted) {
            playback?.saveContentPreferences()
            playback?.writeProgress()
            // The SDK releases the player before onStop on a finish, so 27.1 sync's flush (SYNC-PROTOCOL.md §8.1) happens here.
            (application as? EdendaleApplication)?.sync?.flushSoon()
        }
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
        mainHandler.removeCallbacks(endBlink)
        parentalScope.cancel()
        ui.playback = null
        playback?.release()
        playback = null
    }

    companion object {
        private const val TAG = "TheaterActivity"

        /** The saved state's handover (G.15): what plays now, where, and with no ticket. */
        private const val STATE_HANDOVER = "edendale.theater.state.handover"

        /** About three seconds at 90 fps for a room's mesh to load before it shows unturned. */
        private const val ROOM_FRAME_TIMEOUT_FRAMES = 270
        /** About thirty seconds: after this the room stops looking for its loaded nodes. */
        private const val ROOM_FRAME_GIVE_UP_FRAMES = 2_700
        /** A seat marker this far from where the import turn puts it means the SDK also mirrored the room left to right. */
        private const val MIRROR_CHECK_METRES = 0.1f
        /** Tracking reports the head at (0, 0, 0) until it finds it (Findings, 2026-10-25). */
        private const val TRACKING_EPSILON = 1e-4f

        /** About a second at 90 fps for a loaded room's materials to appear before the lights stop trying. */
        private const val LIGHTS_MISS_FRAMES = 90

        /** The unlit shader's base colour multiplier (the SDK's `MaterialUniform.albedoFactor`). */
        private const val ALBEDO_FACTOR = "albedoFactor"

        /** The registered quad is 16:9; a Scale component fits it to the video. */
        private const val BASE_SCREEN_WIDTH = 4f
        private const val BASE_SCREEN_HEIGHT = 2.25f

        private const val PANEL_DPI = 240
        /** android's dock and seat panel layouts (`TheaterStage`): 680 × 620 dp and 476 × 760 dp. */
        private const val DOCK_DP_WIDTH = 680f
        private const val DOCK_DP_HEIGHT = 620f
        private const val SEATS_DP_WIDTH = 476f
        private const val SEATS_DP_HEIGHT = 760f

        /** How long the room is hidden while the viewer moves seats (visionOS fades out 0.2 s). */
        private const val SEAT_BLINK_MILLIS = 200L
        private const val TICK_MILLIS = 500L

        /** Meta's hybrid-sample extra: Home starts this pending intent after it opens. */
        private const val EXTRA_LAUNCH_IN_HOME = "extra_launch_in_home_pending_intent"
    }
}
