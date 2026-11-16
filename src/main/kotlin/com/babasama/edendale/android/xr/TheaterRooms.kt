package com.babasama.edendale.android.xr

/**
 * The three rooms from the `scenes` branch (ENHANCEMENT.md N-D9), in menu
 * order. Each id names its GLB in the `:xr` module's assets and its seat
 * preference; the titles are string resources, so the module can translate
 * them.
 */
enum class TheaterRoom(val id: String) {
    MOVIE_THEATRE("movie-theatre"),
    PROJECTOR_ROOM("projector-room"),
    PERFORMANCE_THEATRE("performance-theatre");

    /** The GLB's path under the `:xr` module's assets. */
    val assetPath: String get() = "$ASSET_DIRECTORY/$id.glb"

    companion object {
        const val ASSET_DIRECTORY = "theater"

        /** Where a viewer with no remembered room sits (N-D10: the last room is remembered, unlike visionOS). */
        val DEFAULT = MOVIE_THEATRE

        /** The room with [id]; [DEFAULT] for null or a value no release wrote. */
        fun fromId(id: String?): TheaterRoom = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * House lights: the brightness every baked room material is scaled by. Light
 * adds up linearly, so these look brighter than they read: dim is about half
 * as bright to the eye, dark leaves the room just visible.
 */
enum class TheaterLights(val raw: String, val brightness: Float) {
    NORMAL("normal", 1f),
    DIM("dim", 0.3f),
    DARK("dark", 0.06f);

    companion object {
        /** The level stored as [raw]; [NORMAL] for null or an unknown value. */
        fun fromRaw(raw: String?): TheaterLights = entries.firstOrNull { it.raw == raw } ?: NORMAL
    }
}

/**
 * The theater's preference file and keys (N-D10). Device-only and never
 * synced: a seat or a room is a choice about this headset's rooms, not about
 * what was watched.
 */
object TheaterPreferencesRules {
    const val PREFS_NAME = "theater"

    /** [TheaterRoom.id] of the last room. */
    const val KEY_ROOM = "room"

    /** [TheaterLights.raw]. */
    const val KEY_LIGHTS = "lights"

    /** [SeatID.storageValue] of the chosen seat in [room]; absent or unparseable means the room's best seat. */
    fun seatKey(room: TheaterRoom): String = "seat.${room.id}"

    /** Nodes the lights never dim (the picture's own screen and the projector's beam), with their subtrees. */
    val UNTOUCHED_NODES = setOf("Screen", "Projector_beam")
}

/**
 * Which of a room's nodes the house lights dim (N.9): the baked ones, whose
 * material carries an emissive atlas, less [TheaterPreferencesRules.UNTOUCHED_NODES].
 * The screen's material has no texture and the beam's is a base colour, so
 * both fall out of the first rule as well as the second.
 */
object TheaterLightsRules {
    /** Node name → the index of the atlas image to re-supply at a lower emissive factor. */
    fun dimmableNodes(glb: TheaterGlb): Map<String, Int> = glb.emissiveImageByNode.filterKeys { !untouched(it) }

    /** glTF keeps Blender's spaces in node names where USD wrote underscores; the untouched list names the USD form. */
    fun untouched(nodeName: String): Boolean = nodeName.replace(' ', '_') in TheaterPreferencesRules.UNTOUCHED_NODES
}
