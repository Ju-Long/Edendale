package com.babasama.edendale.android.theater

import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType

/**
 * The controllers' and hands' buttons in the theater (HORIZON.md G.6, G.8),
 * polled once a frame as Meta's samples do: a press that began this frame
 * has its bit set in both `changedButtons` and `buttonState`.
 *
 * A trigger, A, or X, or a hand's pinch, calls [onActivate] (the controls
 * panel shows). B or Y on a controller calls [onBack] (the theater exits,
 * as Back closes the 2D player). A hand's pinch arrives on the A and Y bits,
 * so hands never trigger Back. Presses on the panels themselves reach the
 * panels through the SDK's own input and also pass through here, which only
 * keeps the controls up longer.
 */
class TheaterInputSystem(
    private val onActivate: () -> Unit,
    private val onBack: () -> Unit,
) : SystemBase() {

    override fun execute() {
        var activate = false
        var back = false
        for (entity in Query.where { has(Controller.id) }.eval()) {
            if (!entity.isLocal()) continue
            val controller = entity.getComponent<Controller>()
            if (!controller.isActive) continue
            val pressed = controller.changedButtons and controller.buttonState
            if (controller.type == ControllerType.HAND) {
                if (pressed and pinchBits != 0) activate = true
            } else {
                if (pressed and backBits != 0) back = true
                if (pressed and activateBits != 0) activate = true
            }
        }
        when {
            back -> onBack()
            activate -> onActivate()
        }
    }

    private val pinchBits = ButtonBits.ButtonA or ButtonBits.ButtonY
    private val backBits = ButtonBits.ButtonB or ButtonBits.ButtonY
    private val activateBits = ButtonBits.ButtonA or ButtonBits.ButtonX or ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
}
