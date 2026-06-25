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

    constructor(context: android.content.Context?) : super()

    override fun executeShellCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            
            // BUG-A3 FIX: Read output with timeout. Use background threads to avoid blocking forever.
            val stdoutThread = Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                } catch (e: Exception) {}
            }
            val stderrThread = Thread {
                try {
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        errorOutput.append(line).append("\n")
                    }
                } catch (e: Exception) {}
            }
            stdoutThread.start()
            stderrThread.start()
            
            // BUG-A3 FIX: Wait with 15s timeout
            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                val json = org.json.JSONObject()
                json.put("output", output.toString())
                json.put("error", errorOutput.toString() + "\n[TIMEOUT: command did not finish in 15s]")
                json.put("exitCode", -1)
                return json.toString()
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)
            
            val exitCode = process.exitValue()
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

    @Volatile private var streamProcess: Process? = null
    @Volatile private var streamThread: Thread? = null
    private val streamLock = Any()

    override fun executeStreamCommand(command: String, listener: ICommandOutputListener) {
        synchronized(streamLock) {
            stopStreamCommandInternal()
            // BUG-SEC2 FIX: Validate command starts with getevent -l /dev/input/event
            if (!command.startsWith("getevent -l /dev/input/event")) {
                try {
                    listener.onOutputLine("ERROR: Only 'getevent -l /dev/input/eventN' commands are allowed")
                    listener.onExit(-1)
                } catch (e: Exception) {}
                return
            }
            streamThread = Thread {
                try {
                    val cmdArray = command.split(" ").toTypedArray()
                    streamProcess = Runtime.getRuntime().exec(cmdArray)
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(streamProcess!!.inputStream))
                    var line: String?
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            line = reader.readLine() ?: break
                        } catch (e: java.io.InterruptedIOException) {
                            break
                        } catch (e: Exception) {
                            break
                        }
                        try {
                            listener.onOutputLine(line)
                        } catch (e: Exception) {
                            break
                        }
                    }
                    val exitCode = try { streamProcess?.waitFor() ?: -1 } catch (e: Exception) { -1 }
                    try {
                        listener.onExit(exitCode)
                    } catch (e: Exception) {}
                } catch (e: Exception) {
                    try {
                        listener.onOutputLine("ERROR: " + e.localizedMessage)
                        listener.onExit(-1)
                    } catch (ex: Exception) {}
                }
            }.also { it.isDaemon = true }
            streamThread?.start()
        }
    }

    override fun stopStreamCommand() {
        synchronized(streamLock) {
            stopStreamCommandInternal()
        }
    }
    
    private fun stopStreamCommandInternal() {
        try { streamProcess?.destroyForcibly() } catch (e: Exception) {}
        streamProcess = null
        try { streamThread?.interrupt() } catch (e: Exception) {}
        streamThread = null
    }

    override fun destroy() {
        isInitialized = false
        releaseAllPointers()
        stopStreamCommand()
        // Per Shizuku API documentation:
        // "The transaction code for that method is 16777115 (use 16777114 in aidl).
        //  In this method, you can do some cleanup jobs and call System.exit() in the end."
        // System.exit(0) is REQUIRED by Shizuku to properly terminate the user service process.
        // The issue was NOT this method — it was handleOnDestroy() calling destroy() at the wrong time.
        // handleOnDestroy() is now fixed to NOT call destroy(). Only explicit unbindService() calls destroy().
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
        // BUG-M1 FIX: Return -1 if target pointer not found in active set (was returning 0).
        // Returning 0 caused the wrong pointer ID to be encoded in MotionEvent action,
        // leading to touch events being assigned to pointer 0 instead of the actual target.
        var compactedIdx = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) {
                if (pointers.keyAt(i) == targetPointerId) return compactedIdx
                compactedIdx++
            }
        }
        return -1
    }

    private var currentToolType = MotionEvent.TOOL_TYPE_FINGER
    private var currentInputSource = InputDevice.SOURCE_TOUCHSCREEN  // Default: TOUCHSCREEN for game compatibility
    private var isAntiBanEnabled = false

    override fun updateConfig(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val buttons = obj.optJSONArray("buttons")
            val firstBtn = if (buttons != null && buttons.length() > 0) buttons.optJSONObject(0) else null
            
            val tt = obj.optString("toolType", firstBtn?.optString("toolType", "FINGER") ?: "FINGER")
            currentToolType = if (tt == "STYLUS") MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            
            val `is` = obj.optString("inputSource", firstBtn?.optString("inputSource", "TOUCHSCREEN") ?: "TOUCHSCREEN")
            currentInputSource = when (`is`) {
                "MOUSE" -> 8194
                "STYLUS" -> InputDevice.SOURCE_STYLUS
                "GAMEPAD" -> InputDevice.SOURCE_GAMEPAD
                else -> InputDevice.SOURCE_TOUCHSCREEN  // Default for game compatibility
            }
            isAntiBanEnabled = obj.optBoolean("antiBanEnabled", false)
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to parse config", e)
        }
    }

    private fun gaussianRandom(mean: Float, stdDev: Float): Float {
        // BUG-M5 FIX: Clamp u1 to (0, 1] to avoid Math.log(0) = -Infinity → z0 = NaN.
        // Math.random() can theoretically return 0.0 (though very rare).
        var u1 = Math.random()
        if (u1 <= 0.0) u1 = Double.MIN_VALUE
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
                    nx += ((Math.random() * 2) - 1).toFloat()
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
            downTime, eventTime, action, activeIndex,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f, 0, 0, currentInputSource, 0
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
            Log.d("GameMapper", "touchDown: id=$pointerId x=$x y=$y")
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
                // BUG-M1 FIX: Guard against -1 (target not found — shouldn't happen but defensive).
                if (compactedIdx < 0) return false
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
            // BUG-M1 FIX: If pointer not in active set, just clean up state without injecting event.
            if (compactedIdx < 0) {
                state.isDown = false
                pointers.remove(pointerId)
                return false
            }

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
                // BUG-A5 FIX: Only set state.isDown=false in the multi-pointer branch where pointers map still holds it.
                state.isDown = false
                res
            }
            // Note: in the single-pointer branch, pointers.clear() already removed state from the map,
            // so setting state.isDown=false there was redundant.
            return result
        }
    }

    override fun releaseAllPointers(): Boolean {
        synchronized(pointers) {
            var anyReleased = false
            // BUG-M2 FIX: Snapshot the active pointer IDs FIRST, then release them one by one.
            // Previously, the loop recomputed activeCount and compactedIdx on a mutating set,
            // causing indices to shift and the wrong pointer to receive ACTION_UP.
            val activeIds = (0 until pointers.size())
                .map { pointers.keyAt(it) }
                .filter { pointers.valueAt(it).isDown }

            for (pointerId in activeIds) {
                val state = pointers.get(pointerId) ?: continue
                val compactedIdx = getCompactedIndex(pointerId)
                if (compactedIdx < 0) {
                    // Already released by a prior iteration's clear(); skip.
                    state.isDown = false
                    continue
                }

                // Count remaining active pointers BEFORE this release.
                var remainingActive = 0
                for (i in 0 until pointers.size()) {
                    if (pointers.valueAt(i).isDown) remainingActive++
                }

                if (remainingActive <= 1) {
                    // Last pointer — send ACTION_UP and clear all.
                    injectMotionEvent(MotionEvent.ACTION_UP, 0)
                    pointers.clear()
                    anyReleased = true
                    break
                } else {
                    val action = MotionEvent.ACTION_POINTER_UP or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    injectMotionEvent(action, compactedIdx)
                    pointers.remove(pointerId)
                    state.isDown = false
                    anyReleased = true
                }
            }
            // BUG-M11 FIX: Only clear if we actually released something. Don't blindly clear()
            // at the end — that would remove non-active pointers too (which shouldn't exist,
            // but defensive).
            return anyReleased
        }
    }

    // BUG-A1/M6 FIX: Use range 100-199 (100 slots) to avoid collision with gamepad pointers (0-63)
    // AND provide enough slots for concurrent taps (was 100-109, only 10 slots — risky if user
    // rapidly taps 10+ times within 60ms each).
    private var nextTapId = 100
    @Volatile private var isInitialized = true

    override fun injectTap(x: Float, y: Float, duration: Long): Boolean {
        val id = nextTapId
        nextTapId++
        if (nextTapId > 199) nextTapId = 100
        val downRes = touchDown(id, x, y)
        // BUG-A2 FIX: Wrap touchUp in try-catch; check service is still alive before calling.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (isInitialized) {
                    touchUp(id)
                }
            } catch (e: Exception) {
                Log.w("GameMapper", "injectTap touchUp failed: ${e.message}")
            }
        }, duration)
        return downRes
    }

    // BUG-AIDL1 FIX: isAlive() now reflects actual initialization state.
    override fun isAlive(): Boolean {
        return isInitialized && pointers.size() >= 0
    }
}
