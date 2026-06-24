package com.nanomindexplorer.gamemappermind

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(TouchInjectionPlugin::class.java)
        registerPlugin(GamepadPlugin::class.java)
        registerPlugin(GyroPlugin::class.java)
        super.onCreate(savedInstanceState)
        
        bridge?.webView?.let { webView ->
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.requestFocus()
            webView.overScrollMode = View.OVER_SCROLL_NEVER
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false
        }
        // BUG-E1 FIX: Removed setOnCapturedPointerListener (dead code — requestPointerCapture()
        // is never called anywhere in codebase, so this listener was never triggered).
        // To re-enable: implement requestPointerCapture() for mouse-capture mode if needed.
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BUG-E2 NOTE: This intercepts gamepad events before WebView can see them, so Web Gamepad API
        // (navigator.getGamepads()) will NOT receive them. This is by design — events are routed via
        // Capacitor plugin channel (onGamepadButton/onGamepadAxis) to useGamepadLoop.
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_DPAD) != 0) {
            
            if (event.action == KeyEvent.ACTION_DOWN) {
                GamepadPlugin.instance?.handleKeyDown(event.keyCode, event)
            } else if (event.action == KeyEvent.ACTION_UP) {
                GamepadPlugin.instance?.handleKeyUp(event.keyCode, event)
            }
            return true
        }
        val kc = event.keyCode
        // forward specific button codes too
        if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN ||
            kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT ||
            kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_BUTTON_B ||
            kc == KeyEvent.KEYCODE_BUTTON_X || kc == KeyEvent.KEYCODE_BUTTON_Y ||
            kc == KeyEvent.KEYCODE_BUTTON_L1 || kc == KeyEvent.KEYCODE_BUTTON_R1 ||
            kc == KeyEvent.KEYCODE_BUTTON_L2 || kc == KeyEvent.KEYCODE_BUTTON_R2 ||
            kc == KeyEvent.KEYCODE_BUTTON_THUMBL || kc == KeyEvent.KEYCODE_BUTTON_THUMBR ||
            kc == KeyEvent.KEYCODE_BUTTON_START || kc == KeyEvent.KEYCODE_BUTTON_SELECT ||
            kc == KeyEvent.KEYCODE_BUTTON_MODE) {
            
            if (event.action == KeyEvent.ACTION_DOWN) {
                GamepadPlugin.instance?.handleKeyDown(event.keyCode, event)
            } else if (event.action == KeyEvent.ACTION_UP) {
                GamepadPlugin.instance?.handleKeyUp(event.keyCode, event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // BUG-E3 FIX: Removed onKeyDown/onKeyUp overrides — they were dead code.
    // dispatchKeyEvent always returns true for gamepad events, so onKeyDown/onKeyUp
    // are never called by the system. To re-enable as fallback, dispatchKeyEvent must
    // return false for some gamepad events first.

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_DPAD) != 0) {
            
            GamepadPlugin.instance?.handleGenericMotionEvent(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
