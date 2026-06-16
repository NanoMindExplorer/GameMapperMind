package com.nanomindexplorer.gamemappermind

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

class TouchAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TouchAccessibilityService? = null
    }

    private val STROKE_DURATION = 100L // required duration for strokes
    private val activeStrokes = ConcurrentHashMap<Int, StrokePointers>()

    class StrokePointers {
        var x = 0f
        var y = 0f
        var isDown = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("GameMapper", "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        // Interrupted
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.d("GameMapper", "AccessibilityService unbind")
        return super.onUnbind(intent)
    }

    fun dispatchTouchDown(pointerId: Int, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, STROKE_DURATION, true)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(stroke)
            
            val success = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                }
            }, null)

            if (success) {
                val state = StrokePointers()
                state.x = x
                state.y = y
                state.isDown = true
                activeStrokes[pointerId] = state
            }
        }
    }

    fun dispatchTouchMove(pointerId: Int, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = activeStrokes[pointerId] ?: return
            
            val path = Path().apply {
                moveTo(state.x, state.y)
                lineTo(x, y)
            }
            // continueStroke is actually tricky and in older APIs doesn't work well due to bug 350653948.
            // But we try to simulate a continuous stroke. 
            // In a better fallback, we would construct a sequence. For AccessibilityService, 
            // real-time low-latency moving is fundamentally flawed. Which is why Shizuku is primary.
            val stroke = GestureDescription.StrokeDescription(path, 0, STROKE_DURATION, true)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(stroke)
            
            dispatchGesture(gestureBuilder.build(), null, null)
            state.x = x
            state.y = y
        }
    }

    fun dispatchTouchUp(pointerId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = activeStrokes[pointerId] ?: return
            
            val path = Path().apply {
                moveTo(state.x, state.y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 10, false)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(stroke)
            
            dispatchGesture(gestureBuilder.build(), null, null)
            activeStrokes.remove(pointerId)
        }
    }
}
