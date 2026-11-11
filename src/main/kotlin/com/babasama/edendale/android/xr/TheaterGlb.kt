package com.babasama.edendale.android.xr

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** The GLB has no usable `Screen` and `Seat`, or isn't a GLB at all. */
class TheaterGlbException(message: String) : Exception(message)

/** An image embedded in the GLB (the `images` entry at [index]); the build copies it out as an asset for the lights. */
data class GlbImage(val index: Int, val name: String, val mimeType: String?)

/**
 * What a room's GLB says about where the viewer sits and where the picture goes,
 * in the room's own frame, read from the file's JSON chunk alone (never the
 * megabytes of textures behind it).
 */
data class TheaterGlb(
    /** World position of the node named `Seat` (the floor under the best seat; usually the origin). */
    val seat: Vec3,
    /** World-space axis-aligned bounds of the `Screen` node's mesh. */
    val screenBoundsMin: Vec3,
    val screenBoundsMax: Vec3,
    /** The children of the node named `SeatMap`, as (node name, world position), in node order; empty without a `SeatMap`. */
    val seatMarkers: List<Pair<String, Vec3>>,
    /** Names of the nodes that carry a mesh, in node order (the lights rule leaves `Screen` and `Projector_beam` alone). */
    val meshNodeNames: List<String>,
    /** The embedded images in index order; one with a URI instead of a buffer view is left out. */
    val images: List<GlbImage> = emptyList(),
    /** Mesh node name → the image behind its first primitive's material's emissive texture: the baked rooms' atlases. */
    val emissiveImageByNode: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val SCREEN = "Screen"
        const val SEAT = "Seat"
        const val SEAT_MAP = "SeatMap"

        private const val GLB_MAGIC = 0x46546C67 // "glTF", little-endian
        private const val CHUNK_JSON = 0x4E4F534A // "JSON"
        /** The 12-byte GLB header and the first chunk's 8-byte header. */
        private const val HEADER_BYTES = 20
        /** Far above any room's scene description; a bogus length fails before it allocates. */
        private const val MAX_JSON_BYTES = 16 * 1024 * 1024

        /** Reads the header and the JSON chunk and stops: the BIN chunk behind them is the textures. */
        fun parse(stream: InputStream): TheaterGlb {
            val chunkLength = checkHeader(stream.readExactly(HEADER_BYTES), available = null)
            return fromJsonChunk(stream.readExactly(chunkLength))
        }

        fun parse(glb: ByteArray): TheaterGlb {
            if (glb.size < HEADER_BYTES) throw TheaterGlbException("not a GLB: ${glb.size} bytes")
            val chunkLength = checkHeader(glb.copyOf(HEADER_BYTES), available = glb.size - HEADER_BYTES)
            return fromJsonChunk(glb.copyOfRange(HEADER_BYTES, HEADER_BYTES + chunkLength))
        }

        /** The same from the glTF document, so the exporter's check and the tests need no container. */
        fun fromDocument(document: JsonObject): TheaterGlb {
            val nodes = document.array("nodes") ?: throw TheaterGlbException("the GLB has no nodes")
            val screenIndex = nodes.indexOfNamed(SCREEN)
            val seatIndex = nodes.indexOfNamed(SEAT)
            if (screenIndex < 0 || seatIndex < 0) {
                val missing = listOfNotNull(SCREEN.takeIf { screenIndex < 0 }, SEAT.takeIf { seatIndex < 0 })
                throw TheaterGlbException("the GLB has no node named ${missing.joinToString(" or ")}")
            }
            val world = worldMatrices(document, nodes)
            val meshIndex = nodes.node(screenIndex).int("mesh") ?: throw TheaterGlbException("Screen has no mesh")
            val (boundsMin, boundsMax) = screenBounds(document, meshIndex, world[screenIndex])
            val seatMapIndex = nodes.indexOfNamed(SEAT_MAP)
            val markers = if (seatMapIndex < 0) emptyList() else nodes.node(seatMapIndex).children().map { child ->
                (nodes.node(child).name ?: "") to world[child].translation()
            }
            val meshNodeNames = nodes.indices.mapNotNull { index ->
                val node = nodes.node(index)
                if (node.int("mesh") == null) null else node.name?.takeIf { it.isNotEmpty() }
            }
            return TheaterGlb(
                seat = world[seatIndex].translation(),
                screenBoundsMin = boundsMin,
                screenBoundsMax = boundsMax,
                seatMarkers = markers,
                meshNodeNames = meshNodeNames,
                images = images(document),
                emissiveImageByNode = emissiveImages(document, nodes),
            )
        }

        /** Every image that lives in the file (a buffer view), by its `images` index. */
        private fun images(document: JsonObject): List<GlbImage> =
            document.array("images").orEmpty().mapIndexedNotNull { index, element ->
                val image = element.obj ?: return@mapIndexedNotNull null
                if (image.int("bufferView") == null) return@mapIndexedNotNull null
                GlbImage(index, image.name.orEmpty(), image.string("mimeType"))
            }

        /** The exporter gives each baked object one material whose emissive texture is its atlas; this finds that image per node. */
        private fun emissiveImages(document: JsonObject, nodes: JsonArray): Map<String, Int> {
            val meshes = document.array("meshes") ?: return emptyMap()
            val materials = document.array("materials") ?: return emptyMap()
            val textures = document.array("textures") ?: return emptyMap()
            val out = LinkedHashMap<String, Int>()
            for (index in nodes.indices) {
                val node = nodes.node(index)
                val name = node.name?.takeIf { it.isNotEmpty() } ?: continue
                val mesh = node.int("mesh")?.let { meshes.getOrNull(it)?.obj } ?: continue
                val material = mesh.array("primitives")?.firstOrNull()?.obj?.int("material")?.let { materials.getOrNull(it)?.obj } ?: continue
                val texture = material.obj("emissiveTexture")?.int("index")?.let { textures.getOrNull(it)?.obj } ?: continue
                out[name] = texture.int("source") ?: continue
            }
            return out
        }

        /** Validates both headers and returns the JSON chunk's length; [available] is the byte count behind them when known. */
        private fun checkHeader(header: ByteArray, available: Int?): Int {
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(0) != GLB_MAGIC) throw TheaterGlbException("not a GLB: bad magic")
            val version = buffer.getInt(4)
            if (version != 2) throw TheaterGlbException("GLB version $version, expected 2")
            // The total length at 8 goes unchecked: the stream parser never sees the end of the file.
            val chunkLength = buffer.getInt(12)
            if (buffer.getInt(16) != CHUNK_JSON) throw TheaterGlbException("the first GLB chunk isn't JSON")
            if (chunkLength < 0 || chunkLength > MAX_JSON_BYTES || (available != null && chunkLength > available)) {
                throw TheaterGlbException("truncated GLB")
            }
            return chunkLength
        }

        /** A chunk that isn't a glTF document is one failure to the caller, like a bad header. */
        private fun fromJsonChunk(chunk: ByteArray): TheaterGlb {
            val document = try {
                Json.parseToJsonElement(chunk.decodeToString()) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: throw TheaterGlbException("the GLB's JSON chunk isn't a glTF document")
            return fromDocument(document)
        }

        /** Every node's world matrix, parents composed from the first scene's roots (or the orphans without one). */
        private fun worldMatrices(document: JsonObject, nodes: JsonArray): Array<FloatArray> {
            val world = Array(nodes.size) { IDENTITY.copyOf() }
            val childIndices = nodes.indices.flatMapTo(HashSet()) { nodes.node(it).children() }
            val roots = document.array("scenes")?.firstOrNull()?.obj?.ints("nodes")
                ?: nodes.indices.filter { it !in childIndices }

            fun visit(index: Int, parent: FloatArray, depth: Int) {
                // A hierarchy deeper than it has nodes can only be a cycle.
                if (depth > nodes.size) throw TheaterGlbException("the node hierarchy loops")
                val node = nodes.node(index)
                val matrix = multiply(parent, localMatrix(node))
                world[index] = matrix
                for (child in node.children()) visit(child, matrix, depth + 1)
            }
            for (root in roots) visit(root, IDENTITY, 0)
            return world
        }

        /** The eight corners of each primitive's POSITION box through the node's world matrix, unioned. */
        private fun screenBounds(document: JsonObject, meshIndex: Int, world: FloatArray): Pair<Vec3, Vec3> {
            val meshes = document.array("meshes") ?: throw TheaterGlbException("the GLB has no meshes")
            val accessors = document.array("accessors") ?: throw TheaterGlbException("the GLB has no accessors")
            val mesh = meshes.getOrNull(meshIndex)?.obj ?: throw TheaterGlbException("mesh $meshIndex is missing")
            var lo: Vec3? = null
            var hi: Vec3? = null
            for (primitive in mesh.array("primitives").orEmpty()) {
                val position = primitive.obj?.obj("attributes")?.int("POSITION") ?: continue
                val accessor = accessors.getOrNull(position)?.obj ?: continue
                val min = accessor.floats("min")?.takeIf { it.size == 3 } ?: continue
                val max = accessor.floats("max")?.takeIf { it.size == 3 } ?: continue
                for (corner in 0 until 8) {
                    val p = world.transform(
                        if (corner and 1 == 0) min[0] else max[0],
                        if (corner and 2 == 0) min[1] else max[1],
                        if (corner and 4 == 0) min[2] else max[2],
                    )
                    lo = lo?.let { Vec3(minOf(it.x, p.x), minOf(it.y, p.y), minOf(it.z, p.z)) } ?: p
                    hi = hi?.let { Vec3(maxOf(it.x, p.x), maxOf(it.y, p.y), maxOf(it.z, p.z)) } ?: p
                }
            }
            if (lo == null || hi == null) throw TheaterGlbException("Screen's mesh has no POSITION bounds")
            val extent = hi - lo
            // A quad is flat on one axis; only a point has no extent at all.
            if (extent.x <= 0f && extent.y <= 0f && extent.z <= 0f) {
                throw TheaterGlbException("Screen has no size (${extent.x} × ${extent.y} × ${extent.z})")
            }
            return lo to hi
        }

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        /** glTF's `matrix` as-is, else its TRS: the rotation's columns scaled per axis, then the translation. */
        private fun localMatrix(node: JsonObject): FloatArray {
            node.floats("matrix")?.takeIf { it.size == 16 }?.let { return it }
            val t = node.floats("translation")?.takeIf { it.size == 3 } ?: floatArrayOf(0f, 0f, 0f)
            val r = node.floats("rotation")?.takeIf { it.size == 4 } ?: floatArrayOf(0f, 0f, 0f, 1f)
            val s = node.floats("scale")?.takeIf { it.size == 3 } ?: floatArrayOf(1f, 1f, 1f)
            val (x, y, z, w) = r
            return floatArrayOf(
                (1f - 2f * (y * y + z * z)) * s[0], 2f * (x * y + z * w) * s[0], 2f * (x * z - y * w) * s[0], 0f,
                2f * (x * y - z * w) * s[1], (1f - 2f * (x * x + z * z)) * s[1], 2f * (y * z + x * w) * s[1], 0f,
                2f * (x * z + y * w) * s[2], 2f * (y * z - x * w) * s[2], (1f - 2f * (x * x + y * y)) * s[2], 0f,
                t[0], t[1], t[2], 1f,
            )
        }

        /** `a × b` for column-major 4×4 matrices: the parent's matrix times the child's local one. */
        private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (col in 0 until 4) for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
            return out
        }

        private fun FloatArray.translation() = Vec3(this[12], this[13], this[14])

        /** The affine image of a point (a node matrix's bottom row is `0 0 0 1`). */
        private fun FloatArray.transform(x: Float, y: Float, z: Float) = Vec3(
            this[0] * x + this[4] * y + this[8] * z + this[12],
            this[1] * x + this[5] * y + this[9] * z + this[13],
            this[2] * x + this[6] * y + this[10] * z + this[14],
        )

        /** Loops because an asset stream may hand the header out a few bytes at a time. */
        private fun InputStream.readExactly(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = read(bytes, filled, count - filled)
                if (n < 0) throw TheaterGlbException("truncated GLB")
                filled += n
            }
            return bytes
        }

        // The glTF document through kotlinx's JsonElement API, lenient about a field of the wrong type.
        private val JsonElement.obj: JsonObject? get() = this as? JsonObject
        private val JsonObject.name: String? get() = string("name")
        private fun JsonObject.children(): List<Int> = ints("children")
        private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
        private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
        private fun JsonObject.ints(key: String): List<Int> =
            array(key)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        private fun JsonObject.floats(key: String): FloatArray? =
            array(key)?.map { (it as? JsonPrimitive)?.floatOrNull ?: return null }?.toFloatArray()

        /** A node index outside `nodes` is a broken file, named as such rather than an index error. */
        private fun JsonArray.node(index: Int): JsonObject =
            (getOrNull(index) ?: throw TheaterGlbException("node $index is missing")).obj ?: JsonObject(emptyMap())
        private fun JsonArray.indexOfNamed(name: String): Int = indices.indexOfFirst { node(it).name == name }
    }
}
