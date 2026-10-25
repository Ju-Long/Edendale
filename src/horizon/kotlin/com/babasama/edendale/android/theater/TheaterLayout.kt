package com.babasama.edendale.android.theater

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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

/** Where the picture goes: its centre, the yaw it faces back along, and its size in metres. */
data class ScreenPlacement(val center: Point3, val yawRadians: Float, val width: Float, val height: Float) {
    val aspectRatio: Float get() = width / height
}

/**
 * The whole arrangement: the scene's root (the seat, on the floor under the
 * viewer) with the yaw the room turns to, and the screen within it.
 */
data class TheaterPlacement(val root: Point3, val yawRadians: Float, val screen: ScreenPlacement)

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
     * node is, relative to `Seat`; without one it is the spike's default
     * quad at head height. The screen faces back along the viewer's yaw.
     */
    fun place(head: HeadPose, scene: TheaterScene?, aspectRatio: Float?): TheaterPlacement {
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
        val center = root + forward * ahead + right * side + Point3(0f, up, 0f)
        return TheaterPlacement(root, head.yawRadians, ScreenPlacement(center, head.yawRadians, width, height))
    }

    private const val EPSILON = 1e-4f
}
