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
        registerPlugin(ShizukuPlugin::class.java)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.setOnCapturedPointerListener { view, event ->
                GamepadPlugin.instance?.handleGenericMotionEvent(event)
                true
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    // BUG FIX: Also override onKeyDown/onKeyUp as fallback
    // Capacitor WebView may intercept key events before dispatchKeyEvent.
    // onKeyDown/onKeyUp are called by the system if dispatchKeyEvent returns false
    // or if the WebView doesn't consume the event.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KeyEvent.isGamepadButton(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            GamepadPlugin.instance?.handleKeyDown(keyCode, event)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (KeyEvent.isGamepadButton(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            GamepadPlugin.instance?.handleKeyUp(keyCode, event)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

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
