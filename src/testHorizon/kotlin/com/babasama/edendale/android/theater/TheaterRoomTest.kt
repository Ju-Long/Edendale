package com.babasama.edendale.android.theater

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
 * HORIZON.md G.14: the three rooms, and the GLBs that ship for them in
 * `src/horizonTheater/assets/theater/` (exported from `scenes` and prepared
 * by `tools/horizon/prepare_theater_glb.py`).
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

    /** Where the scenes' READMEs say each screen is: width, distance ahead, and its centre's height. */
    @Test
    fun eachRoomsScreenIsWhereItsSceneSaysItIs() {
        val expected = mapOf(
            TheaterRoom.MOVIE_THEATRE to Triple(12f, 14f, 2.4f),
            TheaterRoom.PROJECTOR_ROOM to Triple(5.6f, 7.4f, 2.725f),
            TheaterRoom.PERFORMANCE_THEATRE to Triple(11f, 14f, 3.09375f),
        )
        TheaterRoom.entries.forEach { room ->
            val scene = File(assetDirectory, "${room.id}.glb").inputStream().use { TheaterScene.parse(it) }
            val (width, ahead, up) = expected.getValue(room)
            assertEquals(width, scene.screen.width, 0.01f, "${room.id} width")
            assertEquals(ahead, scene.screen.ahead, 0.01f, "${room.id} distance")
            assertEquals(up, scene.screen.up, 0.01f, "${room.id} height")
            assertEquals(0f, scene.screen.right, 0.01f, "${room.id} is centred")
            // Sized for a 16:9 picture (the scenes' authoring rule).
            assertEquals(16f / 9f, scene.screen.width / scene.screen.height, 0.02f, "${room.id} aspect")
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

    private fun glbJson(glb: ByteArray): String {
        val length = ByteBuffer.wrap(glb, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return String(glb, 20, length, Charsets.UTF_8)
    }
}
