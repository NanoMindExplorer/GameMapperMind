package com.nanomindexplorer.gamemappermind

import android.view.KeyEvent
import android.view.MotionEvent
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "GamepadPlugin")
class GamepadPlugin : Plugin() {

    companion object {
        var instance: GamepadPlugin? = null
    }

    override fun load() {
        super.load()
        instance = this
    }

    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isGamepadButton(keyCode)) {
            emitButtonEvent(keyCode, "PRESSED", event)
            val buttonName = mapKeyCodeToButtonName(keyCode)
            if (buttonName != "UNKNOWN") {
                // CRITICAL FIX: ALWAYS emit JS events + call injection from Android path.
                // Previously, an isRunning guard blocked this when Shizuku service was "running"
                // — but isRunning is true even when getevent stream FAILED to start (no gamepad
                // detected, Shizuku not ready, etc). This caused ZERO events reaching canvas
                // and ZERO injection when getevent wasn't actually streaming.
                // Now: always emit + inject. The lastState dedup in NativeGamepadMapper
                // prevents double injection when both paths are active.
                TouchInjectionPlugin.emitGamepadButton(buttonName, 1, 1.0f)
                GamepadJniPlugin.handleButtonBatched(0, buttonName, true)
            }
            return true
        }
        return false
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isGamepadButton(keyCode)) {
            emitButtonEvent(keyCode, "RELEASED", event)
            val buttonName = mapKeyCodeToButtonName(keyCode)
            if (buttonName != "UNKNOWN") {
                // CRITICAL FIX: Always emit + inject — see handleKeyDown for explanation.
                TouchInjectionPlugin.emitGamepadButton(buttonName, 0, 0.0f)
                GamepadJniPlugin.handleButtonBatched(0, buttonName, false)
            }
            return true
        }
        return false
    }

    fun handleGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        // FIX: Check ALL gamepad-related source flags, not just SOURCE_JOYSTICK.
        // Some gamepads report motion events as SOURCE_GAMEPAD (without JOYSTICK bit).
        // Previously, these events were forwarded by MainActivity but silently dropped here.
        val src = event.source
        if ((src and android.view.InputDevice.SOURCE_JOYSTICK) != 0 ||
            (src and android.view.InputDevice.SOURCE_GAMEPAD) != 0 ||
            (src and android.view.InputDevice.SOURCE_CLASS_JOYSTICK) != 0 ||
            (src and android.view.InputDevice.SOURCE_DPAD) != 0) {
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
            // FIX: Right stick — read BOTH AXIS_RX/RY AND AXIS_Z/RZ, pick whichever has
            // larger magnitude. This handles ALL controller types without needing a latch.
            val rx1 = event.getAxisValue(MotionEvent.AXIS_RX)
            val ry1 = event.getAxisValue(MotionEvent.AXIS_RY)
            val rx2 = event.getAxisValue(MotionEvent.AXIS_Z)
            val ry2 = event.getAxisValue(MotionEvent.AXIS_RZ)
            // Use whichever pair has non-zero values (some controllers report on one pair only)
            val axisRX = if (Math.abs(rx1) > 0.01f || Math.abs(ry1) > 0.01f) rx1 else rx2
            val axisRY = if (Math.abs(rx1) > 0.01f || Math.abs(ry1) > 0.01f) ry1 else ry2

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            // FIX: Trigger — read ALL possible trigger axis candidates and pick the max.
            val l2Brake = event.getAxisValue(MotionEvent.AXIS_BRAKE)
            val r2Gas = event.getAxisValue(MotionEvent.AXIS_GAS)
            val l2Trig = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
            val r2Trig = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
            val l2Trigger = Math.max(Math.abs(l2Brake), Math.abs(l2Trig))
            val r2Trigger = Math.max(Math.abs(r2Gas), Math.abs(r2Trig))

            // BUG-D1 FIX: Only emit if values changed significantly (avoid 60Hz flood when stick is idle).
            val EPSILON = 0.01f
            val changed =
                Math.abs(axisX - lastAxis[0]) > EPSILON ||
                Math.abs(axisY - lastAxis[1]) > EPSILON ||
                Math.abs(axisRX - lastAxis[2]) > EPSILON ||
                Math.abs(axisRY - lastAxis[3]) > EPSILON ||
                Math.abs(hatX - lastAxis[4]) > EPSILON ||
                Math.abs(hatY - lastAxis[5]) > EPSILON ||
                Math.abs(l2Trigger - lastAxis[6]) > EPSILON ||
                Math.abs(r2Trigger - lastAxis[7]) > EPSILON
            if (!changed) return true
            lastAxis = floatArrayOf(axisX, axisY, axisRX, axisRY, hatX, hatY, l2Trigger, r2Trigger)
            
            val ret = JSObject()
            ret.put("type", "AXIS")
            ret.put("axisX", axisX)
            ret.put("axisY", axisY)
            ret.put("axisRX", axisRX)
            ret.put("axisRY", axisRY)
            ret.put("hatX", hatX)
            ret.put("hatY", hatY)
            ret.put("timestamp", event.eventTime)
            notifyListeners("gamepadEvent", ret)

            // CRITICAL FIX: ALWAYS emit JS events + call injection from Android path.
            // The isRunning guard was blocking ALL axis events when Shizuku service was
            // "running" but getevent stream wasn't actually streaming. This caused analog
            // stick to appear dead in canvas and no stick injection.
            val axes = floatArrayOf(axisX, axisY, axisRX, axisRY, l2Trigger, r2Trigger)
            TouchInjectionPlugin.emitGamepadAxis(axes)
            GamepadJniPlugin.handleAxisBatched(0, axisX, axisY, axisRX, axisRY, l2Trigger, r2Trigger)
            
            // D-pad: emit press AND release
            // HAT axis: -1.0 = up/left, 0 = centered, 1.0 = down/right
            if (hatY < -0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 1, 1.0f)
                TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 0, 0.0f)
            } else if (hatY > 0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 1, 1.0f)
                TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 0, 0.0f)
            } else {
                // HAT centered — release all D-pad buttons
                TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 0, 0.0f)
                TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 0, 0.0f)
            }
            
            if (hatX < -0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 1, 1.0f)
                TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 0, 0.0f)
            } else if (hatX > 0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 1, 1.0f)
                TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 0, 0.0f)
            } else {
                // HAT centered — release all D-pad buttons
                TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 0, 0.0f)
                TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 0, 0.0f)
            }
            return true
        }
        return false
    }
    
    // Cache last axis values to detect significant change.
    private var lastAxis = FloatArray(8)

    private fun isGamepadButton(keyCode: Int): Boolean {
        return KeyEvent.isGamepadButton(keyCode) ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    }

    private fun mapKeyCodeToButtonName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
            KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
            KeyEvent.KEYCODE_BUTTON_L2 -> "LT"
            KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            KeyEvent.KEYCODE_BUTTON_START -> "START"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
            KeyEvent.KEYCODE_BUTTON_MODE -> "HOME"
            KeyEvent.KEYCODE_DPAD_UP -> "DPAD_UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
            // BUG-D2 FIX: Return UNKNOWN instead of "BTN_${keyCode}" to avoid polluting mapping cache.
            else -> "UNKNOWN"
        }
    }

    private fun emitButtonEvent(keyCode: Int, action: String, event: KeyEvent?) {
        val ret = JSObject()
        ret.put("type", "BUTTON")
        ret.put("action", action)
        ret.put("keyCode", keyCode)
        ret.put("buttonName", KeyEvent.keyCodeToString(keyCode))
        ret.put("timestamp", event?.eventTime ?: System.currentTimeMillis())
        notifyListeners("gamepadEvent", ret)
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val ret = JSObject()
        ret.put("active", true)
        call.resolve(ret)
    }
}
