package com.babasama.edendale.android.player

import android.content.pm.ActivityInfo
import android.view.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rotation Lock in the handheld player (owner request 10). */
class OrientationLockTest {

    @Test
    fun theValuesAreAndroidsOwn() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, LockedOrientation.LANDSCAPE.activityInfoValue)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, LockedOrientation.PORTRAIT.activityInfoValue)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, LockedOrientation.REVERSE_LANDSCAPE.activityInfoValue)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, LockedOrientation.REVERSE_PORTRAIT.activityInfoValue)
        // Unlocking goes back to the manifest's sensorLandscape, never to "unspecified".
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationLockRules.UNLOCKED)
        assertEquals(listOf(0, 1, 8, 9, 6), LockedOrientation.entries.map { it.activityInfoValue } + OrientationLockRules.UNLOCKED)
    }

    @Test
    fun aNaturallyPortraitPhoneMapsLikeAosp() {
        assertEquals(LockedOrientation.PORTRAIT, OrientationLockRules.lockedOrientation(Surface.ROTATION_0, isLandscape = false))
        assertEquals(LockedOrientation.LANDSCAPE, OrientationLockRules.lockedOrientation(Surface.ROTATION_90, isLandscape = true))
        assertEquals(LockedOrientation.REVERSE_PORTRAIT, OrientationLockRules.lockedOrientation(Surface.ROTATION_180, isLandscape = false))
        assertEquals(LockedOrientation.REVERSE_LANDSCAPE, OrientationLockRules.lockedOrientation(Surface.ROTATION_270, isLandscape = true))
    }

    @Test
    fun aNaturallyLandscapeTabletMapsLikeAosp() {
        assertEquals(LockedOrientation.LANDSCAPE, OrientationLockRules.lockedOrientation(Surface.ROTATION_0, isLandscape = true))
        assertEquals(LockedOrientation.REVERSE_PORTRAIT, OrientationLockRules.lockedOrientation(Surface.ROTATION_90, isLandscape = false))
        assertEquals(LockedOrientation.REVERSE_LANDSCAPE, OrientationLockRules.lockedOrientation(Surface.ROTATION_180, isLandscape = true))
        assertEquals(LockedOrientation.PORTRAIT, OrientationLockRules.lockedOrientation(Surface.ROTATION_270, isLandscape = false))
    }

    @Test
    fun theLockAlwaysKeepsTheOrientationOnScreen() {
        for (rotation in 0..3) {
            for (isLandscape in listOf(true, false)) {
                val locked = OrientationLockRules.lockedOrientation(rotation, isLandscape)
                val lockedIsLandscape = locked == LockedOrientation.LANDSCAPE || locked == LockedOrientation.REVERSE_LANDSCAPE
                assertEquals(isLandscape, lockedIsLandscape, "rotation $rotation")
            }
        }
    }

    @Test
    fun rotationsOutsideZeroToThreeWrapAround() {
        assertEquals(
            OrientationLockRules.lockedOrientation(3, isLandscape = true),
            OrientationLockRules.lockedOrientation(-1, isLandscape = true),
        )
        assertEquals(
            OrientationLockRules.lockedOrientation(1, isLandscape = true),
            OrientationLockRules.lockedOrientation(5, isLandscape = true),
        )
        assertEquals(
            OrientationLockRules.lockedOrientation(2, isLandscape = false),
            OrientationLockRules.lockedOrientation(-6, isLandscape = false),
        )
    }

    @Test
    fun onlyTheLocksOwnRequestsReadAsLocked() {
        // A relaunched player reads the request the system kept and shows the chip to match.
        LockedOrientation.entries.forEach { assertTrue(OrientationLockRules.isLocked(it.activityInfoValue), "$it") }
        assertFalse(OrientationLockRules.isLocked(OrientationLockRules.UNLOCKED))
        assertFalse(OrientationLockRules.isLocked(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
        assertFalse(OrientationLockRules.isLocked(ActivityInfo.SCREEN_ORIENTATION_LOCKED))
        assertFalse(OrientationLockRules.isLocked(ActivityInfo.SCREEN_ORIENTATION_SENSOR))
        assertFalse(OrientationLockRules.isLocked(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE))
    }

    @Test
    fun oppositeIsAHalfTurnAndUndoesItself() {
        assertEquals(LockedOrientation.REVERSE_LANDSCAPE, LockedOrientation.LANDSCAPE.opposite())
        assertEquals(LockedOrientation.REVERSE_PORTRAIT, LockedOrientation.PORTRAIT.opposite())
        LockedOrientation.entries.forEach {
            assertEquals(it, it.opposite().opposite())
            assertTrue(it != it.opposite())
        }
    }

    @Test
    fun halfTurnsAreTwoStepsEitherWay() {
        assertTrue(OrientationLockRules.isHalfTurn(1, 3))
        assertTrue(OrientationLockRules.isHalfTurn(3, 1))
        assertTrue(OrientationLockRules.isHalfTurn(0, 2))
        assertTrue(OrientationLockRules.isHalfTurn(2, 0))
        assertTrue(OrientationLockRules.isHalfTurn(3, 5))
        assertFalse(OrientationLockRules.isHalfTurn(1, 1))
        assertFalse(OrientationLockRules.isHalfTurn(1, 2))
        assertFalse(OrientationLockRules.isHalfTurn(0, 3))
    }

    private fun available(
        isTelevision: Boolean = false,
        isXr: Boolean = false,
        inMultiWindow: Boolean = false,
        inPip: Boolean = false,
        sdkInt: Int = 35,
        targetSdk: Int = 36,
        smallestDisplayWidthDp: Int = 411,
    ) = OrientationLockRules.isAvailable(isTelevision, isXr, inMultiWindow, inPip, sdkInt, targetSdk, smallestDisplayWidthDp)

    @Test
    fun theChipIsOfferedOnlyWhereTheLockCanHold() {
        assertTrue(available())
        assertTrue(available(sdkInt = 36))
        assertTrue(available(sdkInt = 26, targetSdk = 36))
        assertFalse(available(isTelevision = true))
        assertFalse(available(isXr = true))
        assertFalse(available(inMultiWindow = true))
        assertFalse(available(inPip = true))
        // Android 16 ignores the request from apps targeting it on large screens.
        assertFalse(available(sdkInt = 36, smallestDisplayWidthDp = 600))
        assertFalse(available(sdkInt = 37, smallestDisplayWidthDp = 840))
        assertTrue(available(sdkInt = 36, smallestDisplayWidthDp = 599))
        assertTrue(available(sdkInt = 35, smallestDisplayWidthDp = 800))
        assertTrue(available(sdkInt = 36, targetSdk = 35, smallestDisplayWidthDp = 800))
    }
}
