package com.babasama.edendale.android.theater

import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType

/**
 * The controllers' Back in the theater (HORIZON.md G.6), polled once a frame
 * as Meta's samples do: a press that began this frame has its bit set in
 * both `changedButtons` and `buttonState`. B or Y on a controller calls
 * [onBack] (the theater exits, as Back closes the 2D player). A hand's pinch
 * arrives on the A and Y bits, so hands never trigger Back. The dock no
 * longer needs summoning (G.15: it follows the head and never hides), so
 * the trigger and a pinch only press what the ray points at.
 */
class TheaterInputSystem(private val onBack: () -> Unit) : SystemBase() {

    override fun execute() {
        var back = false
        for (entity in Query.where { has(Controller.id) }.eval()) {
            if (!entity.isLocal()) continue
            val controller = entity.getComponent<Controller>()
            if (!controller.isActive || controller.type == ControllerType.HAND) continue
            val pressed = controller.changedButtons and controller.buttonState
            if (pressed and backBits != 0) back = true
        }
        if (back) onBack()
    }

    private val backBits = ButtonBits.ButtonB or ButtonBits.ButtonY
}
