package com.babasama.edendale.android.xr

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A seat in a room: rows count from the front, seats from the left as the
 * viewer faces the screen, both from 1. The same id names the room's marker
 * node and the stored choice, so the two forms are kept here.
 */
data class SeatID(val row: Int, val number: Int) : Comparable<SeatID> {
    /** The marker node's name in the room, `Seat_RR_NN`. */
    val markerName: String
        get() = "Seat_${row.toString().padStart(2, '0')}_${number.toString().padStart(2, '0')}"

    /** The preference form, `row-number`, short and unpadded because it is never shown. */
    val storageValue: String get() = "$row-$number"

    override fun compareTo(other: SeatID): Int = compareValuesBy(this, other, SeatID::row, SeatID::number)

    companion object {
        /** `Seat_06_01` → row 6, seat 1; null for the `Seat`, `SeatMap`, or any other node. */
        fun fromMarkerName(name: String): SeatID? {
            val parts = name.split("_")
            if (parts.size != 3 || parts[0] != "Seat") return null
            return of(parts[1].toIntOrNull(), parts[2].toIntOrNull())
        }

        /** `6-1` → row 6, seat 1; null for anything else, which the caller treats as "the best seat". */
        fun fromStorageValue(value: String): SeatID? {
            val parts = value.split("-")
            if (parts.size != 2) return null
            return of(parts[0].toIntOrNull(), parts[1].toIntOrNull())
        }

        private fun of(row: Int?, number: Int?): SeatID? =
            if (row != null && number != null && row > 0 && number > 0) SeatID(row, number) else null
    }
}

/** A seat and the floor under it, in room space. */
data class Seat(val id: SeatID, val position: Vec3)

/**
 * The room's seats, read from the `SeatMap` node's children, and the flat map
 * the seat panel draws them on. Pure so the layout numbers are tested on the
 * JVM; the panel only draws what [layout] returns.
 *
 * @param markers the `SeatMap` children as `(node name, room-space position)`; names that are not seat markers are dropped.
 * @param bestSeat the `Seat` node's position, the room author's best seat, or null when the room has none.
 */
class TheaterSeatMap(markers: List<Pair<String, Vec3>>, bestSeat: Vec3?) {

    /** Every seat whose marker name parses, front row first and left to right within a row. */
    val seats: List<Seat> = markers
        .mapNotNull { (name, position) -> SeatID.fromMarkerName(name)?.let { Seat(it, position) } }
        .sortedBy { it.id }

    /** The seat the author sat the viewer in: the one nearest [bestSeat], if within half a metre of it. */
    val best: Seat? = bestSeat?.let { target ->
        seats.minByOrNull { Vec3.distance(it.position, target) }
            ?.takeIf { Vec3.distance(it.position, target) < BEST_SEAT_REACH }
    }

    /**
     * The typical distance between neighbouring seats within a row: the upper
     * median of every such gap, so one odd aisle gap does not set the seat
     * size on the map. 0.6 m when no row has two seats.
     */
    val rowPitch: Float = run {
        val gaps = seats.groupBy { it.id.row }.values.flatMap { row ->
            row.zipWithNext { left, right -> Vec3.distance(left.position, right.position) }
        }.sorted()
        if (gaps.isEmpty()) DEFAULT_ROW_PITCH else gaps[gaps.size / 2]
    }

    fun seat(id: SeatID): Seat? = seats.firstOrNull { it.id == id }

    /** A box on the map, in map points with +y down. */
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        val midX: Float get() = x + width / 2f
        val minY: Float get() = y
        val maxY: Float get() = y + height
    }

    /** Where the seats and the screen bar are drawn, in map points; `seatSize` is each seat square's side. */
    data class Layout(val points: Map<SeatID, Pair<Float, Float>>, val seatSize: Float, val screen: Rect) {
        val isEmpty: Boolean get() = points.isEmpty()

        companion object {
            val EMPTY = Layout(emptyMap(), 0f, Rect(0f, 0f, 0f, 0f))
        }
    }

    /**
     * Lays the seats out on a map of [width] × [height] points with the screen
     * bar at the top. Across and front-to-back are scaled separately so a deep
     * room keeps its rows apart; the map's extent includes the screen's edges
     * so the bar shows how the seats sit against it; a seat is 82 % of the
     * smaller pitch, at most 40 points and at least 2; and half a seat of
     * margin keeps the edge seats whole. Empty when there is nothing to draw
     * or no room under the [SCREEN_BAND].
     */
    fun layout(width: Float, height: Float, screenCenterX: Float, screenWidth: Float): Layout {
        if (seats.isEmpty() || width <= 0f || height <= SCREEN_BAND) return Layout.EMPTY
        val screenLeft = screenCenterX - screenWidth / 2f
        val screenRight = screenCenterX + screenWidth / 2f
        val minX = min(seats.minOf { it.position.x }, screenLeft)
        val maxX = max(seats.maxOf { it.position.x }, screenRight)
        val minZ = seats.minOf { it.position.z }
        val maxZ = seats.maxOf { it.position.z } // z grows toward the back; the screen is at −Z
        val rows = seats.map { it.id.row }.toSet().size
        val depth = max(maxZ - minZ, 0.01f)
        val span = max(maxX - minX, 0.01f)
        var seatSize = 0f
        var scaleX = 0f
        var scaleZ = 0f
        repeat(2) { // the second pass accounts for the half-seat margin the first one sized
            val margin = seatSize / 2f
            scaleX = (width - 2f * margin) / span
            scaleZ = (height - SCREEN_BAND - 2f * margin) / depth
            val across = rowPitch * scaleX
            val along = if (rows > 1) depth / (rows - 1) * scaleZ else across
            seatSize = max(min(min(across, along), MAX_SEAT_SIZE) * SEAT_FILL, MIN_SEAT_SIZE)
        }
        val margin = seatSize / 2f
        val points = seats.associate { seat ->
            seat.id to Pair(
                margin + (seat.position.x - minX) * scaleX,
                SCREEN_BAND + margin + (seat.position.z - minZ) * scaleZ,
            )
        }
        val screen = Rect(margin + (screenLeft - minX) * scaleX, 0f, screenWidth * scaleX, SCREEN_BAR_HEIGHT)
        return Layout(points, seatSize, screen)
    }

    /** The seat drawn closest to a touch at ([x], [y]) on [layout]; null when the map is empty. */
    fun nearestSeat(x: Float, y: Float, layout: Layout): Seat? =
        layout.points.minByOrNull { (_, point) -> hypot(point.first - x, point.second - y) }
            ?.let { (id, _) -> seat(id) }

    companion object {
        /** The room node whose children are the seat markers. */
        const val MARKER_PARENT = "SeatMap"

        /** Points above the front row for the screen bar and its label. */
        const val SCREEN_BAND = 40f

        /** A `Seat` node farther than this from every marker names no seat on the map. */
        private const val BEST_SEAT_REACH = 0.5f
        private const val DEFAULT_ROW_PITCH = 0.6f
        private const val MAX_SEAT_SIZE = 40f
        private const val MIN_SEAT_SIZE = 2f
        private const val SEAT_FILL = 0.82f
        private const val SCREEN_BAR_HEIGHT = 8f
    }
}
