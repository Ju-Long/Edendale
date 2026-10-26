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
                seeksWithThumbstick = false,
                offersImmersiveTheater = false,
            ),
            DevicePolicy.resolve(isTelevision = false, isHorizonOs = false, hasTheater = true),
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
                seeksWithThumbstick = false,
                offersImmersiveTheater = false,
            ),
            DevicePolicy.resolve(isTelevision = true, isHorizonOs = false, hasTheater = true),
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
                seeksWithThumbstick = true,
                offersImmersiveTheater = true,
            ),
            DevicePolicy.resolve(isTelevision = false, isHorizonOs = true, hasTheater = true),
        )
    }

    /** D.3: only a headset's player reads the sticks and the D-pad's hold this way. */
    @Test
    fun onlyHorizonSeeksWithTheThumbstick() {
        for (isTelevision in listOf(false, true)) {
            val policy = DevicePolicy.resolve(isTelevision, isHorizonOs = false, hasTheater = false)
            assertEquals(false, policy.seeksWithThumbstick)
        }
        val horizon = DevicePolicy.resolve(isTelevision = false, isHorizonOs = true, hasTheater = false)
        assertEquals(true, horizon.seeksWithThumbstick)
    }

    @Test
    fun aHeadsetIsNeverATelevision() {
        assertEquals(
            DevicePolicy.resolve(isTelevision = false, isHorizonOs = true, hasTheater = true),
            DevicePolicy.resolve(isTelevision = true, isHorizonOs = true, hasTheater = true),
        )
    }

    /** G.T1: only a headset shows the Theater button; a TV or phone build of this branch never would. */
    @Test
    fun onlyHorizonOffersTheTheater() {
        assertEquals(true, DevicePolicy.resolve(isTelevision = false, isHorizonOs = true, hasTheater = true).offersImmersiveTheater)
        assertEquals(false, DevicePolicy.resolve(isTelevision = false, isHorizonOs = false, hasTheater = true).offersImmersiveTheater)
        assertEquals(false, DevicePolicy.resolve(isTelevision = true, isHorizonOs = false, hasTheater = true).offersImmersiveTheater)
    }

    /** D15: a 2D build (horizon.theater=false) has no theater to offer; nothing else changes. */
    @Test
    fun aBuildWithoutTheTheaterOffersNone() {
        for (isTelevision in listOf(false, true)) {
            for (isHorizonOs in listOf(false, true)) {
                val with = DevicePolicy.resolve(isTelevision, isHorizonOs, hasTheater = true)
                val without = DevicePolicy.resolve(isTelevision, isHorizonOs, hasTheater = false)
                assertEquals(with.copy(offersImmersiveTheater = false), without)
            }
        }
    }

    @Test
    fun theBuildFlagIsOnForThisFlavor() {
        assertEquals(true, BuildConfig.HORIZON_OS)
    }
}
