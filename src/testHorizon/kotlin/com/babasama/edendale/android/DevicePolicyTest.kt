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
                offersImmersiveTheater = false,
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
                offersImmersiveTheater = false,
            ),
            DevicePolicy.resolve(isTelevision = true, isHorizonOs = false),
        )
    }

    @Test
    fun horizonIsHandheldWithThumbstickScrollAndTheaterAndWithoutPipEnhancementSwipesOrInlineTrailers() {
        assertEquals(
            DevicePolicy(
                usesTelevisionShell = false,
                publishesWatchNext = false,
                offersPictureInPicture = false,
                enhancementDefault = EnhancementPreset.OFF,
                offersSwipeAdjustments = false,
                playsTrailersInline = false,
                scrollsWithThumbstick = true,
                offersImmersiveTheater = true,
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

    /** G.T1: only a headset shows the Theater button; a TV or phone build of this branch never would. */
    @Test
    fun onlyHorizonOffersTheTheater() {
        assertEquals(true, DevicePolicy.resolve(isTelevision = false, isHorizonOs = true).offersImmersiveTheater)
        assertEquals(false, DevicePolicy.resolve(isTelevision = false, isHorizonOs = false).offersImmersiveTheater)
        assertEquals(false, DevicePolicy.resolve(isTelevision = true, isHorizonOs = false).offersImmersiveTheater)
    }

    @Test
    fun theBuildFlagIsOnForThisFlavor() {
        assertEquals(true, BuildConfig.HORIZON_OS)
    }
}
