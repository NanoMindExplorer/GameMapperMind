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
            TouchInjectionPlugin.emitGamepadButton(buttonName, 1, 1.0f)
            return true
        }
        return false
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isGamepadButton(keyCode)) {
            emitButtonEvent(keyCode, "RELEASED", event)
            val buttonName = mapKeyCodeToButtonName(keyCode)
            TouchInjectionPlugin.emitGamepadButton(buttonName, 0, 0.0f)
            return true
        }
        return false
    }

    fun handleGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        if ((event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
            val axisZ = event.getAxisValue(MotionEvent.AXIS_Z)
            val axisRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            
            // Also read trigger values (L2 = AXIS_BRAKE, R2 = AXIS_GAS)
            val l2Trigger = event.getAxisValue(MotionEvent.AXIS_BRAKE)
            val r2Trigger = event.getAxisValue(MotionEvent.AXIS_GAS)

            // Emit legacy event
            val ret = JSObject()
            ret.put("type", "AXIS")
            ret.put("axisX", axisX)
            ret.put("axisY", axisY)
            ret.put("axisZ", axisZ)
            ret.put("axisRZ", axisRZ)
            ret.put("hatX", hatX)
            ret.put("hatY", hatY)
            ret.put("timestamp", event.eventTime)
            notifyListeners("gamepadEvent", ret)

            // Emit on standard "onGamepadAxis" channel
            // Array format: [lx, ly, rx, ry, l2, r2] — match what GamepadTester expects
            // axisX/axisY = left stick, axisZ/axisRZ = right stick
            // l2Trigger/r2Trigger = L2/R2 triggers (AXIS_BRAKE/GAS)
            val axes = floatArrayOf(axisX, axisY, axisZ, axisRZ, l2Trigger, r2Trigger)
            TouchInjectionPlugin.emitGamepadAxis(axes)
            
            // Also emit D-pad as button events (HAT axis → button press)
            // This fixes "D-pad detected as R2/L2" bug
            if (hatY < -0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 1, 1.0f)
            } else if (hatY > 0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 1, 1.0f)
            }
            if (hatX < -0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 1, 1.0f)
            } else if (hatX > 0.5f) {
                TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 1, 1.0f)
            }
            return true
        }
        return false
    }

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
            else -> "BTN_${keyCode}"
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
