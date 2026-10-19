package com.babasama.edendale.android

import android.content.Context
import com.babasama.edendale.android.player.video.EnhancementPreset

/**
 * What this device gets from the app (HORIZON.md B.1). The Android build
 * decides these from "is this a TV?" alone; Horizon OS is the second input.
 * A Quest reports the handheld `uiMode`, so without this it would get a
 * phone's choices: Picture-in-Picture, which Horizon OS can't float
 * (Findings, 2026-10-10), and an enhancement default measured on phones.
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
            )
        }

        fun current(context: Context): DevicePolicy =
            resolve(isTelevision = context.isTelevisionDevice(), isHorizonOs = BuildConfig.HORIZON_OS)
    }
}
