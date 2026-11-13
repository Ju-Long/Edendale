package com.babasama.edendale.android.theater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.babasama.edendale.android.xr.XrTheaterHandover

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
    /**
     * The explicit request for `TheaterActivity`; the VR category marks it
     * immersive for Horizon OS. The class is named rather than referenced
     * because it lives in src/horizonTheater, which a 2D build leaves out
     * (D15); the player sends it only when `DevicePolicy.offersImmersiveTheater` is true.
     *
     * It carries a ticket ([TheaterTickets]) that tells the theater the
     * player's parental check has just passed this title (G.15), and, as
     * android's `XrTheater.intent` does, a `content:` item as the intent's
     * data with a read grant, so a file the player was only handed (Open
     * With) stays readable after the player finishes. The theater itself
     * asks for a ticketless copy ([withTicket] false) to remember what it
     * plays now, so a theater the system rebuilds checks again.
     */
    fun intent(context: Context, withTicket: Boolean = true): Intent = Intent().setClassName(context, THEATER_ACTIVITY).apply {
        action = Intent.ACTION_MAIN
        addCategory(CATEGORY_VR)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val item = Uri.parse(uri)
        if (item.scheme == "content") {
            data = item
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        putExtras(extras())
        if (withTicket) putExtra(EXTRA_TICKET, TheaterTickets.issue(this@TheaterHandover))
    }

    /** The sixteen values as extras, never a ticket: the intent's, and the theater's saved state (G.15). */
    fun extras(): Bundle = Bundle().apply {
        putString(EXTRA_URI, uri)
        putString(EXTRA_TITLE, title)
        subtitle?.let { putString(EXTRA_SUBTITLE, it) }
        putLong(EXTRA_POSITION, positionMillis)
        putBoolean(EXTRA_PLAY_WHEN_READY, playWhenReady)
        tmdbId?.let { putInt(EXTRA_TMDB_ID, it) }
        putBoolean(EXTRA_IS_EPISODE, isEpisode)
        showTmdbId?.let { putInt(EXTRA_SHOW_TMDB_ID, it) }
        season?.let { putInt(EXTRA_SEASON, it) }
        episode?.let { putInt(EXTRA_EPISODE, it) }
        audioTrackId?.let { putString(EXTRA_AUDIO_ID, it) }
        audioLanguage?.let { putString(EXTRA_AUDIO_LANGUAGE, it) }
        putBoolean(EXTRA_SUBTITLES_OFF, subtitlesOff)
        textTrackId?.let { putString(EXTRA_TEXT_ID, it) }
        textLanguage?.let { putString(EXTRA_TEXT_LANGUAGE, it) }
        putFloat(EXTRA_SPEED, speed)
    }

    /**
     * The same handover in the form android's shared theater playback takes
     * (`TheaterPlayback`, G.15): the two carry the same sixteen values under
     * the same extra keys.
     */
    fun toPlayback(): XrTheaterHandover = XrTheaterHandover(
        uri = uri,
        title = title,
        subtitle = subtitle,
        positionMillis = positionMillis,
        playWhenReady = playWhenReady,
        tmdbId = tmdbId,
        isEpisode = isEpisode,
        showTmdbId = showTmdbId,
        season = season,
        episode = episode,
        audioTrackId = audioTrackId,
        audioLanguage = audioLanguage,
        subtitlesOff = subtitlesOff,
        textTrackId = textTrackId,
        textLanguage = textLanguage,
        speed = speed,
    )

    companion object {
        const val CATEGORY_VR = "com.oculus.intent.category.VR"
        private const val THEATER_ACTIVITY = "com.babasama.edendale.android.theater.TheaterActivity"

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
        private const val EXTRA_TICKET = "edendale.theater.ticket"

        /** The player's ticket on [intent], if it has one (G.15); [TheaterTickets.redeem] says whether it is good. */
        fun ticket(intent: Intent?): String? = intent?.getStringExtra(EXTRA_TICKET)

        /** Null when the intent isn't a handover (nothing but the player starts the theater). */
        fun from(intent: Intent?): TheaterHandover? = from(intent?.extras)

        /** The same from extras or a saved state; null when they aren't a handover. */
        fun from(extras: Bundle?): TheaterHandover? {
            val uri = extras?.getString(EXTRA_URI) ?: return null
            fun positive(key: String): Int? = extras.getInt(key, -1).takeIf { it > 0 }
            return TheaterHandover(
                uri = uri,
                title = extras.getString(EXTRA_TITLE).orEmpty(),
                subtitle = extras.getString(EXTRA_SUBTITLE),
                positionMillis = extras.getLong(EXTRA_POSITION, 0L).coerceAtLeast(0L),
                playWhenReady = extras.getBoolean(EXTRA_PLAY_WHEN_READY, true),
                tmdbId = positive(EXTRA_TMDB_ID),
                isEpisode = extras.getBoolean(EXTRA_IS_EPISODE, false),
                showTmdbId = positive(EXTRA_SHOW_TMDB_ID),
                season = extras.getInt(EXTRA_SEASON, -1).takeIf { it >= 0 },
                episode = positive(EXTRA_EPISODE),
                audioTrackId = extras.getString(EXTRA_AUDIO_ID),
                audioLanguage = extras.getString(EXTRA_AUDIO_LANGUAGE),
                subtitlesOff = extras.getBoolean(EXTRA_SUBTITLES_OFF, false),
                textTrackId = extras.getString(EXTRA_TEXT_ID),
                textLanguage = extras.getString(EXTRA_TEXT_LANGUAGE),
                speed = extras.getFloat(EXTRA_SPEED, 1f).takeIf { it > 0f } ?: 1f,
            )
        }
    }
}
