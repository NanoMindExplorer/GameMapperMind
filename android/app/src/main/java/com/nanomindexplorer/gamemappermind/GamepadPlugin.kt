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
            // BUG FIX: Emit on BOTH "gamepadEvent" (legacy) AND "onGamepadButton" (standard)
            // so that useGamepadLoop and GamepadTester receive native Android gamepad events
            // even WITHOUT Shizuku (Android native input works without Shizuku).
            emitButtonEvent(keyCode, "PRESSED", event)
            val buttonName = mapKeyCodeToButtonName(keyCode)
            // BUG-D2 FIX: Only emit if button is recognized.
            if (buttonName != "UNKNOWN") {
                TouchInjectionPlugin.emitGamepadButton(buttonName, 1, 1.0f)
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
                TouchInjectionPlugin.emitGamepadButton(buttonName, 0, 0.0f)
            }
            return true
        }
        return false
    }

    fun handleGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        if ((event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)   // Left stick X
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y)   // Left stick Y
            // BUG-FIX: Right stick — try AXIS_RX/AXIS_RY first, fallback to AXIS_Z/AXIS_RZ.
            // Beberapa gamepad (terutama Xbox via Bluetooth) hanya emit AXIS_Z/AXIS_RZ untuk right stick.
            // Gamepad standar PS4/PS5 pakai AXIS_RX/AXIS_RY. Cek mana yang ada.
            var axisRX = event.getAxisValue(MotionEvent.AXIS_RX)
            var axisRY = event.getAxisValue(MotionEvent.AXIS_RY)
            // Jika AXIS_RX/AXIS_RY bernilai 0, coba AXIS_Z/AXIS_RZ (Xbox fallback)
            if (Math.abs(axisRX) < 0.01f && Math.abs(axisRY) < 0.01f) {
                axisRX = event.getAxisValue(MotionEvent.AXIS_Z)
                axisRY = event.getAxisValue(MotionEvent.AXIS_RZ)
            }
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            
            val l2Trigger = event.getAxisValue(MotionEvent.AXIS_BRAKE)
            val r2Trigger = event.getAxisValue(MotionEvent.AXIS_GAS)

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

            // BUG-SYNC3 FIX: Emit [LX, LY, RX, RY, L2, R2] — consistent with Shizuku path.
            val axes = floatArrayOf(axisX, axisY, axisRX, axisRY, l2Trigger, r2Trigger)
            TouchInjectionPlugin.emitGamepadAxis(axes)
            
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
    
    // BUG-D1 FIX: Cache last axis values to detect significant change.
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
