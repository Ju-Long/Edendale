package com.babasama.edendale.android

import kotlin.test.Test
import kotlin.test.assertEquals

/** HORIZON.md B.5: the thumbstick's dead zone and speed curve. */
class ThumbstickScrollTest {

    @Test
    fun aRestingStickInsideTheDeadZoneDoesNotScroll() {
        assertEquals(0f, ThumbstickScroll.deflection(0.1f, flat = 0.125f))
        assertEquals(0f, ThumbstickScroll.deflection(-0.15f, flat = 0f))
    }

    @Test
    fun theDeviceFlatWidensTheDeadZone() {
        assertEquals(0f, ThumbstickScroll.deflection(0.2f, flat = 0.25f))
    }

    @Test
    fun deflectionPastTheDeadZoneStretchesBackToFullRange() {
        assertEquals(1f, ThumbstickScroll.deflection(1f, flat = 0.125f))
        assertEquals(-1f, ThumbstickScroll.deflection(-1f, flat = 0.125f))
        assertEquals(0.5f, ThumbstickScroll.deflection(0.575f, flat = 0.15f), 1e-5f)
    }

    @Test
    fun speedIsSquaredAndKeepsTheDirection() {
        assertEquals(ThumbstickScroll.MAX_DP_PER_SECOND, ThumbstickScroll.dpPerSecond(1f))
        assertEquals(-ThumbstickScroll.MAX_DP_PER_SECOND / 4, ThumbstickScroll.dpPerSecond(-0.5f))
        assertEquals(0f, ThumbstickScroll.dpPerSecond(0f))
    }
}
