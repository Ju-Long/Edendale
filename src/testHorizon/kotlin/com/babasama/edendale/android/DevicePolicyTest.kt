package com.babasama.edendale.android

import com.babasama.edendale.android.player.video.EnhancementPreset
import kotlin.test.Test
import kotlin.test.assertEquals

/** HORIZON.md A.T1: every combination of TV and Horizon OS. */
class DevicePolicyTest {

    @Test
    fun handheldKeepsTheAndroidChoices() {
        assertEquals(
            DevicePolicy(
                usesTelevisionShell = false,
                publishesWatchNext = false,
                offersPictureInPicture = true,
                enhancementDefault = EnhancementPreset.BALANCED,
                offersSwipeAdjustments = true,
                playsTrailersInline = true,
                scrollsWithThumbstick = false,
            ),
            DevicePolicy.resolve(isTelevision = false, isHorizonOs = false),
        )
    }

    @Test
    fun televisionGetsTheShellWatchNextAndNoEnhancement() {
        assertEquals(
            DevicePolicy(
                usesTelevisionShell = true,
                publishesWatchNext = true,
                offersPictureInPicture = true,
                enhancementDefault = EnhancementPreset.OFF,
                offersSwipeAdjustments = true,
                playsTrailersInline = true,
                scrollsWithThumbstick = false,
            ),
            DevicePolicy.resolve(isTelevision = true, isHorizonOs = false),
        )
    }

    @Test
    fun horizonIsHandheldWithThumbstickScrollAndWithoutPipEnhancementSwipesOrInlineTrailers() {
        assertEquals(
            DevicePolicy(
                usesTelevisionShell = false,
                publishesWatchNext = false,
                offersPictureInPicture = false,
                enhancementDefault = EnhancementPreset.OFF,
                offersSwipeAdjustments = false,
                playsTrailersInline = false,
                scrollsWithThumbstick = true,
            ),
            DevicePolicy.resolve(isTelevision = false, isHorizonOs = true),
        )
    }

    @Test
    fun aHeadsetIsNeverATelevision() {
        assertEquals(
            DevicePolicy.resolve(isTelevision = false, isHorizonOs = true),
            DevicePolicy.resolve(isTelevision = true, isHorizonOs = true),
        )
    }

    @Test
    fun theBuildFlagIsOnForThisFlavor() {
        assertEquals(true, BuildConfig.HORIZON_OS)
    }
}
