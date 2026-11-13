package com.babasama.edendale.android.theater

import com.babasama.edendale.android.xr.DockPose
import com.babasama.edendale.android.xr.HeadFrame
import com.babasama.edendale.android.xr.Quat
import com.babasama.edendale.android.xr.RoomPose
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterDock
import com.babasama.edendale.android.xr.TheaterGeometry
import com.babasama.edendale.android.xr.TheaterGlb
import com.babasama.edendale.android.xr.TheaterSeatMap
import com.babasama.edendale.android.xr.Vec3

/**
 * Where everything in the theater goes once the viewer is seated (HORIZON.md
 * G.15), from the same rules as android's Android XR theater
 * (`TheaterGeometry`, `TheaterSeatMap`, `TheaterDock`), so the two headsets
 * seat, recentre, and dock alike. The arrangement nests as android's does:
 *
 *     stage   where the viewer is: the seat's floor point under the eyes, turned
 *             to the gaze (the whole room tilted when lying back), from
 *             `TheaterGeometry.recenterPose`
 *       room  the room file's pose on the stage: the chosen seat on the stage's
 *             origin, the screen straight ahead (`TheaterGeometry.roomPose`)
 *         the room's mesh, the picture, the subtitles
 *       the dock and the seat panel, from the head in the stage's frame
 *
 * Everything is computed in the shared frame and handed to the activity in
 * the SDK's numbers through [TheaterFrames].
 */
object TheaterStaging {
    /** The picture sits this far in front of the room's Screen mesh, on top of `TheaterGeometry.SCREEN_LIFT`'s 5 mm (android's N-D12 margin). */
    const val PICTURE_LIFT = 0.03f

    /** The subtitle panel floats this far in front of the picture. */
    const val SUBTITLE_LIFT = 0.02f

    /** Without a usable room file: the 2026-10-25 spike's screen, 4 m wide and 5 m ahead, at a seated viewer's eye height. */
    const val DEFAULT_SCREEN_WIDTH = 4f
    const val DEFAULT_SCREEN_DISTANCE = 5f

    /**
     * The stage for a head that tracking never reported: the seat on the
     * local floor's origin (where the session started), facing the SDK's +Z.
     * The Quest's reference space is `LOCAL_FLOOR`, so y = 0 is the floor
     * itself, unlike Android XR's activity space (android's `seatingPose`).
     */
    val UNTRACKED_STAGE: RoomPose = RoomPose.IDENTITY

    /**
     * The stage for [head]: android's recentre rule unchanged. Level within
     * 20°, the seat's floor point lands on the floor under the eyes and only
     * the heading turns; lying back, the whole room tilts so the seat's eye
     * point lands on the eyes. Null for a head the rule can't use.
     */
    fun stage(head: HeadFrame): RoomPose? = TheaterGeometry.recenterPose(head)

    /** The picture's frame on the room's screen, in the room file's frame; the spike's quad without a room. */
    fun screen(glb: TheaterGlb?): TheaterGeometry.ScreenPlacement =
        glb?.let { TheaterGeometry.screenPlacement(it.screenBoundsMin, it.screenBoundsMax) }
            ?: TheaterGeometry.ScreenPlacement(
                center = Vec3(0f, TheaterGeometry.Recenter.SEATED_EYE_HEIGHT, -DEFAULT_SCREEN_DISTANCE),
                width = DEFAULT_SCREEN_WIDTH,
                height = DEFAULT_SCREEN_WIDTH / TheaterGeometry.DEFAULT_ASPECT,
            )

    /** The floor point the viewer sits on: the chosen seat, else the room's best seat, else its `Seat` node, else the origin. */
    fun seatPosition(glb: TheaterGlb?, map: TheaterSeatMap?, chosen: SeatID?): Vec3 =
        chosen?.let { map?.seat(it)?.position } ?: map?.best?.position ?: glb?.seat ?: Vec3.ZERO

    /** The seat shown as taken: the chosen one while the room has it, else the best seat. */
    fun seatedSeat(map: TheaterSeatMap?, chosen: SeatID?): SeatID? {
        val seats = map ?: return null
        return (chosen?.let { seats.seat(it) } ?: seats.best)?.id
    }

    /**
     * What a seat choice stores: nothing for the best seat (or no choice),
     * so a room whose best seat moves in a later export follows it.
     */
    fun storedChoice(map: TheaterSeatMap?, choice: SeatID?): SeatID? =
        choice?.takeIf { map != null && it != map.best?.id }

    /**
     * The room, the picture, and the subtitles for a seated viewer, in the
     * SDK's numbers. [aspect] is the video's shape (null until known);
     * [importTurn] is how the SDK turned the room when it loaded it
     * ([TheaterFrames.importTurn]), 0 until that is known.
     */
    fun place(
        stage: RoomPose,
        seat: Vec3,
        screen: TheaterGeometry.ScreenPlacement,
        aspect: Float?,
        importTurn: Float,
    ): StagePlacement {
        val room = stage.compose(TheaterGeometry.roomPose(seat, screen.center))
        val (width, height) = TheaterGeometry.fitPicture(aspect, screen.width, screen.height)
        val picture = RoomPose(Quat.IDENTITY, screen.center + Vec3(0f, 0f, PICTURE_LIFT))
        val subtitles = RoomPose(Quat.IDENTITY, screen.center + Vec3(0f, 0f, PICTURE_LIFT + SUBTITLE_LIFT))
        return StagePlacement(
            sdkRoom = TheaterFrames.sdkRoomPose(room, importTurn),
            sdkPicture = TheaterFrames.mirror(room.compose(picture)),
            pictureWidth = width,
            pictureHeight = height,
            sdkSubtitles = TheaterFrames.mirror(room.compose(subtitles)),
        )
    }

    /** The eyes and the gaze's heading in the stage's frame, which is what the dock follows. */
    fun dockInput(stage: RoomPose, head: HeadFrame): Pair<Vec3, Float?> {
        val local = stage.inverse
        return local.apply(head.position) to TheaterDock.yaw(local.applyDirection(head.forward))
    }

    /** A dock panel's pose (the stage's frame, its +Z toward the eyes) as the SDK's numbers for its entity. */
    fun sdkPanelPose(stage: RoomPose, pose: DockPose): RoomPose =
        TheaterFrames.mirror(stage.compose(RoomPose(pose.orientation, pose.position)))
}

/**
 * One arrangement of the room and the picture, in the SDK's numbers. The
 * picture's panel is [pictureWidth] by [pictureHeight] metres, the video's
 * shape fitted inside the room's screen; the subtitle panel takes the same
 * size and shape ([TheaterSubtitleShape]).
 */
data class StagePlacement(
    val sdkRoom: RoomPose,
    val sdkPicture: RoomPose,
    val pictureWidth: Float,
    val pictureHeight: Float,
    val sdkSubtitles: RoomPose,
) {
    val pictureAspect: Float get() = pictureWidth / pictureHeight
}

/**
 * The subtitle panel's layout box (HORIZON.md G.15): 1280 dp across, as the
 * panel player's width (so cues keep their size against the picture), and
 * the height that gives the picture's own shape, so nothing is stretched.
 * The panel is created at [WIDTH_METRES] and scaled evenly to the picture.
 */
data class TheaterSubtitleShape(val widthDp: Float, val heightDp: Float) {
    val heightMetres: Float get() = WIDTH_METRES * heightDp / widthDp

    companion object {
        const val WIDTH_DP = 1280f
        const val WIDTH_METRES = 4f

        /** The shape is rebuilt only when the picture's changes by more than this, so float noise doesn't rebuild the panel. */
        const val ASPECT_TOLERANCE = 0.005f

        /** The box for a picture of [aspect]; a non-finite or non-positive one is 16:9. */
        fun of(aspect: Float): TheaterSubtitleShape {
            val shape = aspect.takeIf { it.isFinite() && it > 0f } ?: TheaterGeometry.DEFAULT_ASPECT
            return TheaterSubtitleShape(WIDTH_DP, WIDTH_DP / shape)
        }

        /** Whether a panel laid out for [current] can show a picture of [aspect] without being rebuilt. */
        fun fits(current: Float?, aspect: Float): Boolean =
            current != null && kotlin.math.abs(current - aspect) <= ASPECT_TOLERANCE * aspect

        /** The even scale that makes the panel as wide as a picture [pictureWidth] metres across. */
        fun scale(pictureWidth: Float): Float = pictureWidth / WIDTH_METRES
    }
}
