package com.babasama.edendale.android.theater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** The GLB has no usable `Screen` and `Seat`, or isn't a GLB at all. */
class TheaterSceneException(message: String) : Exception(message)

/**
 * The authored `Screen` relative to `Seat` (HORIZON.md G.7, D17): metres
 * ahead of the seat, above it, and to its right, and the quad's size.
 * Axis-free on purpose: the `.blend` is exported to glTF (Y up, -Z ahead),
 * and the SDK loads the mesh in its own frame, so what the app needs is
 * only how far ahead, up, and across the picture goes.
 */
data class ScreenNode(val ahead: Float, val up: Float, val right: Float, val width: Float, val height: Float)

/**
 * What the app reads out of a scene GLB from the `scenes` branch: the two
 * named nodes the export script guarantees. The mesh itself is the SDK's to
 * draw; this reads only the JSON chunk, never the geometry.
 */
data class TheaterScene(val screen: ScreenNode) {

    companion object {
        const val SCREEN = "Screen"
        const val SEAT = "Seat"

        private const val GLB_MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_JSON = 0x4E4F534A // "JSON"

        fun parse(stream: InputStream): TheaterScene = parse(stream.readBytes())

        /** Reads the GLB's JSON chunk and finds `Screen` and `Seat` by name. */
        fun parse(glb: ByteArray): TheaterScene {
            if (glb.size < 20) throw TheaterSceneException("not a GLB: ${glb.size} bytes")
            val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(0) != GLB_MAGIC) throw TheaterSceneException("not a GLB: bad magic")
            val version = buffer.getInt(4)
            if (version != 2) throw TheaterSceneException("GLB version $version, expected 2")
            val chunkLength = buffer.getInt(12)
            if (buffer.getInt(16) != CHUNK_JSON) throw TheaterSceneException("the first GLB chunk isn't JSON")
            if (chunkLength < 0 || 20 + chunkLength > glb.size) throw TheaterSceneException("truncated GLB")
            val json = Json.parseToJsonElement(String(glb, 20, chunkLength, Charsets.UTF_8)).jsonObject
            return fromDocument(json)
        }

        /** The same, from the glTF JSON (what `tools/_theater_scene.py` verifies on export). */
        fun fromDocument(document: JsonObject): TheaterScene {
            val nodes = document["nodes"]?.jsonArray ?: throw TheaterSceneException("the GLB has no nodes")
            val screenIndex = nodes.indexOfFirst { it.jsonObject.name() == SCREEN }
            val seatIndex = nodes.indexOfFirst { it.jsonObject.name() == SEAT }
            val missing = listOfNotNull(SCREEN.takeIf { screenIndex < 0 }, SEAT.takeIf { seatIndex < 0 })
            if (missing.isNotEmpty()) throw TheaterSceneException("the GLB has no node named ${missing.joinToString(" or ")}")

            val world = worldMatrices(document, nodes)
            val screenWorld = world[screenIndex]
            val seatWorld = world[seatIndex]
            val screenPosition = screenWorld.translation()
            val seatPosition = seatWorld.translation()

            val screenNode = nodes[screenIndex].jsonObject
            val meshIndex = screenNode["mesh"]?.jsonPrimitive?.int ?: throw TheaterSceneException("$SCREEN has no mesh")
            val (extentX, extentY, extentZ) = meshExtents(document, meshIndex)
            val scale = screenWorld.scale()
            val sizeX = extentX * scale[0]
            val sizeY = extentY * scale[1]
            val sizeZ = extentZ * scale[2]
            // A quad is flat on one axis; its width is its local X, its height whichever other axis spans it.
            val width = sizeX
            val height = max(sizeY, sizeZ)
            if (width <= 0f || height <= 0f) throw TheaterSceneException("$SCREEN has no size (${sizeX} × ${sizeY} × ${sizeZ})")

            return TheaterScene(
                ScreenNode(
                    // glTF: +X right, +Y up, -Z ahead.
                    ahead = -(screenPosition[2] - seatPosition[2]),
                    up = screenPosition[1] - seatPosition[1],
                    right = screenPosition[0] - seatPosition[0],
                    width = width,
                    height = height,
                ),
            )
        }

        private fun JsonObject.name(): String? = this["name"]?.jsonPrimitive?.content

        /** Each node's world matrix (column-major 4×4), from the scene roots down. */
        private fun worldMatrices(document: JsonObject, nodes: JsonArray): Array<FloatArray> {
            val result = Array(nodes.size) { IDENTITY.copyOf() }
            val children = nodes.flatMap { node -> node.jsonObject["children"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty() }.toSet()
            val roots = document["scenes"]?.jsonArray?.firstOrNull()?.jsonObject?.get("nodes")?.jsonArray?.map { it.jsonPrimitive.int }
                ?: nodes.indices.filter { it !in children }
            fun visit(index: Int, parent: FloatArray, depth: Int) {
                if (depth > nodes.size) throw TheaterSceneException("the node hierarchy loops")
                val node = nodes[index].jsonObject
                val world = multiply(parent, localMatrix(node))
                result[index] = world
                node["children"]?.jsonArray?.forEach { visit(it.jsonPrimitive.int, world, depth + 1) }
            }
            roots.forEach { visit(it, IDENTITY, 0) }
            return result
        }

        private fun localMatrix(node: JsonObject): FloatArray {
            node["matrix"]?.jsonArray?.let { m -> return FloatArray(16) { m[it].jsonPrimitive.float } }
            val t = node["translation"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(0f, 0f, 0f)
            val r = node["rotation"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(0f, 0f, 0f, 1f)
            val s = node["scale"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(1f, 1f, 1f)
            val (x, y, z, w) = r
            // Column-major, as glTF stores matrices: rotation columns scaled, then the translation.
            return floatArrayOf(
                (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0f,
                (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0f,
                (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0f,
                t[0], t[1], t[2], 1f,
            )
        }

        private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (column in 0 until 4) for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
                out[column * 4 + row] = sum
            }
            return out
        }

        private fun FloatArray.translation() = floatArrayOf(this[12], this[13], this[14])

        private fun FloatArray.scale() = floatArrayOf(
            length(this[0], this[1], this[2]),
            length(this[4], this[5], this[6]),
            length(this[8], this[9], this[10]),
        )

        private fun length(x: Float, y: Float, z: Float) = kotlin.math.sqrt(x * x + y * y + z * z)

        /** The mesh's bounds across its primitives, from the POSITION accessors' min and max (required by glTF). */
        private fun meshExtents(document: JsonObject, meshIndex: Int): Triple<Float, Float, Float> {
            val meshes = document["meshes"]?.jsonArray ?: throw TheaterSceneException("the GLB has no meshes")
            val accessors = document["accessors"]?.jsonArray ?: throw TheaterSceneException("the GLB has no accessors")
            val mesh = meshes.getOrNull(meshIndex)?.jsonObject ?: throw TheaterSceneException("mesh $meshIndex is missing")
            val mins = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val maxs = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var found = false
            mesh["primitives"]?.jsonArray?.forEach { primitive ->
                val position = primitive.jsonObject["attributes"]?.jsonObject?.get("POSITION")?.jsonPrimitive?.int ?: return@forEach
                val accessor = accessors.getOrNull(position)?.jsonObject ?: return@forEach
                val accessorMin = accessor["min"]?.jsonArray ?: return@forEach
                val accessorMax = accessor["max"]?.jsonArray ?: return@forEach
                for (axis in 0 until 3) {
                    mins[axis] = min(mins[axis], accessorMin[axis].jsonPrimitive.float)
                    maxs[axis] = max(maxs[axis], accessorMax[axis].jsonPrimitive.float)
                }
                found = true
            }
            if (!found) throw TheaterSceneException("$SCREEN's mesh has no POSITION bounds")
            return Triple(maxs[0] - mins[0], maxs[1] - mins[1], maxs[2] - mins[2])
        }

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}
