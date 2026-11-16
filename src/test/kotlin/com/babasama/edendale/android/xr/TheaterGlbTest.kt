package com.babasama.edendale.android.xr

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * N.T1: the Quest theater's eight GLB cases, adapted to world-space screen
 * bounds, plus the `SeatMap` markers and the mesh node names.
 */
class TheaterGlbTest {

    @Test
    fun readsTheSeatAndTheScreenBoundsFromTheCheckScene() {
        val glb = TheaterGlb.parse(glb(CHECK_SCENE))
        assertVec(Vec3(0f, 0f, 0f), glb.seat)
        // 90° about X turns the quad's local Y into world Z: 4 m across X,
        // 2.25 m along Z, flat in Y, centred 5 m ahead at 1.5 m.
        assertVec(Vec3(-2f, 1.5f, -6.125f), glb.screenBoundsMin)
        assertVec(Vec3(2f, 1.5f, -3.875f), glb.screenBoundsMax)
        assertVec(Vec3(0f, 1.5f, -5f), (glb.screenBoundsMin + glb.screenBoundsMax) * 0.5f)
        assertEquals(emptyList(), glb.seatMarkers)
    }

    @Test
    fun aStreamIsReadOnlyAsFarAsTheJsonChunk() {
        val glb = glb(CHECK_SCENE)
        val stream = TricklingStream(glb + ByteArray(64 * 1024) { 0x55 })
        val parsed = TheaterGlb.parse(stream)
        assertVec(Vec3(0f, 1.5f, -5f), (parsed.screenBoundsMin + parsed.screenBoundsMax) * 0.5f)
        assertEquals(glb.size, stream.consumed)
    }

    @Test
    fun aTruncatedStreamFails() {
        val glb = glb(CHECK_SCENE)
        val short = glb.copyOf(glb.size - 3)
        val fromStream = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(ByteArrayInputStream(short)) }
        assertEquals("truncated GLB", fromStream.message)
        val fromBytes = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(short) }
        assertEquals("truncated GLB", fromBytes.message)
    }

    @Test
    fun followsParentsAndScales() {
        val glb = TheaterGlb.parse(glb(ROOM_SCENE))
        assertVec(Vec3(1f, 0f, -2f), glb.seat)
        assertVec(Vec3(-0.75f, 0.375f, -5f), glb.screenBoundsMin)
        assertVec(Vec3(3.25f, 2.625f, -5f), glb.screenBoundsMax)
        val size = glb.screenBoundsMax - glb.screenBoundsMin
        assertVec(Vec3(1.25f, 1.5f, -5f), (glb.screenBoundsMin + glb.screenBoundsMax) * 0.5f)
        assertEquals(4f, size.x, 1e-5f)
        assertEquals(2.25f, size.y, 1e-5f)
    }

    @Test
    fun rootsAreInferredWithoutAScenesArray() {
        // Without `scenes`, the nodes nobody lists as a child are the roots; the
        // parent still composes, so the seat is not left at the identity.
        val json = """
            {"nodes":[
              {"name":"Room","translation":[0,0,-1],"children":[1,2]},
              {"name":"Screen","mesh":0,"translation":[0,1.5,-5]},
              {"name":"Seat"}
            ],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"min":[-2,0,-1.125],"max":[2,0,1.125]}]}
        """
        val glb = TheaterGlb.parse(glb(json))
        assertVec(Vec3(0f, 0f, -1f), glb.seat)
        assertVec(Vec3(-2f, 1.5f, -7.125f), glb.screenBoundsMin)
        assertVec(Vec3(2f, 1.5f, -4.875f), glb.screenBoundsMax)
    }

    @Test
    fun aMissingNodeIsNamed() {
        val onlyScreen = """{"nodes":[{"name":"Screen","mesh":0}],"meshes":[],"accessors":[]}"""
        val missingSeat = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb(onlyScreen)) }
        assertTrue("Seat" in missingSeat.message!!, missingSeat.message)
        assertEquals("the GLB has no node named Seat", missingSeat.message)

        val neither = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb("""{"nodes":[{"name":"Floor"}]}""")) }
        assertEquals("the GLB has no node named Screen or Seat", neither.message)

        val noNodes = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb("""{"asset":{"version":"2.0"}}""")) }
        assertEquals("the GLB has no nodes", noNodes.message)
    }

    @Test
    fun aScreenWithoutAMeshFails() {
        val json = """{"nodes":[{"name":"Screen"},{"name":"Seat"}]}"""
        val e = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb(json)) }
        assertEquals("Screen has no mesh", e.message)
    }

    @Test
    fun aScreenMeshWithoutBoundsOrSizeFails() {
        val noBounds = """
            {"nodes":[{"name":"Screen","mesh":0},{"name":"Seat"}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"count":4}]}
        """
        assertEquals(
            "Screen's mesh has no POSITION bounds",
            assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb(noBounds)) }.message,
        )
        val point = """
            {"nodes":[{"name":"Screen","mesh":0},{"name":"Seat"}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"min":[1,2,3],"max":[1,2,3]}]}
        """
        assertEquals(
            "Screen has no size (0.0 × 0.0 × 0.0)",
            assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb(point)) }.message,
        )
        val missingMesh = """
            {"nodes":[{"name":"Screen","mesh":3},{"name":"Seat"}],
            "meshes":[{"primitives":[]}],"accessors":[]}
        """
        assertEquals(
            "mesh 3 is missing",
            assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb(missingMesh)) }.message,
        )
    }

    @Test
    fun somethingThatIsNotAGlbFails() {
        val text = assertFailsWith<TheaterGlbException> { TheaterGlb.parse("not a glb at all, just text".toByteArray()) }
        assertEquals("not a GLB: bad magic", text.message)
        val empty = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(ByteArray(0)) }
        assertEquals("not a GLB: 0 bytes", empty.message)
        assertFailsWith<TheaterGlbException> { TheaterGlb.parse(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun theHeaderIsChecked() {
        val glb = glb(CHECK_SCENE)
        val version1 = glb.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1) }
        assertEquals(
            "GLB version 1, expected 2",
            assertFailsWith<TheaterGlbException> { TheaterGlb.parse(version1) }.message,
        )
        val binFirst = glb.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0x004E4942) }
        assertEquals(
            "the first GLB chunk isn't JSON",
            assertFailsWith<TheaterGlbException> { TheaterGlb.parse(binFirst) }.message,
        )
        val overlong = glb.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 17 * 1024 * 1024) }
        assertEquals(
            "truncated GLB",
            assertFailsWith<TheaterGlbException> { TheaterGlb.parse(ByteArrayInputStream(overlong)) }.message,
        )
        val notJson = glb(CHECK_SCENE.replace("\"nodes\"", "nodes"))
        assertFailsWith<TheaterGlbException> { TheaterGlb.parse(notJson) }
    }

    @Test
    fun seatMapChildrenBecomeMarkersThroughTheirParent() {
        val json = """
            {"scenes":[{"nodes":[0,1,2]}],
            "nodes":[
              {"name":"Screen","mesh":0,"translation":[0,1.5,-5]},
              {"name":"Seat"},
              {"name":"SeatMap","translation":[0.5,0,0],"children":[3,4,5]},
              {"name":"Seat_01_01","translation":[-1,0,-2]},
              {"name":"Seat_01_02","translation":[1,0,-2]},
              {"translation":[0,0,0]}
            ],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"min":[-2,-1.125,0],"max":[2,1.125,0]}]}
        """
        val markers = TheaterGlb.parse(glb(json)).seatMarkers
        assertEquals(listOf("Seat_01_01", "Seat_01_02", ""), markers.map { it.first })
        assertVec(Vec3(-0.5f, 0f, -2f), markers[0].second)
        assertVec(Vec3(1.5f, 0f, -2f), markers[1].second)
        assertVec(Vec3(0.5f, 0f, 0f), markers[2].second)

        assertEquals(emptyList(), TheaterGlb.parse(glb(CHECK_SCENE)).seatMarkers)
    }

    @Test
    fun aMatrixNodeIsHonoured() {
        // Column-major: the translation sits in elements 12–14.
        val json = """
            {"nodes":[
              {"name":"Screen","mesh":0,"matrix":[1,0,0,0, 0,1,0,0, 0,0,1,0, 0.5,1.5,-5,1]},
              {"name":"Seat","matrix":[1,0,0,0, 0,1,0,0, 0,0,1,0, 0.25,0,-0.5,1]}
            ],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"min":[-2,-1.125,0],"max":[2,1.125,0]}]}
        """
        val glb = TheaterGlb.parse(glb(json))
        assertVec(Vec3(0.25f, 0f, -0.5f), glb.seat)
        assertVec(Vec3(-1.5f, 0.375f, -5f), glb.screenBoundsMin)
        assertVec(Vec3(2.5f, 2.625f, -5f), glb.screenBoundsMax)
    }

    @Test
    fun aLoopingHierarchyIsRefused() {
        val json = """
            {"scenes":[{"nodes":[0]}],
            "nodes":[
              {"name":"Room","children":[1]},
              {"name":"Screen","mesh":0,"children":[2]},
              {"name":"Seat","children":[0]}
            ],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"min":[-2,-1.125,0],"max":[2,1.125,0]}]}
        """
        val e = assertFailsWith<TheaterGlbException> { TheaterGlb.parse(glb(json)) }
        assertEquals("the node hierarchy loops", e.message)
    }

    @Test
    fun meshNodeNamesComeInNodeOrderWithoutEmpties() {
        val json = """
            {"nodes":[
              {"name":"Room","mesh":1,"children":[1,2,3,4,5]},
              {"name":"Screen","mesh":0},
              {"name":"Seat"},
              {"name":"Seats","mesh":2},
              {"mesh":2},
              {"name":"","mesh":2}
            ],
            "meshes":[
              {"primitives":[{"attributes":{"POSITION":0}}]},
              {"primitives":[{"attributes":{"POSITION":1}}]},
              {"primitives":[{"attributes":{"POSITION":1}}]}
            ],
            "accessors":[{"min":[-2,-1.125,0],"max":[2,1.125,0]},{"min":[-5,0,-10],"max":[5,0,5]}]}
        """
        assertEquals(listOf("Room", "Screen", "Seats"), TheaterGlb.parse(glb(json)).meshNodeNames)
        assertEquals(listOf("Screen", "Floor"), TheaterGlb.parse(glb(CHECK_SCENE)).meshNodeNames)
    }

    @Test
    fun bakedNodesMapToTheirAtlasImages() {
        val json = """
            {"nodes":[
              {"name":"Room","mesh":0},
              {"name":"Screen","mesh":1},
              {"name":"Seat"},
              {"name":"Projector beam","mesh":2},
              {"name":"Seats","mesh":3}
            ],
            "meshes":[
              {"primitives":[{"attributes":{"POSITION":0},"material":0}]},
              {"primitives":[{"attributes":{"POSITION":0},"material":1}]},
              {"primitives":[{"attributes":{"POSITION":0},"material":2}]},
              {"primitives":[{"attributes":{"POSITION":0}}]}
            ],
            "materials":[
              {"name":"RoomBaked","emissiveTexture":{"index":0},"emissiveFactor":[1,1,1]},
              {"name":"ScreenSurface","emissiveTexture":{"index":0},"emissiveFactor":[0.012,0.012,0.012]},
              {"name":"ProjectorBeam","pbrMetallicRoughness":{"baseColorTexture":{"index":1}}}
            ],
            "textures":[{"source":1},{"source":0}],
            "images":[
              {"name":"beam","mimeType":"image/png","bufferView":0},
              {"name":"room","mimeType":"image/jpeg","bufferView":1},
              {"name":"elsewhere","uri":"room.jpg"}
            ],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":100},{"buffer":0,"byteOffset":100,"byteLength":2500}],
            "accessors":[{"min":[-2,-1.125,0],"max":[2,1.125,0]}]}
        """
        val parsed = TheaterGlb.parse(glb(json))
        assertEquals(mapOf("Room" to 1, "Screen" to 1), parsed.emissiveImageByNode)
        assertEquals(listOf(GlbImage(0, "beam", "image/png"), GlbImage(1, "room", "image/jpeg")), parsed.images, "the image with a URI is not in the file")
        assertEquals(mapOf("Room" to 1), TheaterLightsRules.dimmableNodes(parsed), "the screen is never dimmed")
    }

    @Test
    fun theUntouchedRuleReadsBlenderSpacesAsUnderscores() {
        assertTrue(TheaterLightsRules.untouched("Projector beam"))
        assertTrue(TheaterLightsRules.untouched("Projector_beam"))
        assertTrue(TheaterLightsRules.untouched("Screen"))
        assertFalse(TheaterLightsRules.untouched("Seats"))
        assertFalse(TheaterLightsRules.untouched("Room"))
    }

    @Test
    fun aDocumentParsesWithoutAContainer() {
        val document = Json.parseToJsonElement(ROOM_SCENE).jsonObject
        assertEquals(TheaterGlb.parse(glb(ROOM_SCENE)), TheaterGlb.fromDocument(document))
    }

    private companion object {
        /** The Quest's check scene: a 4 × 2.25 m quad lying in XY, stood up 90° about X, 5 m ahead at 1.5 m. */
        const val CHECK_SCENE = """
            {"scenes":[{"nodes":[0,1,2]}],
            "nodes":[
              {"name":"Screen","mesh":0,"translation":[0,1.5,-5],"rotation":[0.7071068,0,0,0.7071068]},
              {"name":"Seat"},
              {"name":"Floor","mesh":1}
            ],
            "meshes":[
              {"primitives":[{"attributes":{"POSITION":0}}]},
              {"primitives":[{"attributes":{"POSITION":1}}]}
            ],
            "accessors":[
              {"min":[-2,-1.125,0],"max":[2,1.125,0]},
              {"min":[-5,0,-10],"max":[5,0,5]}
            ]}
        """

        /** A room node carrying the screen and the seat; the screen scaled 2× from a 2 × 1.125 m quad. */
        const val ROOM_SCENE = """
            {"scenes":[{"nodes":[0]}],
            "nodes":[
              {"name":"Room","translation":[1,0,-2],"children":[1,2]},
              {"name":"Screen","mesh":0,"translation":[0.25,1.5,-3],"scale":[2,2,1]},
              {"name":"Seat","translation":[0,0,0]}
            ],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"min":[-1,-0.5625,0],"max":[1,0.5625,0]}]}
        """

        /** A GLB holding only a JSON chunk, padded with spaces to a multiple of four as the format asks. */
        fun glb(json: String): ByteArray {
            val content = json.toByteArray(Charsets.UTF_8)
            val padded = content.size + (4 - content.size % 4) % 4
            val chunk = ByteArray(padded) { 0x20 }
            content.copyInto(chunk)
            return ByteBuffer.allocate(20 + padded).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67).putInt(2).putInt(20 + padded)
                .putInt(padded).putInt(0x4E4F534A)
                .put(chunk)
                .array()
        }

        /** Per component, because a 45° quaternion's rotation reads 1.0000001 in floats. */
        fun assertVec(expected: Vec3, actual: Vec3, tolerance: Float = 1e-5f) {
            assertTrue(
                abs(expected.x - actual.x) <= tolerance &&
                    abs(expected.y - actual.y) <= tolerance &&
                    abs(expected.z - actual.z) <= tolerance,
                "expected $expected, got $actual",
            )
        }
    }

    /** Hands out at most seven bytes per read and counts what was taken. */
    private class TricklingStream(private val bytes: ByteArray) : InputStream() {
        var consumed = 0

        override fun read(): Int = if (consumed < bytes.size) bytes[consumed++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (consumed >= bytes.size) return -1
            val n = minOf(7, len, bytes.size - consumed)
            System.arraycopy(bytes, consumed, b, off, n)
            consumed += n
            return n
        }
    }
}
