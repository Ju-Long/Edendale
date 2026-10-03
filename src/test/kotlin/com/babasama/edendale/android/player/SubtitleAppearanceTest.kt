package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings → Subtitles (B.4), ported from Apple's SubtitleAppearanceTests. */
class SubtitleAppearanceTest {

    private fun preferences() = PlayerPreferences(InMemoryPlayerPreferencesStore())

    @Test
    fun keysMatchApple() {
        assertEquals("subtitles.font", PlayerPreferencesRules.KEY_SUBTITLES_FONT)
        assertEquals("subtitles.textColor", PlayerPreferencesRules.KEY_SUBTITLES_TEXT_COLOR)
        assertEquals("subtitles.backgroundColor", PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_COLOR)
        assertEquals("subtitles.backgroundOpacity", PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_OPACITY)
        assertEquals(listOf("system", "rounded", "serif", "monospaced"), SubtitleFontStyle.entries.map { it.raw })
        assertEquals(
            listOf("parchment", "white", "yellow", "cyan", "green", "black"),
            SubtitleTextColor.entries.map { it.raw },
        )
        assertEquals(listOf("ink", "black", "charcoal", "navy", "white"), SubtitleBackgroundColor.entries.map { it.raw })
    }

    @Test
    fun defaultsKeepTheArchiveLook() {
        val appearance = preferences().subtitleAppearance
        assertEquals(SubtitleFontStyle.SYSTEM, appearance.font)
        assertEquals(SubtitleTextColor.PARCHMENT, appearance.textColor)
        assertEquals(SubtitleBackgroundColor.INK, appearance.backgroundColor)
        assertEquals(1f, appearance.backgroundOpacity)
        assertTrue(appearance.isDefault)
        // The archive's own colors.
        assertEquals(0xFFE4E1E9.toInt(), SubtitleTextColor.PARCHMENT.argb)
        assertEquals(0xFF0A0A0F.toInt(), SubtitleBackgroundColor.INK.argb)
    }

    @Test
    fun choicesPersist() {
        val store = InMemoryPlayerPreferencesStore()
        PlayerPreferences(store).subtitleAppearance = SubtitleAppearance(
            font = SubtitleFontStyle.SERIF,
            textColor = SubtitleTextColor.YELLOW,
            backgroundColor = SubtitleBackgroundColor.NAVY,
            backgroundOpacity = 0.4f,
        )

        val relaunched = PlayerPreferences(store).subtitleAppearance
        assertEquals(SubtitleFontStyle.SERIF, relaunched.font)
        assertEquals(SubtitleTextColor.YELLOW, relaunched.textColor)
        assertEquals(SubtitleBackgroundColor.NAVY, relaunched.backgroundColor)
        assertEquals(0.4f, relaunched.backgroundOpacity)
        assertFalse(relaunched.isDefault)
        assertEquals("serif", store.getString(PlayerPreferencesRules.KEY_SUBTITLES_FONT, null))
    }

    @Test
    fun unknownStoredValuesReadAsDefaults() {
        val store = InMemoryPlayerPreferencesStore()
        store.putString(PlayerPreferencesRules.KEY_SUBTITLES_FONT, "comic-sans")
        store.putString(PlayerPreferencesRules.KEY_SUBTITLES_TEXT_COLOR, "plaid")
        store.putString(PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_COLOR, "tartan")
        store.putFloat(PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_OPACITY, Float.NaN)
        assertTrue(PlayerPreferences(store).subtitleAppearance.isDefault)
        assertEquals(SubtitleFontStyle.SYSTEM, SubtitleFontStyle.fromRaw(null))
    }

    @Test
    fun opacityRoundsAndClamps() {
        assertEquals(0.12f, SubtitleAppearance.normalizedOpacity(0.123f))
        assertEquals(0f, SubtitleAppearance.normalizedOpacity(-1f))
        assertEquals(1f, SubtitleAppearance.normalizedOpacity(2f))
        assertEquals(1f, SubtitleAppearance.normalizedOpacity(Float.NaN))
        assertEquals(1f, SubtitleAppearance.normalizedOpacity(Float.POSITIVE_INFINITY))
        // Repeated TV steps never drift off whole percents.
        var appearance = SubtitleAppearance(backgroundOpacity = 0f)
        repeat(3) { appearance = appearance.steppingOpacity(1) }
        assertEquals(0.3f, appearance.backgroundOpacity)
        assertEquals(1f, SubtitleAppearance().steppingOpacity(1).backgroundOpacity)
        assertEquals(0f, SubtitleAppearance(backgroundOpacity = 0.05f).steppingOpacity(-1).backgroundOpacity)
    }

    @Test
    fun resetRestoresEveryDefault() {
        val prefs = preferences()
        prefs.subtitleAppearance = SubtitleAppearance(
            SubtitleFontStyle.MONOSPACED,
            SubtitleTextColor.BLACK,
            SubtitleBackgroundColor.WHITE,
            0f,
        )
        prefs.resetSubtitleAppearance()
        assertTrue(prefs.subtitleAppearance.isDefault)
    }

    @Test
    fun changesReachTheOpenPlayer() {
        val prefs = preferences()
        var notified = 0
        prefs.addChangeListener { notified++ }
        prefs.subtitleTextColor = "cyan"
        prefs.subtitleBackgroundOpacity = 0.5f
        assertEquals(2, notified)
    }

    @Test
    fun outlineContrastsWithTheText() {
        assertEquals(SubtitleTextColor.WHITE.argb, SubtitleTextColor.BLACK.outlineArgb)
        SubtitleTextColor.entries.filter { it != SubtitleTextColor.BLACK }.forEach {
            assertEquals(SubtitleBackgroundColor.INK.argb, it.outlineArgb, it.name)
        }
    }

    @Test
    fun boxOpacityBecomesTheAlpha() {
        assertEquals(0xFF0F1A3D.toInt(), SubtitleAppearance(backgroundColor = SubtitleBackgroundColor.NAVY).backgroundArgb)
        assertEquals(0x800F1A3D.toInt(), SubtitleAppearance(backgroundColor = SubtitleBackgroundColor.NAVY, backgroundOpacity = 0.5f).backgroundArgb)
        assertEquals(0x00FFFFFF, SubtitleAppearance(backgroundColor = SubtitleBackgroundColor.WHITE, backgroundOpacity = 0f).backgroundArgb)
    }

    @Test
    fun textSizeClampsAndScales() {
        // 5.5 % of the visible height…
        assertEquals(22f, SubtitleLayout.textSizeDp(400f, 1f), 0.001f)
        // …never under 16 dp or over 48 dp…
        assertEquals(16f, SubtitleLayout.textSizeDp(100f, 1f))
        assertEquals(48f, SubtitleLayout.textSizeDp(2000f, 1f))
        // …then times the system caption scale.
        assertEquals(32f, SubtitleLayout.textSizeDp(100f, 2f))
        assertEquals(72f, SubtitleLayout.textSizeDp(2000f, 1.5f))
        assertEquals(16f, SubtitleLayout.textSizeDp(100f, Float.NaN))
    }

    @Test
    fun fitLetterboxesInsideTheContainer() {
        // A 4:3 picture on a 16:9 screen: pillarboxed, all of it visible.
        val video = SubtitleLayout.videoRect(1920f, 1080f, 1440, 1080, aspectFill = false)
        assertEquals(SubtitleLayout.Box(240f, 0f, 1440f, 1080f), video)
        assertEquals(video, SubtitleLayout.visibleRect(video, 1920f, 1080f))
    }

    @Test
    fun fillCropsAndTheVisibleRectStaysOnScreen() {
        // A 2.39:1 picture filled into 16:9 hangs past the left and right edges.
        val video = SubtitleLayout.videoRect(1920f, 1080f, 2390, 1000, aspectFill = true)
        assertEquals(1080f, video.height, 0.01f)
        assertTrue(video.left < 0f && video.right > 1920f)
        val visible = SubtitleLayout.visibleRect(video, 1920f, 1080f)
        assertEquals(SubtitleLayout.Box(0f, 0f, 1920f, 1080f), visible)

        // A 4:3 picture filled into 16:9 hangs past the top and bottom instead.
        val tall = SubtitleLayout.videoRect(1920f, 1080f, 1440, 1080, aspectFill = true)
        assertTrue(tall.top < 0f && tall.bottom > 1080f)
        assertEquals(SubtitleLayout.Box(0f, 0f, 1920f, 1080f), SubtitleLayout.visibleRect(tall, 1920f, 1080f))
    }

    @Test
    fun anamorphicPixelsAndUnknownSizes() {
        // 720×576 with 64:45 pixels displays as 1024×576 (16:9).
        val video = SubtitleLayout.videoRect(1920f, 1080f, 720, 576, pixelRatio = 64f / 45f, aspectFill = false)
        assertEquals(1920f, video.width, 0.5f)
        assertEquals(1080f, video.height, 0.5f)
        assertEquals(SubtitleLayout.Box(0f, 0f, 1920f, 1080f), SubtitleLayout.videoRect(1920f, 1080f, 0, 0, aspectFill = true))
    }

    @Test
    fun cuesClearTheVisibleControls() {
        val visible = SubtitleLayout.Box(0f, 0f, 1920f, 1000f)
        assertEquals(SubtitleLayout.DEFAULT_BOTTOM_PADDING_FRACTION, SubtitleLayout.bottomPaddingFraction(visible, null, 12f))
        // Controls from y = 850 down: the cue rests 12 px above them.
        assertEquals(0.162f, SubtitleLayout.bottomPaddingFraction(visible, 850f, 12f), 0.0001f)
        // Controls below the picture (letterbox) don't move it.
        assertEquals(SubtitleLayout.DEFAULT_BOTTOM_PADDING_FRACTION, SubtitleLayout.bottomPaddingFraction(visible, 1050f, 12f))
        // Never above the lower 40 %.
        assertEquals(SubtitleLayout.MAX_BOTTOM_PADDING_FRACTION, SubtitleLayout.bottomPaddingFraction(visible, 100f, 12f))
    }
}
