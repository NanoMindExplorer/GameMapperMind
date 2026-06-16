package com.nanomindexplorer.gamemappermind

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class GamepadListenerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private val CHANNEL_ID = "GamepadListenerChannel"

    // Singleton instance to allow starting/stopping from plugin easily
    companion object {
        var isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("GameMapper", "GamepadListenerService: onCreate")
        createNotificationChannel()
        startForegroundService()
        setupOverlay()
        isRunning = true
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gamepad Listener Active")
            .setContentText("Listening for gamepad inputs...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Gamepad Listener", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = object : View(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || 
                    event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
                    
                    val action = if (event.action == KeyEvent.ACTION_DOWN) 1 else 0
                    // Map keycodes to simple names for JS
                    val buttonName = mapKeyCodeToName(event.keyCode)
                    TouchInjectionPlugin.emitGamepadButton(buttonName, action, 1.0f)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
                    val axisX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
                    val axisY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
                    val axisZ = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
                    val axisRZ = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ))

                    val axes = floatArrayOf(axisX, axisY, axisZ, axisRZ)
                    TouchInjectionPlugin.emitGamepadAxis(axes)
                    return true
                }
                return super.dispatchGenericMotionEvent(event)
            }
        }

        // We make it 1x1 pixel, transparent.
        // It must NOT have FLAG_NOT_FOCUSABLE, so it receives key/joystick events globally.
        // It has FLAG_NOT_TOUCH_MODAL | FLAG_NOT_TOUCHABLE so touches pass down to the game.
        // # PERLU VERIFIKASI DEVICE: Apakah pendekatan unfocusing game ini menghentikan game rendering? 
        // Sebagian besar game tetap jalan, namun beberapa bisa auto-pause.
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL 
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE 
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true
        
        windowManager.addView(overlayView, params)
        overlayView.requestFocus()
    }

    private fun mapKeyCodeToName(keyCode: Int): String {
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
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            else -> "UNKNOWN_$keyCode"
        }
    }

    private fun applyDeadzone(value: Float, deadzone: Float = 0.15f): Float {
        return if (Math.abs(value) > deadzone) value else 0f
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("GameMapper", "GamepadListenerService: onDestroy")
        isRunning = false
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
