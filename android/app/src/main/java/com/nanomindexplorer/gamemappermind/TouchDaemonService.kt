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
            
            val output = java.lang.StringBuilder()
            val errorOutput = java.lang.StringBuilder()
            
            val timeoutMs = 5000L
            val startTime = System.currentTimeMillis()
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (output.length < 65536) output.append(line).append("\n")
                if (System.currentTimeMillis() - startTime > timeoutMs) break
            }
            while (errorReader.readLine().also { line = it } != null) {
                if (errorOutput.length < 65536) errorOutput.append(line).append("\n")
                if (System.currentTimeMillis() - startTime > timeoutMs) break
            }
            
            // Timeout support via modern Android API using generic wait/interrupt
            var exitCode = -1
            val thread = Thread {
                try { exitCode = process.waitFor() } catch (ignored: Exception) {}
            }
            thread.start()
            thread.join(timeoutMs)
            if (thread.isAlive) {
                process.destroy()
                thread.interrupt()
            }
            
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
                // If it's a getevent command, execute it directly avoiding 'sh -c' pipe buffering
                val cmdArray = if (command.startsWith("getevent")) {
                    command.split(" ").toTypedArray()
                } else {
                    arrayOf("sh", "-c", command)
                }
                streamProcess = Runtime.getRuntime().exec(cmdArray)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(streamProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    try {
                        listener.onOutputLine(line)
                    } catch (e: Exception) {
                        break
                    }
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

    private var currentToolType = MotionEvent.TOOL_TYPE_FINGER
    private var currentInputSource = 8194 // Default to InputDevice.SOURCE_MOUSE
    private var isAntiBanEnabled = false

    override fun updateConfig(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val buttons = obj.optJSONArray("buttons")
            val firstBtn = if (buttons != null && buttons.length() > 0) buttons.optJSONObject(0) else null
            
            val tt = obj.optString("toolType", firstBtn?.optString("toolType", "FINGER") ?: "FINGER")
            currentToolType = if (tt == "STYLUS") MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            
            val `is` = obj.optString("inputSource", firstBtn?.optString("inputSource", "MOUSE") ?: "MOUSE")
            currentInputSource = when (`is`) {
                "TOUCHSCREEN" -> InputDevice.SOURCE_TOUCHSCREEN
                "STYLUS" -> InputDevice.SOURCE_STYLUS
                "GAMEPAD" -> InputDevice.SOURCE_GAMEPAD
                else -> 8194 // InputDevice.SOURCE_MOUSE
            }
            isAntiBanEnabled = obj.optBoolean("antiBanEnabled", false)
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to parse config", e)
        }
    }

    private fun gaussianRandom(mean: Float, stdDev: Float): Float {
        val u1 = Math.random()
        val u2 = Math.random()
        val z0 = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)
        return (z0 * stdDev + mean).toFloat()
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
                pointerProperties[activeIndex].toolType = currentToolType
                
                var nx = state.x
                var ny = state.y
                var press = 1.0f
                var sz = 1.0f

                if (isAntiBanEnabled) {
                    nx += ((Math.random() * 2) - 1).toFloat() // +- 1px jitter
                    ny += ((Math.random() * 2) - 1).toFloat() 
                    press = gaussianRandom(0.92f, 0.04f).coerceIn(0.85f, 1.0f)
                    sz = gaussianRandom(1.0f, 0.05f).coerceIn(0.9f, 1.1f)
                }

                pointerCoords[activeIndex].x = nx
                pointerCoords[activeIndex].y = ny
                pointerCoords[activeIndex].pressure = press
                pointerCoords[activeIndex].size = sz
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
            currentInputSource,
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
        synchronized(pointers) {
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
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        synchronized(pointers) {
            val state = pointers.get(pointerId) ?: return false
            state.x = x
            state.y = y
            if (state.isDown) {
                return injectMotionEvent(MotionEvent.ACTION_MOVE, 0)
            }
            return false
        }
    }

    override fun touchUp(pointerId: Int): Boolean {
        synchronized(pointers) {
            val state = pointers.get(pointerId) ?: return false
            val compactedIdx = getCompactedIndex(pointerId)
            
            var activeCount = 0
            for (i in 0 until pointers.size()) {
                if (pointers.valueAt(i).isDown) activeCount++
            }

            val result = if (activeCount <= 1) {
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
    }

    override fun releaseAllPointers(): Boolean {
        synchronized(pointers) {
            var anyReleased = false
            val keys = (0 until pointers.size()).map { pointers.keyAt(it) }
            for (pointerId in keys) {
                val state = pointers.get(pointerId)
                if (state?.isDown == true) {
                    val compactedIdx = getCompactedIndex(pointerId)
                    var activeCount = 0
                    for (i in 0 until pointers.size()) {
                        if (pointers.valueAt(i).isDown) activeCount++
                    }
                    if (activeCount <= 1) {
                        injectMotionEvent(MotionEvent.ACTION_UP, 0)
                        pointers.clear()
                    } else {
                        val action = MotionEvent.ACTION_POINTER_UP or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        injectMotionEvent(action, compactedIdx)
                        pointers.remove(pointerId)
                    }
                    state.isDown = false
                    anyReleased = true
                }
            }
            pointers.clear()
            return anyReleased
        }
    }

    private var nextTapId = 90
    override fun injectTap(x: Float, y: Float, duration: Long): Boolean {
        val id = nextTapId
        nextTapId++
        if (nextTapId > 99) nextTapId = 90
        val downRes = touchDown(id, x, y)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            touchUp(id)
        }, duration)
        return downRes
    }

    override fun isAlive(): Boolean {
        return true
    }
}
