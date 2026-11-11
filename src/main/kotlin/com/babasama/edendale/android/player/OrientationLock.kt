package com.babasama.edendale.android.player

/**
 * The orientations Rotation Lock pins the player to, with their
 * `ActivityInfo.SCREEN_ORIENTATION_*` values. Exact variants only: the
 * system's `SCREEN_ORIENTATION_LOCKED` re-pins whatever rotation the display
 * happens to have after Picture in Picture or split screen.
 */
enum class LockedOrientation(val activityInfoValue: Int) {
    LANDSCAPE(0),
    PORTRAIT(1),
    REVERSE_LANDSCAPE(8),
    REVERSE_PORTRAIT(9),
    ;

    /** The same orientation turned half way round. */
    fun opposite(): LockedOrientation = when (this) {
        LANDSCAPE -> REVERSE_LANDSCAPE
        REVERSE_LANDSCAPE -> LANDSCAPE
        PORTRAIT -> REVERSE_PORTRAIT
        REVERSE_PORTRAIT -> PORTRAIT
    }
}

/**
 * Rotation Lock for the handheld player (Apple's iOS chip): it pins the
 * orientation on screen until it is turned off or the player closes. The
 * player's manifest asks for `sensorLandscape`, which flips between landscape
 * and reverse landscape from the sensor even with the system's auto-rotate
 * off, so without this a viewer lying on their side can't keep the picture
 * still. Pure, so the JVM suite covers it.
 */
object OrientationLockRules {

    /** `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, the manifest's own value, which unlocking goes back to. */
    const val UNLOCKED = 6

    /** Android 16 ignores orientation requests from apps that target it on displays at least this wide. */
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600

    /** The first API level (and target) where that applies. */
    const val IGNORES_ORIENTATION_FROM_SDK = 36

    /**
     * The orientation on screen for a display [rotation] (`Surface.ROTATION_*`,
     * normalized into 0…3), with AOSP's default mapping. The display's natural
     * orientation follows from the two: an even rotation shows it, an odd one
     * shows the other. Devices built with `config_reverseDefaultRotation` map
     * the odd rotations the other way round; the player finds that out from
     * its first lock and turns the lock with [LockedOrientation.opposite].
     */
    fun lockedOrientation(rotation: Int, isLandscape: Boolean): LockedOrientation {
        val turn = rotation.mod(4)
        val naturalIsLandscape = if (turn % 2 == 0) isLandscape else !isLandscape
        return if (naturalIsLandscape) {
            when (turn) {
                0 -> LockedOrientation.LANDSCAPE
                1 -> LockedOrientation.REVERSE_PORTRAIT
                2 -> LockedOrientation.REVERSE_LANDSCAPE
                else -> LockedOrientation.PORTRAIT
            }
        } else {
            when (turn) {
                0 -> LockedOrientation.PORTRAIT
                1 -> LockedOrientation.LANDSCAPE
                2 -> LockedOrientation.REVERSE_PORTRAIT
                else -> LockedOrientation.REVERSE_LANDSCAPE
            }
        }
    }

    /**
     * Whether the chip is offered: never on TV or Android XR (no rotation to
     * lock), in split screen, a freeform or desktop window, or Picture in
     * Picture (the system owns the orientation there), nor where Android 16
     * would ignore the request: an app targeting API 36 on a display at least
     * 600 dp wide.
     */
    fun isAvailable(
        isTelevision: Boolean,
        isXr: Boolean,
        inMultiWindow: Boolean,
        inPip: Boolean,
        sdkInt: Int,
        targetSdk: Int,
        smallestDisplayWidthDp: Int,
    ): Boolean {
        if (isTelevision || isXr || inMultiWindow || inPip) return false
        val requestsIgnored = sdkInt >= IGNORES_ORIENTATION_FROM_SDK &&
            targetSdk >= IGNORES_ORIENTATION_FROM_SDK &&
            smallestDisplayWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP
        return !requestsIgnored
    }

    /**
     * Whether the display turned half way round between two rotations. A
     * 180° turn changes no configuration, so only a display listener sees it.
     */
    fun isHalfTurn(from: Int, to: Int): Boolean = (to - from).mod(4) == 2

    /**
     * Whether an activity's requested orientation is one Rotation Lock asks
     * for. The system keeps the request when it relaunches the player for a
     * change the player doesn't handle itself (a keyboard, the font size, the
     * language), so the relaunched player reads it back and shows the chip to
     * match instead of starting unlocked while the screen stays locked.
     */
    fun isLocked(requestedOrientation: Int): Boolean =
        LockedOrientation.entries.any { it.activityInfoValue == requestedOrientation }
}
