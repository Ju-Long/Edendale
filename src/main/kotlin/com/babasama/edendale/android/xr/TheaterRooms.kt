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
