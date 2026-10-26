package com.babasama.edendale.android

import android.os.Handler
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * The controllers' thumbsticks as the player's skip and speed control
 * (HORIZON.md D.3).
 *
 * Horizon OS hands a 2D panel the Touch controllers' sticks as wheel events
 * at the ray (Meta's Android input guide; Findings, 2026-10-26), which the
 * player has nothing to scroll with; a gamepad's sticks come as joystick
 * axes. A push left or
 * right that springs back skips by the App Controls lengths; a push held past
 * [HOLD_DELAY_MILLIS] plays at the hold speeds until the stick is released,
 * the same split the remote's rewind and fast-forward keys have in
 * `PlayerActivity`. Up and down, and diagonal pushes, do nothing.
 */
internal class ThumbstickSeeker(
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** The stick sprang back before the hold delay. */
        fun onSkip(forward: Boolean)

        /** The stick has been held for the delay; speed stays until [onHoldEnd]. */
        fun onHoldBegin(forward: Boolean)

        fun onHoldEnd()
    }

    private val gesture = ThumbstickSeekGesture()
    private var holdRunnable: Runnable? = null
    private var holdActive = false
    private var wheelReleaseRunnable: Runnable? = null

    /** Whether a push is in progress, so the caller keeps routing the stick here until it ends. */
    val engaged: Boolean get() = gesture.direction != 0

    /** True when [event] was a thumbstick move, which this then owns. */
    fun onGenericMotion(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) return onWheel(event)
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        // A gamepad's right stick sends RX/RY; X/Y covers the left stick and `adb shell input joystick`.
        val rightX = event.getAxisValue(MotionEvent.AXIS_RX)
        val rightY = event.getAxisValue(MotionEvent.AXIS_RY)
        val leftX = event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        val useRight = abs(rightX) + abs(rightY) >= abs(leftX) + abs(leftY)
        feed(if (useRight) rightX else leftX, if (useRight) rightY else leftY)
        return true
    }

    /**
     * The Touch controllers' sticks as Horizon OS delivers them: while the ray
     * is over the panel, a held stick arrives as a run of wheel events
     * (AXIS_HSCROLL, AXIS_VSCROLL) with no release, so the run stopping for
     * [WHEEL_GAP_MILLIS] counts as the stick springing back.
     */
    private fun onWheel(event: MotionEvent): Boolean {
        val direction = ThumbstickSeekGesture.wheelDirection(
            horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL),
            vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            engaged = gesture.direction,
        )
        if (direction == 0 && gesture.direction == 0) return false
        feed(direction.toFloat(), 0f)
        wheelReleaseRunnable?.let(handler::removeCallbacks)
        wheelReleaseRunnable = null
        if (gesture.direction != 0) {
            val runnable = Runnable {
                wheelReleaseRunnable = null
                feed(0f, 0f)
            }
            wheelReleaseRunnable = runnable
            handler.postDelayed(runnable, WHEEL_GAP_MILLIS)
        }
        return true
    }

    private fun feed(x: Float, y: Float) {
        when (val change = gesture.update(x, y)) {
            is ThumbstickSeekGesture.Change.Pushed -> schedule(change.forward)
            is ThumbstickSeekGesture.Change.Released -> release(change.forward)
            null -> Unit
        }
    }

    /** Ends a hold and forgets the push: stick events follow window focus and the ray, so without it no release comes. */
    fun cancel() {
        holdRunnable?.let(handler::removeCallbacks)
        holdRunnable = null
        wheelReleaseRunnable?.let(handler::removeCallbacks)
        wheelReleaseRunnable = null
        gesture.reset()
        if (holdActive) {
            holdActive = false
            listener.onHoldEnd()
        }
    }

    private fun schedule(forward: Boolean) {
        holdRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            holdRunnable = null
            holdActive = true
            listener.onHoldBegin(forward)
        }
        holdRunnable = runnable
        handler.postDelayed(runnable, HOLD_DELAY_MILLIS)
    }

    private fun release(forward: Boolean) {
        val pending = holdRunnable
        holdRunnable = null
        if (holdActive) {
            holdActive = false
            listener.onHoldEnd()
        } else if (pending != null) {
            handler.removeCallbacks(pending)
            listener.onSkip(forward)
        }
    }

    companion object {
        /** Matches the rewind and fast-forward keys' press-and-hold delay in `PlayerActivity`. */
        const val HOLD_DELAY_MILLIS = 400L

        /** Longer than the gap between a held stick's wheel events, short enough that a flick still skips. */
        const val WHEEL_GAP_MILLIS = 150L
    }
}

/** The push-and-release state of one stick, apart from Android so [ThumbstickSeekTest] covers it. */
internal class ThumbstickSeekGesture {
    sealed interface Change {
        data class Pushed(val forward: Boolean) : Change
        data class Released(val forward: Boolean) : Change
    }

    /** +1 while pushed forward (right), -1 while pushed back (left), 0 while centred. */
    var direction = 0
        private set

    /**
     * Feeds the stick's raw [x] and [y] in -1…1. A sideways push past
     * [ENGAGE] starts a seek; the stick is released once it comes back inside
     * [RELEASE] or crosses to the other side, so a wobble while held changes
     * nothing. A push that's more up or down than sideways is ignored.
     */
    fun update(x: Float, y: Float): Change? {
        val magnitude = abs(x)
        if (direction == 0) {
            if (magnitude < ENGAGE || magnitude <= abs(y)) return null
            direction = sign(x).toInt()
            return Change.Pushed(forward = direction > 0)
        }
        if (magnitude < RELEASE || sign(x).toInt() != direction) {
            val wasForward = direction > 0
            direction = 0
            return Change.Released(forward = wasForward)
        }
        return null
    }

    fun reset() {
        direction = 0
    }

    companion object {
        /**
         * One wheel event of a stick run as a push: +1 right, -1 left, 0 none.
         * A run starts on a mostly sideways event and keeps going while the
         * events lean the same way, however much they drift up or down;
         * Android's positive HSCROLL is to the right.
         */
        fun wheelDirection(horizontal: Float, vertical: Float, engaged: Int): Int {
            val side = sign(horizontal).toInt()
            return when {
                engaged != 0 -> if (side == engaged) engaged else 0
                abs(horizontal) >= WHEEL_ENGAGE && abs(horizontal) > abs(vertical) -> side
                else -> 0
            }
        }

        /**
         * About half a push: on the Quest 3 a full push reads about 0.3 a
         * frame, ramping up over two or three frames, so brushing the stick
         * doesn't skip.
         */
        const val WHEEL_ENGAGE = 0.15f

        /** Well past the Quest's reported dead zone (0.125), so brushing the stick doesn't seek. */
        const val ENGAGE = 0.6f

        /** Lower than [ENGAGE], so the stick must come most of the way back before it counts as released. */
        const val RELEASE = 0.3f
    }
}
