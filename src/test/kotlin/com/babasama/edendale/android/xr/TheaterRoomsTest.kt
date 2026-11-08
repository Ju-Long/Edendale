package com.babasama.edendale.android.xr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N.T1, the visionOS `TheaterLayoutTests` room and lights cases that need no
 * asset on disk, and the preference keys of N-D10.
 */
class TheaterRoomsTest {

    @Test
    fun `every room has its own export`() {
        val rooms = TheaterRoom.entries
        assertEquals(listOf("movie-theatre", "projector-room", "performance-theatre"), rooms.map { it.id })
        assertEquals(rooms.size, rooms.map { it.id }.toSet().size)
        assertEquals(rooms.size, rooms.map { it.assetPath }.toSet().size)
        for (room in rooms) {
            assertTrue(room.id.isNotEmpty())
            assertEquals("theater/${room.id}.glb", room.assetPath)
            assertEquals(room, TheaterRoom.fromId(room.id))
        }
    }

    @Test
    fun `an unknown room is the default`() {
        assertEquals(TheaterRoom.MOVIE_THEATRE, TheaterRoom.DEFAULT)
        assertEquals(TheaterRoom.DEFAULT, TheaterRoom.fromId(null))
        assertEquals(TheaterRoom.DEFAULT, TheaterRoom.fromId(""))
        assertEquals(TheaterRoom.DEFAULT, TheaterRoom.fromId("ballroom"))
        assertEquals(TheaterRoom.PERFORMANCE_THEATRE, TheaterRoom.fromId("performance-theatre"))
    }

    @Test
    fun `lights get darker down the list`() {
        val lights = TheaterLights.entries
        assertEquals(1f, lights.first().brightness)
        for ((brighter, darker) in lights.zipWithNext()) {
            assertTrue(brighter.brightness > darker.brightness, "$brighter before $darker")
        }
        assertTrue(lights.all { it.brightness > 0f })
        assertEquals(lights.size, lights.map { it.raw }.toSet().size)
        assertEquals(listOf(1f, 0.3f, 0.06f), lights.map { it.brightness })
    }

    @Test
    fun `lights are stored by raw value and fall back to normal`() {
        for (level in TheaterLights.entries) assertEquals(level, TheaterLights.fromRaw(level.raw))
        assertEquals(TheaterLights.NORMAL, TheaterLights.fromRaw(null))
        assertEquals(TheaterLights.NORMAL, TheaterLights.fromRaw("bright"))
        assertEquals(TheaterLights.NORMAL, TheaterLights.fromRaw("Dim")) // raw values are lower case
    }

    @Test
    fun `preferences keep the keys of the plan`() {
        assertEquals("theater", TheaterPreferencesRules.PREFS_NAME)
        assertEquals("room", TheaterPreferencesRules.KEY_ROOM)
        assertEquals("lights", TheaterPreferencesRules.KEY_LIGHTS)
        assertEquals("seat.movie-theatre", TheaterPreferencesRules.seatKey(TheaterRoom.MOVIE_THEATRE))
        assertEquals("seat.projector-room", TheaterPreferencesRules.seatKey(TheaterRoom.PROJECTOR_ROOM))
        assertEquals("seat.performance-theatre", TheaterPreferencesRules.seatKey(TheaterRoom.PERFORMANCE_THEATRE))
        assertEquals(setOf("Screen", "Projector_beam"), TheaterPreferencesRules.UNTOUCHED_NODES)
    }

    @Test
    fun `a stored seat parses or means the best seat`() {
        // The visionOS controller test's stored values: "4-9" is a seat, "rubbish" is the best seat.
        assertEquals(SeatID(4, 9), SeatID.fromStorageValue("4-9"))
        assertNull(SeatID.fromStorageValue("rubbish"))
    }
}
