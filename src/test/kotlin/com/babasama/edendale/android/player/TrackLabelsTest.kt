package com.babasama.edendale.android.player

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** B.3.T1: the Video Track and Audio Track row labels. */
class TrackLabelsTest {

    private fun channels(count: Int) = TrackLabels.channels(count, "Mono", "Stereo") { "$it ch" }
    private fun english(code: String?) = TrackLabels.languageName(code, Locale.ENGLISH)

    private fun audio(label: String?, code: String?, count: Int) =
        TrackLabels.audio(label, code, english(code), channels(count), fallback = "Track 1")

    @Test
    fun anUnnamedTrackIsNamedByItsLanguage() {
        assertEquals("English — Stereo", audio(null, "en", 2))
    }

    @Test
    fun aNamedTrackGainsItsLanguage() {
        assertEquals("Commentary (English) — 5.1", audio("Commentary", "en", 6))
    }

    @Test
    fun aNameThatAlreadySaysTheLanguageGetsNoSuffix() {
        assertEquals("English Commentary — Stereo", audio("English Commentary", "en", 2))
        assertEquals("english stereo mix — Stereo", audio("english stereo mix", "en", 2))
        // The code counts as a whole word only.
        assertEquals("Dub [en] — Stereo", audio("Dub [en]", "en", 2))
        assertEquals("Lenses (English) — Stereo", audio("Lenses", "en", 2))
    }

    @Test
    fun channelLayouts() {
        assertEquals("Mono", channels(1))
        assertEquals("Stereo", channels(2))
        assertEquals("3 ch", channels(3))
        assertEquals("5.1", channels(6))
        assertEquals("7.1", channels(8))
        assertNull(channels(-1))
        assertEquals("Director (French)", TrackLabels.audio("Director", "fr", english("fr"), null, "Track 1"))
    }

    @Test
    fun fallbacksWhenNothingIsKnown() {
        assertEquals("Track 3 — Mono", TrackLabels.audio(null, "und", english("und"), channels(1), "Track 3"))
        assertEquals("Track 2", TrackLabels.audio("  ", null, null, null, "Track 2"))
        assertNull(english(null))
        assertNull(english("und"))
    }

    @Test
    fun videoRowsShowTheResolution() {
        assertEquals("Main (Japanese) — 1920×1080", TrackLabels.video("Main", "ja", english("ja"), 1920, 1080, "Track 1"))
        assertEquals("Track 2 — 3840×2160", TrackLabels.video(null, null, null, 3840, 2160, "Track 2"))
        assertEquals("Angle 2", TrackLabels.video("Angle 2", null, null, -1, -1, "Track 2"))
    }

    @Test
    fun languageNamesFollowTheDisplayLocale() {
        assertEquals("Anglais", TrackLabels.languageName("en", Locale.FRENCH))
        assertEquals("Commentary (Anglais) — Stereo", TrackLabels.audio("Commentary", "en", TrackLabels.languageName("en", Locale.FRENCH), "Stereo", "Piste 1"))
    }
}
