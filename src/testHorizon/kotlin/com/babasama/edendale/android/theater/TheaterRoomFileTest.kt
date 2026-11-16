package com.babasama.edendale.android.theater

import com.babasama.edendale.android.xr.TheaterGlbException
import com.babasama.edendale.android.xr.TheaterLights
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** HORIZON.md G.15: which of a prepared room's materials the house lights scale, and by how much. */
class TheaterRoomFileTest {

    private val document = """
        {
          "asset": {"version": "2.0"},
          "nodes": [
            {"name": "Room", "mesh": 0},
            {"name": "Screen", "mesh": 1, "translation": [0, 2, -5]},
            {"name": "Seat"},
            {"name": "Projector beam", "mesh": 2},
            {"name": "Props", "mesh": 3},
            {"name": "Odd", "mesh": 4}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0}, "material": 0}]},
            {"primitives": [{"attributes": {"POSITION": 1}, "material": 1}]},
            {"primitives": [{"attributes": {"POSITION": 0}, "material": 2}]},
            {"primitives": [{"attributes": {"POSITION": 0}, "material": 3}]},
            {"primitives": [{"attributes": {"POSITION": 0}, "material": 2}]}
          ],
          "accessors": [
            {"min": [-1, -1, -1], "max": [1, 1, 1]},
            {"min": [-2, -1.125, 0], "max": [2, 1.125, 0]}
          ],
          "materials": [
            {"name": "RoomBaked", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}},
            {"name": "ScreenSurface", "pbrMetallicRoughness": {"baseColorFactor": [0.012, 0.012, 0.012, 1]}},
            {"name": "ProjectorBeam", "alphaMode": "BLEND", "pbrMetallicRoughness": {"baseColorTexture": {"index": 1}, "baseColorFactor": [1, 0.714, 0.523, 1]}},
            {"name": "PropsBaked", "pbrMetallicRoughness": {"baseColorTexture": {"index": 2}, "baseColorFactor": [0.5, 0.5, 0.5, 1]}}
          ]
        }
    """.trimIndent()

    @Test
    fun onlyTheBakedOpaqueMaterialsDim() {
        val file = TheaterRoomFile.fromDocument(Json.parseToJsonElement(document).jsonObject)
        assertEquals(listOf(0 to "RoomBaked", 3 to "PropsBaked"), file.dimmable.map { it.index to it.name })
        assertEquals(listOf(1f, 1f, 1f, 1f), file.dimmable[0].baseColor)
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 1f), file.dimmable[1].baseColor)
    }

    @Test
    fun aMaterialTheBeamAlsoUsesIsLeftAlone() {
        // "Odd" draws with the beam's material: the beam's node keeps it untouched, blended or not.
        val shared = document.replace("\"alphaMode\": \"BLEND\", ", "")
        val file = TheaterRoomFile.fromDocument(Json.parseToJsonElement(shared).jsonObject)
        assertEquals(listOf("RoomBaked", "PropsBaked"), file.dimmable.map { it.name })
    }

    @Test
    fun eachLevelScalesTheColourAndKeepsTheAlpha() {
        val baked = DimmableMaterial(3, "PropsBaked", listOf(0.5f, 0.5f, 0.5f, 1f))
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 1f), baked.albedo(TheaterLights.NORMAL))
        assertEquals(listOf(0.15f, 0.15f, 0.15f, 1f), baked.albedo(TheaterLights.DIM).map { (it * 1000).toInt() / 1000f })
        assertEquals(listOf(0.03f, 0.03f, 0.03f, 1f), baked.albedo(TheaterLights.DARK).map { (it * 1000).toInt() / 1000f })
    }

    @Test
    fun theFileIsReadFromItsJsonChunkAlone() {
        val json = document.toByteArray(Charsets.UTF_8)
        val padded = json + ByteArray((4 - json.size % 4) % 4) { ' '.code.toByte() }
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(20 + padded.size + 12)
            .putInt(padded.size).putInt(0x4E4F534A)
            .array()
        // The binary chunk's header promises bytes that aren't there: never read.
        val bin = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(4096).putInt(0x004E4942).array()
        val file = TheaterRoomFile.read(ByteArrayInputStream(header + padded + bin))
        assertEquals(2, file.dimmable.size)
        assertEquals(4f, file.glb.screenBoundsMax.x - file.glb.screenBoundsMin.x, 1e-4f)
    }

    @Test
    fun anythingButAGlbIsRefused() {
        assertFailsWith<TheaterGlbException> { TheaterRoomFile.read(ByteArrayInputStream(ByteArray(40))) }
        assertFailsWith<TheaterGlbException> { TheaterRoomFile.read(ByteArrayInputStream(ByteArray(4))) }
    }
}
