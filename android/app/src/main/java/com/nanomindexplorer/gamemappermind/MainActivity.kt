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
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_DPAD) != 0) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
