package com.babasama.edendale.android.xr.theater

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.GltfModelNode
import androidx.xr.scenecore.KhronosPbrMaterial
import androidx.xr.scenecore.Texture
import com.babasama.edendale.android.xr.GlbImage
import com.babasama.edendale.android.xr.TheaterGlb
import com.babasama.edendale.android.xr.TheaterLights
import com.babasama.edendale.android.xr.TheaterLightsRules
import com.babasama.edendale.android.xr.TheaterRoom
import java.nio.file.Paths

/**
 * The house lights (N.9, N-D10). SceneCore 1.0.0 can put another material on
 * a node but not scale the one it has, so a dimmed room gets a copy of each
 * baked material: the node's own atlas as the emissive texture at a lower
 * factor, the base colour black as the exporter left it. `Texture.create`
 * reads assets only, so the build copies each room's atlases out of its GLB
 * into `assets/theater/<room>-<image>.<jpg|png>` (`extractTheaterAtlases` in
 * the module's build file). Normal clears the overrides; a room change or the
 * end of the theater frees the copies.
 */
internal class TheaterLighting(private val session: Session) {
    private val textures = HashMap<Int, Texture>()
    private val materials = HashMap<Pair<Int, TheaterLights>, KhronosPbrMaterial>()
    private val overridden = ArrayList<GltfModelNode>()
    private var room: TheaterRoom? = null

    /**
     * Sets [level] on [entity], loaded from [room]'s [glb]. Each texture load is
     * a wait, and [stillCurrent] is asked after it, so a room or level that
     * changed meanwhile is never written over.
     */
    suspend fun apply(entity: GltfModelEntity, room: TheaterRoom, glb: TheaterGlb, level: TheaterLights, stillCurrent: () -> Boolean) {
        if (this.room != room) {
            release()
            this.room = room
        }
        clear()
        if (level == TheaterLights.NORMAL) return
        val images = glb.images.associateBy { it.index }
        for ((name, imageIndex) in TheaterLightsRules.dimmableNodes(glb)) {
            val node = entity.nodes.firstOrNull { it.name == name } ?: continue
            val image = images[imageIndex] ?: continue
            val material = material(room, image, level)
            if (!stillCurrent()) return
            node.setMaterialOverride(material)
            overridden += node
        }
    }

    private suspend fun material(room: TheaterRoom, image: GlbImage, level: TheaterLights): KhronosPbrMaterial {
        materials[image.index to level]?.let { return it }
        val texture = textures[image.index]
            ?: Texture.create(session, Paths.get(atlasAssetPath(room, image))).also { textures[image.index] = it }
        return KhronosPbrMaterial.create(session, AlphaMode.OPAQUE).apply {
            setBaseColorFactor(Vector4(0f, 0f, 0f, 1f))
            setMetallicFactor(0f)
            setRoughnessFactor(1f)
            setEmissiveTexture(texture)
            setEmissiveFactor(Vector3(level.brightness, level.brightness, level.brightness))
            materials[image.index to level] = this
        }
    }

    /** Back to the GLB's own materials. */
    fun clear() {
        overridden.forEach { node -> runCatching { node.clearMaterialOverride() } }
        overridden.clear()
    }

    /** Clears, and frees the material and texture copies. */
    fun release() {
        clear()
        materials.values.forEach { runCatching { it.close() } }
        materials.clear()
        textures.values.forEach { runCatching { it.close() } }
        textures.clear()
        room = null
    }

    private companion object {
        /** The build's name for an atlas: the room's file name, the image's index, its kind. */
        fun atlasAssetPath(room: TheaterRoom, image: GlbImage): String =
            "theater/${room.id}-${image.index}." + if (image.mimeType == "image/png") "png" else "jpg"
    }
}
