package com.babasama.edendale.android.theater

/**
 * The rooms the theater offers (HORIZON.md G.14): the three scenes on the
 * `scenes` branch, exported to GLB and prepared by
 * `tools/horizon/prepare_theater_glb.py` into `src/horizonTheater/assets/theater/`.
 * The same three, under the same ids, are the visionOS theater's rooms on
 * `apple`. [id] is the scene's folder on `scenes` and the asset's file name.
 */
enum class TheaterRoom(val id: String) {
    /** A stadium cinema, the viewer in its centre seat. */
    MOVIE_THEATRE("movie-theatre"),

    /** A 1920s picture hall: wooden chairs, a reel projector behind, its beam overhead. */
    PROJECTOR_ROOM("projector-room"),

    /** A proscenium playhouse, red curtains tied back around the screen on the stage. */
    PERFORMANCE_THEATRE("performance-theatre"),
    ;

    /** The GLB's path inside the APK's assets. */
    val assetPath: String get() = "$ASSET_DIRECTORY/$id.glb"

    /** The Spatial SDK's URI for the asset (its `apk:///` scheme reads the APK's assets). */
    val meshUri: String get() = "apk:///$assetPath"

    companion object {
        const val ASSET_DIRECTORY = "theater"

        /** The room a first visit opens: the cinema, the most familiar of the three. */
        val DEFAULT = MOVIE_THEATRE

        /** The room stored by an earlier visit; anything unknown (or nothing) is [DEFAULT]. */
        fun fromId(id: String?): TheaterRoom = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
