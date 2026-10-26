package com.babasama.edendale.android

import com.babasama.edendale.android.ThumbstickSeekGesture.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** HORIZON.md D.3: a sideways push and its release, with hysteresis. */
class ThumbstickSeekTest {

    @Test
    fun aPushPastTheEngageThresholdStartsASeekInItsDirection() {
        val gesture = ThumbstickSeekGesture()
        assertEquals(Change.Pushed(forward = true), gesture.update(0.7f, 0f))
        assertEquals(1, gesture.direction)

        val back = ThumbstickSeekGesture()
        assertEquals(Change.Pushed(forward = false), back.update(-0.9f, 0.1f))
        assertEquals(-1, back.direction)
    }

    @Test
    fun aLightTouchOrAVerticalPushDoesNothing() {
        val gesture = ThumbstickSeekGesture()
        assertNull(gesture.update(0.5f, 0f))
        assertNull(gesture.update(0f, 1f))
        assertNull(gesture.update(0.7f, -0.8f))
        assertNull(gesture.update(0.7f, 0.7f))
        assertEquals(0, gesture.direction)
    }

    @Test
    fun wobblingWhileHeldChangesNothing() {
        val gesture = ThumbstickSeekGesture()
        gesture.update(0.8f, 0f)
        assertNull(gesture.update(1f, 0.2f))
        assertNull(gesture.update(0.4f, 0f))
        assertNull(gesture.update(0.65f, 0.9f))
        assertEquals(1, gesture.direction)
    }

    @Test
    fun comingBackInsideTheReleaseThresholdReleasesTheSameDirection() {
        val gesture = ThumbstickSeekGesture()
        gesture.update(-0.8f, 0f)
        assertEquals(Change.Released(forward = false), gesture.update(-0.2f, 0f))
        assertEquals(0, gesture.direction)
        assertNull(gesture.update(0f, 0f))
    }

    @Test
    fun crossingToTheOtherSideReleasesFirst() {
        val gesture = ThumbstickSeekGesture()
        gesture.update(0.8f, 0f)
        assertEquals(Change.Released(forward = true), gesture.update(-0.9f, 0f))
        // The next move past the threshold starts the new direction.
        assertEquals(Change.Pushed(forward = false), gesture.update(-0.9f, 0f))
    }

    @Test
    fun resetForgetsAPush() {
        val gesture = ThumbstickSeekGesture()
        gesture.update(0.8f, 0f)
        gesture.reset()
        assertEquals(0, gesture.direction)
        assertNull(gesture.update(0f, 0f))
    }

    @Test
    fun aMostlySidewaysWheelEventStartsARunAndAnUpOrDownOneDoesNot() {
        assertEquals(1, ThumbstickSeekGesture.wheelDirection(horizontal = 0.4f, vertical = 0.1f, engaged = 0))
        assertEquals(-1, ThumbstickSeekGesture.wheelDirection(horizontal = -1f, vertical = 0f, engaged = 0))
        assertEquals(0, ThumbstickSeekGesture.wheelDirection(horizontal = 0.2f, vertical = -0.6f, engaged = 0))
        assertEquals(0, ThumbstickSeekGesture.wheelDirection(horizontal = 0f, vertical = 0f, engaged = 0))
        // The first frames of a push, and a brush, read below the engage threshold.
        assertEquals(0, ThumbstickSeekGesture.wheelDirection(horizontal = 0.05f, vertical = 0f, engaged = 0))
    }

    @Test
    fun aRunKeepsGoingWhileItLeansTheSameWayAndEndsOtherwise() {
        assertEquals(1, ThumbstickSeekGesture.wheelDirection(horizontal = 0.1f, vertical = 0.9f, engaged = 1))
        assertEquals(0, ThumbstickSeekGesture.wheelDirection(horizontal = 0f, vertical = 0.9f, engaged = 1))
        assertEquals(0, ThumbstickSeekGesture.wheelDirection(horizontal = 0.5f, vertical = 0f, engaged = -1))
    }
}
