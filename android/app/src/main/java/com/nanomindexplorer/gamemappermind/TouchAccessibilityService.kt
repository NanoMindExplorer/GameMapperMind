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

        @Volatile private var macroCaptureEnabled: Boolean = false
        @Volatile private var macroCaptureStart: Long = 0L

        fun setMacroCaptureEnabled(enabled: Boolean, startTimeMs: Long) {
            macroCaptureEnabled = enabled
            macroCaptureStart = startTimeMs
            Log.d("GameMapper", "Macro capture: enabled=$enabled start=$startTimeMs")
        }
    }

    private val STROKE_DURATION = 100L
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
        if (event == null) return
        // Auto-start game detection — listen for window state changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg.isNotEmpty() && pkg != "com.nanomindexplorer.gamemappermind") {
                Log.d("GameMapper", "Foreground app changed: $pkg")
                GameMapperPlugin.emitForegroundAppChanged(pkg)
            }
        }
    }

    override fun onInterrupt() {
        // Interrupted
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.d("GameMapper", "AccessibilityService unbind")
        return super.onUnbind(intent)
    }

    // NOTE: AccessibilityService does NOT override onTouchEvent.
    // Real macro capture via MotionEvents requires either:
    //   1. A custom WindowManager overlay that captures touch events
    //   2. Using dispatchGesture with GestureResultCallback for synthetic capture
    //   3. Root access + InputManager event observation
    // For now, macro capture is UI-driven (manual coordinate input).
    // Real MotionEvent capture will be implemented in a future version via
    // an overlay-based interceptor.

    fun dispatchTouchDown(pointerId: Int, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path = Path().apply { moveTo(x, y); lineTo(x, y) }
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
                state.x = x; state.y = y; state.isDown = true
                activeStrokes[pointerId] = state
            }
        }
    }

    fun dispatchTouchMove(pointerId: Int, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = activeStrokes[pointerId] ?: return
            val path = Path().apply { moveTo(state.x, state.y); lineTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, STROKE_DURATION, true)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(stroke)
            dispatchGesture(gestureBuilder.build(), null, null)
            state.x = x; state.y = y
        }
    }

    fun dispatchTouchUp(pointerId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = activeStrokes[pointerId] ?: return
            val path = Path().apply { moveTo(state.x, state.y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 10, false)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(stroke)
            dispatchGesture(gestureBuilder.build(), null, null)
            activeStrokes.remove(pointerId)
        }
    }
}
