package com.babasama.edendale.android.xr

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N.T1: the twelve controls-dock cases of the visionOS theater's
 * `TheaterLayoutTests`, so the panels sit and follow the head the same way on
 * every headset: where `TheaterDock.pose` puts a panel, the heading of a
 * gaze, and `DockFollower`'s dead zones, easing, and shortest turn, one
 * update per 90 Hz frame.
 */
class TheaterDockTest {

    private val dt = 1f / 90f
    private val tolerance = 1e-4f
    private val pi = Math.PI.toFloat()
    private val restingEye = Vec3(0f, 1.2f, 0f)

    private fun degrees(value: Double): Float = Math.toRadians(value).toFloat()

    /** A follower that has snapped to [yaw] and [eye] on its first frame. */
    private fun follower(yaw: Float = 0f, eye: Vec3 = restingEye) = DockFollower().apply { update(yaw, eye, dt) }

    private fun DockFollower.frames(count: Int, headYaw: Float?, eye: Vec3 = restingEye) =
        repeat(count) { update(headYaw, eye, dt) }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    @Test
    fun dockSitsFortyFiveDegreesBelowTheEyesStraightAhead() {
        val eye = Vec3(0.3f, 1.2f, -0.4f)
        val offset = TheaterDock.pose(eye, 0f).position - eye
        assertEquals(TheaterDock.DISTANCE, offset.length, tolerance)
        assertEquals(0f, offset.x, tolerance)
        assertTrue(offset.z < 0f, "offset $offset")
        assertEquals(offset.y, offset.z, tolerance)
        assertEquals(pi / 4f, atan2(-offset.y, -offset.z), tolerance)
        assertEquals(-0.4596f, offset.y, tolerance)
        assertEquals(-0.4596f, offset.z, tolerance)
    }

    @Test
    fun dockFacesTheEyes() {
        for (yaw in listOf(0f, 0.7f, -2.5f, pi)) {
            val pose = TheaterDock.pose(restingEye, yaw)
            val normal = pose.orientation.rotate(Vec3(0f, 0f, 1f))
            val toEye = (restingEye - pose.position).normalized()
            assertTrue((normal dot toEye) > 0.9999f, "yaw $yaw: front $normal, toward the eyes $toEye")
        }
    }

    @Test
    fun dockTurnsWithTheHeading() {
        val yaw = assertNotNull(TheaterDock.yaw(Vec3(1f, 0f, 0f)))
        assertEquals(-pi / 2f, yaw, tolerance)
        val pose = TheaterDock.pose(restingEye, yaw)
        assertTrue(pose.position.x > 0.4f, "position ${pose.position}")
        assertEquals(0f, pose.position.z, tolerance)
    }

    @Test
    fun headingIgnoresPitchButNotStraightDown() {
        val down45 = Vec3(0f, -1f / sqrt(2f), -1f / sqrt(2f))
        assertEquals(0f, assertNotNull(TheaterDock.yaw(down45)), tolerance)
        assertNull(TheaterDock.yaw(Vec3(0f, -1f, 0f)))
        assertNull(TheaterDock.yaw(Vec3(0.05f, -0.998f, 0f)))
    }

    // ------------------------------------------------------------------
    // Follower
    // ------------------------------------------------------------------

    @Test
    fun followerStartsWhereTheHeadIs() {
        val follower = DockFollower()
        assertNull(follower.pose)
        assertNull(follower.sidePose)
        val eye = Vec3(0f, 1.5f, 0f)
        follower.update(1f, eye, dt)
        assertEquals(TheaterDock.pose(eye, 1f), follower.pose)
        assertEquals(1f, follower.yaw)
        assertEquals(eye, follower.eye)
    }

    @Test
    fun smallTurnsAndLeansLeaveTheDockStill() {
        val follower = follower()
        // A 10° turn inside the 12° dead zone and a 0.141 m lean inside the 0.15 m one.
        follower.frames(180, degrees(10.0), Vec3(0f, 1.1f, -0.1f))
        assertEquals(0f, follower.yaw)
        assertEquals(restingEye, follower.eye)
        assertFalse(follower.isTurning)
        assertFalse(follower.isMoving)
    }

    @Test
    fun turningAroundBringsTheDockBackInFront() {
        val follower = follower()
        follower.frames(90, pi)
        assertEquals(pi, abs(assertNotNull(follower.yaw)), tolerance)
        assertFalse(follower.isTurning)
        // A 5° glance back is inside the dead zone and leaves it.
        follower.update(pi - degrees(5.0), restingEye, dt)
        assertEquals(pi, abs(assertNotNull(follower.yaw)), tolerance)
    }

    @Test
    fun dockTakesTheShortWayRound() {
        val follower = follower(yaw = degrees(-170.0))
        follower.update(degrees(170.0), restingEye, dt)
        val yaw = assertNotNull(follower.yaw)
        val heading = if (yaw < 0f) yaw + 2f * pi else yaw
        assertTrue(
            heading > degrees(170.0) && heading < degrees(190.0),
            "heading ${Math.toDegrees(heading.toDouble())}°: a 20° turn right, not 340° left",
        )
    }

    @Test
    fun standingUpLiftsTheDock() {
        val follower = follower()
        follower.frames(90, 0f, Vec3(0f, 1.65f, 0f))
        assertEquals(1.65f, assertNotNull(follower.eye).y, tolerance)
        assertFalse(follower.isMoving)
    }

    @Test
    fun lookingStraightDownKeepsTheHeading() {
        val follower = follower(yaw = 0.5f)
        follower.update(null, restingEye, dt)
        assertEquals(0.5f, follower.yaw)
    }

    @Test
    fun seatPanelSitsBesideTheControlsOnTheLeft() {
        val follower = follower()
        val dock = assertNotNull(follower.pose).position
        val side = assertNotNull(follower.sidePose).position
        assertTrue(side.x < dock.x - TheaterDock.WIDTH / 2f, "side $side, dock $dock")
        assertEquals(dock.y, side.y, tolerance)
        assertEquals(TheaterDock.DISTANCE, (side - restingEye).length, tolerance)
        // Clear of the controls' half width and its own, with 2° to spare.
        val radius = TheaterDock.DISTANCE * cos(TheaterDock.DECLINATION)
        val spans = atan(TheaterDock.WIDTH / 2f / radius) + atan(TheaterDock.SIDE_WIDTH / 2f / radius)
        assertTrue(TheaterDock.SIDE_TURN > spans + degrees(2.0), "sideTurn ${TheaterDock.SIDE_TURN}, spans $spans")
        assertEquals(0.80501f, TheaterDock.SIDE_TURN, tolerance)
        assertEquals(0f, dock.x, tolerance)
        assertEquals(0.7404f, dock.y, tolerance)
        assertEquals(-0.4596f, dock.z, tolerance)
        assertEquals(-0.3313f, side.x, tolerance)
        assertEquals(-0.3186f, side.z, tolerance)
    }

    @Test
    fun lookingLeftAtTheSeatPanelHoldsBothPanels() {
        val follower = follower()
        follower.frames(90, TheaterDock.SIDE_TURN)
        assertEquals(0f, follower.yaw)
        assertFalse(follower.isTurning)
        follower.frames(90, -TheaterDock.SIDE_TURN)
        assertEquals(-TheaterDock.SIDE_TURN, assertNotNull(follower.yaw), tolerance)
    }
}
