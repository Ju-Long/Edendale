package com.babasama.edendale.android.xr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A map standing in for an Intent's extras. */
private class MapExtras : ExtrasReader, ExtrasWriter {
    val values = LinkedHashMap<String, Any>()
    override fun string(key: String) = values[key] as? String
    override fun int(key: String) = values[key] as? Int
    override fun long(key: String) = values[key] as? Long
    override fun boolean(key: String) = values[key] as? Boolean
    override fun float(key: String) = values[key] as? Float
    override fun put(key: String, value: String) { values[key] = value }
    override fun put(key: String, value: Int) { values[key] = value }
    override fun put(key: String, value: Long) { values[key] = value }
    override fun put(key: String, value: Boolean) { values[key] = value }
    override fun put(key: String, value: Float) { values[key] = value }
}

class XrTheaterHandoverTest {

    private val full = XrTheaterHandover(
        uri = "content://media/video/42",
        title = "Good News About Hell",
        subtitle = "S01E01",
        positionMillis = 1_234_567L,
        playWhenReady = false,
        tmdbId = 1_195_650,
        isEpisode = true,
        showTmdbId = 95_396,
        season = 0,
        episode = 1,
        audioTrackId = "1/2",
        audioLanguage = "en",
        subtitlesOff = false,
        textTrackId = "2/0",
        textLanguage = "fr",
        speed = 1.25f,
    )

    @Test
    fun everyFieldRoundTrips() {
        val extras = MapExtras()
        full.write(extras)
        assertEquals(full, XrTheaterHandover.read(extras))
        // Season 0 (specials) survives; it is a real season.
        assertEquals(0, XrTheaterHandover.read(extras)?.season)
    }

    @Test
    fun absentOptionalsStayAbsentAndNothingNullIsWritten() {
        val bare = XrTheaterHandover(
            uri = "file:///sdcard/Movies/clip.mkv", title = "clip", subtitle = null, positionMillis = 0L,
            playWhenReady = true, tmdbId = null, isEpisode = false, showTmdbId = null, season = null,
            episode = null, audioTrackId = null, audioLanguage = null, subtitlesOff = true,
            textTrackId = null, textLanguage = null, speed = 1f,
        )
        val extras = MapExtras()
        bare.write(extras)
        assertEquals(
            setOf(
                XrTheaterHandover.KEY_URI, XrTheaterHandover.KEY_TITLE, XrTheaterHandover.KEY_POSITION,
                XrTheaterHandover.KEY_PLAY_WHEN_READY, XrTheaterHandover.KEY_IS_EPISODE,
                XrTheaterHandover.KEY_SUBTITLES_OFF, XrTheaterHandover.KEY_SPEED,
            ),
            extras.values.keys,
        )
        assertEquals(bare, XrTheaterHandover.read(extras))
    }

    @Test
    fun anythingWithoutAUriIsNotAHandover() {
        assertNull(XrTheaterHandover.read(MapExtras()))
        val extras = MapExtras().apply { put(XrTheaterHandover.KEY_TITLE, "no uri") }
        assertNull(XrTheaterHandover.read(extras))
    }

    @Test
    fun oddValuesAreNormalised() {
        val extras = MapExtras().apply {
            put(XrTheaterHandover.KEY_URI, "smb://nas/film.mkv")
            put(XrTheaterHandover.KEY_POSITION, -5L)
            put(XrTheaterHandover.KEY_TMDB_ID, 0)
            put(XrTheaterHandover.KEY_SHOW_TMDB_ID, -1)
            put(XrTheaterHandover.KEY_SEASON, -1)
            put(XrTheaterHandover.KEY_EPISODE, 0)
            put(XrTheaterHandover.KEY_SPEED, 0f)
        }
        val read = XrTheaterHandover.read(extras)!!
        assertEquals("", read.title)
        assertEquals(0L, read.positionMillis)
        assertEquals(true, read.playWhenReady)
        assertNull(read.tmdbId)
        assertNull(read.showTmdbId)
        assertNull(read.season)
        assertNull(read.episode)
        assertEquals(1f, read.speed)
        assertEquals(false, read.subtitlesOff)
    }
}
