package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentPlayerPreferencesTest {

    // ------------------------------------------------------------------
    // B.2.T1: Content keys for movie and episode; missing IDs store nothing
    // ------------------------------------------------------------------

    @Test
    fun contentKeyForMovieUsesMovieTmdbId() {
        assertEquals(
            "player.content.movie.550",
            ContentPlayerPreferencesRules.contentKey(tmdbId = 550, isEpisode = false, showTmdbId = null)
        )
    }

    @Test
    fun contentKeyForEpisodeUsesShowTmdbIdSoEntireShowSharesOneEntry() {
        assertEquals(
            "player.content.show.1399",
            ContentPlayerPreferencesRules.contentKey(tmdbId = 63056, isEpisode = true, showTmdbId = 1399)
        )
    }

    @Test
    fun missingOrInvalidIdsReturnNullStoringNothing() {
        // Missing IDs
        assertNull(ContentPlayerPreferencesRules.contentKey(tmdbId = null, isEpisode = false, showTmdbId = null))
        assertNull(ContentPlayerPreferencesRules.contentKey(tmdbId = 63056, isEpisode = true, showTmdbId = null))
        assertNull(ContentPlayerPreferencesRules.contentKey(tmdbId = null, isEpisode = true, showTmdbId = null))

        // Non-positive IDs
        assertNull(ContentPlayerPreferencesRules.contentKey(tmdbId = 0, isEpisode = false, showTmdbId = null))
        assertNull(ContentPlayerPreferencesRules.contentKey(tmdbId = -1, isEpisode = false, showTmdbId = null))
        assertNull(ContentPlayerPreferencesRules.contentKey(tmdbId = 63056, isEpisode = true, showTmdbId = 0))
        assertNull(ContentPlayerPreferencesRules.contentKey(tmdbId = 63056, isEpisode = true, showTmdbId = -10))
    }

    // ------------------------------------------------------------------
    // B.2.T2: JSON round trip; unknown fields ignored; corrupt JSON yields null
    // ------------------------------------------------------------------

    @Test
    fun jsonCodecRoundTripFullPreferences() {
        val original = ContentPlayerPreferences(
            speed = 1.25f,
            audioTrackLanguage = "eng",
            audioTrackName = "Surround 5.1",
            subtitleEnabled = true,
            subtitleTrackLanguage = "spa",
            subtitleTrackName = "Spanish Full",
            videoTrackWidth = 1920,
            videoTrackHeight = 1080,
        )

        val encoded = ContentPlayerPreferencesRules.encode(original)
        val decoded = ContentPlayerPreferencesRules.decode(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun jsonCodecRoundTripPartialPreferences() {
        val partial = ContentPlayerPreferences(
            speed = 0.75f,
            subtitleEnabled = false,
        )

        val encoded = ContentPlayerPreferencesRules.encode(partial)
        val decoded = ContentPlayerPreferencesRules.decode(encoded)

        assertEquals(partial, decoded)
    }

    @Test
    fun unknownFieldsAreIgnoredGracefully() {
        val jsonWithUnknownFields = """
            {
                "speed": 1.5,
                "unknownKey": "someValue",
                "nestedObject": {"a": 1, "b": "c"},
                "extraList": [1, 2, 3],
                "audioTrackLanguage": "fra",
                "subtitleEnabled": true
            }
        """.trimIndent()

        val decoded = ContentPlayerPreferencesRules.decode(jsonWithUnknownFields)

        assertEquals(1.5f, decoded?.speed)
        assertEquals("fra", decoded?.audioTrackLanguage)
        assertNull(decoded?.audioTrackName)
        assertEquals(true, decoded?.subtitleEnabled)
        assertNull(decoded?.videoTrackWidth)
    }

    @Test
    fun corruptJsonYieldsNoPreferences() {
        assertNull(ContentPlayerPreferencesRules.decode("{corrupt-json"))
        assertNull(ContentPlayerPreferencesRules.decode(""))
        assertNull(ContentPlayerPreferencesRules.decode("   "))
        assertNull(ContentPlayerPreferencesRules.decode(null))
        assertNull(ContentPlayerPreferencesRules.decode("12345"))
        assertNull(ContentPlayerPreferencesRules.decode("true"))
        assertNull(ContentPlayerPreferencesRules.decode("""["array", "not", "object"]"""))
    }

    @Test
    fun inMemoryStoreRoundTripAndRemoval() {
        val store = InMemoryContentPlayerPreferencesStore()
        val key = "player.content.movie.550"
        val prefs = ContentPlayerPreferences(speed = 1.75f, audioTrackLanguage = "eng")

        assertNull(store.get(key))
        store.save(key, prefs)
        assertEquals(prefs, store.get(key))

        store.remove(key)
        assertNull(store.get(key))
    }

    // ------------------------------------------------------------------
    // B.2.T3: Matching: language beats name, name fallback, ext- excluded,
    // video multi-track WxH only, Off restored
    // ------------------------------------------------------------------

    @Test
    fun audioMatchingLanguageBeatsName() {
        val prefs = ContentPlayerPreferences(
            audioTrackLanguage = "fra",
            audioTrackName = "Director's Commentary",
        )

        val candidates = listOf(
            TrackCandidate(id = "1", language = "eng", label = "Director's Commentary"),
            TrackCandidate(id = "2", language = "fra", label = "French Main"),
        )

        val match = ContentPlayerPreferencesRules.bestAudioMatch(prefs, candidates)
        assertEquals("2", match?.id)
        assertEquals("fra", match?.language)
    }

    @Test
    fun audioMatchingFallsBackToNameWhenLanguageMissingOrAbsent() {
        // Case 1: Language absent from file
        val prefsLanguageAbsent = ContentPlayerPreferences(
            audioTrackLanguage = "deu",
            audioTrackName = "Commentary",
        )
        val candidates1 = listOf(
            TrackCandidate(id = "1", language = "eng", label = "English 5.1"),
            TrackCandidate(id = "2", language = "eng", label = "Commentary"),
        )
        val match1 = ContentPlayerPreferencesRules.bestAudioMatch(prefsLanguageAbsent, candidates1)
        assertEquals("2", match1?.id)

        // Case 2: Language null in preferences
        val prefsNoLanguage = ContentPlayerPreferences(
            audioTrackLanguage = null,
            audioTrackName = "Director's Cut",
        )
        val candidates2 = listOf(
            TrackCandidate(id = "10", language = "und", label = "Theatrical"),
            TrackCandidate(id = "20", language = "und", label = "Director's Cut"),
        )
        val match2 = ContentPlayerPreferencesRules.bestAudioMatch(prefsNoLanguage, candidates2)
        assertEquals("20", match2?.id)
    }

    @Test
    fun subtitleMatchingLanguageBeatsName() {
        val prefs = ContentPlayerPreferences(
            subtitleTrackLanguage = "spa",
            subtitleTrackName = "English SDH",
        )
        val candidates = listOf(
            TrackCandidate(id = "1", language = "eng", label = "English SDH"),
            TrackCandidate(id = "2", language = "spa", label = "Spanish"),
        )
        val match = ContentPlayerPreferencesRules.bestSubtitleMatch(prefs, candidates)
        assertEquals("2", match?.id)
    }

    @Test
    fun subtitleMatchingFallsBackToNameWhenLanguageAbsent() {
        val prefs = ContentPlayerPreferences(
            subtitleTrackLanguage = "ita",
            subtitleTrackName = "Commentary Subtitles",
        )
        val candidates = listOf(
            TrackCandidate(id = "1", language = "eng", label = "English"),
            TrackCandidate(id = "2", language = "eng", label = "Commentary Subtitles"),
        )
        val match = ContentPlayerPreferencesRules.bestSubtitleMatch(prefs, candidates)
        assertEquals("2", match?.id)
    }

    @Test
    fun externalTracksAreNeverChosenForSubtitles() {
        val prefs = ContentPlayerPreferences(
            subtitleTrackLanguage = "spa",
            subtitleTrackName = "Spanish",
        )

        // Even though ext- has exact language and name match, embedded track is chosen
        val candidatesWithEmbedded = listOf(
            TrackCandidate(id = "ext-wyzie-1", language = "spa", label = "Spanish"),
            TrackCandidate(id = "sub-2", language = "spa", label = "Spanish (Embedded)"),
        )
        val match = ContentPlayerPreferencesRules.bestSubtitleMatch(prefs, candidatesWithEmbedded)
        assertEquals("sub-2", match?.id)

        // If only ext- tracks exist, no match is made
        val candidatesOnlyExt = listOf(
            TrackCandidate(id = "ext-wyzie-1", language = "spa", label = "Spanish"),
        )
        assertNull(ContentPlayerPreferencesRules.bestSubtitleMatch(prefs, candidatesOnlyExt))
    }

    @Test
    fun videoTrackRestoredOnlyWithMoreThanOneTrackAndExactDimensions() {
        val prefs = ContentPlayerPreferences(
            videoTrackWidth = 3840,
            videoTrackHeight = 2160,
        )

        // Single video track: never restored even if dimensions match
        val singleTrack = listOf(
            TrackCandidate(id = "vid-1", language = null, label = null, width = 3840, height = 2160)
        )
        assertNull(ContentPlayerPreferencesRules.bestVideoMatch(prefs, singleTrack))

        // Multiple tracks: exact match chosen
        val multipleTracks = listOf(
            TrackCandidate(id = "vid-1080", language = null, label = null, width = 1920, height = 1080),
            TrackCandidate(id = "vid-4k", language = null, label = null, width = 3840, height = 2160),
        )
        val match = ContentPlayerPreferencesRules.bestVideoMatch(prefs, multipleTracks)
        assertEquals("vid-4k", match?.id)

        // Multiple tracks without dimension match: returns null
        val prefsNoMatch = ContentPlayerPreferences(videoTrackWidth = 1280, videoTrackHeight = 720)
        assertNull(ContentPlayerPreferencesRules.bestVideoMatch(prefsNoMatch, multipleTracks))
    }

    @Test
    fun subtitleOffPreferenceExplicitlyRepresented() {
        val prefs = ContentPlayerPreferences(subtitleEnabled = false)
        assertFalse(prefs.subtitleEnabled == true)
        assertEquals(false, prefs.subtitleEnabled)
    }

    // ------------------------------------------------------------------
    // B.2.1: Loop and Aspect Fill persistence
    // ------------------------------------------------------------------

    @Test
    fun loopAndAspectFillPersistAcrossLaunches() {
        val store = InMemoryPlayerPreferencesStore()
        val prefs1 = PlayerPreferences(store)

        // Defaults
        assertFalse(prefs1.loopEnabled)
        assertFalse(prefs1.aspectFill)

        // Set values
        prefs1.loopEnabled = true
        prefs1.aspectFill = true

        // Relaunch
        val prefs2 = PlayerPreferences(store)
        assertTrue(prefs2.loopEnabled)
        assertTrue(prefs2.aspectFill)
    }
}
