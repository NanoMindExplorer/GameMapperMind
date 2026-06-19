package com.nanomindexplorer.gamemappermind

import android.content.Intent
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.Shizuku

class TouchDaemonService : Shizuku.UserService() {

    private val touchStub = object : ITouchService.Stub() {
        override fun touchDown(pointerId: Int, x: Float, y: Float) {
            this@TouchDaemonService.touchDown(pointerId, x, y)
        }

        override fun touchMove(pointerId: Int, x: Float, y: Float) {
            this@TouchDaemonService.touchMove(pointerId, x, y)
        }

        override fun touchUp(pointerId: Int) {
            this@TouchDaemonService.touchUp(pointerId)
        }

        override fun injectTap(x: Float, y: Float) {
            this@TouchDaemonService.injectTap(x, y)
        }

        override fun isAlive(): Boolean {
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return touchStub
    }

    private val inputManager: InputManager? by lazy {
        try {
            InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get InputManager", e)
            null
        }
    }

    private val injectInputEventMethod by lazy {
        try {
            // mode 0 is INJECT_INPUT_EVENT_MODE_ASYNC
            InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get injectInputEvent method", e)
            null
        }
    }

    class PointerState {
        var x: Float = 0f
        var y: Float = 0f
        var isDown: Boolean = false
    }

    private val pointers = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L

    private fun injectMotionEvent(action: Int, actionIndex: Int) {
        val downTime = baseDownTime
        val eventTime = SystemClock.uptimeMillis()

        var pointerCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) {
                pointerCount++
            }
        }
        
        // If an up action caused counter to drop to 0 but we are dispatching the UP event
        if (pointerCount == 0 && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            pointerCount = 1 // Need at least 1 pointer to construct event
        }

        if (pointerCount == 0) return

        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        var activeIndex = 0
        for (i in 0 until pointers.size()) {
            val pointerId = pointers.keyAt(i)
            val state = pointers.valueAt(i)
            
            // Include pointer if it's down, or if it's the one going up/down
            val isActive = state.isDown || 
                ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP && pointerId == actionIndex) ||
                ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP)

            if (isActive) {
                pointerProperties[activeIndex].id = pointerId
                pointerProperties[activeIndex].toolType = MotionEvent.TOOL_TYPE_FINGER
                
                pointerCoords[activeIndex].x = state.x
                pointerCoords[activeIndex].y = state.y
                pointerCoords[activeIndex].pressure = 1.0f
                pointerCoords[activeIndex].size = 1.0f

                // Remap actionIndex to the compacted array index if needed
                if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN || 
                    (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
                    if (pointerId == actionIndex) {
                        // The actionIndex in the action integer needs to be the shifted index
                    }
                }
                activeIndex++
            }
        }
        
        // Find the index of the pointer triggering the action in the compacted array
        var compactedActionIndex = 0
        if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN || 
            (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
            for (i in 0 until activeIndex) {
                if (pointerProperties[i].id == actionIndex) {
                    compactedActionIndex = i
                    break
                }
            }
        }

        val finalAction = if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN || 
                              (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
            (action and MotionEvent.ACTION_MASK) or (compactedActionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            finalAction,
            activeIndex,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        try {
            injectInputEventMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            Log.e("GameMapper", "Injection failed", e)
        }
        event.recycle()
    }

    fun touchDown(pointerId: Int, x: Float, y: Float) {
        var state = pointers.get(pointerId)
        if (state == null) {
            state = PointerState()
            pointers.put(pointerId, state)
        }
        state.x = x
        state.y = y
        state.isDown = true

        var activePointersCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activePointersCount++
        }

        if (activePointersCount == 1) {
            baseDownTime = SystemClock.uptimeMillis()
            injectMotionEvent(MotionEvent.ACTION_DOWN, pointerId)
        } else {
            injectMotionEvent(MotionEvent.ACTION_POINTER_DOWN, pointerId)
        }
    }

    fun touchMove(pointerId: Int, x: Float, y: Float) {
        val state = pointers.get(pointerId) ?: return
        state.x = x
        state.y = y
        if (state.isDown) {
            injectMotionEvent(MotionEvent.ACTION_MOVE, 0)
        }
    }

    fun touchUp(pointerId: Int) {
        val state = pointers.get(pointerId) ?: return
        state.isDown = false

        var activePointersCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activePointersCount++
        }

        if (activePointersCount == 0) {
            injectMotionEvent(MotionEvent.ACTION_UP, pointerId)
            pointers.clear()
        } else {
            injectMotionEvent(MotionEvent.ACTION_POINTER_UP, pointerId)
            pointers.remove(pointerId)
        }
    }

    fun injectTap(x: Float, y: Float) {
        val id = 99 // Reserved ID for simple taps
        touchDown(id, x, y)
        Thread.sleep(20)
        touchUp(id)
    }

    fun isAlive(): Boolean {
        return true
    }
}
