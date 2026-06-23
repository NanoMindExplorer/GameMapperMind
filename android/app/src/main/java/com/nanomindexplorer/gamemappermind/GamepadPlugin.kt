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
            return true
        }
        return false
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isGamepadButton(keyCode)) {
            emitButtonEvent(keyCode, "RELEASED", event)
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
