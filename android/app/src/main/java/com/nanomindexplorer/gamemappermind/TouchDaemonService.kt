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

    constructor(context: android.content.Context?) : super() {
        // BUG-INJECT-REFLECT FIX: Capture context so InputManager can be obtained via
        // the PUBLIC getSystemService API (not the hidden getInstance reflection).
        this.ctx = context
    }

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

    // BUG-INJECT-REFLECT FIX: InputManager.getInstance() reflection is BLOCKED on Android 10+
    // (and especially HarmonyOS 4.2) by the hidden API restriction list. The `getMethod("getInstance")`
    // call throws NoSuchMethodException, which `by lazy` swallows → inputManager = null → all
    // injectInputEvent calls silently return false → ZERO INJECTION.
    //
    // Fix: Use the PUBLIC API `context.getSystemService(Context.INPUT_SERVICE) as InputManager`.
    // This has been available since API 16 and returns the SAME singleton as the hidden getInstance().
    // The `injectInputEvent` method itself is still hidden (signature varies by Android version),
    // so we still need reflection for that — but we try ALL known signatures.
    //
    // Shizuku constructs the UserService with the no-arg constructor (Context is NOT passed).
    // We obtain Context via `ActivityThread.currentApplication()` (hidden API, but Shizuku runs
    // as shell uid so hidden API restrictions are bypassed). If that fails, we fall back to
    // the deprecated getInstance() reflection.
    private var ctx: android.content.Context? = null
    private val inputManager: InputManager? by lazy {
        // Path 1 (preferred): Public API via Context.getSystemService
        // Get Context via ActivityThread.currentApplication() (works in Shizuku shell-uid process)
        var context = ctx
        if (context == null) {
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentApp = atClass.getMethod("currentApplication").invoke(null)
                if (currentApp is android.content.Context) {
                    context = currentApp
                    ctx = context  // cache for future calls
                }
            } catch (e: Exception) {
                Log.w("GameMapper", "ActivityThread.currentApplication() failed: ${e.message}")
            }
        }
        try {
            val im = context?.getSystemService(android.content.Context.INPUT_SERVICE) as? InputManager
            if (im != null) {
                Log.i("GameMapper", "InputManager obtained via Context.getInputService (public API)")
                return@lazy im
            }
        } catch (e: Exception) {
            Log.w("GameMapper", "Context.getInputService failed: ${e.message}, falling back to reflection")
        }
        // Path 2 (fallback): Hidden reflection — may fail on Android 10+ but worth trying
        try {
            val im = InputManager::class.java.getMethod("getInstance").invoke(null) as? InputManager
            if (im != null) Log.i("GameMapper", "InputManager obtained via reflection (getInstance)")
            im
        } catch (e: Exception) {
            Log.e("GameMapper", "InputManager.getInstance() reflection FAILED — injection will NOT work. " +
                "This is expected on Android 10+ with hidden API restrictions. " +
                "Device may need Shizuku running as ROOT, or app must target SDK <= 28.", e)
            null
        }
    }

    private val injectInputEventMethod by lazy {
        if (inputManager == null) {
            Log.e("GameMapper", "injectInputEventMethod: inputManager is null — cannot reflect method")
            return@lazy null
        }
        // Try ALL known signatures of injectInputEvent across Android versions:
        // - API 16+:  injectInputEvent(InputEvent, int)
        // - Some OEMs: injectInputEvent(InputEvent, int, int)  (mode + flags)
        // - Huawei HarmonyOS may have a custom signature
        val signatures = listOf(
            arrayOf(android.view.InputEvent::class.java, Int::class.javaPrimitiveType) to "injectInputEvent(InputEvent, int)",
            arrayOf(android.view.InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) to "injectInputEvent(InputEvent, int, int)",
            arrayOf(android.view.InputEvent::class.java, java.lang.Integer::class.java) to "injectInputEvent(InputEvent, Integer)"
        )
        for ((paramTypes, sigName) in signatures) {
            try {
                val m = InputManager::class.java.getMethod("injectInputEvent", *paramTypes)
                m.isAccessible = true
                Log.i("GameMapper", "injectInputEvent method found: $sigName")
                return@lazy m
            } catch (e: NoSuchMethodException) {
                // try next signature
            }
        }
        Log.e("GameMapper", "injectInputEvent: NO matching signature found on this device. " +
            "Touch injection is NOT possible on this Android version / OEM.", null)
        null
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

    // BUG-INJECT-FALLBACK FIX: Track consecutive injectInputEvent failures.
    // After 3 consecutive failures, switch to `input tap` shell command fallback
    // which is slower (~100ms per tap) but works on ALL Android versions and OEMs
    // including HarmonyOS 4.2 where InputManager.injectInputEvent reflection may be blocked.
    @Volatile private var injectFailCount = 0
    @Volatile private var useShellFallback = false
    private val MAX_INJECT_FAILURES = 3

    /**
     * Fallback: use `input tap X Y` shell command to inject a tap.
     * This is the SAME mechanism Android's `input` binary uses internally — it goes
     * through IInputManager.injectInputEvent via AIDL. Works on all devices where
     * Shizuku runs as shell uid (which has INJECT_EVENTS permission).
     *
     * Limitations:
     *   - Only works for single taps (no multi-touch)
     *   - ~100ms latency per tap (process spawn overhead)
     *   - Cannot do touchMove (analog stick won't work with this fallback)
     *
     * But it GUARANTEES that button presses reach the game, which is the #1 user complaint.
     */
    private fun shellInputTap(x: Float, y: Float): Boolean {
        return try {
            val xi = x.toInt()
            val yi = y.toInt()
            val process = Runtime.getRuntime().exec(arrayOf("input", "tap", xi.toString(), yi.toString()))
            val finished = process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w("GameMapper", "shellInputTap: TIMEOUT at ($xi, $yi)")
                return false
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                Log.d("GameMapper", "shellInputTap: OK at ($xi, $yi)")
                true
            } else {
                Log.w("GameMapper", "shellInputTap: exit=$exitCode at ($xi, $yi)")
                false
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "shellInputTap failed: ${e.message}")
            false
        }
    }

    /**
     * Fallback: use `input swipe` for touchDown→Move→Up sequence.
     * Used for analog stick movement when InputManager.injectInputEvent is blocked.
     * Duration is in milliseconds.
     */
    private fun shellInputSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "input", "swipe",
                x1.toInt().toString(), y1.toInt().toString(),
                x2.toInt().toString(), y2.toInt().toString(),
                durationMs.toString()
            ))
            val finished = process.waitFor(durationMs + 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "shellInputSwipe failed: ${e.message}")
            false
        }
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
            // BUG-INJECT-FALLBACK FIX: If we've already detected that InputManager.injectInputEvent
            // is broken on this device (3+ consecutive failures), skip straight to shell fallback
            // for ACTION_DOWN/ACTION_UP (button taps). Analog stick (ACTION_MOVE) cannot use shell
            // fallback — it will silently fail, but at least button presses will work.
            if (useShellFallback && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == (MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)))) {
                val x = pointerCoords[0].x
                val y = pointerCoords[0].y
                if (action == MotionEvent.ACTION_DOWN || action == (MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))) {
                    // For DOWN, we can't do a standalone tap yet (need UP to complete).
                    // Store the coords and return true — the UP will trigger shellInputTap.
                    // Actually, simpler: just do the tap immediately for DOWN, ignore UP.
                    // This means each button press = 1 tap, which is what we want.
                    val tapResult = shellInputTap(x, y)
                    event.recycle()
                    return tapResult
                } else {
                    // ACTION_UP — already tapped on DOWN, just return true
                    event.recycle()
                    return true
                }
            }

            // BUG-INJECT-REFLECT FIX: Handle multiple injectInputEvent signatures.
            val method = injectInputEventMethod
            val im = inputManager
            if (method == null || im == null) {
                Log.e("GameMapper", "injectMotionEvent: method=$method inputManager=$im — cannot inject")
                if (!useShellFallback) {
                    injectFailCount++
                    if (injectFailCount >= MAX_INJECT_FAILURES) {
                        useShellFallback = true
                        Log.e("GameMapper", "SWITCHING TO SHELL FALLBACK — InputManager.injectInputEvent is broken on this device. " +
                            "Button taps will use `input tap` (slower but reliable). Analog stick movement will NOT work.")
                    }
                }
                return false
            }
            val result: Boolean = when (method.parameterCount) {
                2 -> method.invoke(im, event, 0) as? Boolean ?: false
                3 -> method.invoke(im, event, 0, 0) as? Boolean ?: false
                else -> method.invoke(im, event, 0) as? Boolean ?: false
            }
            if (result) {
                // Reset failure counter on success
                if (injectFailCount > 0) {
                    injectFailCount = 0
                    if (useShellFallback) {
                        useShellFallback = false
                        Log.i("GameMapper", "InputManager.injectInputEvent recovered — switching back from shell fallback")
                    }
                }
            } else {
                injectFailCount++
                Log.w("GameMapper", "injectInputEvent returned false (fail #$injectFailCount) — " +
                    "source=${currentInputSource}, action=0x${action.toString(16)}, " +
                    "coords=(${pointerCoords[0].x},${pointerCoords[0].y})")
                if (injectFailCount >= MAX_INJECT_FAILURES && !useShellFallback) {
                    useShellFallback = true
                    Log.e("GameMapper", "SWITCHING TO SHELL FALLBACK after $injectFailCount consecutive failures. " +
                        "Button taps will now use `input tap` shell command.")
                }
            }
            result
        } catch (e: Exception) {
            Log.e("GameMapper", "Injection failed: ${e.javaClass.simpleName}: ${e.message}", e)
            injectFailCount++
            if (injectFailCount >= MAX_INJECT_FAILURES && !useShellFallback) {
                useShellFallback = true
                Log.e("GameMapper", "SWITCHING TO SHELL FALLBACK after exception: ${e.javaClass.simpleName}")
            }
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
    // BUG-RACE FIX: nextTapId access must be atomic. injectTap() is called from Shizuku binder
    // thread pool — multiple binder threads can call injectTap() concurrently. Previously,
    // nextTapId was a plain var (not @Volatile, not synchronized), so two concurrent calls
    // could both read the same id value before either incremented, causing both taps to share
    // the same pointer id (one tap "ghosts" the other). Now: use AtomicInteger to guarantee
    // unique IDs across concurrent threads.
    private val nextTapId = java.util.concurrent.atomic.AtomicInteger(100)
    @Volatile private var isInitialized = true

    private fun nextTapIdAndWrap(): Int {
        // Atomically increment and wrap around 100..199 (100 slots).
        // getAndUpdate is atomic — concurrent callers always receive distinct IDs.
        return nextTapId.getAndUpdate { cur ->
            val nxt = cur + 1
            if (nxt > 199) 100 else nxt
        }
    }

    override fun injectTap(x: Float, y: Float, duration: Long): Boolean {
        val id = nextTapIdAndWrap()
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

    /**
     * Diagnostic: test injection at (x, y) and return a JSON report with:
     *   - inputManager obtained? (which path)
     *   - injectInputEventMethod found? (which signature)
     *   - injectInputEvent return value
     *   - shell fallback active?
     *   - `input tap` test result
     *
     * This lets the user verify injection works WITHOUT needing the gamepad.
     * They tap "Test Injection" in the app, and a touch appears at (x, y) on screen
     * if everything is working. The JSON report tells them exactly what's broken.
     */
    override fun testInjection(x: Float, y: Float): String {
        val report = org.json.JSONObject()
        try {
            report.put("coords", "[${x.toInt()}, ${y.toInt()}]")

            // Step 1: InputManager
            val im = inputManager
            report.put("inputManager_null", im == null)
            if (im != null) {
                report.put("inputManager_class", im.javaClass.name)
            }

            // Step 2: injectInputEvent method
            val method = injectInputEventMethod
            report.put("injectMethod_null", method == null)
            if (method != null) {
                report.put("injectMethod_signature", "parameterCount=${method.parameterCount}")
            }

            // Step 3: Try injectInputEvent directly
            report.put("useShellFallback", useShellFallback)
            report.put("injectFailCount", injectFailCount)

            // Step 4: Try a real touchDown + touchUp
            val testPointerId = 200  // use tap range to avoid gamepad pointer collision
            var downResult = false
            var upResult = false
            try {
                downResult = touchDown(testPointerId, x, y)
                Thread.sleep(50)
                upResult = touchUp(testPointerId)
            } catch (e: Exception) {
                report.put("touchException", e.message)
            }
            report.put("touchDown_result", downResult)
            report.put("touchUp_result", upResult)

            // Step 5: Test shell fallback
            var shellResult = false
            try {
                shellResult = shellInputTap(x, y)
            } catch (e: Exception) {
                report.put("shellException", e.message)
            }
            report.put("shellInputTap_result", shellResult)

            // Step 6: Recommendation
            val recommendation = when {
                im == null && shellResult -> "InputManager reflection blocked on this device. Shell fallback (input tap) WORKS. Button presses will work; analog stick movement will NOT."
                im == null && !shellResult -> "CRITICAL: Both InputManager and shell fallback failed. Shizuku may not be running as shell uid, or device is heavily restricted."
                method == null && shellResult -> "injectInputEvent method not found via reflection. Shell fallback works. Buttons OK, analog stick NO."
                method != null && downResult && shellResult -> "ALL PATHS WORK. Injection should function normally."
                method != null && !downResult && shellResult -> "InputManager.injectInputEvent returns false (likely filtered by OEM). Shell fallback works — will auto-switch after 3 failures."
                method != null && downResult && !shellResult -> "InputManager works, shell fallback failed (unusual). Will use InputManager path."
                else -> "Injection status unclear. Check logcat for details."
            }
            report.put("recommendation", recommendation)

            Log.i("GameMapper", "testInjection report: $report")
        } catch (e: Exception) {
            report.put("fatalError", e.message)
        }
        return report.toString()
    }
}
