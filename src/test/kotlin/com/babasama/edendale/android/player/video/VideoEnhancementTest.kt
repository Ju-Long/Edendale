package com.babasama.edendale.android.player.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F.2.T1–T2, F.3.T1, F.6.T1, and the saved enhancement choice. */
class VideoEnhancementTest {

    // MARK: - Picture adjustments (F.2.T1)

    @Test
    fun adjustmentsSnapClampAndFallBackToNeutral() {
        assertEquals(1.05f, VideoAdjustment.BRIGHTNESS.normalize(1.04f))
        assertEquals(2f, VideoAdjustment.BRIGHTNESS.normalize(7f))
        assertEquals(0f, VideoAdjustment.CONTRAST.normalize(-1f))
        assertEquals(0.25f, VideoAdjustment.GAMMA.normalize(0.1f))
        assertEquals(3f, VideoAdjustment.SATURATION.normalize(3.2f))
        assertEquals(45f, VideoAdjustment.HUE.normalize(43f))
        assertEquals(360f, VideoAdjustment.HUE.normalize(400f))
        assertEquals(1f, VideoAdjustment.GAMMA.normalize(Float.NaN))
        assertEquals(0f, VideoAdjustment.HUE.normalize(Float.POSITIVE_INFINITY))
        // Repeated steps stay on the grid.
        var value = 1f
        repeat(7) { value = VideoAdjustment.BRIGHTNESS.normalize(value + 0.05f) }
        assertEquals(1.35f, value)
    }

    @Test
    fun neutralDetectionAndTheJsonRoundTrip() {
        assertTrue(VideoAdjustmentValues().isNeutral)
        val adjusted = VideoAdjustmentValues().with(VideoAdjustment.CONTRAST, 1.2f).with(VideoAdjustment.HUE, 120f)
        assertFalse(adjusted.isNeutral)
        assertEquals(adjusted, VideoAdjustmentValues.fromJson(adjusted.toJson()))
        assertTrue(adjusted.toJson().contains("\"contrast\":1.2"))
        // Missing, unknown, and non-finite fields read as neutral; garbage is all neutral.
        assertEquals(VideoAdjustmentValues(saturation = 2f), VideoAdjustmentValues.fromJson("""{"saturation":2,"sparkle":9}"""))
        assertEquals(VideoAdjustmentValues.NEUTRAL, VideoAdjustmentValues.fromJson("""{"brightness":"NaN"}"""))
        assertEquals(VideoAdjustmentValues.NEUTRAL, VideoAdjustmentValues.fromJson("not json"))
        assertEquals(VideoAdjustmentValues.NEUTRAL, VideoAdjustmentValues.fromJson(null))
        assertEquals(VideoAdjustmentValues(gamma = 3f), VideoAdjustmentValues.fromJson("""{"gamma":9.9}"""))
    }

    // MARK: - The saved enhancement choice (owner request 9)

    @Test
    fun enhancementRoundTripsThroughJsonWithoutShowOriginal() {
        val choice = EnhancementSettings(preset = EnhancementPreset.HIGH_QUALITY, sharpness = 0.75f, denoise = 0.2f, showOriginal = true)
        val json = choice.toJson()
        assertFalse(json.contains("showOriginal"))
        assertEquals(choice.copy(showOriginal = false), EnhancementSettings.fromJson(json))
        EnhancementPreset.entries.forEach { preset ->
            assertEquals(preset, EnhancementSettings.fromJson(EnhancementSettings(preset = preset).toJson())?.preset)
            assertEquals(preset, EnhancementPreset.fromRaw(preset.raw))
        }
        assertTrue(json.contains("\"preset\":\"quality\""))
    }

    @Test
    fun anUnreadableChoiceIsNoChoiceNeverBalanced() {
        assertNull(EnhancementSettings.fromJson(null))
        assertNull(EnhancementSettings.fromJson(""))
        assertNull(EnhancementSettings.fromJson("not json"))
        assertNull(EnhancementSettings.fromJson("[\"balanced\"]"))
        assertNull(EnhancementSettings.fromJson("{}"))
        assertNull(EnhancementSettings.fromJson("""{"sharpness":0.5,"denoise":0.5}"""))
        assertNull(EnhancementSettings.fromJson("""{"preset":"ultra"}"""))
        assertNull(EnhancementSettings.fromJson("""{"preset":2}"""))
        assertNull(EnhancementSettings.fromJson("""{"preset":null}"""))
        assertNull(EnhancementSettings.fromJson("""{"preset":{"raw":"off"}}"""))
        assertNull(EnhancementPreset.fromRaw("Balanced"))
        assertNull(EnhancementPreset.fromRaw(null))
    }

    @Test
    fun storedSlidersFollowTheSliderRules() {
        // Snapped to 0.05, clamped, and 0.5 where missing or broken.
        assertEquals(
            EnhancementSettings(preset = EnhancementPreset.OFF).withSharpness(0.65f).withDenoise(1f),
            EnhancementSettings.fromJson("""{"preset":"off","sharpness":0.66,"denoise":7}"""),
        )
        assertEquals(
            EnhancementSettings(preset = EnhancementPreset.SHARPEN_ONLY, sharpness = 0.5f, denoise = 0.5f),
            EnhancementSettings.fromJson("""{"preset":"sharpenOnly","sharpness":"lots","denoise":[1]}"""),
        )
        assertEquals(
            EnhancementSettings(preset = EnhancementPreset.BALANCED, sharpness = 0f, denoise = 0.5f),
            EnhancementSettings.fromJson("""{"preset":"balanced","sharpness":-2}"""),
        )
    }

    @Test
    fun theStoredChoiceWinsOnEveryDeviceElseTheDeviceDefault() {
        val stored = EnhancementSettings(preset = EnhancementPreset.HIGH_QUALITY, sharpness = 0.3f, denoise = 0.9f)
        // TV and a failed (or not yet run) capability check no longer override an explicit choice.
        for (isTelevision in listOf(true, false)) {
            for (capable in listOf(true, false, null)) {
                assertEquals(stored, EnhancementSettings.initial(stored, isTelevision, capable))
            }
        }
        assertFalse(EnhancementSettings.initial(stored.copy(showOriginal = true), false, true).showOriginal)
        // Nothing stored: Balanced only on a handheld that passed the check.
        assertEquals(EnhancementSettings(preset = EnhancementPreset.BALANCED), EnhancementSettings.initial(null, false, true))
        assertEquals(EnhancementPreset.OFF, EnhancementSettings.initial(null, false, false).preset)
        assertEquals(EnhancementPreset.OFF, EnhancementSettings.initial(null, false, null).preset)
        assertEquals(EnhancementPreset.OFF, EnhancementSettings.initial(null, true, true).preset)
        assertEquals(0.5f, EnhancementSettings.initial(null, true, true).sharpness)
    }

    // MARK: - Color math (F.2.T2)

    private fun assertRgb(expected: DoubleArray, actual: DoubleArray, tolerance: Double = 1e-6) {
        expected.indices.forEach { assertEquals(expected[it], actual[it], tolerance, "channel $it") }
    }

    @Test
    fun neutralIsTheIdentity() {
        val rgb = doubleArrayOf(0.2, 0.5, 0.9)
        assertRgb(rgb, ColorMath.apply(rgb, VideoAdjustmentValues()))
    }

    @Test
    fun saturationZeroGivesTheLuma() {
        val rgb = doubleArrayOf(0.8, 0.4, 0.1)
        val luma = 0.8 * 0.2126 + 0.4 * 0.7152 + 0.1 * 0.0722
        assertRgb(doubleArrayOf(luma, luma, luma), ColorMath.apply(rgb, VideoAdjustmentValues(saturation = 0f)))
    }

    @Test
    fun aHundredAndTwentyDegreeTurnMovesRedToGreen() {
        assertRgb(doubleArrayOf(0.0, 1.0, 0.0), ColorMath.apply(doubleArrayOf(1.0, 0.0, 0.0), VideoAdjustmentValues(hue = 120f)), 1e-5)
        assertRgb(doubleArrayOf(0.0, 0.0, 1.0), ColorMath.apply(doubleArrayOf(0.0, 1.0, 0.0), VideoAdjustmentValues(hue = 120f)), 1e-5)
    }

    @Test
    fun brightnessContrastAndGammaInOrder() {
        // 0.4 × 1.5 = 0.6; (0.6 − 0.5) × 2 + 0.5 = 0.7; 0.7^(1/2).
        val out = ColorMath.apply(doubleArrayOf(0.4, 0.4, 0.4), VideoAdjustmentValues(brightness = 1.5f, contrast = 2f, gamma = 2f))
        assertRgb(doubleArrayOf(Math.sqrt(0.7), Math.sqrt(0.7), Math.sqrt(0.7)), out)
        // And the result clamps to 0…1.
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0), ColorMath.apply(doubleArrayOf(0.9, 0.9, 0.9), VideoAdjustmentValues(brightness = 2f)))
    }

    // MARK: - Target resolution (F.3.T1)

    private fun size(w: Int, h: Int) = PixelSize(w, h)

    @Test
    fun appleTargetResolutionCases() {
        val p720 = size(1280, 720)
        assertEquals(size(1920, 1080), UpscaleRules.targetResolution(p720, size(1920, 1080)))
        assertEquals(size(1920, 1080), UpscaleRules.targetResolution(p720, size(3840, 2160)))
        assertEquals(size(1440, 810), UpscaleRules.targetResolution(p720, size(1440, 900)))
        val p1080 = size(1920, 1080)
        assertEquals(p1080, UpscaleRules.targetResolution(p1080, size(1920, 1080)))
        assertEquals(size(3840, 2160), UpscaleRules.targetResolution(p1080, size(3840, 2160)))
        val p4k = size(3840, 2160)
        assertEquals(p4k, UpscaleRules.targetResolution(p4k, size(3840, 2160)))
        assertEquals(size(3840, 2160), UpscaleRules.targetResolution(p720, size(1920, 1080), override = size(3840, 2160)))
    }

    @Test
    fun oddUltrawideAndPortraitSources() {
        val odd = UpscaleRules.targetResolution(size(853, 480), size(1920, 1080))
        assertEquals(0, odd.width % 2)
        assertEquals(0, odd.height % 2)
        assertEquals(size(1920, 1080), odd)
        // A 2.39:1 720p-tier source fits the 1080p box by width.
        assertEquals(size(1920, 804), UpscaleRules.targetResolution(size(1280, 536), size(1920, 1080)))
        // A portrait phone video fits by height.
        assertEquals(size(608, 1080), UpscaleRules.targetResolution(size(360, 640), size(1080, 2340)))
        // A source larger than the display in one dimension but not both still scales within the box.
        assertEquals(size(1280, 720), UpscaleRules.targetResolution(size(1280, 720), size(1200, 2000)))
    }

    @Test
    fun theResolutionLabel() {
        assertEquals("1280×720 → 1920×1080", UpscaleRules.label(size(1280, 720), size(1920, 1080)))
        assertEquals("3840×2160", UpscaleRules.label(size(3840, 2160), size(3840, 2160)))
    }

    @Test
    fun presetsAndTheirStages() {
        assertEquals(EnhancementStages(false, false, false), EnhancementStages.forPreset(EnhancementPreset.OFF, 0.5f, 0.5f))
        assertEquals(EnhancementStages(false, true, false), EnhancementStages.forPreset(EnhancementPreset.SHARPEN_ONLY, 0.5f, 0.5f))
        assertEquals(EnhancementStages(true, true, false), EnhancementStages.forPreset(EnhancementPreset.BALANCED, 0.5f, 0.5f))
        assertEquals(EnhancementStages(true, true, true), EnhancementStages.forPreset(EnhancementPreset.HIGH_QUALITY, 0.5f, 0.5f))
        // Sharpness 0 skips the sharpening pass; denoise 0 skips denoise.
        assertEquals(EnhancementStages(true, false, false), EnhancementStages.forPreset(EnhancementPreset.HIGH_QUALITY, 0f, 0f))
        assertEquals(0.55f, EnhancementSettings().withSharpness(0.56f).sharpness)
        assertEquals(1f, EnhancementSettings().withDenoise(4f).denoise)
        assertEquals(listOf("off", "sharpenOnly", "balanced", "quality"), EnhancementPreset.entries.map { it.raw })
    }

    // MARK: - Governor (F.6.T1)

    private val all = EnhancementStages(upscale = true, sharpen = true, denoise = true)

    @Test
    fun overBudgetDropsDenoiseThenTheUpscale() {
        val governor = EnhancementGovernor(window = 5)
        repeat(5) { governor.record(12.0) }
        assertEquals(EnhancementStages(upscale = true, sharpen = true, denoise = false), governor.limit(all))
        repeat(5) { governor.record(10.0) }
        assertEquals(EnhancementStages(upscale = false, sharpen = true, denoise = false), governor.limit(all))
        // Sharpening is never dropped.
        repeat(20) { governor.record(30.0) }
        assertTrue(governor.limit(all).sharpen)
        assertEquals(2, governor.level)
    }

    @Test
    fun itRecoversWithHysteresis() {
        val governor = EnhancementGovernor(window = 5)
        repeat(5) { governor.record(12.0) }
        assertEquals(1, governor.level)
        // Just under budget isn't enough to come back: no flapping.
        repeat(10) { governor.record(7.5) }
        assertEquals(1, governor.level)
        repeat(5) { governor.record(3.0) }
        assertEquals(0, governor.level)
        assertEquals(all, governor.limit(all))
    }

    @Test
    fun thermalPressureOrBatterySaverForcesAStepDown() {
        val governor = EnhancementGovernor(window = 5)
        governor.setPressure(thermalModerateOrWorse = true, batterySaver = false)
        assertEquals(EnhancementStages(upscale = true, sharpen = true, denoise = false), governor.limit(all))
        repeat(5) { governor.record(12.0) }
        assertEquals(EnhancementStages(upscale = false, sharpen = true, denoise = false), governor.limit(all))
        governor.setPressure(thermalModerateOrWorse = false, batterySaver = false)
        assertEquals(EnhancementStages(upscale = true, sharpen = true, denoise = false), governor.limit(all))
        governor.reset()
        governor.setPressure(thermalModerateOrWorse = false, batterySaver = true)
        assertFalse(governor.limit(all).denoise)
    }

    // MARK: - Motion Smoothing in the governor (G.5.2)

    @Test
    fun motionSmoothingGoesFirstWhenTheEnhancementPassesRunOverBudget() {
        val governor = EnhancementGovernor(window = 5)
        governor.motionSmoothingActive = true
        repeat(5) { governor.record(12.0) }
        assertFalse(governor.allowsMotionSmoothing)
        // Every enhancement stage is still there.
        assertEquals(all, governor.limit(all))
        assertEquals(0, governor.level)
        // Next time over budget, denoise goes as before.
        repeat(5) { governor.record(12.0) }
        assertEquals(1, governor.level)
        // Coming back under budget restores enhancement, not Motion Smoothing.
        repeat(5) { governor.record(3.0) }
        assertEquals(0, governor.level)
        assertFalse(governor.allowsMotionSmoothing)
        // The next video starts again.
        governor.resetMotionSmoothing()
        assertTrue(governor.allowsMotionSmoothing)
    }

    @Test
    fun motionSmoothingOverItsOwnBudgetHalvesTheSearchThenTurnsOff() {
        val governor = EnhancementGovernor(window = 5)
        governor.motionSmoothingActive = true
        repeat(5) { governor.recordMotionSmoothing(6.0) }
        assertFalse(governor.motionSmoothingAtHalfResolution)
        repeat(5) { governor.recordMotionSmoothing(11.0) }
        assertTrue(governor.motionSmoothingAtHalfResolution)
        assertTrue(governor.allowsMotionSmoothing)
        // Frames timed at full resolution before the GL thread saw the step don't count against half resolution.
        repeat(EnhancementGovernor.SETTLE_SAMPLES) { assertFalse(governor.recordMotionSmoothing(12.0)) }
        repeat(5) { governor.recordMotionSmoothing(4.0) }
        assertTrue(governor.allowsMotionSmoothing)
        repeat(5) { governor.recordMotionSmoothing(13.0) }
        assertFalse(governor.allowsMotionSmoothing)
        assertEquals(2, governor.motionSmoothingLevel)
        // Off stays off: nothing measures it, and junk samples don't count.
        repeat(10) { governor.recordMotionSmoothing(1.0) }
        assertFalse(governor.recordMotionSmoothing(Double.NaN))
        assertFalse(governor.allowsMotionSmoothing)
        // Enhancement stages are untouched by Motion Smoothing's own budget.
        assertEquals(all, governor.limit(all))
    }

    @Test
    fun pressureTurnsMotionSmoothingOffUntilItClears() {
        val governor = EnhancementGovernor(window = 5)
        governor.motionSmoothingActive = true
        governor.setPressure(thermalModerateOrWorse = false, batterySaver = true)
        assertFalse(governor.allowsMotionSmoothing)
        governor.setPressure(thermalModerateOrWorse = false, batterySaver = false)
        assertTrue(governor.allowsMotionSmoothing)
        // With Motion Smoothing off for this video, over budget drops denoise as it always did.
        val plain = EnhancementGovernor(window = 5)
        repeat(5) { plain.record(12.0) }
        assertEquals(1, plain.level)
        assertTrue(plain.allowsMotionSmoothing)
    }

    @Test
    fun motionSmoothingIsSavedOffByDefaultAndReadBackSafely() {
        val on = EnhancementSettings(preset = EnhancementPreset.SHARPEN_ONLY, motionSmoothing = true)
        assertTrue(on.toJson().contains("\"motionSmoothing\":true"))
        assertEquals(on, EnhancementSettings.fromJson(on.toJson()))
        // A choice saved before G.5 has no such key: off.
        assertEquals(false, EnhancementSettings.fromJson("""{"preset":"balanced","sharpness":0.5,"denoise":0.5}""")?.motionSmoothing)
        // Anything but a JSON true reads as off.
        assertEquals(false, EnhancementSettings.fromJson("""{"preset":"balanced","motionSmoothing":"true"}""")?.motionSmoothing)
        assertEquals(false, EnhancementSettings.fromJson("""{"preset":"balanced","motionSmoothing":1}""")?.motionSmoothing)
        // Nothing saved: off on every device class.
        assertFalse(EnhancementSettings.initial(stored = null, isTelevision = false, capable = true).motionSmoothing)
        assertTrue(EnhancementSettings.initial(stored = on, isTelevision = false, capable = true).motionSmoothing)
    }

    @Test
    fun theTrackSizeStandsInForTheOneMedia3DoesNotReport() {
        // Media3 1.9.0 reports no video size while effects are installed; the track's own size,
        // shaped like ExoPlayer's direct-path report, keeps Fit and Fill apart.
        val plain = VideoEffectsController.displayVideoSize(1280, 720, 0, 1f)!!
        assertEquals(listOf(1280f, 720f, 1f), listOf(plain.width.toFloat(), plain.height.toFloat(), plain.pixelWidthHeightRatio))
        // A quarter turn swaps the sides and inverts the pixel shape.
        val portrait = VideoEffectsController.displayVideoSize(1920, 1080, 90, 1f)!!
        assertEquals(listOf(1080, 1920), listOf(portrait.width, portrait.height))
        val anamorphic = VideoEffectsController.displayVideoSize(720, 576, 270, 16f / 15f)!!
        assertEquals(15f / 16f, anamorphic.pixelWidthHeightRatio, 1e-6f)
        // Unknown or nonsense values.
        assertEquals(1f, VideoEffectsController.displayVideoSize(720, 480, 180, Float.NaN)!!.pixelWidthHeightRatio)
        assertEquals(null, VideoEffectsController.displayVideoSize(-1, 720, 0, 1f))
    }
}
