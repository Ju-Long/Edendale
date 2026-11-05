package com.babasama.edendale.android.theater

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HORIZON.md G.T1: the screen's size and pose as a function of the head pose and the video's aspect ratio. */
class TheaterLayoutTest {

    private val seated = HeadPose(Point3(0f, 1.2f, 0f), yawRadians = 0f)

    @Test
    fun theOriginMeansTrackingHasNotStarted() {
        assertFalse(TheaterLayout.isTracking(HeadPose(Point3.ORIGIN, 0f)))
        assertTrue(TheaterLayout.isTracking(HeadPose(Point3(0f, 1.6f, 0f), 0f)))
        assertTrue(TheaterLayout.isTracking(HeadPose(Point3(0.3f, 0f, 0f), 1f)))
    }

    @Test
    fun withoutASceneTheScreenIsTheSpikesQuadAheadAtHeadHeight() {
        val placement = TheaterLayout.place(seated, scene = null, aspectRatio = 16f / 9f)
        assertEquals(Point3(0f, 0f, 0f), placement.root)
        assertPoint(Point3(0f, 1.2f, 5f), placement.screen.center)
        assertEquals(4f, placement.screen.width)
        assertEquals(2.25f, placement.screen.height)
        assertEquals(0f, placement.screen.yawRadians)
    }

    @Test
    fun theScreenFollowsTheHeadsYawAndPosition() {
        // Facing +X from (1, 1.5, 2): five metres ahead is (6, 1.5, 2).
        val head = HeadPose(Point3(1f, 1.5f, 2f), yawRadians = (PI / 2).toFloat())
        val placement = TheaterLayout.place(head, scene = null, aspectRatio = 16f / 9f)
        assertPoint(Point3(1f, 0f, 2f), placement.root)
        assertPoint(Point3(6f, 1.5f, 2f), placement.screen.center)
        assertEquals(head.yawRadians, placement.screen.yawRadians)
        assertEquals(head.yawRadians, placement.yawRadians)
    }

    @Test
    fun aSceneBuildsTheScreenWhereTheAuthorPutItRelativeToTheSeat() {
        val scene = TheaterScene(ScreenNode(ahead = 5f, up = 1.5f, right = 0.5f, width = 4f, height = 2.25f))
        val placement = TheaterLayout.place(seated, scene, aspectRatio = 16f / 9f)
        // The seat goes under the head on the floor; the screen is 0.5 m to the right of it,
        // the picture 2 cm in front of the authored quad.
        assertPoint(Point3(0.5f, 1.5f, 5f - TheaterLayout.PICTURE_LIFT_METRES), placement.screen.center)
        assertEquals(4f, placement.screen.width)
        assertEquals(2.25f, placement.screen.height)

        // Turned to face -X, "right" is +Z... and "ahead" is -X.
        val turned = HeadPose(Point3(0f, 1.2f, 0f), yawRadians = (-PI / 2).toFloat())
        val turnedPlacement = TheaterLayout.place(turned, scene, aspectRatio = 16f / 9f)
        assertPoint(Point3(-5f + TheaterLayout.PICTURE_LIFT_METRES, 1.5f, 0.5f), turnedPlacement.screen.center)
    }

    // G.14: the room turns by the Seat and Screen the SDK loaded, whichever axis its glTF import turned.

    private val cinema = TheaterScene(ScreenNode(ahead = 14f, up = 2.4f, right = 0f, width = 12f, height = 6.75f))

    @Test
    fun aRoomLoadedWithItsScreenAlongPlusZKeepsTheViewersYaw() {
        // The SDK flipped glTF's Z: the screen, 14 m along -Z in the GLB, is along +Z.
        val frame = RoomFrame(seat = Point3.ORIGIN, screen = Point3(0f, 2.4f, 14f))
        val placement = TheaterLayout.place(seated, cinema, 16f / 9f, frame)
        assertEquals(0f, placement.roomYawRadians, 1e-6f)
        assertPoint(Point3.ORIGIN, placement.roomOrigin)
        assertPoint(Point3(0f, 2.4f, 14f - TheaterLayout.PICTURE_LIFT_METRES), placement.screen.center)
    }

    @Test
    fun aRoomLoadedWithItsScreenAlongMinusZIsTurnedAround() {
        // The SDK flipped glTF's X instead: the screen stays along -Z, behind a viewer facing +Z.
        val frame = RoomFrame(seat = Point3.ORIGIN, screen = Point3(0f, 2.4f, -14f))
        val placement = TheaterLayout.place(seated, cinema, 16f / 9f, frame)
        assertEquals(PI.toFloat(), abs(placement.roomYawRadians), 1e-5f)
        // Turned, the room's screen is ahead of the viewer, and the picture is on it.
        val roomScreen = TheaterLayout.roomPointInWorld(placement, frame.screen)
        assertPoint(Point3(0f, 2.4f, 14f), roomScreen)
        assertPoint(Point3(0f, 2.4f, 14f - TheaterLayout.PICTURE_LIFT_METRES), placement.screen.center)
        assertEquals(seated.yawRadians, placement.screen.yawRadians)
    }

    @Test
    fun theRoomsSeatLandsUnderTheHeadWhereverTheViewerFaces() {
        val head = HeadPose(Point3(1f, 1.2f, -2f), yawRadians = 0.7f)
        // A room authored with its seat off the mesh origin, its screen off to one side of it.
        val frame = RoomFrame(seat = Point3(0.5f, 0f, 1f), screen = Point3(3.5f, 2f, -3f))
        val placement = TheaterLayout.place(head, cinema, 16f / 9f, frame)
        assertPoint(Point3(1f, 0f, -2f), TheaterLayout.roomPointInWorld(placement, frame.seat))
        val screen = TheaterLayout.roomPointInWorld(placement, frame.screen)
        val distance = kotlin.math.hypot(frame.screen.x - frame.seat.x, frame.screen.z - frame.seat.z)
        assertPoint(Point3(1f, 0f, -2f) + head.forward * distance + Point3(0f, 2f, 0f), screen)
        assertPoint(screen + head.forward * -TheaterLayout.PICTURE_LIFT_METRES, placement.screen.center)
    }

    @Test
    fun aFrameWithTheScreenOnTheSeatIsIgnored() {
        val frame = RoomFrame(seat = Point3.ORIGIN, screen = Point3(0.1f, 2f, 0.1f))
        val placement = TheaterLayout.place(seated, cinema, 16f / 9f, frame)
        assertEquals(seated.yawRadians, placement.roomYawRadians)
        assertPoint(Point3(0f, 2.4f, 14f - TheaterLayout.PICTURE_LIFT_METRES), placement.screen.center)
    }

    @Test
    fun rotationMatchesTheSdksLookRotationAroundY() {
        // Checked against meta-spatial-sdk 0.14.0's Quaternion on the JVM:
        // lookRotationAroundY((1, 0, 0)) takes +Z to +X and +X to -Z.
        val quarter = (PI / 2).toFloat()
        assertPoint(Point3(1f, 0f, 0f), TheaterLayout.rotateY(Point3(0f, 0f, 1f), quarter))
        assertPoint(Point3(0f, 0f, -1f), TheaterLayout.rotateY(Point3(1f, 0f, 0f), quarter))
        assertPoint(HeadPose(Point3.ORIGIN, quarter).right, TheaterLayout.rotateY(Point3(1f, 0f, 0f), quarter))
    }

    @Test
    fun aTallPictureIsPillarboxedInsideTheAuthoredQuad() {
        val scene = TheaterScene(ScreenNode(ahead = 5f, up = 1.5f, right = 0f, width = 4f, height = 2.25f))
        val placement = TheaterLayout.place(seated, scene, aspectRatio = 4f / 3f)
        assertEquals(2.25f, placement.screen.height)
        assertEquals(3f, placement.screen.width, 1e-5f)
    }

    @Test
    fun aWidePictureKeepsTheWidthAndIsLetterboxed() {
        val scene = TheaterScene(ScreenNode(ahead = 5f, up = 1.5f, right = 0f, width = 4f, height = 2.25f))
        val placement = TheaterLayout.place(seated, scene, aspectRatio = 2.39f)
        assertEquals(4f, placement.screen.width)
        assertEquals(4f / 2.39f, placement.screen.height, 1e-5f)
    }

    @Test
    fun withoutASceneOnlyTheWidthIsFixed() {
        val (width, height) = TheaterLayout.fitScreen(4f / 3f, maxWidth = 4f, maxHeight = null)
        assertEquals(4f, width)
        assertEquals(3f, height, 1e-5f)
    }

    @Test
    fun anUnknownAspectRatioIsSixteenByNine() {
        assertEquals(4f to 2.25f, TheaterLayout.fitScreen(null, 4f, null))
        assertEquals(4f to 2.25f, TheaterLayout.fitScreen(0f, 4f, null))
        assertEquals(4f to 2.25f, TheaterLayout.fitScreen(Float.NaN, 4f, null))
    }

    @Test
    fun panelsCoverTheScreenAt1080Rows() {
        assertEquals(1920 to 1080, TheaterLayout.panelPixels(16f / 9f))
        assertEquals(1440 to 1080, TheaterLayout.panelPixels(4f / 3f))
    }

    @Test
    fun yawComesFromTheForwardVector() {
        assertEquals(0f, HeadPose.yawOf(forwardX = 0f, forwardZ = 1f))
        assertEquals((PI / 2).toFloat(), HeadPose.yawOf(forwardX = 1f, forwardZ = 0f), 1e-6f)
        assertEquals(PI.toFloat(), abs(HeadPose.yawOf(forwardX = 0f, forwardZ = -1f)), 1e-6f)
    }

    private fun assertPoint(expected: Point3, actual: Point3) {
        assertEquals(expected.x, actual.x, 1e-5f, "x of $actual")
        assertEquals(expected.y, actual.y, 1e-5f, "y of $actual")
        assertEquals(expected.z, actual.z, 1e-5f, "z of $actual")
    }
}
