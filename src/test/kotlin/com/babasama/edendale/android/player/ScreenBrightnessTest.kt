package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The player's brightness on Android's own slider scale (owner request 8). */
class ScreenBrightnessTest {

    private val stock = BrightnessSettingRange.DEFAULT

    // MARK: - SystemUI's curve

    @Test
    fun linearForLevelMatchesSystemUi() {
        assertEquals(0f, BrightnessCurve.linearForLevel(0f))
        // The knee: the middle of the slider is 1/12 of the panel's range.
        assertEquals(1f / 12f, BrightnessCurve.linearForLevel(0.5f), 1e-6f)
        assertEquals(0.020833f, BrightnessCurve.linearForLevel(0.25f), 1e-5f)
        assertEquals(0.26496f, BrightnessCurve.linearForLevel(0.75f), 1e-4f)
        assertEquals(1f, BrightnessCurve.linearForLevel(1f), 1e-5f)
    }

    @Test
    fun levelForLinearMatchesSystemUi() {
        assertEquals(0f, BrightnessCurve.levelForLinear(0f))
        assertEquals(0.5f, BrightnessCurve.levelForLinear(1f / 12f), 1e-5f)
        assertEquals(0.87164f, BrightnessCurve.levelForLinear(0.5f), 1e-4f)
        assertEquals(0.54409f, BrightnessCurve.levelForLinear(0.1f), 1e-4f)
        assertEquals(1f, BrightnessCurve.levelForLinear(1f), 1e-4f)
    }

    @Test
    fun theCurveRoundTripsAndOnlyRises() {
        var previousLevel = -1f
        var previousLinear = -1f
        for (step in 0..1000) {
            val x = step / 1000f
            assertEquals(x, BrightnessCurve.levelForLinear(BrightnessCurve.linearForLevel(x)), 1e-4f, "level $x")
            assertEquals(x, BrightnessCurve.linearForLevel(BrightnessCurve.levelForLinear(x)), 1e-4f, "linear $x")
            val level = BrightnessCurve.levelForLinear(x)
            val linear = BrightnessCurve.linearForLevel(x)
            assertTrue(level >= previousLevel, "levelForLinear falls at $x")
            assertTrue(linear >= previousLinear, "linearForLevel falls at $x")
            previousLevel = level
            previousLinear = linear
        }
    }

    @Test
    fun theCurveClampsNonsense() {
        assertEquals(0f, BrightnessCurve.levelForLinear(-1f))
        assertEquals(1f, BrightnessCurve.levelForLinear(7f))
        assertEquals(0f, BrightnessCurve.levelForLinear(Float.NaN))
        assertEquals(0f, BrightnessCurve.linearForLevel(Float.NaN))
        assertEquals(0f, BrightnessCurve.linearForLevel(Float.NEGATIVE_INFINITY))
        assertEquals(1f, BrightnessCurve.linearForLevel(Float.POSITIVE_INFINITY))
    }

    // MARK: - The setting, as the slider shows it

    @Test
    fun android11AndLaterReadTheSettingAsOneTo255() {
        assertEquals(0f, BrightnessCurve.levelForSetting(1, stock, 31))
        assertEquals(0.498f, BrightnessCurve.levelForSetting(22, stock, 31), 1e-3f)
        assertEquals(0.749f, BrightnessCurve.levelForSetting(68, stock, 31), 1e-3f)
        assertEquals(0.872f, BrightnessCurve.levelForSetting(128, stock, 31), 1e-3f)
        assertEquals(1f, BrightnessCurve.levelForSetting(255, stock, 31), 1e-4f)
    }

    @Test
    fun aSettingPastTheMaximumWidensTheScale() {
        // Some manufacturers run the setting to 2047.
        val oem = BrightnessSettingRange(1, 2047)
        assertEquals(0.593f, BrightnessCurve.levelForSetting(255, oem, 31), 1e-3f)
        // A stock-configured range that reads 1500 means a 2047-step scale.
        assertEquals(BrightnessSettingRange(1, 2047), stock.including(1500))
        assertEquals(BrightnessSettingRange(1, 511), stock.including(256))
        assertEquals(BrightnessSettingRange(1, 4095), stock.including(4095))
        assertEquals(stock, stock.including(255))
        assertEquals(
            BrightnessCurve.levelForSetting(1500, BrightnessSettingRange(1, 2047), 31),
            BrightnessCurve.levelForSetting(1500, stock, 31),
        )
        assertEquals(1f, BrightnessCurve.levelForSetting(2047, stock, 31), 1e-4f)
    }

    @Test
    fun beforeAndroid11TheConfiguredRangeCounts() {
        val pie = BrightnessSettingRange(10, 255)
        assertEquals(0.865f, BrightnessCurve.levelForSetting(128, pie, 28), 1e-3f)
        assertEquals(0f, BrightnessCurve.levelForSetting(10, pie, 28))
        assertEquals(0f, BrightnessCurve.levelForSetting(3, pie, 29))
        // Android 8's slider was linear.
        assertEquals(0.4816f, BrightnessCurve.levelForSetting(128, pie, 26), 1e-4f)
    }

    @Test
    fun theWindowValueLandsOnTheSettingTheSliderShows() {
        for (setting in 1..255) {
            val level = BrightnessCurve.levelForSetting(setting, stock, 31)
            assertEquals((setting - 1) / 254f, BrightnessCurve.windowValueForLevel(level, stock, 31), 1e-4f, "setting $setting")
        }
        // Before Android 11 the system truncates value × 255 into the int
        // setting (`(int) (value * 255)`), so a value a hair under a whole
        // step would land one step low.
        for (range in listOf(BrightnessSettingRange(10, 255), stock, BrightnessSettingRange(0, 255))) {
            for (sdk in 26..29) {
                for (setting in maxOf(range.min, 1)..range.max) {
                    val level = BrightnessCurve.levelForSetting(setting, range, sdk)
                    val window = BrightnessCurve.windowValueForLevel(level, range, sdk)
                    assertEquals(setting, (window * 255f).toInt(), "API $sdk, $range, setting $setting")
                }
            }
        }
    }

    @Test
    fun theBottomOfTheSwipeIsThePanelsMinimumNeverOff() {
        assertEquals(0f, BrightnessCurve.windowValueForLevel(0f, stock, 31))
        assertEquals(1f, BrightnessCurve.windowValueForLevel(1f, stock, 31), 1e-5f)
        val pie = BrightnessSettingRange(10, 255)
        assertEquals(10, (BrightnessCurve.windowValueForLevel(0f, pie, 28) * 255).toInt())
        assertEquals(1f, BrightnessCurve.windowValueForLevel(1f, pie, 28))
        // A configured minimum of 0 still never asks for int 0, which counts as no override.
        val zero = BrightnessSettingRange(0, 255)
        assertEquals(1, (BrightnessCurve.windowValueForLevel(0f, zero, 27) * 255).toInt())
        assertEquals(0f, BrightnessCurve.windowValueForLevel(Float.NaN, stock, 31))
    }

    @Test
    fun theConfiguredRangeFallsBackToTheDefault() {
        assertEquals(BrightnessSettingRange(1, 255), BrightnessSettingRange.of(null, null))
        assertEquals(BrightnessSettingRange(10, 255), BrightnessSettingRange.of(10, 255))
        assertEquals(BrightnessSettingRange(10, 255), BrightnessSettingRange.of(10, null))
        assertEquals(BrightnessSettingRange(1, 255), BrightnessSettingRange.of(-4, 0))
        assertEquals(BrightnessSettingRange(1, 4095), BrightnessSettingRange.of(1, 4095))
        // A maximum at or below the minimum makes no range.
        assertEquals(BrightnessSettingRange(20, 255), BrightnessSettingRange.of(20, 20))
    }

    // MARK: - Coming back to the player

    @Test
    fun thePlayersLevelReturnsUnlessTheViewerChangedTheBrightness() {
        assertTrue(BrightnessResumeRules.keepsPlayerLevel(automatic = false, settingAtSuspend = 120, settingNow = 120))
        assertFalse(BrightnessResumeRules.keepsPlayerLevel(automatic = false, settingAtSuspend = 120, settingNow = 60))
        // Adaptive brightness moves the setting by itself, so a change says nothing.
        assertTrue(BrightnessResumeRules.keepsPlayerLevel(automatic = true, settingAtSuspend = 120, settingNow = 60))
        assertTrue(BrightnessResumeRules.keepsPlayerLevel(automatic = true, settingAtSuspend = 120, settingNow = 120))
    }
}
