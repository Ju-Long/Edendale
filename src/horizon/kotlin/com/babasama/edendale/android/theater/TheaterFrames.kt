package com.babasama.edendale.android.theater

import com.babasama.edendale.android.xr.HeadFrame
import com.babasama.edendale.android.xr.Quat
import com.babasama.edendale.android.xr.RoomPose
import com.babasama.edendale.android.xr.Vec3
import com.babasama.edendale.android.xr.angleGap
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Between the shared theater geometry's frame and the Spatial SDK's
 * (HORIZON.md G.15). The geometry in `src/main`'s `xr` package (android's
 * Android XR theater, ENHANCEMENT.md Section N) works in glTF's frame:
 * right-handed, +Y up, the viewer's ahead −Z. The Spatial SDK's frame is
 * left-handed, +Y up, ahead +Z (`Vector3.Forward`), with the same Hamilton
 * quaternion algebra. One place in the room has the same x and y in both and
 * the opposite z, and a rotation is the same turn written for the mirrored
 * axis: (x, y, z, w) ↔ (−x, −y, z, w).
 *
 * A panel whose front faces the eyes in the shared frame (its +Z toward
 * them, as Jetpack XR's panels) comes out with its +Z away from the eyes,
 * which is how a Spatial SDK panel faces its viewer (the 2026-10-25 spike's
 * screen), so no extra turn is needed.
 *
 * Every pose here is a [RoomPose]: the shared frame's, unless its name says
 * `sdk`, in which case its numbers are the SDK's and the activity copies them
 * into `Pose(Vector3(x, y, z), Quaternion(w, x, y, z))`.
 */
object TheaterFrames {
    /** A point in one frame, in the other: z changes sign (the mirror is its own inverse). */
    fun mirror(v: Vec3): Vec3 = Vec3(v.x, v.y, -v.z)

    /** A rotation in one frame, in the other. */
    fun mirror(q: Quat): Quat = Quat(-q.x, -q.y, q.z, q.w)

    /** A rigid transform in one frame, in the other: `mirror ∘ p ∘ mirror`. */
    fun mirror(p: RoomPose): RoomPose = RoomPose(mirror(p.rotation), mirror(p.translation))

    /** The head from the SDK's numbers (its position and rotation), in the shared frame. */
    fun head(sdkPosition: Vec3, sdkRotation: Quat): HeadFrame = HeadFrame.of(mirror(sdkPosition), mirror(sdkRotation))

    /** A seat-to-screen line shorter than this, ignoring height, says nothing about which way a room faces. */
    const val MIN_SCREEN_DISTANCE_METRES = 0.5f

    /**
     * How the SDK turned the room when it loaded the GLB. Meta documents a
     * left-handed frame and that glTF is converted to it, not which axis
     * flips, so the loaded room is taken to be the file's mirrored (z) and
     * then turned about +Y by the returned angle: 0 when the SDK flips z, π
     * when it flips x. The angle comes from the `Seat`-to-`Screen` line in
     * the file ([fileSeat], [fileScreen], shared frame) and in the loaded
     * mesh ([loadedSeat], [loadedScreen], the SDK's numbers). Null when either
     * line is too short to say.
     */
    fun importTurn(fileSeat: Vec3, fileScreen: Vec3, loadedSeat: Vec3, loadedScreen: Vec3): Float? {
        val file = mirror(fileScreen - fileSeat)
        val loaded = loadedScreen - loadedSeat
        if (!file.isFinite || !loaded.isFinite) return null
        if (hypot(file.x, file.z) < MIN_SCREEN_DISTANCE_METRES || hypot(loaded.x, loaded.z) < MIN_SCREEN_DISTANCE_METRES) return null
        return angleGap(sdkYaw(loaded), sdkYaw(file))
    }

    /**
     * Where a point of the room file lands in the loaded mesh's own frame, for
     * a room the SDK turned by [importTurn]: the check G.10 reads in `logcat`
     * against a seat marker the SDK reports.
     */
    fun loadedPoint(filePoint: Vec3, importTurn: Float): Vec3 = Quat.yaw(importTurn).rotate(mirror(filePoint))

    /**
     * The room mesh entity's transform (the SDK's numbers) that shows the
     * file's room at [room], a pose in the shared frame (stage after the
     * room's own pose): mirrored, after undoing the SDK's [importTurn].
     */
    fun sdkRoomPose(room: RoomPose, importTurn: Float): RoomPose =
        mirror(room).compose(RoomPose(Quat.yaw(-importTurn), Vec3.ZERO))

    /** The heading of a direction in the SDK's numbers: 0 along +Z, π/2 along +X. */
    private fun sdkYaw(v: Vec3): Float = atan2(v.x, v.z)
}
