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

    /**
     * Helper function untuk mengecek apakah event berasal dari gamepad atau joystick.
     * Bitmask check: (event.source and SOURCE_GAMEPAD) == SOURCE_GAMEPAD
     * Invariant: return true hanya jika event source mengandung bit GAMEPAD atau JOYSTICK.
     * Kompleksitas: O(1) (bitwise AND operation).
     */
    private fun isFromGamepad(event: MotionEvent): Boolean {
        val source = event.source
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * Helper function untuk mengecek apakah KeyEvent berasal dari gamepad atau joystick.
     * Sama dengan isFromGamepad(MotionEvent) tetapi untuk KeyEvent.
     * Invariant: return true hanya jika event source mengandung bit GAMEPAD atau JOYSTICK.
     * Kompleksitas: O(1) (bitwise AND operation).
     */
    private fun isFromGamepad(event: KeyEvent): Boolean {
        val source = event.source
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * Override onKeyDown untuk dispatch gamepad key event ke WebView.
     *
     * Fix untuk BUG-N02 (regression dari fix BUG-H06):
     * - Sebelumnya user menghapus override ini sepenuhnya untuk menghindari double dispatch.
     * - Akibatnya GamepadTester.tsx yang menggunakan navigator.getGamepads() tidak detect gamepad.
     * - Fix: kembalikan override dengan filter source gamepad.
     * - Filter memastikan hanya event dari gamepad/joystick yang di-dispatch, bukan keyboard biasa.
     * - Tidak ada double dispatch karena Capacitor BridgeActivity tidak menangani gamepad source.
     *
     * Invariant:
     * - Jika event dari gamepad: dispatch ke webView, return true (event consumed)
     * - Jika event dari keyboard: fallback ke super.onKeyDown (Capacitor handle)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isFromGamepad(event)) {
            bridge?.webView?.let { webView ->
                webView.dispatchKeyEvent(event)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Override onKeyUp untuk dispatch gamepad key release event ke WebView.
     * Logika sama dengan onKeyDown: filter source gamepad, dispatch hanya sekali.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isFromGamepad(event)) {
            bridge?.webView?.let { webView ->
                webView.dispatchKeyEvent(event)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Override onGenericMotionEvent untuk dispatch gamepad axis (analog stick) event.
     *
     * Sebelumnya hanya call super.onGenericMotionEvent tanpa dispatch ke WebView.
     * Gamepad analog stick (SOURCE_JOYSTICK) mengirim axis value via MotionEvent,
     * bukan KeyEvent. Tanpa dispatch, navigator.getGamepads() tidak update axis value.
     *
     * Invariant:
     * - Jika event dari gamepad/joystick: dispatch ke webView, return true
     * - Jika event dari trackball/mouse: fallback ke super
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isFromGamepad(event)) {
            bridge?.webView?.let { webView ->
                webView.dispatchGenericMotionEvent(event)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
