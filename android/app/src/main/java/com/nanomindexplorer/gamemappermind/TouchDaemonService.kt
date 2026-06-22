package com.nanomindexplorer.gamemappermind

import android.app.Service
import android.content.Intent
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.Shizuku

class TouchDaemonService : ITouchService.Stub {

    constructor() : super()

    // Optionally accept Context for Shizuku v13+
    constructor(context: android.content.Context?) : super() {
        // we can store context if needed
    }

    override fun executeShellCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }
            
            val exitCode = process.waitFor()
            // We can serialize as JSON or just return output since right now TouchInjectionPlugin parses this?
            // Actually, wait, TouchInjectionPlugin expects exitCode and everything. Let's return a JSON string manually or just return the output if exitCode==0
            // Since it's a string, we can return JSON string.
            val json = org.json.JSONObject()
            json.put("output", output.toString())
            json.put("error", errorOutput.toString())
            json.put("exitCode", exitCode)
            json.toString()
        } catch (e: Exception) {
            val json = org.json.JSONObject()
            json.put("output", "")
            json.put("error", e.localizedMessage)
            json.put("exitCode", -1)
            json.toString()
        }
    }

    private var streamProcess: Process? = null
    private var streamThread: Thread? = null

    override fun executeStreamCommand(command: String, listener: ICommandOutputListener) {
        stopStreamCommand()
        streamThread = Thread {
            try {
                streamProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val reader = java.io.BufferedReader(java.io.InputStreamReader(streamProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    try {
                        listener.onOutputLine(line)
                    } catch (e: Exception) {}
                }
                val exitCode = streamProcess?.waitFor() ?: -1
                try {
                    listener.onExit(exitCode)
                } catch (e: Exception) {}
            } catch (e: Exception) {
                try {
                    listener.onOutputLine("ERROR: " + e.localizedMessage)
                    listener.onExit(-1)
                } catch (ex: Exception) {}
            }
        }
        streamThread?.start()
    }

    override fun stopStreamCommand() {
        try {
            streamProcess?.destroy()
        } catch (e: Exception) {}
        streamProcess = null
        try {
            streamThread?.interrupt()
        } catch (e: Exception) {}
        streamThread = null
    }

    override fun destroy() {
        releaseAllPointers()
        System.exit(0)
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

    private fun getCompactedIndex(targetPointerId: Int): Int {
        var compactedIdx = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) {
                if (pointers.keyAt(i) == targetPointerId) return compactedIdx
                compactedIdx++
            }
        }
        return 0 // fallback
    }

    private fun injectMotionEvent(action: Int, actionIndex: Int): Boolean {
        val downTime = baseDownTime
        val eventTime = SystemClock.uptimeMillis()

        var pointerCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) {
                pointerCount++
            }
        }
        
        if (pointerCount == 0) return false

        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        var activeIndex = 0
        for (i in 0 until pointers.size()) {
            val pointerId = pointers.keyAt(i)
            val state = pointers.valueAt(i)
            
            if (state.isDown) {
                pointerProperties[activeIndex].id = pointerId
                pointerProperties[activeIndex].toolType = MotionEvent.TOOL_TYPE_FINGER
                
                pointerCoords[activeIndex].x = state.x
                pointerCoords[activeIndex].y = state.y
                pointerCoords[activeIndex].pressure = 1.0f
                pointerCoords[activeIndex].size = 1.0f
                activeIndex++
            }
        }

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
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

        return try {
            val result = injectInputEventMethod?.invoke(inputManager, event, 0) as? Boolean ?: false
            if (!result) Log.w("GameMapper", "injectInputEvent returned false")
            result
        } catch (e: Exception) {
            Log.e("GameMapper", "Injection failed", e)
            false
        } finally {
            event.recycle()
        }
    }

    override fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        var state = pointers.get(pointerId)
        if (state == null) {
            state = PointerState()
            pointers.put(pointerId, state)
        }
        state.x = x
        state.y = y
        state.isDown = true

        var activeCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activeCount++
        }

        return if (activeCount == 1) {
            baseDownTime = SystemClock.uptimeMillis()
            injectMotionEvent(MotionEvent.ACTION_DOWN, 0)
        } else {
            val compactedIdx = getCompactedIndex(pointerId)
            val action = MotionEvent.ACTION_POINTER_DOWN or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            injectMotionEvent(action, compactedIdx)
        }
    }

    fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        val state = pointers.get(pointerId) ?: return false
        state.x = x
        state.y = y
        if (state.isDown) {
            return injectMotionEvent(MotionEvent.ACTION_MOVE, 0)
        }
        return false
    }

    fun touchUp(pointerId: Int): Boolean {
        val state = pointers.get(pointerId) ?: return false
        val compactedIdx = getCompactedIndex(pointerId)
        
        var activeCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activeCount++
        }

        val result = if (activeCount == 1) {
            val res = injectMotionEvent(MotionEvent.ACTION_UP, 0)
            pointers.clear()
            res
        } else {
            val action = MotionEvent.ACTION_POINTER_UP or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val res = injectMotionEvent(action, compactedIdx)
            pointers.remove(pointerId)
            res
        }
        
        state.isDown = false
        return result
    }

    fun releaseAllPointers(): Boolean {
        var anyReleased = false
        val keys = (0 until pointers.size()).map { pointers.keyAt(it) }
        for (pointerId in keys) {
            val state = pointers.get(pointerId)
            if (state?.isDown == true) {
                touchUp(pointerId)
                anyReleased = true
            }
        }
        pointers.clear()
        return anyReleased
    }

    private var nextTapId = 90
    fun injectTap(x: Float, y: Float): Boolean {
        val id = nextTapId
        nextTapId++
        if (nextTapId > 99) nextTapId = 90
        val downRes = touchDown(id, x, y)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            touchUp(id)
        }, 20L)
        return downRes
    }

    fun isAlive(): Boolean {
        return true
    }
}
