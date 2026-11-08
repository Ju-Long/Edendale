package com.babasama.edendale.android.xr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/*
 * The immersive theater's controls dock (ENHANCEMENT.md Section N, decision
 * N-D11): where the controls and seat panels sit around the viewer and how
 * they follow the head. Pure Kotlin on TheaterMath, so the visionOS theater's
 * dock cases port one to one into the hermetic JVM suite; the :xr module only
 * reads the poses and places its panel entities.
 */

/** Where a floating panel sits and how it is turned (its +Z toward the eyes). */
data class DockPose(val position: Vec3, val orientation: Quat)

/**
 * The dock's geometry: a remote held in the lap, 0.65 m from the eyes and 45°
 * below them, so it never crosses the line of sight to the screen and needs no
 * hiding. Both panels sit on one horizontal ring around the eyes, the seat
 * panel a fixed turn to the left of the controls.
 */
object TheaterDock {
    /** Eyes to panel centre, in metres. */
    const val DISTANCE = 0.65f

    /** How far below eye level the panels sit. */
    val DECLINATION = (Math.PI / 4).toFloat()

    /** The controls panel's width in the room. */
    const val WIDTH = 0.4f

    /** Head turns smaller than this leave the panels still. */
    val TURN_DEAD_ZONE = Math.toRadians(12.0).toFloat()

    /** The seat panel's width, at the controls' scale so its text is the same size. */
    const val SIDE_WIDTH = 0.28f

    /** The gap between the two panels. */
    const val SIDE_GAP = 0.03f

    /**
     * How far left (a positive yaw) of the controls the seat panel's centre
     * sits, as a turn about the eyes: the two half widths and the gap, laid
     * along the panels' ring of radius `DISTANCE · cos DECLINATION`, about
     * 0.80501 rad (46.1°).
     */
    val SIDE_TURN: Float = (WIDTH / 2f + SIDE_GAP + SIDE_WIDTH / 2f) / (DISTANCE * cos(DECLINATION))

    /** Head moves smaller than this leave the panels still, in metres. */
    const val MOVE_DEAD_ZONE = 0.15f

    /** The catch-up rate per second: a 125 ms time constant, settled in about half a second. */
    const val FOLLOW_RATE = 8f

    /** Layout points per metre: 680 pt across the 0.4 m controls panel, the seat panel at the same scale. */
    const val POINTS_PER_METRE = 1700f

    /** The eyes of someone seated in the room's seat facing the screen, for when the head can't be tracked. */
    val RESTING_EYE = Vec3(0f, 1.2f, 0f)

    /** Frames (about half a second at 90 Hz) to wait for head tracking before resting the panels there. */
    const val HEAD_WAIT_FRAMES = 45

    /**
     * The heading of a gaze, `atan2(−forward.x, −forward.z)`: 0 facing −Z,
     * −π/2 facing +X, and pitch ignored. Null when the horizontal part is
     * shorter than 0.2 (within about 11.5° of straight up or down), where a
     * heading would swing with the slightest head movement.
     */
    fun yaw(facing: Vec3): Float? {
        val horizontal = Vec3(facing.x, 0f, facing.z)
        if (!horizontal.isFinite || horizontal.length <= 0.2f) return null
        return atan2(-facing.x, -facing.z)
    }

    /**
     * The panel [DISTANCE] from [eye] at [yaw], tilted so its front (+Z in its
     * own space) points at the eyes: `position = eye + turn·(0, −sin δ, −cos δ)·DISTANCE`
     * and `orientation = turn × tilt`, with `turn = Quat.yaw(yaw)` and
     * `tilt = Quat.axisAngle(+X, −δ)`.
     */
    fun pose(eye: Vec3, yaw: Float): DockPose {
        val turn = Quat.yaw(yaw)
        val offset = Vec3(0f, -sin(DECLINATION), -cos(DECLINATION)) * DISTANCE
        val tilt = Quat.axisAngle(Vec3(1f, 0f, 0f), -DECLINATION)
        return DockPose(position = eye + turn.rotate(offset), orientation = turn * tilt)
    }
}

/**
 * Eases the panels after the head only past the dead zones, so small turns
 * and leans leave them still, as a remote in the lap would be. Heading and
 * position are followed independently, and once a move starts it goes on
 * until the panels have caught up, so they never stop part way.
 */
class DockFollower {
    /** The heading the panels sit at; null until the first usable heading. */
    var yaw: Float? = null
        private set

    /** The eyes the panels sit around; null until the first update. */
    var eye: Vec3? = null
        private set

    var isTurning: Boolean = false
        private set

    var isMoving: Boolean = false
        private set

    /** The controls panel; null until both the heading and the eyes are known. */
    val pose: DockPose?
        get() {
            val heading = yaw ?: return null
            val eyes = eye ?: return null
            return TheaterDock.pose(eyes, heading)
        }

    /** The seat panel, [TheaterDock.SIDE_TURN] to the left of the controls. */
    val sidePose: DockPose?
        get() {
            val heading = yaw ?: return null
            val eyes = eye ?: return null
            return TheaterDock.pose(eyes, heading + TheaterDock.SIDE_TURN)
        }

    /**
     * One call per rendered frame. A null [headYaw] (the gaze near vertical)
     * leaves the heading as it is; the position is followed regardless.
     */
    fun update(headYaw: Float?, eye: Vec3, deltaTime: Float) {
        // The same fraction of the remaining gap per second whatever the frame
        // rate, clamped so a long frame never overshoots.
        val step = (1f - exp(-TheaterDock.FOLLOW_RATE * max(deltaTime, 0f))).coerceIn(0f, 1f)
        if (headYaw != null) turn(headYaw, step)
        val from = this.eye
        if (from == null) {
            this.eye = eye
            return
        }
        val gap = eye - from
        if (gap.length > TheaterDock.MOVE_DEAD_ZONE) isMoving = true
        if (isMoving) {
            if (gap.length <= SETTLED_MOVE) {
                this.eye = eye
                isMoving = false
            } else {
                this.eye = from + gap * step
            }
        }
    }

    private fun turn(headYaw: Float, step: Float) {
        val from = yaw
        if (from == null) {
            yaw = headYaw
            return
        }
        // The shortest way round, positive when the head turned left.
        val gap = angleGap(headYaw, from)
        // Asymmetric: the seat panel sits to the left, so looking at it must
        // not drag both panels along.
        if (gap > TheaterDock.TURN_DEAD_ZONE + TheaterDock.SIDE_TURN || gap < -TheaterDock.TURN_DEAD_ZONE) {
            isTurning = true
        }
        if (isTurning) {
            if (abs(gap) <= SETTLED_TURN) {
                yaw = headYaw
                isTurning = false
            } else {
                yaw = angleGap(from + gap * step, 0f)
            }
        }
    }

    private companion object {
        /** Within this the panels snap and stop, so the ease never trails off into a shimmer. */
        val SETTLED_TURN = Math.toRadians(1.0).toFloat()
        const val SETTLED_MOVE = 0.005f
    }
}
