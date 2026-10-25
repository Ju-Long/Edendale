package com.babasama.edendale.android

import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/**
 * The controllers' thumbsticks as a scroll wheel under the ray (HORIZON.md B.5).
 *
 * Horizon OS hands a 2D panel both thumbsticks as one joystick's RX/RY axes
 * (Findings, 2026-10-25). Compose reads no joystick, and Android only turns
 * X/Y into D-pad keys, so the sticks did nothing. Compose's lists do scroll on
 * mouse-wheel events at the pointer, a row taking the horizontal wheel and the
 * page the vertical one, so while a stick is held this sends one wheel event a
 * frame where the ray last was.
 */
internal class ThumbstickScroller(private val view: View) : Choreographer.FrameCallback {
    private var pointerX = Float.NaN
    private var pointerY = Float.NaN
    private var stickX = 0f
    private var stickY = 0f
    private var running = false
    private var lastFrameNanos = 0L
    private var downTime = 0L

    /** Where the ray or a touch last was; the wheel goes there. */
    fun trackPointer(event: MotionEvent) {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.pointerCount > 0) {
            pointerX = event.x
            pointerY = event.y
        }
    }

    /** True when [event] was a thumbstick move, which this then handles. */
    fun onGenericMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) {
            trackPointer(event)
            return false
        }
        // The Quest sends RX/RY; X/Y covers other gamepads and `adb shell input joystick`.
        val right = stick(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        val left = stick(event, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        val (x, y) = if (hypot(right.first, right.second) >= hypot(left.first, left.second)) right else left
        // A centred stick that wasn't scrolling stays Android's (hat → D-pad).
        if (x == 0f && y == 0f && !running) return false
        stickX = x
        stickY = y
        if (!running && (x != 0f || y != 0f)) {
            running = true
            lastFrameNanos = 0L
            downTime = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallback(this)
        }
        return true
    }

    override fun doFrame(frameTimeNanos: Long) {
        // Joystick events follow window focus, so without it the release never comes.
        if ((stickX == 0f && stickY == 0f) || !view.hasWindowFocus()) {
            running = false
            stickX = 0f
            stickY = 0f
            return
        }
        if (lastFrameNanos != 0L) {
            scroll(seconds = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(MAX_FRAME_SECONDS))
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stick(event: MotionEvent, xAxis: Int, yAxis: Int): Pair<Float, Float> =
        ThumbstickScroll.deflection(event.getAxisValue(xAxis), flat(event, xAxis)) to
            ThumbstickScroll.deflection(event.getAxisValue(yAxis), flat(event, yAxis))

    private fun flat(event: MotionEvent, axis: Int): Float =
        event.device?.getMotionRange(axis, event.source)?.flat ?: 0f

    private fun scroll(seconds: Float) {
        val horizontal = abs(stickX) >= abs(stickY)
        val pixels = ThumbstickScroll.dpPerSecond(if (horizontal) stickX else stickY) *
            view.resources.displayMetrics.density * seconds
        val config = ViewConfiguration.get(view.context)
        val coords = MotionEvent.PointerCoords().apply {
            x = if (pointerX.isNaN()) view.width / 2f else pointerX
            y = if (pointerY.isNaN()) view.height / 2f else pointerY
            // Android's wheel axes: positive HSCROLL scrolls right, positive VSCROLL scrolls up.
            if (horizontal) {
                setAxisValue(MotionEvent.AXIS_HSCROLL, pixels / config.scaledHorizontalScrollFactor)
            } else {
                setAxisValue(MotionEvent.AXIS_VSCROLL, -pixels / config.scaledVerticalScrollFactor)
            }
        }
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val wheel = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL,
            1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        view.dispatchGenericMotionEvent(wheel)
        wheel.recycle()
    }

    private companion object {
        /** A long frame (a hitch, the first frame back) doesn't turn into a jump. */
        const val MAX_FRAME_SECONDS = 0.05f
    }
}

/** The stick-to-speed curve, apart from Android so [ThumbstickScrollTest] covers it. */
internal object ThumbstickScroll {
    /** Full deflection; about eight poster cards a second. */
    const val MAX_DP_PER_SECOND = 1400f

    /** At least this much of the travel is dead zone, whatever the device reports. */
    const val MIN_DEAD_ZONE = 0.15f

    /** [raw] in -1…1 with the dead zone cut out and the rest stretched back to -1…1. */
    fun deflection(raw: Float, flat: Float): Float {
        val deadZone = max(flat, MIN_DEAD_ZONE)
        val magnitude = abs(raw)
        if (magnitude <= deadZone) return 0f
        return sign(raw) * ((magnitude - deadZone) / (1f - deadZone)).coerceAtMost(1f)
    }

    /** Signed speed for a [deflection]; squared, so a slight tilt scrolls slowly enough to aim. */
    fun dpPerSecond(deflection: Float): Float = sign(deflection) * deflection * deflection * MAX_DP_PER_SECOND
}
