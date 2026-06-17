package com.nanomindexplorer.gamemappermind

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.getcapacitor.BridgeActivity
import com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Register BOTH plugins during transition period
        // Old plugin (TouchInjectionPlugin) keeps existing JS code working
        // New plugin (GameMapperPlugin) is the rewritten version per contract
        registerPlugin(TouchInjectionPlugin::class.java)
        registerPlugin(GameMapperPlugin::class.java)
        super.onCreate(savedInstanceState)
        
        // Aktifkan Gamepad API & properti WebView
        bridge?.webView?.let { webView ->
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.requestFocus()
            
            // Nonaktifkan scroll
            webView.overScrollMode = View.OVER_SCROLL_NEVER
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            bridge?.webView?.let {
                it.dispatchKeyEvent(event)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            bridge?.webView?.let {
                it.dispatchGenericMotionEvent(event)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
