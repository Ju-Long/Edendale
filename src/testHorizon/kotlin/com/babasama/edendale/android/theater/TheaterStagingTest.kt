package com.babasama.edendale.android.theater

import com.babasama.edendale.android.xr.DockFollower
import com.babasama.edendale.android.xr.Quat
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterDock
import com.babasama.edendale.android.xr.TheaterGlb
import com.babasama.edendale.android.xr.TheaterSeatMap
import com.babasama.edendale.android.xr.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HORIZON.md G.15: seating, seats, the picture, the subtitles, and the dock
 * on the Quest, from android's shared rules, checked in the SDK's numbers
 * the activity hands to its entities.
 */
class TheaterStagingTest {

    private val half = (PI / 2).toFloat()

    /** The cinema as the scenes export lays it out: Seat at the origin, a 12 × 6.75 m screen 10.55 m ahead, three seats. */
    private val cinema = TheaterGlb(
        seat = Vec3.ZERO,
        screenBoundsMin = Vec3(-6f, 0.225f, -10.55f),
        screenBoundsMax = Vec3(6f, 6.975f, -10.55f),
        seatMarkers = listOf(
            "Seat_03_10" to Vec3.ZERO,
            "Seat_03_01" to Vec3(-4.2f, 0f, 0f),
            "Seat_09_10" to Vec3(0f, 1.8f, 6f),
        ),
        meshNodeNames = listOf("Room", "Screen", "Seats"),
    )
    private val map = TheaterSeatMap(cinema.seatMarkers, cinema.seat)

    @Test
    fun aLevelViewerSitsOnTheFloorUnderTheirEyesFacingWhereTheyLook() {
        // In the SDK's numbers: eyes at (1, 1.6, 2), looking along +X.
        val head = TheaterFrames.head(Vec3(1f, 1.6f, 2f), Quat.yaw(half))
        val stage = assertNotNull(TheaterStaging.stage(head))
        val placement = TheaterStaging.place(stage, TheaterStaging.seatPosition(cinema, map, null), TheaterStaging.screen(cinema), 16f / 9f, 0f)
        // The best seat (the origin) lands on the floor under the eyes.
        assertClose(Vec3(1f, 0f, 2f), placement.sdkRoom.apply(TheaterFrames.loadedPoint(Vec3.ZERO, 0f)))
        // The picture is 10.55 m ahead along +X, less the lifts, at the screen's height.
        val ahead = 10.55f - 0.005f - TheaterStaging.PICTURE_LIFT
        assertClose(Vec3(1f + ahead, 3.6f, 2f), placement.sdkPicture.translation, 1e-3f)
        assertEquals(12f, placement.pictureWidth, 1e-4f)
        assertEquals(6.75f, placement.pictureHeight, 1e-3f)
        // The subtitles float 2 cm nearer than the picture.
        assertClose(Vec3(1f + ahead - TheaterStaging.SUBTITLE_LIFT, 3.6f, 2f), placement.sdkSubtitles.translation, 1e-3f)
    }

    @Test
    fun thePictureKeepsTheVideosShapeInsideTheScreen() {
        val stage = assertNotNull(TheaterStaging.stage(TheaterFrames.head(Vec3(0f, 1.2f, 0f), Quat.IDENTITY)))
        val scope = TheaterStaging.place(stage, Vec3.ZERO, TheaterStaging.screen(cinema), 2.39f, 0f)
        assertEquals(12f, scope.pictureWidth, 1e-4f)
        assertEquals(12f / 2.39f, scope.pictureHeight, 1e-3f)
        val academy = TheaterStaging.place(stage, Vec3.ZERO, TheaterStaging.screen(cinema), 4f / 3f, 0f)
        assertEquals(6.75f, academy.pictureHeight, 1e-3f)
        assertEquals(9f, academy.pictureWidth, 1e-3f)
        // Unknown yet: 16:9.
        val unknown = TheaterStaging.place(stage, Vec3.ZERO, TheaterStaging.screen(cinema), null, 0f)
        assertEquals(16f / 9f, unknown.pictureAspect, 1e-3f)
    }

    @Test
    fun withoutARoomTheScreenIsTheSpikesQuadFiveMetresAhead() {
        val stage = assertNotNull(TheaterStaging.stage(TheaterFrames.head(Vec3(0f, 1.2f, 0f), Quat.IDENTITY)))
        val placement = TheaterStaging.place(stage, TheaterStaging.seatPosition(null, null, null), TheaterStaging.screen(null), null, 0f)
        assertClose(Vec3(0f, 1.2f, 5f - TheaterStaging.PICTURE_LIFT), placement.sdkPicture.translation)
        assertEquals(4f, placement.pictureWidth, 1e-4f)
        assertEquals(2.25f, placement.pictureHeight, 1e-4f)
    }

    /** Whichever axis the SDK flips, the chosen seat's floor point lands under the eyes. */
    @Test
    fun aChosenSeatIsWhereTheViewerSits() {
        val head = TheaterFrames.head(Vec3(-0.5f, 1.1f, 3f), Quat.yaw(-0.4f))
        val stage = assertNotNull(TheaterStaging.stage(head))
        val chosen = SeatID(3, 1)
        val seat = TheaterStaging.seatPosition(cinema, map, chosen)
        assertEquals(Vec3(-4.2f, 0f, 0f), seat)
        listOf(0f, PI.toFloat()).forEach { turn ->
            val placement = TheaterStaging.place(stage, seat, TheaterStaging.screen(cinema), null, turn)
            assertClose(Vec3(-0.5f, 0f, 3f), placement.sdkRoom.apply(TheaterFrames.loadedPoint(seat, turn)))
        }
    }

    @Test
    fun theSeatFallsBackToTheBestSeatThenTheSeatNode() {
        assertEquals(Vec3(0f, 1.8f, 6f), TheaterStaging.seatPosition(cinema, map, SeatID(9, 10)))
        // A seat the room no longer has: the best seat.
        assertEquals(Vec3.ZERO, TheaterStaging.seatPosition(cinema, map, SeatID(40, 1)))
        // No seat map: the Seat node; no file: the origin.
        val noSeats = cinema.copy(seat = Vec3(0f, 0f, 1f), seatMarkers = emptyList())
        assertEquals(Vec3(0f, 0f, 1f), TheaterStaging.seatPosition(noSeats, TheaterSeatMap(emptyList(), noSeats.seat), null))
        assertEquals(Vec3.ZERO, TheaterStaging.seatPosition(null, null, null))
    }

    @Test
    fun theBestSeatIsStoredAsNoChoice() {
        assertNull(TheaterStaging.storedChoice(map, SeatID(3, 10)))
        assertNull(TheaterStaging.storedChoice(map, null))
        assertEquals(SeatID(9, 10), TheaterStaging.storedChoice(map, SeatID(9, 10)))
        assertEquals(SeatID(9, 10), TheaterStaging.seatedSeat(map, SeatID(9, 10)))
        assertEquals(SeatID(3, 10), TheaterStaging.seatedSeat(map, null))
        assertEquals(SeatID(3, 10), TheaterStaging.seatedSeat(map, SeatID(40, 1)))
        assertNull(TheaterStaging.seatedSeat(null, SeatID(9, 10)))
    }

    /** Lying back (gaze 70° up): the whole room tilts with the viewer and the picture is where they look. */
    @Test
    fun lyingBackTheScreenIsAboveTheViewer() {
        val up = 70f * PI.toFloat() / 180f
        // In the SDK's numbers a positive turn about +X tips +Z down, so looking up is a negative one.
        val sdkRotation = Quat.axisAngle(Vec3(1f, 0f, 0f), -up)
        val sdkEye = Vec3(0f, 0.5f, 0f)
        val head = TheaterFrames.head(sdkEye, sdkRotation)
        assertTrue(head.forward.y > 0.9f, "the shared gaze points up: ${head.forward}")
        val stage = assertNotNull(TheaterStaging.stage(head))
        val placement = TheaterStaging.place(stage, Vec3.ZERO, TheaterStaging.screen(cinema), 16f / 9f, 0f)
        val toPicture = (placement.sdkPicture.translation - sdkEye).normalized()
        val sdkGaze = sdkRotation.rotate(Vec3(0f, 0f, 1f))
        assertTrue(toPicture dot sdkGaze > 0.9f, "the picture is ahead of the gaze: $toPicture vs $sdkGaze")
        assertTrue(placement.sdkPicture.translation.y > sdkEye.y + 5f, "and above the viewer")
    }

    @Test
    fun anUntrackedViewerSitsAtTheOriginFacingTheSdksAhead() {
        val placement = TheaterStaging.place(TheaterStaging.UNTRACKED_STAGE, Vec3.ZERO, TheaterStaging.screen(cinema), null, 0f)
        assertTrue(placement.sdkPicture.translation.z > 10f)
        assertEquals(0f, placement.sdkPicture.translation.x, 1e-4f)
    }

    /** The dock is android's: 0.65 m from the eyes, 45° below them, along the gaze; the seat panel to its left. */
    @Test
    fun theDockSitsInTheLapAlongTheGaze() {
        val sdkEye = Vec3(1f, 1.2f, 2f)
        val head = TheaterFrames.head(sdkEye, Quat.yaw(half))
        val stage = assertNotNull(TheaterStaging.stage(head))
        val follower = DockFollower()
        val (eye, yaw) = TheaterStaging.dockInput(stage, head)
        follower.update(yaw, eye, 1f / 90f)
        val dock = TheaterStaging.sdkPanelPose(stage, assertNotNull(follower.pose))
        val offset = dock.translation - sdkEye
        assertEquals(TheaterDock.DISTANCE, offset.length, 1e-3f)
        assertEquals(-TheaterDock.DISTANCE * sin(TheaterDock.DECLINATION), offset.y, 1e-3f)
        assertEquals(TheaterDock.DISTANCE * cos(TheaterDock.DECLINATION), offset.x, 1e-3f)
        assertEquals(0f, offset.z, 1e-3f)
        // Its +Z points away from the eyes (the SDK's panels face their viewer that way).
        assertTrue(dock.rotation.rotate(Vec3(0f, 0f, 1f)) dot offset.normalized() > 0.999f)

        val side = TheaterStaging.sdkPanelPose(stage, assertNotNull(follower.sidePose))
        // Facing +X in the SDK's left-handed frame, the viewer's right is −Z and their left +Z.
        assertTrue(side.translation.z > sdkEye.z + 0.2f, "the seat panel is to the left: ${side.translation}")
    }

    @Test
    fun theSubtitlesTakeThePicturesShapeAtThePlayersWidth() {
        val scope = TheaterSubtitleShape.of(2.39f)
        assertEquals(1280f, scope.widthDp)
        assertEquals(1280f / 2.39f, scope.heightDp, 1e-3f)
        assertEquals(4f / 2.39f, scope.heightMetres, 1e-4f)
        assertEquals(720f, TheaterSubtitleShape.of(16f / 9f).heightDp, 1e-3f)
        assertEquals(720f, TheaterSubtitleShape.of(Float.NaN).heightDp, 1e-3f)
        assertEquals(720f, TheaterSubtitleShape.of(0f).heightDp, 1e-3f)
        assertEquals(3f, TheaterSubtitleShape.scale(12f), 1e-5f)
        assertTrue(TheaterSubtitleShape.fits(2.39f, 2.3905f))
        assertTrue(!TheaterSubtitleShape.fits(16f / 9f, 2.39f))
        assertTrue(!TheaterSubtitleShape.fits(null, 2.39f))
    }

    private fun assertClose(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) {
        assertTrue(
            abs(expected.x - actual.x) < tolerance && abs(expected.y - actual.y) < tolerance && abs(expected.z - actual.z) < tolerance,
            "expected $expected, got $actual",
        )
    }
}
