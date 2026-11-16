package com.babasama.edendale.android.xr

/** Reads one typed extra; null when it is absent or of another type. */
interface ExtrasReader {
    fun string(key: String): String?
    fun int(key: String): Int?
    fun long(key: String): Long?
    fun boolean(key: String): Boolean?
    fun float(key: String): Float?
}

/** Writes typed extras. */
interface ExtrasWriter {
    fun put(key: String, value: String)
    fun put(key: String, value: Int)
    fun put(key: String, value: Long)
    fun put(key: String, value: Boolean)
    fun put(key: String, value: Float)
}

/**
 * What the 2D player hands to the Android XR theater when the viewer taps
 * Theater: the item, where it is, how fast it plays, which audio and subtitle
 * tracks are on, and the TMDB identity that progress is written under. The
 * theater owns its own player from then on, so this is everything it needs
 * to carry on as if nothing had changed (ENHANCEMENT.md N-D7).
 *
 * The codec is pure so the hermetic suite can round-trip it; `XrTheater`
 * adapts it to an `Intent`.
 */
data class XrTheaterHandover(
    val uri: String,
    val title: String,
    val subtitle: String?,
    val positionMillis: Long,
    val playWhenReady: Boolean,
    val tmdbId: Int?,
    val isEpisode: Boolean,
    val showTmdbId: Int?,
    val season: Int?,
    val episode: Int?,
    val audioTrackId: String?,
    val audioLanguage: String?,
    val subtitlesOff: Boolean,
    val textTrackId: String?,
    val textLanguage: String?,
    val speed: Float,
) {
    fun write(out: ExtrasWriter) {
        out.put(KEY_URI, uri)
        out.put(KEY_TITLE, title)
        subtitle?.let { out.put(KEY_SUBTITLE, it) }
        out.put(KEY_POSITION, positionMillis)
        out.put(KEY_PLAY_WHEN_READY, playWhenReady)
        tmdbId?.let { out.put(KEY_TMDB_ID, it) }
        out.put(KEY_IS_EPISODE, isEpisode)
        showTmdbId?.let { out.put(KEY_SHOW_TMDB_ID, it) }
        season?.let { out.put(KEY_SEASON, it) }
        episode?.let { out.put(KEY_EPISODE, it) }
        audioTrackId?.let { out.put(KEY_AUDIO_ID, it) }
        audioLanguage?.let { out.put(KEY_AUDIO_LANGUAGE, it) }
        out.put(KEY_SUBTITLES_OFF, subtitlesOff)
        textTrackId?.let { out.put(KEY_TEXT_ID, it) }
        textLanguage?.let { out.put(KEY_TEXT_LANGUAGE, it) }
        out.put(KEY_SPEED, speed)
    }

    companion object {
        const val KEY_URI = "edendale.theater.uri"
        const val KEY_TITLE = "edendale.theater.title"
        const val KEY_SUBTITLE = "edendale.theater.subtitle"
        const val KEY_POSITION = "edendale.theater.position"
        const val KEY_PLAY_WHEN_READY = "edendale.theater.playWhenReady"
        const val KEY_TMDB_ID = "edendale.theater.tmdbId"
        const val KEY_IS_EPISODE = "edendale.theater.isEpisode"
        const val KEY_SHOW_TMDB_ID = "edendale.theater.showTmdbId"
        const val KEY_SEASON = "edendale.theater.season"
        const val KEY_EPISODE = "edendale.theater.episode"
        const val KEY_AUDIO_ID = "edendale.theater.audioId"
        const val KEY_AUDIO_LANGUAGE = "edendale.theater.audioLanguage"
        const val KEY_SUBTITLES_OFF = "edendale.theater.subtitlesOff"
        const val KEY_TEXT_ID = "edendale.theater.textId"
        const val KEY_TEXT_LANGUAGE = "edendale.theater.textLanguage"
        const val KEY_SPEED = "edendale.theater.speed"

        /** Null when the extras aren't a handover at all: only the player starts the theater. */
        fun read(extras: ExtrasReader): XrTheaterHandover? {
            val uri = extras.string(KEY_URI) ?: return null
            return XrTheaterHandover(
                uri = uri,
                title = extras.string(KEY_TITLE).orEmpty(),
                subtitle = extras.string(KEY_SUBTITLE),
                positionMillis = (extras.long(KEY_POSITION) ?: 0L).coerceAtLeast(0L),
                playWhenReady = extras.boolean(KEY_PLAY_WHEN_READY) ?: true,
                tmdbId = extras.int(KEY_TMDB_ID)?.takeIf { it > 0 },
                isEpisode = extras.boolean(KEY_IS_EPISODE) ?: false,
                showTmdbId = extras.int(KEY_SHOW_TMDB_ID)?.takeIf { it > 0 },
                // Season 0 is real (specials); an episode number is not.
                season = extras.int(KEY_SEASON)?.takeIf { it >= 0 },
                episode = extras.int(KEY_EPISODE)?.takeIf { it > 0 },
                audioTrackId = extras.string(KEY_AUDIO_ID),
                audioLanguage = extras.string(KEY_AUDIO_LANGUAGE),
                subtitlesOff = extras.boolean(KEY_SUBTITLES_OFF) ?: false,
                textTrackId = extras.string(KEY_TEXT_ID),
                textLanguage = extras.string(KEY_TEXT_LANGUAGE),
                speed = extras.float(KEY_SPEED)?.takeIf { it > 0f } ?: 1f,
            )
        }
    }
}
