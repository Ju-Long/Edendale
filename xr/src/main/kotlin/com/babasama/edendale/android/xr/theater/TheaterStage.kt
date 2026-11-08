package com.babasama.edendale.android.xr.theater

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.Scene
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import com.babasama.edendale.android.xr.DockFollower
import com.babasama.edendale.android.xr.HeadFrame
import com.babasama.edendale.android.xr.Quat
import com.babasama.edendale.android.xr.RoomPose
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterDock
import com.babasama.edendale.android.xr.TheaterGeometry
import com.babasama.edendale.android.xr.TheaterGlb
import com.babasama.edendale.android.xr.TheaterPlayback
import com.babasama.edendale.android.xr.TheaterRoom
import com.babasama.edendale.android.xr.TheaterSeatMap
import com.babasama.edendale.android.xr.Vec3
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The 3D side of the theater (ENHANCEMENT.md N.6–N.9): the Jetpack XR session,
 * the room, the picture, the subtitle panel, the two floating panels, and the
 * head loop that seats the viewer and keeps the dock in front of them.
 *
 * Entities hang off three roots under the activity space:
 *
 *     stageRoot      the seating pose: the room's floor under the viewer, turned to the gaze
 *       roomRoot     the room's pose: the chosen seat on the stage origin, the screen straight ahead
 *         room model, picture, subtitles
 *       dock, seat panel    posed every frame from the head, in the stage's frame
 *
 * Every rule is the base's pure geometry; this class only converts frames.
 */
internal class TheaterStage(
    private val activity: Activity,
    private val playback: TheaterPlayback,
    private val preferences: TheaterPreferences,
    private val scope: CoroutineScope,
    private val ui: TheaterUiState,
    private val panels: TheaterPanelFactory,
    private val listener: Listener,
) {
    interface Listener {
        /** The session could not be created; the theater cannot open. */
        fun onUnavailable(reason: String)
    }

    private var session: Session? = null
    private var scene: Scene? = null
    private var arDevice: ArDevice? = null

    private var stageRoot: Entity? = null
    private var roomRoot: Entity? = null
    private var roomEntity: GltfModelEntity? = null
    private var roomModel: GltfModel? = null
    private var picture: SurfaceEntity? = null
    private var subtitles: PanelEntity? = null
    private var dock: PanelEntity? = null
    private var seatPanel: PanelEntity? = null

    private var entitiesReady = false
    private var seated = false
    private var framesWithoutHead = 0
    private var lastFrameNanos = 0L
    private var lastAspect: Float? = null
    private var follower = DockFollower()
    private var fadeJob: Job? = null
    private var released = false

    /** What the current room's GLB says; null while a room loads or when its file is unusable. */
    private var glb: TheaterGlb? = null
    private var screen: TheaterGeometry.ScreenPlacement? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released) return
            onFrame(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    suspend fun start() {
        val result = Session.create(activity as Context)
        if (result !is SessionCreateSuccess) {
            listener.onUnavailable(result::class.java.simpleName)
            return
        }
        val session = result.session
        this.session = session
        // Nothing tracks until the session is configured: the default Config
        // disables device tracking, and ArDevice refuses to exist without it.
        val configured = session.configure(Config(deviceTracking = DeviceTrackingMode.SPATIAL))
        if (configured !is SessionConfigureSuccess) {
            Log.w(TAG, "device tracking unavailable (${configured::class.java.simpleName}); the room is placed without the head")
        } else {
            arDevice = runCatching { ArDevice.getInstance(session) }
                .onFailure { Log.w(TAG, "no device tracking: ${it.message}") }
                .getOrNull()
        }
        val scene = session.scene
        this.scene = scene
        // Space changes (a recentre by the system) are ours to handle: the
        // viewer is re-seated from a fresh head sample instead of the
        // recommended pose, which is meant for a panel.
        scene.setSpaceChangedListener { if (!released) seated = false }
        scene.activitySpace.addOriginChangedListener(Runnable { if (!released) seated = false })
        scene.addSpatialCapabilitiesChangedListener { onCapabilities(it) }
        scene.requestFullSpace()
        onCapabilities(scene.spatialCapabilities)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onCapabilities(capabilities: Set<SpatialCapability>) {
        val scene = scene ?: return
        if (SpatialCapability.APP_ENVIRONMENT in capabilities || SpatialCapability.PASSTHROUGH_CONTROL in capabilities) {
            runCatching {
                // No skybox and no geometry is a black surround; the rooms are closed anyway.
                scene.spatialEnvironment.preferredSpatialEnvironment =
                    SpatialEnvironment.SpatialEnvironmentPreference(null, null)
                scene.spatialEnvironment.preferredPassthroughOpacity = 0f
            }.onFailure { Log.w(TAG, "environment preference refused: ${it.message}") }
        }
        if (!entitiesReady && SpatialCapability.SPATIAL_3D_CONTENT in capabilities) {
            entitiesReady = true
            scope.launch { createEntities() }
        }
    }

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    private suspend fun createEntities() {
        val session = session ?: return
        val scene = scene ?: return
        val stage = Entity.create(session, "theater-stage", Pose.Identity, scene.activitySpace)
        stage.setEnabled(false)
        stageRoot = stage
        roomRoot = Entity.create(session, "theater-room", Pose.Identity, stage)

        val density = activity.resources.displayMetrics.density
        dock = panels.create(session, pixels(DOCK_DP, density), "theater-dock", stage, panels.dockView())
        seatPanel = panels.create(session, pixels(SEAT_PANEL_DP, density), "theater-seats", stage, panels.seatPanelView())
        dock?.let { it.setScale(TheaterDock.WIDTH / it.size.width) }
        seatPanel?.let { it.setScale(TheaterDock.SIDE_WIDTH / it.size.width) }

        loadRoom(preferences.room)
    }

    /** Loads [room] and seats the viewer in it; the previous room goes away first. */
    private suspend fun loadRoom(room: TheaterRoom) {
        val session = session ?: return
        val roomRoot = roomRoot ?: return
        // SceneCore reclaims entities; detaching is how one leaves the scene.
        roomEntity?.let { runCatching { it.parent = null } }; roomEntity = null
        roomModel?.close(); roomModel = null
        glb = null; screen = null
        ui.room = room
        ui.seatMap = null

        val parsed = runCatching { activity.assets.open(room.assetPath).use { TheaterGlb.parse(it) } }
            .onFailure { Log.w(TAG, "${room.id}'s file is unusable (${it.message}); showing the picture alone") }
            .getOrNull()
        glb = parsed
        screen = parsed?.let { TheaterGeometry.screenPlacement(it.screenBoundsMin, it.screenBoundsMax) }
            ?: TheaterGeometry.ScreenPlacement(Vec3(0f, 1.2f, -DEFAULT_SCREEN_DISTANCE), DEFAULT_SCREEN_WIDTH, DEFAULT_SCREEN_WIDTH / TheaterGeometry.DEFAULT_ASPECT)
        screen?.let { ui.screenCenterX = it.center.x; ui.screenWidth = it.width }
        if (parsed != null) {
            ui.seatMap = TheaterSeatMap(parsed.seatMarkers, parsed.seat)
            val model = runCatching { GltfModel.create(session, Paths.get(room.assetPath)) }
                .onFailure { Log.w(TAG, "${room.id} didn't load (${it.message}); showing the picture alone") }
                .getOrNull()
            roomModel = model
            if (model != null) {
                val entity = GltfModelEntity.create(session, model, Pose.Identity, roomRoot)
                roomEntity = entity
                val seatNode = entity.nodes.firstOrNull { it.name == TheaterGlb.SEAT }?.modelPose?.translation
                val screenNode = entity.nodes.firstOrNull { it.name == TheaterGlb.SCREEN }?.modelPose?.translation
                Log.i(TAG, "${room.id} loaded with Seat at $seatNode and Screen at $screenNode; the file says seat ${parsed.seat}, screen ${screen?.center}")
            }
        }
        ensurePicture()
        applySeat(preferences.seat(room), animated = false)
        ui.seatedSeat = currentSeat()
    }

    /** The picture quad and the subtitle panel, made once and re-fitted per room and aspect. */
    private fun ensurePicture() {
        val session = session ?: return
        val roomRoot = roomRoot ?: return
        val screen = screen ?: return
        val size = fittedPictureSize()
        val center = Pose(screen.center.toVector3(), Quaternion.Identity)
        val surface = picture ?: SurfaceEntity.create(
            session,
            center,
            SurfaceEntity.Shape.Quad(FloatSize2d(size.first, size.second)),
            SurfaceEntity.StereoMode.MONO,
            SurfaceEntity.SuperSampling.PENTAGON,
            SurfaceEntity.SurfaceProtection.NONE,
            roomRoot,
        ).also {
            picture = it
            playback.player.setVideoSurface(it.getSurface())
        }
        surface.setPose(center)
        surface.shape = SurfaceEntity.Shape.Quad(FloatSize2d(size.first, size.second))

        val canvas = TheaterGeometry.subtitleCanvas(screen)
        val panel = subtitles ?: panels.create(session, IntSize2d(canvas.first, canvas.second), "theater-subtitles", roomRoot, panels.subtitleView())
            .also { subtitles = it }
        panel.setPose(Pose(Vector3(screen.center.x, screen.center.y, screen.center.z + SUBTITLE_LIFT), Quaternion.Identity))
        panel.setScale(screen.width / panel.size.width)
    }

    private fun fittedPictureSize(): Pair<Float, Float> {
        val screen = screen ?: return DEFAULT_SCREEN_WIDTH to DEFAULT_SCREEN_WIDTH / TheaterGeometry.DEFAULT_ASPECT
        return TheaterGeometry.fitPicture(playback.videoAspect, screen.width, screen.height)
    }

    // ------------------------------------------------------------------
    // Seats, rooms, recentre
    // ------------------------------------------------------------------

    private fun currentSeat(): SeatID? {
        val map = ui.seatMap ?: return null
        val chosen = preferences.seat(ui.room)
        return (chosen?.let { map.seat(it) } ?: map.best)?.id
    }

    /** Moves the room so [seat] (or the best seat, or the GLB's `Seat`) sits on the stage origin facing the screen. */
    private fun applySeat(seat: SeatID?, animated: Boolean) {
        val roomRoot = roomRoot ?: return
        val map = ui.seatMap
        val position = seat?.let { map?.seat(it)?.position } ?: map?.best?.position ?: glb?.seat ?: Vec3.ZERO
        val pose = TheaterGeometry.roomPose(position, screen?.center)
        if (!animated) {
            roomRoot.setPose(pose.toPose())
            return
        }
        // Sliding a whole room past someone sitting still makes them queasy:
        // fade out, move, fade in (0.2 s and 0.3 s, the visionOS timings).
        fadeJob?.cancel()
        fadeJob = scope.launch {
            fade(roomRoot, from = 1f, to = 0f, millis = 200)
            roomRoot.setPose(pose.toPose())
            fade(roomRoot, from = 0f, to = 1f, millis = 300)
        }
    }

    private suspend fun fade(entity: Entity, from: Float, to: Float, millis: Long) {
        val steps = (millis / 10).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            if (released) return
            entity.setAlpha(from + (to - from) * i / steps)
            delay(10)
        }
    }

    fun chooseSeat(seat: SeatID?) {
        val map = ui.seatMap ?: return
        val choice = if (seat == null || seat == map.best?.id) null else seat
        preferences.setSeat(ui.room, choice)
        ui.seatedSeat = currentSeat()
        applySeat(choice, animated = true)
    }

    fun chooseRoom(room: TheaterRoom) {
        if (room == ui.room) return
        preferences.room = room
        scope.launch { loadRoom(room) }
    }

    /** Re-seats the viewer where they are looking now, lying down included. */
    fun recentre() {
        seated = false
    }

    // ------------------------------------------------------------------
    // The frame loop: head, seating, dock
    // ------------------------------------------------------------------

    private fun onFrame(frameTimeNanos: Long) {
        val stage = stageRoot ?: return
        val scene = scene ?: return
        val deltaTime = if (lastFrameNanos == 0L) 1f / 90f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos

        refit()

        val head = trackedHead(scene)
        if (head == null) framesWithoutHead++ else framesWithoutHead = 0

        if (!seated) {
            val pose = head?.let { TheaterStageRules.seatingPose(it) }
                ?: if (framesWithoutHead > TheaterDock.HEAD_WAIT_FRAMES * 2) TheaterStageRules.UNTRACKED_SEATING else null
            if (pose != null) {
                stage.setPose(pose.toPose())
                stage.setEnabled(true)
                follower = DockFollower()
                seated = true
                // The main panel only held the "look down" hint; the dock is up now.
                runCatching { scene.mainPanelEntity.setEnabled(false) }
            } else {
                return
            }
        }

        // The follower works in the stage's frame, so the panels sit below the
        // line of sight as the viewer lies, not as the room stands.
        val stagePose = stage.getPose().toRoomPose()
        val eye: Vec3
        val yaw: Float?
        if (head != null) {
            val local = stagePose.inverse
            eye = local.apply(head.position)
            yaw = TheaterDock.yaw(local.applyDirection(head.forward))
        } else if (follower.pose == null && framesWithoutHead > TheaterDock.HEAD_WAIT_FRAMES) {
            eye = TheaterDock.RESTING_EYE
            yaw = 0f
        } else {
            return
        }
        follower.update(yaw, eye, deltaTime)
        follower.pose?.let { dock?.setPose(Pose(it.position.toVector3(), it.orientation.toQuaternion())) }
        follower.sidePose?.let { seatPanel?.setPose(Pose(it.position.toVector3(), it.orientation.toQuaternion())) }
        seatPanel?.setEnabled(ui.seatMap?.seats?.isNotEmpty() == true)
    }

    /** The head in the activity space, or null until tracking has it. */
    private fun trackedHead(scene: Scene): HeadFrame? {
        val device = arDevice ?: return null
        val state = device.state.value
        if (state.trackingState != TrackingState.TRACKING) return null
        val pose = scene.perceptionSpace.transformPoseTo(state.devicePose, scene.activitySpace)
        val frame = pose.toHeadFrame()
        return frame.takeIf { it.position.isFinite && it.forward.isFinite && it.position.length > 1e-4f }
    }

    private fun refit() {
        val aspect = playback.videoAspect
        if (aspect != lastAspect && picture != null) {
            lastAspect = aspect
            ensurePicture()
        }
    }

    fun release() {
        if (released) return
        released = true
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        fadeJob?.cancel()
        runCatching { playback.player.setVideoSurface(null) }
        listOf(dock, seatPanel, subtitles, picture, roomEntity, roomRoot, stageRoot).forEach { entity ->
            runCatching { entity?.parent = null }
        }
        runCatching { roomModel?.close() }
        dock = null; seatPanel = null; subtitles = null; picture = null; roomEntity = null; roomRoot = null; stageRoot = null
        runCatching { scene?.requestHomeSpace() }
    }

    companion object {
        private const val TAG = "XrTheater"

        /** The subtitle panel floats this far in front of the picture. */
        private const val SUBTITLE_LIFT = 0.02f

        /** Without a usable room: the Quest's quad, 4 m wide and 5 m ahead. */
        private const val DEFAULT_SCREEN_WIDTH = 4f
        private const val DEFAULT_SCREEN_DISTANCE = 5f

        /** 680 dp across the 0.4 m dock, as the visionOS panel's 680 pt; the height holds a menu or the Up Next card. */
        private val DOCK_DP = 680 to 620
        private val SEAT_PANEL_DP = 476 to 760

        private fun pixels(dp: Pair<Int, Int>, density: Float) =
            IntSize2d((dp.first * density).toInt(), (dp.second * density).toInt())
    }
}

/** Builds the panels' views and entities; separated so the stage stays about geometry. */
internal interface TheaterPanelFactory {
    fun dockView(): View
    fun seatPanelView(): View
    fun subtitleView(): View
    fun create(session: Session, pixels: IntSize2d, name: String, parent: Entity, view: View): PanelEntity
}

/** The default factory: a view hosted in a panel needs the activity's tree owners, which SceneCore does not set. */
internal class ActivityPanelFactory(
    private val activity: androidx.activity.ComponentActivity,
    private val dock: () -> View,
    private val seats: () -> View,
    private val subtitles: () -> View,
) : TheaterPanelFactory {
    override fun dockView(): View = owned(dock())
    override fun seatPanelView(): View = owned(seats())
    override fun subtitleView(): View = owned(subtitles())

    private fun owned(view: View): View = view.apply {
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
    }

    override fun create(session: Session, pixels: IntSize2d, name: String, parent: Entity, view: View): PanelEntity =
        PanelEntity.create(session, view, pixels, name, Pose.Identity, parent)
}

/** The stage's own small rules, kept pure for the tests. */
internal object TheaterStageRules {
    /**
     * Where the stage goes for a head: `recenterPose`, except that a level
     * viewer's seat floor is 1.2 m under the eyes rather than at the activity
     * space's y = 0, whose height Android XR does not promise to be the floor.
     */
    fun seatingPose(head: HeadFrame): RoomPose? {
        val pose = TheaterGeometry.recenterPose(head) ?: return null
        val up = pose.rotation.rotate(Vec3.UP)
        val levelled = up.y > 0.9999f
        return if (levelled) {
            RoomPose(pose.rotation, Vec3(head.position.x, head.position.y - TheaterGeometry.Recenter.SEATED_EYE_HEIGHT, head.position.z))
        } else {
            pose
        }
    }

    /** When tracking never reports: the seat 1.2 m under the activity space's origin, facing −Z. */
    val UNTRACKED_SEATING = RoomPose(Quat.IDENTITY, Vec3(0f, -TheaterGeometry.Recenter.SEATED_EYE_HEIGHT, 0f))
}

// Frame conversions between the base's pure math and Jetpack XR's.

private fun Vec3.toVector3() = Vector3(x, y, z)
private fun Quat.toQuaternion() = Quaternion(x, y, z, w)
private fun RoomPose.toPose() = Pose(translation.toVector3(), rotation.toQuaternion())
private fun Pose.toRoomPose() = RoomPose(Quat(rotation.x, rotation.y, rotation.z, rotation.w), Vec3(translation.x, translation.y, translation.z))
private fun Pose.toHeadFrame() = HeadFrame.of(
    Vec3(translation.x, translation.y, translation.z),
    Quat(rotation.x, rotation.y, rotation.z, rotation.w),
)
