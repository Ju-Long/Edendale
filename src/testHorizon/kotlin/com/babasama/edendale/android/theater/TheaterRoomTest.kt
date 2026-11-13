package com.babasama.edendale.android.theater

import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterSeatMap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HORIZON.md G.14, G.15: the three rooms, and the GLBs that ship for them in
 * `src/horizonTheater/assets/theater/` (exported from `scenes` at adad81e
 * and prepared by `tools/horizon/prepare_theater_glb.py`).
 */
class TheaterRoomTest {

    /** Gradle runs unit tests in the project directory. */
    private val assetDirectory = File("src/horizonTheater/assets/${TheaterRoom.ASSET_DIRECTORY}")

    @Test
    fun aStoredRoomComesBackAndAnythingElseIsTheCinema() {
        TheaterRoom.entries.forEach { assertEquals(it, TheaterRoom.fromId(it.id)) }
        assertEquals(TheaterRoom.MOVIE_THEATRE, TheaterRoom.fromId(null))
        assertEquals(TheaterRoom.MOVIE_THEATRE, TheaterRoom.fromId("theater"))
        assertEquals(TheaterRoom.MOVIE_THEATRE, TheaterRoom.DEFAULT)
    }

    @Test
    fun theIdsAreTheScenesFoldersAndTheVisionOSRooms() {
        assertEquals(listOf("movie-theatre", "projector-room", "performance-theatre"), TheaterRoom.entries.map { it.id })
        assertEquals("theater/projector-room.glb", TheaterRoom.PROJECTOR_ROOM.assetPath)
        assertEquals("apk:///theater/projector-room.glb", TheaterRoom.PROJECTOR_ROOM.meshUri)
    }

    @Test
    fun everyRoomShipsAndNothingElseDoes() {
        val shipped = assetDirectory.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(TheaterRoom.entries.map { "${it.id}.glb" }.sorted(), shipped)
    }

    /**
     * Where each screen is in the `scenes` export at adad81e (the bigger
     * screens: the viewer sits closer): width, distance ahead of `Seat`, and
     * its centre's height.
     */
    @Test
    fun eachRoomsScreenIsWhereItsSceneSaysItIs() {
        val expected = mapOf(
            TheaterRoom.MOVIE_THEATRE to Triple(12f, 10.55f, 3.6f),
            TheaterRoom.PROJECTOR_ROOM to Triple(5.6f, 5.478f, 2.725f),
            TheaterRoom.PERFORMANCE_THEATRE to Triple(11f, 9.25f, 3.84375f),
        )
        TheaterRoom.entries.forEach { room ->
            val glb = roomFile(room).glb
            val (width, ahead, up) = expected.getValue(room)
            val min = glb.screenBoundsMin
            val max = glb.screenBoundsMax
            assertEquals(width, max.x - min.x, 0.01f, "${room.id} width")
            assertEquals(ahead, glb.seat.z - (min.z + max.z) / 2f, 0.01f, "${room.id} distance")
            assertEquals(up, (min.y + max.y) / 2f - glb.seat.y, 0.01f, "${room.id} height")
            assertEquals(0f, (min.x + max.x) / 2f - glb.seat.x, 0.01f, "${room.id} is centred")
            // Sized for a 16:9 picture (the scenes' authoring rule).
            assertEquals(16f / 9f, (max.x - min.x) / (max.y - min.y), 0.02f, "${room.id} aspect")
        }
    }

    /** G.15: every room carries its seat map, and the author's `Seat` is one of its seats (the best seat). */
    @Test
    fun eachRoomHasItsSeatMapAndABestSeat() {
        val expected = mapOf(
            TheaterRoom.MOVIE_THEATRE to (209 to SeatID(3, 10)),
            TheaterRoom.PROJECTOR_ROOM to (54 to SeatID(2, 5)),
            TheaterRoom.PERFORMANCE_THEATRE to (384 to SeatID(4, 14)),
        )
        TheaterRoom.entries.forEach { room ->
            val glb = roomFile(room).glb
            val map = TheaterSeatMap(glb.seatMarkers, glb.seat)
            val (count, best) = expected.getValue(room)
            assertEquals(count, map.seats.size, "${room.id} seats")
            assertEquals(count, glb.seatMarkers.size, "${room.id} markers are all seats")
            assertEquals(best, map.best?.id, "${room.id} best seat")
        }
    }

    /**
     * G.15: the house lights scale the baked atlases and nothing else: the
     * screen's flat glow and the projector's blended beam stay as they are.
     */
    @Test
    fun theLightsDimTheBakedMaterialsOnly() {
        val expected = mapOf(
            TheaterRoom.MOVIE_THEATRE to setOf("RoomBaked", "SeatsBaked"),
            TheaterRoom.PROJECTOR_ROOM to setOf("ChairsBaked", "PropsBaked", "RoomBaked"),
            TheaterRoom.PERFORMANCE_THEATRE to setOf("DrapesBaked", "RoomBaked", "SeatsBaked"),
        )
        TheaterRoom.entries.forEach { room ->
            val dimmable = roomFile(room).dimmable
            assertEquals(expected.getValue(room), dimmable.mapNotNull { it.name }.toSet(), "${room.id} dims its baked materials")
            dimmable.forEach { assertEquals(listOf(1f, 1f, 1f, 1f), it.baseColor, "${room.id}/${it.name} starts at full brightness") }
        }
    }

    /** The Spatial SDK draws them unlit only if every material says so (prepare_theater_glb.py). */
    @Test
    fun everyMaterialIsUnlitAndNothingGlows() {
        TheaterRoom.entries.forEach { room ->
            val document = Json.parseToJsonElement(glbJson(File(assetDirectory, "${room.id}.glb").readBytes())).jsonObject
            val used = document["extensionsUsed"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue("KHR_materials_unlit" in used, "${room.id} declares the extension")
            val materials = document["materials"]?.jsonArray.orEmpty().map { it.jsonObject }
            assertTrue(materials.isNotEmpty(), "${room.id} has materials")
            materials.forEach { material ->
                val name = "${room.id}/${material["name"]?.jsonPrimitive?.content}"
                assertTrue(material["extensions"]?.jsonObject?.containsKey("KHR_materials_unlit") == true, "$name is unlit")
                assertTrue("emissiveTexture" !in material && "emissiveFactor" !in material, "$name has no emission left")
            }
        }
    }

    private fun roomFile(room: TheaterRoom): TheaterRoomFile =
        File(assetDirectory, "${room.id}.glb").inputStream().use { TheaterRoomFile.read(it) }

    private fun glbJson(glb: ByteArray): String {
        val length = ByteBuffer.wrap(glb, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return String(glb, 20, length, Charsets.UTF_8)
    }
}
