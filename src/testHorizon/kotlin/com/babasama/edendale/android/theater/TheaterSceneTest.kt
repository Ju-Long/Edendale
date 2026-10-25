package com.babasama.edendale.android.theater

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * HORIZON.md G.T1: the GLB reader finds `Screen` and `Seat` the way
 * `tools/export_gltf.py` on the `scenes` branch writes them (Y up, -Z ahead,
 * the quad's size in its POSITION accessor's bounds).
 */
class TheaterSceneTest {

    /** The G.13 check scene: a 4 m screen 5 m ahead at 1.5 m, the seat at the origin, a floor. */
    private val checkScene = """
        {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1,2]}],
         "nodes":[
           {"name":"Screen","mesh":0,"translation":[0,1.5,-5],"rotation":[0.7071068,0,0,0.7071068]},
           {"name":"Seat"},
           {"name":"Floor","mesh":1}],
         "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]},{"primitives":[{"attributes":{"POSITION":1}}]}],
         "accessors":[
           {"componentType":5126,"count":4,"type":"VEC3","min":[-2,-1.125,0],"max":[2,1.125,0]},
           {"componentType":5126,"count":4,"type":"VEC3","min":[-5,0,-10],"max":[5,0,5]}]}
    """.trimIndent()

    @Test
    fun readsTheScreenRelativeToTheSeat() {
        val scene = TheaterScene.parse(glb(checkScene))
        assertNode(ScreenNode(ahead = 5f, up = 1.5f, right = 0f, width = 4f, height = 2.25f), scene.screen)
    }

    @Test
    fun followsParentsAndScales() {
        // Everything under a Room node that is moved 1 m right and 2 m ahead; the screen is scaled up twofold.
        val json = """
            {"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],
             "nodes":[
               {"name":"Room","translation":[1,0,-2],"children":[1,2]},
               {"name":"Screen","mesh":0,"translation":[0.25,1.5,-3],"scale":[2,2,1]},
               {"name":"Seat","translation":[0,0,0]}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
             "accessors":[{"componentType":5126,"count":4,"type":"VEC3","min":[-1,-0.5625,0],"max":[1,0.5625,0]}]}
        """.trimIndent()
        val scene = TheaterScene.parse(glb(json))
        assertEquals(3f, scene.screen.ahead, 1e-5f)
        assertEquals(1.5f, scene.screen.up, 1e-5f)
        assertEquals(0.25f, scene.screen.right, 1e-5f)
        assertEquals(4f, scene.screen.width, 1e-5f)
        assertEquals(2.25f, scene.screen.height, 1e-5f)
    }

    @Test
    fun aQuadAlreadyLyingInXZKeepsItsHeight() {
        val json = """
            {"asset":{"version":"2.0"},
             "nodes":[{"name":"Screen","mesh":0,"translation":[0,1.5,-5]},{"name":"Seat"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
             "accessors":[{"componentType":5126,"count":4,"type":"VEC3","min":[-2,0,-1.125],"max":[2,0,1.125]}]}
        """.trimIndent()
        assertNode(ScreenNode(5f, 1.5f, 0f, 4f, 2.25f), TheaterScene.parse(glb(json)).screen)
    }

    @Test
    fun aMissingNodeIsNamed() {
        val json = """{"asset":{"version":"2.0"},"nodes":[{"name":"Screen","mesh":0}],"meshes":[],"accessors":[]}"""
        val error = assertFailsWith<TheaterSceneException> { TheaterScene.parse(glb(json)) }
        assertTrue("Seat" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aScreenWithoutAMeshFails() {
        val json = """{"asset":{"version":"2.0"},"nodes":[{"name":"Screen"},{"name":"Seat"}]}"""
        assertFailsWith<TheaterSceneException> { TheaterScene.parse(glb(json)) }
    }

    @Test
    fun somethingThatIsNotAGlbFails() {
        assertFailsWith<TheaterSceneException> { TheaterScene.parse("not a glb at all, just text".toByteArray()) }
        assertFailsWith<TheaterSceneException> { TheaterScene.parse(ByteArray(0)) }
    }

    /** Field by field, within float rounding (a 45° quaternion's scale reads 1.0000001). */
    private fun assertNode(expected: ScreenNode, actual: ScreenNode) {
        assertEquals(expected.ahead, actual.ahead, 1e-5f, "ahead of $actual")
        assertEquals(expected.up, actual.up, 1e-5f, "up of $actual")
        assertEquals(expected.right, actual.right, 1e-5f, "right of $actual")
        assertEquals(expected.width, actual.width, 1e-5f, "width of $actual")
        assertEquals(expected.height, actual.height, 1e-5f, "height of $actual")
    }

    /** A GLB with only a JSON chunk: the header, the chunk, and the 4-byte padding the format asks for. */
    private fun glb(json: String): ByteArray {
        val content = json.toByteArray(Charsets.UTF_8)
        val padded = content.size + (4 - content.size % 4) % 4
        val total = 12 + 8 + padded
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46546C67).putInt(2).putInt(total)
        buffer.putInt(padded).putInt(0x4E4F534A).put(content)
        repeat(padded - content.size) { buffer.put(0x20) }
        return buffer.array()
    }
}
