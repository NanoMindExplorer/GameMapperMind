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
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_DPAD) != 0) {
            return true
        }
        val kc = event.keyCode
        if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN ||
            kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT ||
            kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_BUTTON_B ||
            kc == KeyEvent.KEYCODE_BUTTON_X || kc == KeyEvent.KEYCODE_BUTTON_Y ||
            kc == KeyEvent.KEYCODE_BUTTON_L1 || kc == KeyEvent.KEYCODE_BUTTON_R1 ||
            kc == KeyEvent.KEYCODE_BUTTON_L2 || kc == KeyEvent.KEYCODE_BUTTON_R2 ||
            kc == KeyEvent.KEYCODE_BUTTON_THUMBL || kc == KeyEvent.KEYCODE_BUTTON_THUMBR ||
            kc == KeyEvent.KEYCODE_BUTTON_START || kc == KeyEvent.KEYCODE_BUTTON_SELECT ||
            kc == KeyEvent.KEYCODE_BUTTON_MODE) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_DPAD) != 0) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
