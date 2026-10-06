package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayKnobState
import kotlin.math.abs

/** CarProjection pad coordinates are relative focus steps, not absolute joystick positions. */
object CarLifeInputMapping {
    fun knobState(buttons: Int, x: Int, y: Int): AirPlayKnobState = AirPlayKnobState(
        select = buttons and 1 != 0,
        home = buttons and 2 != 0,
        back = buttons and 4 != 0,
        // Match the native D-pad/rotary focus path. Do not alter legacy HID X/Y semantics.
        wheel = if (abs(x.toLong()) >= abs(y.toLong())) x else y,
    )
}
