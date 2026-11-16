package com.babasama.edendale.android.xr

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Where the picture, the room, and the viewer go (ENHANCEMENT.md N.3): the
 * visionOS theater's placement rules in pure Kotlin, so the activity in the
 * `:xr` module only applies poses it never computes and the JVM suite checks
 * every number. The frame is [TheaterMath]'s: metres, +Y up, the viewer's
 * ahead is −Z, a positive yaw turns to the left.
 */
object TheaterGeometry {
    /** The picture sits this far in front of the room's Screen mesh, toward the seat (+Z), so it cannot z-fight. */
    const val SCREEN_LIFT = 0.005f

    /**
     * Rows of the picture canvas. From its seat each room's screen spans
     * 54–62°, about 2,000–2,300 display pixels on a headset, so 1440 rows is
     * close to one texel per pixel without a mipmap chain.
     */
    const val CANVAS_ROWS = 1440

    /** What stands in for a video shape that is unknown or nonsense. */
    val DEFAULT_ASPECT = 16f / 9f

    /** The subtitle panel is laid out at this many pixels across, whatever the screen's size in metres. */
    private const val SUBTITLE_CANVAS_WIDTH = 1600

    /** The Screen mesh's room-space frame for the picture: its centre, lifted off the mesh, and its size in metres. */
    data class ScreenPlacement(val center: Vec3, val width: Float, val height: Float) {
        val aspect: Float get() = width / height
    }

    /**
     * The picture's frame from the Screen mesh's room-space bounds, or null
     * when the mesh has no area (a side at or under 1 cm, or a non-finite
     * extent), in which case the room loads without a picture. The centre is
     * lifted [SCREEN_LIFT] toward the seat.
     */
    fun screenPlacement(boundsMin: Vec3, boundsMax: Vec3): ScreenPlacement? {
        val size = boundsMax - boundsMin
        if (!(size.x > 0.01f && size.y > 0.01f && size.x.isFinite() && size.y.isFinite())) return null
        val middle = (boundsMin + boundsMax) * 0.5f
        return ScreenPlacement(
            center = Vec3(middle.x, middle.y, boundsMax.z + SCREEN_LIFT),
            width = size.x,
            height = size.y,
        )
    }

    /**
     * Fits a video of [aspect] inside a screen quad: the width rules unless
     * the height overflows, so 4:3 is pillarboxed on a wide screen and 2.39:1
     * is letterboxed on a 16:9 one. A null, non-finite, or non-positive
     * aspect is taken as 16:9; a null [maxHeight] caps nothing. Returns the
     * fitted width and height.
     */
    fun fitPicture(aspect: Float?, maxWidth: Float, maxHeight: Float?): Pair<Float, Float> {
        val shape = aspect?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT
        var width = maxWidth
        var height = width / shape
        if (maxHeight != null && height > maxHeight) {
            height = maxHeight
            width = height * shape
        }
        return width to height
    }

    /**
     * The picture canvas in pixels: [CANVAS_ROWS] rows and the width that
     * gives the screen's shape, made even because video planes want even
     * sides. A non-finite or non-positive aspect falls back to 16:9.
     */
    fun canvasSize(aspect: Float): Pair<Int, Int> {
        val shape = if (aspect.isFinite() && aspect > 0f) aspect else DEFAULT_ASPECT
        return even(CANVAS_ROWS * shape.toDouble()) to CANVAS_ROWS
    }

    /** The subtitle panel's pixel box, in the picture's shape so cues sit where they do on a flat screen. */
    fun subtitleCanvas(placement: ScreenPlacement): Pair<Int, Int> =
        SUBTITLE_CANVAS_WIDTH to (SUBTITLE_CANVAS_WIDTH / placement.aspect.toDouble()).roundToInt()

    /**
     * The room's pose that puts [seat] on the origin (the floor under the
     * viewer) and turns the room about +Y only, so [screenCenter] is straight
     * ahead at the same height difference and distance while the floor stays
     * level. Without a screen, or with one within 1 cm horizontally of the
     * seat (no direction to face), the room only moves.
     */
    fun roomPose(seat: Vec3, screenCenter: Vec3?): RoomPose {
        var yaw = 0f
        if (screenCenter != null) {
            val aheadX = screenCenter.x - seat.x
            val aheadZ = screenCenter.z - seat.z
            if (aheadX.isFinite() && aheadZ.isFinite() && hypot(aheadX, aheadZ) > 0.01f) {
                // The turn about +Y from −Z that faces the screen; the room is turned back by it.
                yaw = atan2(-aheadX, -aheadZ)
            }
        }
        val rotation = Quat.yaw(-yaw)
        return RoomPose(rotation, -rotation.rotate(seat))
    }

    /** Recentring thresholds, shared with the panel copy that explains them. */
    object Recenter {
        /** A gaze within this pitch of level is a seated or standing viewer: the floor stays on the floor. */
        val LEVEL_PITCH = Math.toRadians(20.0).toFloat()

        /** Where a seated viewer's eyes are above the seat's floor point, so a tilted room meets a lying viewer's eyes. */
        const val SEATED_EYE_HEIGHT = 1.2f

        /** `sin 15°`: a gaze closer than this to straight up or down carries no left/right, so the head's own right decides. */
        const val NEAR_VERTICAL = 0.26f
    }

    /**
     * The pose that puts the screen where the viewer is looking. Within
     * [Recenter.LEVEL_PITCH] of level only the heading changes and the seat's
     * floor point lands under the eyes on the real floor; lying back or
     * reclined, the whole room tilts with the viewer and the seat's eye point
     * lands on the real eyes, because the platform will not tilt the space
     * for a viewer lying down. A side tilt of the head never tilts the room.
     * Null for a head that cannot be used (non-finite, or a gaze with no
     * usable right).
     */
    fun recenterPose(head: HeadFrame): RoomPose? {
        val eye = head.position
        val gaze = head.forward
        if (!eye.isFinite || !gaze.isFinite || !(gaze.length > 0f)) return null
        val forward = gaze.normalized()
        // Level and square to the gaze, whatever the head's side tilt.
        var right = forward cross Vec3.UP
        if (right.length < Recenter.NEAR_VERTICAL) {
            // Looking nearly straight up or down: the head's own right, made level, says which way the viewer lies.
            right = Vec3(head.right.x, 0f, head.right.z)
        }
        if (!(right.length > 0.1f)) return null
        right = right.normalized()
        val ahead = (forward - right * (forward dot right)).normalized()
        val pitch = asin(ahead.y.coerceIn(-1f, 1f))
        return if (abs(pitch) < Recenter.LEVEL_PITCH) {
            val level = Vec3.UP cross right
            RoomPose(Quat.yaw(atan2(-level.x, -level.z)), Vec3(eye.x, 0f, eye.z))
        } else {
            val back = -ahead
            val up = back cross right
            val rotation = Quat.fromColumns(right, up, back)
            RoomPose(rotation, eye - rotation.rotate(Vec3(0f, Recenter.SEATED_EYE_HEIGHT, 0f)))
        }
    }

    /** Rounds to the nearest integer and then up to an even one. */
    private fun even(value: Double): Int {
        val rounded = value.roundToInt()
        return if (rounded % 2 == 0) rounded else rounded + 1
    }
}
