package com.babasama.edendale.android

import android.content.Context
import com.babasama.edendale.android.player.video.EnhancementPreset

/**
 * What this device gets from the app (HORIZON.md B.1). The Android build
 * decides these from "is this a TV?" alone; Horizon OS is the second input.
 * A Quest reports the handheld `uiMode`, so without this it would get a
 * phone's choices: Picture-in-Picture, which Horizon OS can't float
 * (Findings, 2026-10-10), an enhancement default measured on phones, swipe
 * controls a panel ignores, a trailer whose picture a panel doesn't show, and
 * no use for the thumbsticks (Findings, 2026-10-25).
 *
 * [resolve] is pure, so [DevicePolicyTest] covers every combination;
 * [current] reads the device and the build flag.
 */
data class DevicePolicy(
    /** Drive the app with a remote: the TV shell, its layouts, and the TV-only flows. */
    val usesTelevisionShell: Boolean,
    /** Copy Continue Watching into the TV home screen's Watch Next row (I.3). */
    val publishesWatchNext: Boolean,
    /** Offer Picture-in-Picture, provided the system also reports the feature. */
    val offersPictureInPicture: Boolean,
    /** Video Enhancement before the viewer chooses; the capability check may still lower it to Off. */
    val enhancementDefault: EnhancementPreset,
    /** The player's vertical swipes: window brightness on the left, player volume on the right (B.6). */
    val offersSwipeAdjustments: Boolean,
    /** Play the trailer in the detail hero; otherwise Watch Trailer hands it to YouTube or the browser (B.10). */
    val playsTrailersInline: Boolean,
    /** The library scrolls what the ray points at with a thumbstick ([ThumbstickScroller], B.5). */
    val scrollsWithThumbstick: Boolean,
) {
    companion object {
        fun resolve(isTelevision: Boolean, isHorizonOs: Boolean): DevicePolicy {
            // A headset is never a TV, whatever its uiMode says.
            val television = isTelevision && !isHorizonOs
            return DevicePolicy(
                usesTelevisionShell = television,
                publishesWatchNext = television,
                offersPictureInPicture = !isHorizonOs,
                // D6: Off on Horizon until B.8 measures the panel; the compositor
                // already resamples it, and the GPU also draws the headset's view.
                enhancementDefault = if (television || isHorizonOs) EnhancementPreset.OFF else EnhancementPreset.BALANCED,
                // B.6: neither swipe made a visible or audible change in a panel on the Quest 3.
                offersSwipeAdjustments = !isHorizonOs,
                // B.10: the embed played sound but no picture in a panel on the Quest 3.
                playsTrailersInline = !isHorizonOs,
                // B.5: Horizon sends the sticks as joystick axes that nothing else reads.
                scrollsWithThumbstick = isHorizonOs,
            )
        }

        fun current(context: Context): DevicePolicy =
            resolve(isTelevision = context.isTelevisionDevice(), isHorizonOs = BuildConfig.HORIZON_OS)
    }
}
