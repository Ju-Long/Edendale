package com.babasama.edendale.android.theater

import com.babasama.edendale.android.xr.TheaterGlb
import com.babasama.edendale.android.xr.TheaterGlbException
import com.babasama.edendale.android.xr.TheaterLights
import com.babasama.edendale.android.xr.TheaterLightsRules
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One baked material the house lights scale (HORIZON.md G.15): its index and
 * name in the GLB's `materials`, and its `baseColorFactor` (RGBA, linear),
 * which every level multiplies.
 */
data class DimmableMaterial(val index: Int, val name: String?, val baseColor: List<Float>) {
    /** The material's base colour factor at [lights]: RGB scaled by the level's brightness, alpha kept. */
    fun albedo(lights: TheaterLights): List<Float> =
        listOf(baseColor[0] * lights.brightness, baseColor[1] * lights.brightness, baseColor[2] * lights.brightness, baseColor[3])
}

/**
 * What the theater reads out of a room's GLB (HORIZON.md G.14, G.15): the
 * shared reader's `Seat`, `Screen`, and seat markers ([glb], android's
 * `TheaterGlb`), and the materials the house lights dim. Only the header and
 * the JSON chunk are read, never the megabytes of textures behind them.
 *
 * The Quest's rooms are prepared by `tools/horizon/prepare_theater_glb.py`:
 * the baked light is each material's base colour texture under
 * `KHR_materials_unlit`, so the lights scale the base colour factor, the one
 * number an unlit material still multiplies. android's rule
 * (`TheaterLightsRules.dimmableNodes`) looks for emissive atlases, which a
 * prepared room no longer has.
 */
data class TheaterRoomFile(val glb: TheaterGlb, val dimmable: List<DimmableMaterial>) {

    companion object {
        private const val GLB_MAGIC = 0x46546C67 // "glTF", little-endian
        private const val CHUNK_JSON = 0x4E4F534A // "JSON"
        private const val HEADER_BYTES = 20
        private const val MAX_JSON_BYTES = 16 * 1024 * 1024

        /** Reads the header and the JSON chunk and stops. */
        fun read(stream: InputStream): TheaterRoomFile {
            val header = ByteBuffer.wrap(stream.readExactly(HEADER_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
            if (header.getInt(0) != GLB_MAGIC) throw TheaterGlbException("not a GLB: bad magic")
            if (header.getInt(4) != 2) throw TheaterGlbException("GLB version ${header.getInt(4)}, expected 2")
            val length = header.getInt(12)
            if (header.getInt(16) != CHUNK_JSON) throw TheaterGlbException("the first GLB chunk isn't JSON")
            if (length < 0 || length > MAX_JSON_BYTES) throw TheaterGlbException("truncated GLB")
            return fromDocument(Json.parseToJsonElement(String(stream.readExactly(length), Charsets.UTF_8)).jsonObject)
        }

        fun fromDocument(document: JsonObject): TheaterRoomFile =
            TheaterRoomFile(TheaterGlb.fromDocument(document), dimmableMaterials(document))

        /**
         * The materials the lights scale: those a mesh node draws with whose
         * base colour is a texture (the baked atlases) and that aren't
         * blended, less any material that an untouched node (the `Screen`, the
         * `Projector beam`; android's list) also draws with. The screen's
         * flat dark glow and the beam's blended haze stay as they are, as on
         * Android XR.
         */
        fun dimmableMaterials(document: JsonObject): List<DimmableMaterial> {
            val nodes = document.array("nodes") ?: return emptyList()
            val meshes = document.array("meshes") ?: return emptyList()
            val materials = document.array("materials") ?: return emptyList()
            val used = sortedSetOf<Int>()
            val untouched = mutableSetOf<Int>()
            for (element in nodes) {
                val node = element as? JsonObject ?: continue
                val mesh = node.int("mesh")?.let { meshes.getOrNull(it) as? JsonObject } ?: continue
                val indices = mesh.array("primitives").orEmpty().mapNotNull { (it as? JsonObject)?.int("material") }
                if (TheaterLightsRules.untouched(node.string("name").orEmpty())) untouched += indices else used += indices
            }
            return used.filter { it !in untouched }.mapNotNull { index ->
                val material = materials.getOrNull(index) as? JsonObject ?: return@mapNotNull null
                val pbr = material["pbrMetallicRoughness"] as? JsonObject
                if (pbr?.get("baseColorTexture") !is JsonObject) return@mapNotNull null
                if (material.string("alphaMode") == "BLEND") return@mapNotNull null
                val factor = (pbr["baseColorFactor"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
                    ?.takeIf { it.size == 4 }
                    ?: listOf(1f, 1f, 1f, 1f)
                DimmableMaterial(index, material.string("name"), factor)
            }
        }

        private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun InputStream.readExactly(count: Int): ByteArray {
            val out = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = read(out, offset, count - offset)
                if (read < 0) throw TheaterGlbException("truncated GLB")
                offset += read
            }
            return out
        }
    }
}
