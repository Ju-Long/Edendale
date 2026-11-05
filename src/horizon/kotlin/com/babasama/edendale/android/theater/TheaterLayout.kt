package com.babasama.edendale.android.theater

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A point in the theater's space: metres, Y up, in the Spatial SDK's frame
 * (X to the viewer's right, Z ahead of a viewer with yaw 0).
 */
data class Point3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Point3) = Point3(x + other.x, y + other.y, z + other.z)
    operator fun times(factor: Float) = Point3(x * factor, y * factor, z * factor)

    companion object {
        val ORIGIN = Point3(0f, 0f, 0f)
    }
}

/**
 * The head as tracking reports it: its position, and its yaw about +Y in
 * radians. Yaw 0 looks along +Z, the SDK's forward; [forward] and [right]
 * follow from it, pitch and roll are ignored so the screen stays level.
 */
data class HeadPose(val position: Point3, val yawRadians: Float) {
    val forward: Point3 get() = Point3(sin(yawRadians), 0f, cos(yawRadians))
    val right: Point3 get() = Point3(cos(yawRadians), 0f, -sin(yawRadians))

    companion object {
        /** The yaw of a forward vector that tracking has already rotated. */
        fun yawOf(forwardX: Float, forwardZ: Float): Float = atan2(forwardX, forwardZ)
    }
}

/**
 * Where the picture goes: its centre, its yaw, and its size in metres. The
 * yaw is the viewer's: a panel's +Z points along it, away from the viewer,
 * which is how the 2026-10-25 spike faced its screen on the Quest 3
 * (`Quaternion.lookRotation(forward)`), so its front is toward the viewer.
 */
data class ScreenPlacement(val center: Point3, val yawRadians: Float, val width: Float, val height: Float) {
    val aspectRatio: Float get() = width / height
}

/**
 * The whole arrangement: the seat (on the floor under the viewer) with the
 * viewer's yaw, the screen, and the room mesh's own transform: where its
 * origin goes and the yaw it turns by. The room's transform equals the
 * seat's for a room laid out the way the SDK is assumed to load it (its
 * `Seat` at the origin, its screen along +Z); a [RoomFrame] read from the
 * loaded mesh corrects it.
 */
data class TheaterPlacement(
    val root: Point3,
    val yawRadians: Float,
    val screen: ScreenPlacement,
    val roomOrigin: Point3 = root,
    val roomYawRadians: Float = yawRadians,
)

/**
 * `Seat` and `Screen` as the Spatial SDK loaded them: their positions in the
 * room mesh's own frame, read from the loaded mesh's node matrices. The SDK
 * converts glTF (right-handed, -Z ahead in these exports) into its
 * left-handed frame, and its documentation doesn't say which axis it turns;
 * with the frame read back, the room faces the viewer either way.
 */
data class RoomFrame(val seat: Point3, val screen: Point3) {
    /** Seat to screen, ignoring height. */
    val aheadX: Float get() = screen.x - seat.x
    val aheadZ: Float get() = screen.z - seat.z
}

/**
 * The theater's geometry as pure functions (HORIZON.md G.7, G.T1): where the
 * screen sits for a head pose and a video's aspect ratio, with or without an
 * authored scene. The activity turns the result into SDK transforms.
 */
object TheaterLayout {
    /** The spike's screen: 4 m wide, 5 m ahead, at head height (G.7). */
    const val DEFAULT_SCREEN_WIDTH_METRES = 4f
    const val DEFAULT_SCREEN_DISTANCE_METRES = 5f
    const val DEFAULT_ASPECT_RATIO = 16f / 9f

    /** The compositor layer's resolution for a panel in front of the screen (G.8): 1080 rows, the width from the aspect. */
    const val PANEL_HEIGHT_PX = 1080

    /**
     * Tracking reports (0, 0, 0) until the head is found (Findings,
     * 2026-10-25), so the first query must not place anything.
     */
    fun isTracking(head: HeadPose): Boolean =
        abs(head.position.x) > EPSILON || abs(head.position.y) > EPSILON || abs(head.position.z) > EPSILON

    /**
     * The largest picture with [aspectRatio] inside a screen [maxWidth] wide
     * and, when the scene says so, [maxHeight] tall: the width rules unless
     * the height overflows, so a 4:3 film is pillarboxed on a wide screen and
     * a 2.39:1 one is letterboxed on a 16:9 quad.
     */
    fun fitScreen(aspectRatio: Float?, maxWidth: Float, maxHeight: Float?): Pair<Float, Float> {
        val aspect = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT_RATIO
        var width = maxWidth
        var height = width / aspect
        if (maxHeight != null && height > maxHeight) {
            height = maxHeight
            width = height * aspect
        }
        return width to height
    }

    /** The compositor layer's pixel size for a panel that covers the screen. */
    fun panelPixels(aspectRatio: Float): Pair<Int, Int> =
        (PANEL_HEIGHT_PX * aspectRatio).toInt().coerceAtLeast(2) to PANEL_HEIGHT_PX

    /**
     * Places the room and the screen for [head]. The seat goes on the floor
     * under the head, turned to the head's yaw, so the room and the screen
     * are ahead of wherever the viewer faces when tracking starts or after a
     * recenter. With a [scene] the screen is where the authored `Screen`
     * node is, relative to `Seat`, [PICTURE_LIFT_METRES] toward the viewer;
     * without one it is the spike's default quad at head height. The screen
     * takes the viewer's yaw. With the [frame] the SDK loaded the room in,
     * the room turns so its screen is ahead of the viewer whichever way the
     * SDK's glTF import faced it, and the picture goes onto that screen.
     */
    fun place(head: HeadPose, scene: TheaterScene?, aspectRatio: Float?, frame: RoomFrame? = null): TheaterPlacement {
        val root = Point3(head.position.x, 0f, head.position.z)
        val forward = head.forward
        val right = head.right
        val (ahead, up, side) = if (scene != null) {
            Triple(scene.screen.ahead, scene.screen.up, scene.screen.right)
        } else {
            Triple(DEFAULT_SCREEN_DISTANCE_METRES, head.position.y, 0f)
        }
        val (width, height) = fitScreen(
            aspectRatio,
            maxWidth = scene?.screen?.width ?: DEFAULT_SCREEN_WIDTH_METRES,
            maxHeight = scene?.screen?.height,
        )
        // The picture sits just in front of an authored Screen mesh, so the two never z-fight.
        val lift = if (scene != null) forward * -PICTURE_LIFT_METRES else Point3.ORIGIN
        val center = root + forward * ahead + right * side + Point3(0f, up, 0f) + lift
        val usable = frame?.takeIf { scene != null && hypot(it.aheadX, it.aheadZ) >= MIN_SCREEN_DISTANCE_METRES }
            ?: return TheaterPlacement(root, head.yawRadians, ScreenPlacement(center, head.yawRadians, width, height))

        // Turn the room so its seat-to-screen line points where the viewer
        // looks, then move it so its Seat is on the floor under the head. The
        // picture goes where the loaded room's Screen is.
        val roomYaw = head.yawRadians - atan2(usable.aheadX, usable.aheadZ)
        val roomOrigin = root + rotateY(usable.seat, roomYaw) * -1f
        val onRoomScreen = roomOrigin + rotateY(usable.screen, roomYaw) + lift
        return TheaterPlacement(
            root,
            head.yawRadians,
            ScreenPlacement(onRoomScreen, head.yawRadians, width, height),
            roomOrigin,
            roomYaw,
        )
    }

    /**
     * The point [p] turned by [yawRadians] about +Y, the way the SDK's
     * `Quaternion.lookRotationAroundY` turns +Z onto a yaw's forward:
     * +Z goes to (sin, 0, cos) and +X to (cos, 0, -sin).
     */
    fun rotateY(p: Point3, yawRadians: Float): Point3 {
        val c = cos(yawRadians)
        val s = sin(yawRadians)
        return Point3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
    }

    /** Where a point of the room mesh ends up once the room has [placement]'s transform. */
    fun roomPointInWorld(placement: TheaterPlacement, p: Point3): Point3 =
        placement.roomOrigin + rotateY(p, placement.roomYawRadians)

    private const val EPSILON = 1e-4f

    /** A frame whose screen is nearer the seat than this is unusable, and the assumed one stays. */
    private const val MIN_SCREEN_DISTANCE_METRES = 0.5f

    /** How far in front of an authored Screen mesh the picture's panel sits. */
    const val PICTURE_LIFT_METRES = 0.02f
}
