package com.babasama.edendale.android.theater

import android.content.Context
import android.content.Intent

/**
 * What the player hands the theater (HORIZON.md G.5): the item, where it
 * was, what was chosen, and the episode context, as intent extras. The
 * theater builds its own player from them. A title with a TMDB id also has
 * the per-title preferences store, which both players read and write.
 */
data class TheaterHandover(
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
    /** The audio track playing, by id then language, so the theater picks the same one. */
    val audioTrackId: String?,
    val audioLanguage: String?,
    /** Subtitles were off (or the file has none); otherwise the track showing. */
    val subtitlesOff: Boolean,
    val textTrackId: String?,
    val textLanguage: String?,
    val speed: Float,
) {
    /** The explicit request for [TheaterActivity]; the VR category marks it immersive for Horizon OS. */
    fun intent(context: Context): Intent = Intent(context, TheaterActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(CATEGORY_VR)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(EXTRA_URI, uri)
        putExtra(EXTRA_TITLE, title)
        subtitle?.let { putExtra(EXTRA_SUBTITLE, it) }
        putExtra(EXTRA_POSITION, positionMillis)
        putExtra(EXTRA_PLAY_WHEN_READY, playWhenReady)
        tmdbId?.let { putExtra(EXTRA_TMDB_ID, it) }
        putExtra(EXTRA_IS_EPISODE, isEpisode)
        showTmdbId?.let { putExtra(EXTRA_SHOW_TMDB_ID, it) }
        season?.let { putExtra(EXTRA_SEASON, it) }
        episode?.let { putExtra(EXTRA_EPISODE, it) }
        audioTrackId?.let { putExtra(EXTRA_AUDIO_ID, it) }
        audioLanguage?.let { putExtra(EXTRA_AUDIO_LANGUAGE, it) }
        putExtra(EXTRA_SUBTITLES_OFF, subtitlesOff)
        textTrackId?.let { putExtra(EXTRA_TEXT_ID, it) }
        textLanguage?.let { putExtra(EXTRA_TEXT_LANGUAGE, it) }
        putExtra(EXTRA_SPEED, speed)
    }

    companion object {
        const val CATEGORY_VR = "com.oculus.intent.category.VR"

        private const val EXTRA_URI = "edendale.theater.uri"
        private const val EXTRA_TITLE = "edendale.theater.title"
        private const val EXTRA_SUBTITLE = "edendale.theater.subtitle"
        private const val EXTRA_POSITION = "edendale.theater.position"
        private const val EXTRA_PLAY_WHEN_READY = "edendale.theater.playWhenReady"
        private const val EXTRA_TMDB_ID = "edendale.theater.tmdbId"
        private const val EXTRA_IS_EPISODE = "edendale.theater.isEpisode"
        private const val EXTRA_SHOW_TMDB_ID = "edendale.theater.showTmdbId"
        private const val EXTRA_SEASON = "edendale.theater.season"
        private const val EXTRA_EPISODE = "edendale.theater.episode"
        private const val EXTRA_AUDIO_ID = "edendale.theater.audioId"
        private const val EXTRA_AUDIO_LANGUAGE = "edendale.theater.audioLanguage"
        private const val EXTRA_SUBTITLES_OFF = "edendale.theater.subtitlesOff"
        private const val EXTRA_TEXT_ID = "edendale.theater.textId"
        private const val EXTRA_TEXT_LANGUAGE = "edendale.theater.textLanguage"
        private const val EXTRA_SPEED = "edendale.theater.speed"

        /** Null when the intent isn't a handover (nothing but the player starts the theater). */
        fun from(intent: Intent?): TheaterHandover? {
            val uri = intent?.getStringExtra(EXTRA_URI) ?: return null
            fun positive(key: String): Int? = intent.getIntExtra(key, -1).takeIf { it > 0 }
            return TheaterHandover(
                uri = uri,
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                subtitle = intent.getStringExtra(EXTRA_SUBTITLE),
                positionMillis = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L),
                playWhenReady = intent.getBooleanExtra(EXTRA_PLAY_WHEN_READY, true),
                tmdbId = positive(EXTRA_TMDB_ID),
                isEpisode = intent.getBooleanExtra(EXTRA_IS_EPISODE, false),
                showTmdbId = positive(EXTRA_SHOW_TMDB_ID),
                season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 },
                episode = positive(EXTRA_EPISODE),
                audioTrackId = intent.getStringExtra(EXTRA_AUDIO_ID),
                audioLanguage = intent.getStringExtra(EXTRA_AUDIO_LANGUAGE),
                subtitlesOff = intent.getBooleanExtra(EXTRA_SUBTITLES_OFF, false),
                textTrackId = intent.getStringExtra(EXTRA_TEXT_ID),
                textLanguage = intent.getStringExtra(EXTRA_TEXT_LANGUAGE),
                speed = intent.getFloatExtra(EXTRA_SPEED, 1f).takeIf { it > 0f } ?: 1f,
            )
        }
    }
}
