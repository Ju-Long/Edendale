package com.babasama.edendale.android.xr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N.T1, the visionOS `TheaterLayoutTests` seat-map cases: marker and storage
 * names, the ordered map and its best seat, the drawn layout with the screen
 * bar on top (with the worked example's numbers), and the empty map.
 */
class TheaterSeatMapTest {

    /** 3 rows × 5 seats, 0.6 m apart, rows 1 m apart and rising 0.3 m, plus the room's other nodes. */
    private val smallRoom: List<Pair<String, Vec3>> =
        (1..3).flatMap { row ->
            (1..5).map { number ->
                SeatID(row, number).markerName to Vec3((number - 3) * 0.6f, (row - 1) * 0.3f, (row - 1).toFloat())
            }
        } + listOf("Seat" to Vec3.ZERO, "Screen" to Vec3(0f, 3f, -8f))

    @Test
    fun `seats are named by row and number`() {
        val seat = assertNotNull(SeatID.fromMarkerName("Seat_06_01"))
        assertEquals(SeatID(6, 1), seat)
        assertEquals("Seat_06_01", seat.markerName)
        assertEquals("Seat_12_27", SeatID(12, 27).markerName)
        assertEquals("6-1", seat.storageValue)
        assertEquals(seat, SeatID.fromStorageValue(seat.storageValue))
        for (name in listOf("Seat", "Seats", "SeatMap", "Seat_06", "Seat_0_3", "Seat_a_b", "Chair_01_02", "Seat_01_02_03")) {
            assertNull(SeatID.fromMarkerName(name), name)
        }
        for (value in listOf("", "6", "6-", "-1", "0-3", "x-y", "1-2-3")) {
            assertNull(SeatID.fromStorageValue(value), "'$value'")
        }
    }

    @Test
    fun `seats order front row first then left to right`() {
        assertTrue(SeatID(1, 5) < SeatID(2, 1))
        assertTrue(SeatID(2, 1) < SeatID(2, 2))
        assertEquals(
            listOf(SeatID(1, 1), SeatID(1, 2), SeatID(2, 1)),
            listOf(SeatID(2, 1), SeatID(1, 2), SeatID(1, 1)).sorted(),
        )
    }

    @Test
    fun `map keeps only seats in order`() {
        val map = TheaterSeatMap(smallRoom.reversed(), bestSeat = Vec3.ZERO)
        assertEquals(15, map.seats.size)
        assertEquals(SeatID(1, 1), map.seats.first().id)
        assertEquals(SeatID(3, 5), map.seats.last().id)
        assertEquals(SeatID(1, 3), map.best?.id)
        assertEquals(Vec3(0.6f, 0.3f, 1f), map.seat(SeatID(2, 4))?.position)
        assertNull(map.seat(SeatID(4, 1)))
        // A Seat node 9 m from every marker names no seat; a room without one has no best seat.
        assertNull(TheaterSeatMap(smallRoom, bestSeat = Vec3(0f, 0f, 9f)).best)
        assertNull(TheaterSeatMap(smallRoom, bestSeat = null).best)
    }

    @Test
    fun `map draws the screen on top and the rows below it`() {
        val map = TheaterSeatMap(smallRoom, bestSeat = Vec3.ZERO)
        val layout = map.layout(width = 400f, height = 300f, screenCenterX = 0f, screenWidth = 4f)
        assertEquals(15, layout.points.size)
        assertFalse(layout.isEmpty)
        assertEquals(0f, layout.screen.minY)
        assertEquals(200f, layout.screen.midX, 0.5f)
        val half = layout.seatSize / 2f
        for ((id, point) in layout.points) {
            assertTrue(point.first - half >= -0.01f && point.first + half <= 400.01f, "$id across: $point")
            assertTrue(
                point.second - half >= TheaterSeatMap.SCREEN_BAND - 0.01f && point.second + half <= 300.01f,
                "$id along: $point",
            )
        }
        val frontLeft = layout.points.getValue(SeatID(1, 1))
        val frontRight = layout.points.getValue(SeatID(1, 5))
        val backLeft = layout.points.getValue(SeatID(3, 1))
        assertTrue(frontLeft.first < frontRight.first)
        assertTrue(frontLeft.second < backLeft.second)
        assertTrue(layout.points.getValue(SeatID(1, 2)).first - frontLeft.first > layout.seatSize)
        assertEquals(SeatID(1, 5), map.nearestSeat(frontRight.first + 3f, frontRight.second - 2f, layout)?.id)

        // The worked example: pitch 0.6 m, 91.8 pt/m across and 113.6 pt/m front to back.
        assertEquals(0.6f, map.rowPitch, 1e-5f)
        assertEquals(32.8f, layout.seatSize, 0.01f)
        assertRect(16.4f, 0f, 367.2f, 8f, layout.screen)
        assertEquals(8f, layout.screen.maxY, 0.01f)
        assertPoint(89.84f, 56.4f, frontLeft)
        assertPoint(310.16f, 56.4f, frontRight)
        assertPoint(89.84f, 283.6f, backLeft)
    }

    @Test
    fun `empty map draws nothing`() {
        val map = TheaterSeatMap(listOf("Seat" to Vec3.ZERO), bestSeat = Vec3.ZERO)
        assertTrue(map.seats.isEmpty())
        assertNull(map.best)
        val layout = map.layout(400f, 300f, 0f, 4f)
        assertTrue(layout.isEmpty)
        assertTrue(layout.points.isEmpty())
        assertNull(map.nearestSeat(0f, 0f, layout))
        assertEquals(0.6f, map.rowPitch) // the default pitch when no row has two seats
    }

    @Test
    fun `a map with no room to draw in is empty`() {
        val map = TheaterSeatMap(smallRoom, bestSeat = Vec3.ZERO)
        assertTrue(map.layout(0f, 300f, 0f, 4f).isEmpty)
        assertTrue(map.layout(400f, TheaterSeatMap.SCREEN_BAND, 0f, 4f).isEmpty)
        assertFalse(map.layout(400f, TheaterSeatMap.SCREEN_BAND + 1f, 0f, 4f).isEmpty)
    }

    @Test
    fun `row pitch is the upper median of the gaps between neighbours`() {
        // One row with an aisle: gaps 0.6, 0.6, 1.4, 0.6 → sorted, the element at index 2 is 0.6.
        val aisle = listOf(0f, 0.6f, 1.2f, 2.6f, 3.2f).mapIndexed { i, x -> SeatID(1, i + 1).markerName to Vec3(x, 0f, 0f) }
        assertEquals(0.6f, TheaterSeatMap(aisle, null).rowPitch, 1e-5f)
        // Two gaps: the upper one.
        val two = listOf(0f, 0.5f, 1.5f).mapIndexed { i, x -> SeatID(1, i + 1).markerName to Vec3(x, 0f, 0f) }
        assertEquals(1f, TheaterSeatMap(two, null).rowPitch, 1e-5f)
    }

    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>, tolerance: Float = 0.01f) {
        assertEquals(x, actual.first, tolerance, "x of $actual")
        assertEquals(y, actual.second, tolerance, "y of $actual")
    }

    private fun assertRect(x: Float, y: Float, width: Float, height: Float, actual: TheaterSeatMap.Rect, tolerance: Float = 0.01f) {
        assertEquals(x, actual.x, tolerance, "x of $actual")
        assertEquals(y, actual.y, tolerance, "y of $actual")
        assertEquals(width, actual.width, tolerance, "width of $actual")
        assertEquals(height, actual.height, tolerance, "height of $actual")
    }
}
