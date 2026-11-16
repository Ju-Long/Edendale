package com.babasama.edendale.android.theater

import com.babasama.edendale.android.xr.Quat
import com.babasama.edendale.android.xr.RoomPose
import com.babasama.edendale.android.xr.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HORIZON.md G.15: between the shared theater geometry's frame (glTF's, −Z ahead) and the Spatial SDK's (+Z ahead). */
class TheaterFramesTest {

    private val half = (PI / 2).toFloat()
    private val turned = RoomPose(Quat.axisAngle(Vec3(0.3f, 1f, -0.2f), 0.9f), Vec3(1f, -0.5f, 2f))

    @Test
    fun theMirrorIsItsOwnInverse() {
        val v = Vec3(1f, 2f, 3f)
        assertEquals(v, TheaterFrames.mirror(TheaterFrames.mirror(v)))
        assertEquals(turned.rotation, TheaterFrames.mirror(TheaterFrames.mirror(turned.rotation)))
        assertEquals(Vec3(1f, 2f, -3f), TheaterFrames.mirror(v))
    }

    /** A turn written for the mirrored axis does to mirrored points what the original does to the originals. */
    @Test
    fun aMirroredPoseMovesMirroredPoints() {
        val sdk = TheaterFrames.mirror(turned)
        listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0.4f, -2f, 7f)).forEach { p ->
            assertClose(TheaterFrames.mirror(turned.apply(p)), sdk.apply(TheaterFrames.mirror(p)))
        }
    }

    /** The SDK's yaw 0 looks along its +Z, which is the shared frame's ahead (−Z); its +X is the viewer's right in both. */
    @Test
    fun theHeadComesOverWithTheSameAheadAndRight() {
        val level = TheaterFrames.head(Vec3(0f, 1.6f, 0f), Quat.IDENTITY)
        assertClose(Vec3(0f, 1.6f, 0f), level.position)
        assertClose(Vec3(0f, 0f, -1f), level.forward)
        assertClose(Vec3(1f, 0f, 0f), level.right)

        // Turned to face the SDK's +X: the shared gaze is +X too (x is not mirrored).
        val turnedRight = TheaterFrames.head(Vec3(1f, 1.6f, 2f), Quat.yaw(half))
        assertClose(Vec3(1f, 1.6f, -2f), turnedRight.position)
        assertClose(Vec3(1f, 0f, 0f), turnedRight.forward)
        assertClose(Vec3(0f, 0f, 1f), turnedRight.right)
    }

    @Test
    fun aRoomTheSdkMirroredInZNeedsNoTurn() {
        val turn = assertNotNull(TheaterFrames.importTurn(Vec3.ZERO, Vec3(0f, 3.6f, -10.55f), Vec3.ZERO, Vec3(0f, 3.6f, 10.55f)))
        assertEquals(0f, turn, 1e-5f)
    }

    @Test
    fun aRoomTheSdkMirroredInXIsHalfATurnRound() {
        val turn = assertNotNull(TheaterFrames.importTurn(Vec3.ZERO, Vec3(0f, 3.6f, -10.55f), Vec3.ZERO, Vec3(0f, 3.6f, -10.55f)))
        assertEquals(PI.toFloat(), abs(turn), 1e-5f)
    }

    @Test
    fun aSeatOnTopOfItsScreenSaysNothing() {
        assertNull(TheaterFrames.importTurn(Vec3.ZERO, Vec3(0f, 3f, -0.2f), Vec3.ZERO, Vec3(0f, 3f, 10f)))
        assertNull(TheaterFrames.importTurn(Vec3.ZERO, Vec3(0f, 3f, -10f), Vec3.ZERO, Vec3(0.1f, 3f, 0.1f)))
        assertNull(TheaterFrames.importTurn(Vec3.ZERO, Vec3(Float.NaN, 0f, -10f), Vec3.ZERO, Vec3(0f, 0f, 10f)))
    }

    /**
     * Whichever axis the SDK flips, the room entity's transform puts every
     * point of the loaded mesh where the shared room pose puts the file's.
     */
    @Test
    fun theRoomLandsTheSameWhicheverAxisTheSdkFlips() {
        val points = listOf(Vec3.ZERO, Vec3(0f, 3.6f, -10.55f), Vec3(-4.2f, 0.6f, 3f), Vec3(2f, 1f, -1f))
        val flipZ = { p: Vec3 -> Vec3(p.x, p.y, -p.z) }
        val flipX = { p: Vec3 -> Vec3(-p.x, p.y, p.z) }
        listOf(flipZ, flipX).forEach { load ->
            val turn = assertNotNull(TheaterFrames.importTurn(points[0], points[1], load(points[0]), load(points[1])))
            val entity = TheaterFrames.sdkRoomPose(turned, turn)
            points.forEach { p ->
                assertClose(load(p), TheaterFrames.loadedPoint(p, turn))
                assertClose(TheaterFrames.mirror(turned.apply(p)), entity.apply(load(p)))
            }
        }
    }

    /** A panel facing the eyes in the shared frame (+Z toward them) has its +Z away from them in the SDK's, as the SDK's panels face a viewer. */
    @Test
    fun aPanelFacingTheEyesFacesThemInTheSdk() {
        val eye = Vec3(0f, 1.2f, 0f)
        val panel = RoomPose(Quat.yaw(0.7f), eye + Quat.yaw(0.7f).rotate(Vec3(0f, 0f, -2f)))
        val sdk = TheaterFrames.mirror(panel)
        val sdkEye = TheaterFrames.mirror(eye)
        val back = sdk.rotation.rotate(Vec3(0f, 0f, 1f))
        val away = (sdk.translation - sdkEye).normalized()
        assertTrue(back dot away > 0.999f, "the panel's +Z points away from the eyes")
        // Its +X is still the viewer's right: text reads left to right.
        val sharedRight = panel.rotation.rotate(Vec3(1f, 0f, 0f))
        assertClose(TheaterFrames.mirror(sharedRight), sdk.rotation.rotate(Vec3(1f, 0f, 0f)))
    }

    private fun assertClose(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) {
        assertTrue(
            abs(expected.x - actual.x) < tolerance && abs(expected.y - actual.y) < tolerance && abs(expected.z - actual.z) < tolerance,
            "expected $expected, got $actual",
        )
    }
}
