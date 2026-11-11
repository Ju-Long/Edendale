package com.babasama.edendale.android.xr

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N.T1, `TheaterMath`: the handedness and composition order the theater's
 * rules lean on. A positive yaw turns to the left, `a * b` applies `b` first,
 * a pose composes as `this(first(p))`, and `fromColumns` reads back the
 * rotation whose columns it was given.
 */
class TheaterMathTest {

    private val quarter = (PI / 2).toFloat()

    @Test
    fun `a positive yaw turns ahead to the left`() {
        assertNear(Vec3(-1f, 0f, 0f), Quat.yaw(quarter).rotate(Vec3.FORWARD))
        assertNear(Vec3(1f, 0f, 0f), Quat.yaw(-quarter).rotate(Vec3.FORWARD))
        assertNear(Vec3.UP, Quat.yaw(1.3f).rotate(Vec3.UP))
        assertNear(Vec3(0f, 0f, -1f), Quat.yaw(quarter).rotate(Vec3(1f, 0f, 0f)))
        assertEquals(quarter, Quat.yaw(quarter).angle, 1e-5f)
    }

    @Test
    fun `axis angle is right handed`() {
        assertNear(Vec3(0f, 0f, 1f), Quat.axisAngle(Vec3(1f, 0f, 0f), quarter).rotate(Vec3.UP))
        assertNear(Vec3(0f, 1f, 0f), Quat.axisAngle(Vec3(0f, 0f, 1f), quarter).rotate(Vec3(1f, 0f, 0f)))
        assertEquals(quarter, Quat.axisAngle(Vec3(0f, 0f, 2f), quarter).angle, 1e-5f) // the axis is normalised
    }

    @Test
    fun `a product applies the right factor first`() {
        val a = Quat.yaw(0.7f)
        val b = Quat.axisAngle(Vec3(1f, 0f, 0f), -0.4f)
        val v = Vec3(0.3f, -1.2f, 2f)
        assertNear(a.rotate(b.rotate(v)), (a * b).rotate(v))
        assertNear(v, (a * a.inverse).rotate(v))
        assertTrue((a * a.inverse).sameRotationAs(Quat.IDENTITY))
    }

    @Test
    fun `a pose composes and undoes itself`() {
        val turn = RoomPose(Quat.yaw(quarter), Vec3(1f, 0f, 0f))
        val move = RoomPose(Quat.IDENTITY, Vec3(0f, 0f, -1f))
        val p = Vec3(2f, 0.5f, -3f)
        assertNear(turn.apply(move.apply(p)), turn.compose(move).apply(p))
        assertNear(Vec3(1f, 0f, 0f) + Quat.yaw(quarter).rotate(Vec3(0f, 0f, -1f)), turn.compose(move).translation)
        assertNear(p, turn.inverse.apply(turn.apply(p)))
        assertNear(p, turn.compose(turn.inverse).apply(p))
        assertNear(turn.applyDirection(Vec3.FORWARD), turn.apply(Vec3.FORWARD) - turn.apply(Vec3.ZERO))
        assertEquals(RoomPose.IDENTITY, RoomPose(Quat.IDENTITY, Vec3.ZERO))
    }

    @Test
    fun `from columns reads back the rotation it was given`() {
        val rotations = listOf(
            Quat.IDENTITY,
            Quat.yaw(0.5f),
            Quat.axisAngle(Vec3(1f, 1f, 0f), 2.1f),
            Quat.axisAngle(Vec3(1f, 0f, 0f), PI.toFloat()), // trace −1: the x branch
            Quat.axisAngle(Vec3(0f, 1f, 0f), PI.toFloat()), // the y branch
            Quat.axisAngle(Vec3(0f, 0f, 1f), 3f), // the z branch
            Quat.axisAngle(Vec3(0.2f, -0.7f, 0.4f), -2.8f),
        )
        for (q in rotations) {
            val back = Quat.fromColumns(q.rotate(Vec3(1f, 0f, 0f)), q.rotate(Vec3.UP), q.rotate(Vec3(0f, 0f, 1f)))
            assertTrue(back.sameRotationAs(q), "$q read back as $back")
        }
    }

    @Test
    fun `a head frame is the columns of its rotation`() {
        val q = Quat.axisAngle(Vec3(0f, 1f, 1f), 0.9f)
        val head = HeadFrame.of(Vec3(0f, 1.2f, 0f), q)
        assertNear(q.rotate(Vec3(1f, 0f, 0f)), head.right)
        assertNear(q.rotate(Vec3.UP), head.up)
        assertNear(q.rotate(Vec3(0f, 0f, 1f)), head.back)
        assertNear(-head.back, head.forward)
    }

    @Test
    fun `vectors keep their lengths and angles`() {
        val v = Vec3(3f, 0f, 4f)
        assertEquals(5f, v.length)
        assertNear(Vec3(0.6f, 0f, 0.8f), v.normalized())
        assertEquals(Vec3.ZERO, Vec3.ZERO.normalized())
        assertEquals(0f, Vec3(1f, 0f, 0f) dot Vec3.UP)
        assertNear(Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f) cross Vec3.UP)
        assertEquals(sqrt(2f), Vec3.distance(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)), 1e-6f)
        assertFalse(Vec3(Float.NaN, 0f, 0f).isFinite)
    }

    @Test
    fun `an angle gap takes the short way round`() {
        val degree = (PI / 180).toFloat()
        assertEquals(20f * degree, angleGap(-170f * degree, 170f * degree), 1e-5f)
        assertEquals(-20f * degree, angleGap(170f * degree, -170f * degree), 1e-5f)
        assertEquals(0.3f, angleGap(0.5f, 0.2f), 1e-6f)
    }

    private fun assertNear(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) {
        assertTrue(Vec3.distance(expected, actual) < tolerance, "expected $expected, got $actual")
    }
}
