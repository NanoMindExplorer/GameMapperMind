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
                // DUAL-PATH FIX: When Shizuku getevent path is active, it is the SINGLE source
                // of truth for both JS events AND injection. This prevents:
                //   1. Double JS emit (UI flicker)
                //   2. Double injection call with conflicting gamepadIndex (Android path uses
                //      index=0 hardcoded, Shizuku path uses indexOf(devPath) — for multi-gamepad
                //      these don't match, causing lastState dedup to fail → double injection)
                // When Shizuku is NOT running, this Android path is the fallback for injection.
                if (!GamepadListenerService.isRunning) {
                    TouchInjectionPlugin.emitGamepadButton(buttonName, 1, 1.0f)
                    GamepadJniPlugin.handleButtonBatched(0, buttonName, true)
                }
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
                // DUAL-PATH FIX: see handleKeyDown — skip both JS emit AND injection when
                // Shizuku path is live.
                if (!GamepadListenerService.isRunning) {
                    TouchInjectionPlugin.emitGamepadButton(buttonName, 0, 0.0f)
                    GamepadJniPlugin.handleButtonBatched(0, buttonName, false)
                }
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
            // BUG-RIGHTAXIS FIX: Only fall back to AXIS_Z/AXIS_RZ if this device has EVER reported
            // a non-zero value on AXIS_RX/AXIS_RY. Previously, falling back when stick was momentarily
            // at (0, 0) caused flicker on right-stick release. Now: latch `hasReportedRXRY` per
            // device id — once we've seen RX/RY signal, we trust RX/RY as the right-stick source.
            val deviceId = event.deviceId
            if (!hasReportedRXRY.contains(deviceId)) {
                if (Math.abs(axisRX) > 0.05f || Math.abs(axisRY) > 0.05f) {
                    hasReportedRXRY.add(deviceId)
                } else {
                    // Device has never reported RX/RY signal — assume it's an Xbox-style controller
                    // that uses AXIS_Z/AXIS_RZ for right stick.
                    axisRX = event.getAxisValue(MotionEvent.AXIS_Z)
                    axisRY = event.getAxisValue(MotionEvent.AXIS_RZ)
                }
            }
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            // BUG-TRIGGER FIX: Read ALL possible trigger axis candidates and pick the max.
            // Different controllers report triggers via different axes:
            //   - AXIS_BRAKE/GAS (22/23) — most generic, Xbox Bluetooth, PS4/PS5
            //   - AXIS_LTRIGGER/RTRIGGER (17/18) — Xbox USB, some clones
            // Some controllers emit BOTH (one as 0, the other as actual value). Take max
            // to be robust against whichever axis is active.
            val l2Brake = event.getAxisValue(MotionEvent.AXIS_BRAKE)
            val r2Gas = event.getAxisValue(MotionEvent.AXIS_GAS)
            val l2Trig = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
            val r2Trig = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
            val l2Trigger = if (Math.abs(l2Brake) >= Math.abs(l2Trig)) l2Brake else l2Trig
            val r2Trigger = if (Math.abs(r2Gas) >= Math.abs(r2Trig)) r2Gas else r2Trig

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
            // DUAL-PATH FIX: When Shizuku getevent path is active, it is the single source
            // for both JS events AND injection. Skip both to prevent:
            //   1. Double JS emit (axis indicator flicker)
            //   2. Double injection with conflicting gamepadIndex (Android=0, Shizuku=indexOf)
            val axes = floatArrayOf(axisX, axisY, axisRX, axisRY, l2Trigger, r2Trigger)
            if (!GamepadListenerService.isRunning) {
                TouchInjectionPlugin.emitGamepadAxis(axes)
                GamepadJniPlugin.handleAxisBatched(0, axisX, axisY, axisRX, axisRY, l2Trigger, r2Trigger)
            }
            
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

    // BUG-RIGHTAXIS FIX: Track which device IDs have reported non-zero AXIS_RX/RY signal.
    // Once a device has reported RX/RY, we trust it as the right-stick source for that device
    // and never fall back to AXIS_Z/AXIS_RZ (prevents flicker on right-stick release).
    private val hasReportedRXRY: MutableSet<Int> = mutableSetOf()

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
