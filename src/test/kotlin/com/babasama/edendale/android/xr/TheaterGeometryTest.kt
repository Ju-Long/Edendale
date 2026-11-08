package com.babasama.edendale.android.xr

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N.T1, the visionOS `TheaterLayoutTests` cases that need no scene file
 * (canvas, screen and seat, recentring, subtitles) ported one to one in the
 * frame of N-D6, plus the Quest `TheaterLayoutTest`'s picture-fit cases and
 * the three rooms' screen spans as data.
 */
class TheaterGeometryTest {

    private val degree = (PI / 180).toFloat()

    // MARK: - Canvas

    @Test
    fun `canvas takes the shape of the screen at the fixed row count`() {
        assertEquals(1440, TheaterGeometry.CANVAS_ROWS)
        assertEquals(2560 to 1440, TheaterGeometry.canvasSize(16f / 9f))
        assertEquals(3442 to 1440, TheaterGeometry.canvasSize(2.39f))
        assertEquals(1920 to 1440, TheaterGeometry.canvasSize(4f / 3f))
    }

    @Test
    fun `canvas sides are even`() {
        val (width, height) = TheaterGeometry.canvasSize(1.85f)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
        assertEquals(2664 to 1440, width to height)
    }

    @Test
    fun `canvas falls back to sixteen by nine without a screen shape`() {
        for (aspect in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(2560 to 1440, TheaterGeometry.canvasSize(aspect), "aspect $aspect")
        }
    }

    // MARK: - Picture fit (the Quest's rule, N-D12)

    @Test
    fun `a tall picture is pillarboxed inside the authored quad`() {
        val (width, height) = TheaterGeometry.fitPicture(4f / 3f, 4f, 2.25f)
        assertEquals(2.25f, height, 1e-5f)
        assertEquals(3f, width, 1e-5f)
    }

    @Test
    fun `a wide picture keeps the width and is letterboxed`() {
        val (width, height) = TheaterGeometry.fitPicture(2.39f, 4f, 2.25f)
        assertEquals(4f, width, 1e-5f)
        assertEquals(4f / 2.39f, height, 1e-5f)
    }

    @Test
    fun `without a height cap only the width is fixed`() {
        assertEquals(4f to 3f, TheaterGeometry.fitPicture(4f / 3f, 4f, null))
    }

    @Test
    fun `an unknown aspect ratio is sixteen by nine`() {
        for (aspect in listOf(null, 0f, Float.NaN)) {
            val (width, height) = TheaterGeometry.fitPicture(aspect, 4f, null)
            assertEquals(4f, width, 1e-5f, "aspect $aspect")
            assertEquals(2.25f, height, 1e-5f, "aspect $aspect")
        }
    }

    // MARK: - Screen and seat

    @Test
    fun `screen quad sits just in front of the screen`() {
        // The movie theatre's screen.
        val placement = assertNotNull(
            TheaterGeometry.screenPlacement(Vec3(-6f, 0.225f, -10.55f), Vec3(6f, 6.975f, -10.55f)),
        )
        assertEquals(12f, placement.width)
        assertEquals(6.75f, placement.height, 1e-4f)
        assertEquals(0f, placement.center.x)
        assertEquals(3.6f, placement.center.y, 1e-4f)
        assertEquals(-10.55f + TheaterGeometry.SCREEN_LIFT, placement.center.z)
        assertEquals(16f / 9f, placement.aspect, 1e-4f)
    }

    @Test
    fun `a screen without area gets no picture`() {
        assertNull(TheaterGeometry.screenPlacement(Vec3.ZERO, Vec3.ZERO))
        assertNull(TheaterGeometry.screenPlacement(Vec3.ZERO, Vec3(4f, 0f, 0f)))
        assertNull(TheaterGeometry.screenPlacement(Vec3(Float.NEGATIVE_INFINITY, 0f, 0f), Vec3(0f, 2f, 0f)))
    }

    @Test
    fun `room moves its seat onto the origin`() {
        val seat = Vec3(0.4f, 1.2f, -3f)
        val pose = TheaterGeometry.roomPose(seat, Vec3(0.4f, 3.6f, -13.55f))
        assertTrue(pose.apply(seat).length < 1e-4f)
        assertTrue(pose.rotation.angle < 1e-4f)
        assertTrue((pose.translation + seat).length < 1e-4f)
        assertNear(Vec3(-0.4f, -1.2f, 3f), pose.translation)
    }

    @Test
    fun `room turns an end seat to face the screen`() {
        val seat = Vec3(-5.4f, 1.2f, 3.45f)
        val screen = Vec3(0f, 3.6f, -10.55f)
        val pose = TheaterGeometry.roomPose(seat, screen)
        assertTrue(pose.apply(seat).length < 1e-4f)
        val ahead = pose.apply(screen)
        assertEquals(0f, ahead.x, 1e-4f)
        assertTrue(ahead.z < 0f)
        assertEquals(2.4f, ahead.y, 1e-4f) // the same height difference
        assertEquals(15.0053f, -ahead.z, 1e-4f) // the horizontal seat-to-screen distance (yaw −21.09°)
        assertEquals(1f, pose.rotation.rotate(Vec3.UP).y, 1e-4f) // the floor stays level
    }

    @Test
    fun `room only moves without a screen`() {
        val seat = Vec3(2f, 0.5f, 1f)
        val pose = TheaterGeometry.roomPose(seat, null)
        assertTrue(pose.rotation.angle < 1e-4f)
        assertTrue(pose.apply(seat).length < 1e-4f)
        // A screen directly above the seat gives no direction to face.
        val above = TheaterGeometry.roomPose(seat, Vec3(2f, 4f, 1f))
        assertTrue(above.rotation.angle < 1e-4f)
    }

    @Test
    fun `pose undoes itself`() {
        val pose = TheaterGeometry.roomPose(Vec3(-5.4f, 1.2f, 3.45f), Vec3(0f, 3.6f, -10.55f))
        val point = Vec3(1f, 2f, 3f)
        assertNear(point, pose.inverse.apply(pose.apply(point)))
        assertNear(point, RoomPose.IDENTITY.apply(point))
    }

    @Test
    fun `the screens of all three rooms span fifty to seventy degrees from the seat`() {
        // The rooms' Seat and Screen facts from the scenes exports (N-D9), as data: no GLB needed.
        val rooms = listOf(
            Triple("movie-theatre", Vec3(0f, 3.6f, -10.55f), 12f),
            Triple("projector-room", Vec3(0f, 2.725f, -5.478f), 5.6f),
            Triple("performance-theatre", Vec3(0f, 3.84375f, -9.25f), 11f),
        )
        for ((room, screenCenter, width) in rooms) {
            val ahead = TheaterGeometry.roomPose(Vec3.ZERO, screenCenter).apply(screenCenter)
            assertTrue(ahead.z < -3f && ahead.y > 1f, room)
            val span = 2f * atan(width / 2f / -ahead.z) / degree
            assertTrue(span > 50f && span < 70f, "$room spans $span degrees")
        }
    }

    // MARK: - Recentring

    /** A head frame from where the eyes are and the head's right and up, as the Swift tests build their 4×4. */
    private fun head(at: Vec3, right: Vec3, up: Vec3) = HeadFrame(at, right, up, right cross up)

    @Test
    fun `recentring upright only turns and keeps the floor`() {
        val pitch = -10f * degree
        val up = Vec3(-sin(pitch), cos(pitch), 0f)
        // Turned 90° to the right, looking a little down.
        val pose = assertNotNull(TheaterGeometry.recenterPose(head(Vec3(0.3f, 1.2f, -0.2f), Vec3(0f, 0f, 1f), up)))
        assertNear(Vec3(0.3f, 0f, -0.2f), pose.apply(Vec3.ZERO))
        assertNear(Vec3(1f, 0f, 0f), pose.rotation.rotate(Vec3.FORWARD))
        assertEquals(1f, pose.rotation.rotate(Vec3.UP).y, 1e-4f)
    }

    @Test
    fun `recentring lying back tilts the theater overhead`() {
        val eye = Vec3(0f, 0.5f, 0.4f)
        // On the back, looking at the ceiling.
        val pose = assertNotNull(TheaterGeometry.recenterPose(head(eye, Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f))))
        assertNear(eye, pose.apply(Vec3(0f, TheaterGeometry.Recenter.SEATED_EYE_HEIGHT, 0f)))
        assertNear(Vec3(0f, 1f, 0f), pose.rotation.rotate(Vec3.FORWARD))
        assertNear(Vec3(0f, 0f, 1f), pose.rotation.rotate(Vec3.UP))
        assertNear(Vec3(1f, 0f, 0f), pose.rotation.rotate(Vec3(1f, 0f, 0f)))
        assertNear(Vec3(0f, 0.5f, -0.8f), pose.translation)
        val screen = pose.apply(Vec3(0f, TheaterGeometry.Recenter.SEATED_EYE_HEIGHT, -10.55f))
        assertEquals(eye.x, screen.x, 1e-4f)
        assertEquals(eye.z, screen.z, 1e-4f)
        assertTrue(screen.y > eye.y + 10f)
        assertNear(Vec3(0f, 11.05f, 0.4f), screen, 1e-3f)
    }

    @Test
    fun `recentring reclined ignores a head tilted to one side`() {
        val recline = 40f * degree
        val tip = 15f * degree
        val eye = Vec3(0f, 0.9f, 0f)
        val level = head(eye, Vec3(1f, 0f, 0f), Vec3(0f, cos(recline), sin(recline)))
        // The same head tipped 15° about its own back axis.
        val tipped = HeadFrame.of(
            eye,
            Quat.fromColumns(level.right, level.up, level.back) * Quat.axisAngle(Vec3(0f, 0f, 1f), -tip),
        )
        val straight = assertNotNull(TheaterGeometry.recenterPose(level))
        val pose = assertNotNull(TheaterGeometry.recenterPose(tipped))
        assertTrue(straight.rotation.sameRotationAs(pose.rotation), "tip changed the pose: $straight vs $pose")
        assertEquals(recline, asin(pose.rotation.rotate(Vec3.FORWARD).y), 1e-4f)
    }

    @Test
    fun `recentring lying on a side keeps the theater upright`() {
        // On the right side, looking along −Z.
        val pose = assertNotNull(
            TheaterGeometry.recenterPose(head(Vec3(0f, 0.4f, 0f), Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f))),
        )
        assertNear(Vec3.UP, pose.rotation.rotate(Vec3.UP))
        assertNear(Vec3.FORWARD, pose.rotation.rotate(Vec3.FORWARD))
        assertTrue(pose.apply(Vec3.ZERO).length < 1e-4f)
    }

    @Test
    fun `recentring needs a usable head`() {
        val head = HeadFrame(Vec3(Float.NaN, 1f, 0f), Vec3(1f, 0f, 0f), Vec3.UP, Vec3(0f, 0f, 1f))
        assertNull(TheaterGeometry.recenterPose(head))
        assertNull(TheaterGeometry.recenterPose(HeadFrame(Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f), Vec3.UP, Vec3.ZERO)))
    }

    // MARK: - Subtitles

    @Test
    fun `subtitles are laid out in the shape of the screen`() {
        // The projector room's screen.
        val placement = assertNotNull(
            TheaterGeometry.screenPlacement(Vec3(-2.8f, 1.15f, -7.4f), Vec3(2.8f, 4.3f, -7.4f)),
        )
        assertEquals(1600 to 900, TheaterGeometry.subtitleCanvas(placement))
    }

    private fun assertNear(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) {
        assertTrue(Vec3.distance(expected, actual) < tolerance, "expected $expected, got $actual")
    }
}
