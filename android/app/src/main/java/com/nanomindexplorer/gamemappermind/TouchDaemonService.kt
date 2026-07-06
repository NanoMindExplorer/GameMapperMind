package com.nanomindexplorer.gamemappermind

import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * REBUILT TouchDaemonService — clean multi-path touch injection for Android 12+.
 *
 * INJECTION SCHEME (tried in order, first success wins):
 *
 *   PATH A (primary): IInputManager AIDL via ServiceManager
 *     - ServiceManager.getService("input") returns the raw IBinder
 *     - IInputManager.Stub.asInterface(binder) creates the proxy
 *     - proxy.injectInputEvent(event, mode) goes directly to InputManagerService
 *     - This is the SAME path Android's `input` binary uses internally
 *     - Shizuku runs as shell uid (2000) which bypasses hidden API restrictions
 *     - Shell uid has INJECT_EVENTS permission → injection succeeds
 *
 *   PATH B (fallback): InputManager class via Context.getSystemService
 *     - context.getSystemService(INPUT_SERVICE) as InputManager (public API)
 *     - Reflection for injectInputEvent(InputEvent, int) hidden method
 *     - Works if Path A fails (e.g., ServiceManager class name change)
 *
 *   PATH C (last resort): `input tap` / `input swipe` shell commands
 *     - Runtime.exec("input tap X Y") — spawns a process per tap
 *     - ~100ms latency, single-touch only
 *     - GUARANTEED to work (same as adb shell input tap)
 *     - Auto-activates after 3 consecutive Path A+B failures
 *
 * The service tracks which path succeeded and sticks with it (no retry overhead
 * on every event). If the active path fails 3 times, switches to next path.
 */
class TouchDaemonService : ITouchService.Stub {

    constructor() : super()

    constructor(context: android.content.Context?) : super() {
        this.ctx = context
    }

    private var ctx: android.content.Context? = null

    // ========================================================================
    // SECTION 1: Shell command execution (used by all paths + diagnostics)
    // ========================================================================

    override fun executeShellCommand(command: String): String {
        // SECURITY FIX: Whitelist + metacharacter filter moved INTO the service itself.
        // Previously, whitelist only existed in TouchInjectionPlugin.executeShizukuCommand.
        // Since ITouchService is a binder running as shell uid (2000), any process that
        // can bind to it could execute arbitrary shell commands as shell user (RCE).
        // Now: the service itself enforces the whitelist, regardless of caller.
        val ALLOWED_PREFIXES = listOf("getevent -lp", "getevent -l", "dumpsys input", "pm list packages")
        val isAllowed = ALLOWED_PREFIXES.any { command.startsWith(it) }
        if (!isAllowed) {
            val json = org.json.JSONObject()
            json.put("output", "")
            json.put("error", "Command not allowed by service whitelist: $command")
            json.put("exitCode", -1)
            return json.toString()
        }
        // Reject commands with shell metacharacters (command injection prevention)
        if (Regex("[;|&\n<>`$]").containsMatchIn(command)) {
            val json = org.json.JSONObject()
            json.put("output", "")
            json.put("error", "Command contains forbidden shell metacharacters")
            json.put("exitCode", -1)
            return json.toString()
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))

            val output = StringBuilder()
            val errorOutput = StringBuilder()

            val stdoutThread = Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }
            val stderrThread = Thread {
                try {
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        errorOutput.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }
            stdoutThread.start()
            stderrThread.start()

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

    // ========================================================================
    // SECTION 2: Stream command (getevent -l)
    // ========================================================================

    @Volatile private var streamProcess: Process? = null
    @Volatile private var streamThread: Thread? = null
    private val streamLock = Any()

    override fun executeStreamCommand(command: String, listener: ICommandOutputListener) {
        synchronized(streamLock) {
            stopStreamCommandInternal()
            if (!command.startsWith("getevent -l /dev/input/event")) {
                try {
                    listener.onOutputLine("ERROR: Only 'getevent -l /dev/input/eventN' commands are allowed")
                    listener.onExit(-1)
                } catch (_: Exception) {}
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
                        } catch (_: java.io.InterruptedIOException) {
                            break
                        } catch (_: Exception) {
                            break
                        }
                        try {
                            listener.onOutputLine(line)
                        } catch (_: Exception) {
                            break
                        }
                    }
                    val exitCode = try { streamProcess?.waitFor() ?: -1 } catch (_: Exception) { -1 }
                    try { listener.onExit(exitCode) } catch (_: Exception) {}
                } catch (e: Exception) {
                    try {
                        listener.onOutputLine("ERROR: " + e.localizedMessage)
                        listener.onExit(-1)
                    } catch (_: Exception) {}
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
        try { streamProcess?.destroyForcibly() } catch (_: Exception) {}
        streamProcess = null
        try { streamThread?.interrupt() } catch (_: Exception) {}
        streamThread = null
    }

    override fun destroy() {
        isInitialized = false
        releaseAllPointers()
        stopStreamCommand()
        // Per Shizuku API: System.exit(0) is REQUIRED to terminate the user service process.
        System.exit(0)
    }

    // ========================================================================
    // SECTION 3: Injection paths — A (IInputManager AIDL), B (InputManager class)
    // ========================================================================

    /**
     * PATH A: Get IInputManager proxy via ServiceManager.getService("input").
     *
     * ServiceManager is hidden API, but Shizuku runs as shell uid which bypasses
     * the hidden API restriction. This is the most direct path to InputManagerService.
     */
    private val iInputManagerProxy: Any? by lazy {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val inputBinder = getServiceMethod.invoke(null, "input") as? IBinder
            if (inputBinder == null) {
                Log.e("GameMapper", "PATH A: ServiceManager.getService(\"input\") returned null")
                return@lazy null
            }
            // IInputManager.Stub.asInterface(binder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterfaceMethod.invoke(null, inputBinder)
            Log.i("GameMapper", "PATH A: IInputManager proxy obtained via ServiceManager AIDL")
            proxy
        } catch (e: Exception) {
            Log.e("GameMapper", "PATH A: Failed to get IInputManager via AIDL: ${e.message}", e)
            null
        }
    }

    private val pathA_injectMethod: Method? by lazy {
        val proxy = iInputManagerProxy ?: return@lazy null
        try {
            // Signature: boolean injectInputEvent(InputEvent event, int mode)
            // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            val m = proxy.javaClass.getMethod("injectInputEvent",
                android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            m.isAccessible = true
            Log.i("GameMapper", "PATH A: injectInputEvent method found on IInputManager proxy")
            m
        } catch (e: Exception) {
            Log.e("GameMapper", "PATH A: injectInputEvent method NOT found: ${e.message}")
            null
        }
    }

    /**
     * PATH B: Get InputManager via Context.getSystemService (public API), then
     * reflect for injectInputEvent. Falls back to InputManager.getInstance() if
     * context is unavailable.
     */
    private val inputManagerInstance: android.hardware.input.InputManager? by lazy {
        // Try public API first
        var context = ctx
        if (context == null) {
            // Shizuku no-arg constructor: get Application context via ActivityThread
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentApp = atClass.getMethod("currentApplication").invoke(null)
                if (currentApp is android.content.Context) {
                    context = currentApp
                    ctx = context
                }
            } catch (e: Exception) {
                Log.w("GameMapper", "PATH B: ActivityThread.currentApplication() failed: ${e.message}")
            }
        }
        try {
            val im = context?.getSystemService(android.content.Context.INPUT_SERVICE)
                as? android.hardware.input.InputManager
            if (im != null) {
                Log.i("GameMapper", "PATH B: InputManager obtained via Context.getInputService")
                return@lazy im
            }
        } catch (e: Exception) {
            Log.w("GameMapper", "PATH B: Context.getInputService failed: ${e.message}")
        }
        // Last resort: InputManager.getInstance() reflection (hidden, but shell uid bypasses)
        try {
            val im = android.hardware.input.InputManager::class.java
                .getMethod("getInstance").invoke(null) as? android.hardware.input.InputManager
            if (im != null) Log.i("GameMapper", "PATH B: InputManager obtained via getInstance() reflection")
            im
        } catch (e: Exception) {
            Log.e("GameMapper", "PATH B: All InputManager acquisition methods failed: ${e.message}")
            null
        }
    }

    private val pathB_injectMethod: Method? by lazy {
        val im = inputManagerInstance ?: return@lazy null
        try {
            val m = android.hardware.input.InputManager::class.java
                .getMethod("injectInputEvent",
                    android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            m.isAccessible = true
            Log.i("GameMapper", "PATH B: injectInputEvent method found on InputManager class")
            m
        } catch (e: Exception) {
            Log.e("GameMapper", "PATH B: injectInputEvent method NOT found: ${e.message}")
            null
        }
    }

    /**
     * PATH C: Shell fallback. `input tap` and `input swipe` use the same AIDL
     * path internally (IInputManager.injectInputEvent), so they ALWAYS work when
     * Shizuku runs as shell uid. Slower (~100ms per tap) but reliable.
     */
    private fun shellInputTap(x: Float, y: Float): Boolean {
        return try {
            val xi = x.toInt().toString()
            val yi = y.toInt().toString()
            val process = Runtime.getRuntime().exec(arrayOf("input", "tap", xi, yi))
            val finished = process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w("GameMapper", "PATH C: shellInputTap TIMEOUT at ($xi, $yi)")
                return false
            }
            val ok = process.exitValue() == 0
            if (ok) Log.d("GameMapper", "PATH C: shellInputTap OK at ($xi, $yi)")
            else Log.w("GameMapper", "PATH C: shellInputTap exit=${process.exitValue()}")
            ok
        } catch (e: Exception) {
            Log.e("GameMapper", "PATH C: shellInputTap failed: ${e.message}")
            false
        }
    }

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
            Log.e("GameMapper", "PATH C: shellInputSwipe failed: ${e.message}")
            false
        }
    }

    // ========================================================================
    // SECTION 4: Path selection + failure tracking
    // ========================================================================

    // Active injection path: "A", "B", "C", or null (not determined yet)
    @Volatile private var activePath: String? = null
    // FIX: Use AtomicInteger instead of @Volatile Int — pathFailCount++ is read-modify-write
    // and can race when called from binder thread (touchDown) + main thread (injectTap's
    // scheduled touchUp). AtomicInteger.incrementAndGet is atomic.
    private val pathFailCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val MAX_FAIL_BEFORE_SWITCH = 3
    @Volatile private var isInitialized = true

    /**
     * Try to inject a MotionEvent via the active path. If active path fails 3 times,
     * switch to next path. Path C (shell) is the terminal fallback.
     */
    private fun injectMotionEvent(action: Int, actionIndex: Int, pointerCount: Int,
                                    pointerProperties: Array<MotionEvent.PointerProperties>,
                                    pointerCoords: Array<MotionEvent.PointerCoords>): Boolean {
        val downTime = baseDownTime
        val eventTime = SystemClock.uptimeMillis()

        // BUG-CRITICAL-INJECT-1 FIX (root cause of "no touch reaches the game at all"):
        //
        // The previous code here ALWAYS shortcut every single-pointer ACTION_DOWN/UP straight
        // to Path C (`input tap`, a self-contained down+up) BEFORE ever attempting Path A/B —
        // whenever activePath was null (i.e. on literally the very first touch of the session,
        // which is the common case for every fresh daemon connection).
        //
        // `input tap` is atomic: it presses AND releases in one shell call. That is fine for a
        // discrete tap, but touchDown()/touchMove()/touchUp() are also the SAME primitives used
        // for HELD interactions — most importantly the analog sticks (player movement), but also
        // hold-type buttons, swipes, gestures and toggles. For all of those, the real ACTION_DOWN
        // must stay "down" at the OS input-dispatcher level until a later, separate ACTION_UP is
        // injected. Because the old shortcut fired a complete shell tap on the very first
        // touchDown(), the touch was released again within microseconds — before any
        // touchMove() could ever be sent. Android's InputDispatcher requires a currently "touched"
        // window/gesture-stream to accept ACTION_MOVE; once the shell tap's own release fires,
        // there is no such window anymore, so every subsequent touchMove() (i.e. every analog
        // stick movement, every held button, every swipe/gesture) was silently dropped by the
        // system. This is why moving the stick (or holding a button) had zero visible effect in
        // eFootball, even though a plain single tap sometimes appeared to register.
        //
        // FIX: Always attempt Path A, then Path B, for EVERY action (DOWN, MOVE, POINTER_DOWN/UP,
        // UP) exactly as documented in the class-level comment at the top of this file. Path C
        // (shell) is now only ever used as the genuine last resort, after Path A and B have both
        // actually been tried and failed for this specific call — never pre-emptively. This
        // matches the file's own documented "PATH A primary / PATH B fallback / PATH C last
        // resort" architecture, which the removed shortcut was silently violating.
        if (activePath == "C") {
            // Path C (shell) was already confirmed to be the only working path on this device.
            // It can still service single-pointer DOWN/UP as a best-effort discrete tap, but it
            // fundamentally cannot represent a sustained MOVE (there is no "held" state via shell).
            if (pointerCount == 1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)) {
                return handleShellInjection(action, pointerCoords, pointerCount)
            }
            return false
        }

        // Build the MotionEvent for Path A/B
        val event = MotionEvent.obtain(
            downTime, eventTime, action, actionIndex,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f, -1, 0, currentInputSource, 0
        )

        try {
            // Try active path first
            val currentPath = activePath
            if (currentPath != null && currentPath != "C") {
                val result = when (currentPath) {
                    "A" -> tryPathA(event)
                    "B" -> tryPathB(event)
                    else -> false
                }
                if (result) {
                    pathFailCount.set(0)
                    return true
                }
                val fails = pathFailCount.incrementAndGet()
                if (fails >= MAX_FAIL_BEFORE_SWITCH) {
                    Log.w("GameMapper", "Path $currentPath failed $fails times — switching")
                    switchToNextPath(currentPath)
                }
                // FALLBACK: If Path A/B failed, try shell for DOWN/UP
                if (pointerCount == 1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)) {
                    Log.w("GameMapper", "Path $currentPath failed, falling back to shell for tap")
                    return handleShellInjection(action, pointerCoords, pointerCount)
                }
                return false
            }

            // No active path yet — try A, then B, then shell
            if (tryPathA(event)) {
                activePath = "A"
                Log.i("GameMapper", "Active injection path set to A (IInputManager AIDL)")
                pathFailCount.set(0)
                return true
            }
            if (tryPathB(event)) {
                activePath = "B"
                Log.i("GameMapper", "Active injection path set to B (InputManager class)")
                pathFailCount.set(0)
                return true
            }
            // Both failed — use shell for taps
            Log.e("GameMapper", "Paths A and B both failed — using shell fallback (Path C)")
            activePath = "C"
            return handleShellInjection(action, pointerCoords, pointerCount)
        } finally {
            event.recycle()
        }
    }

    private fun tryPathA(event: MotionEvent): Boolean {
        val method = pathA_injectMethod ?: return false
        val proxy = iInputManagerProxy ?: return false
        return try {
            val result = method.invoke(proxy, event, 0) as? Boolean ?: false
            if (!result) Log.w("GameMapper", "Path A: injectInputEvent returned false")
            result
        } catch (e: Exception) {
            Log.w("GameMapper", "Path A: exception: ${e.message}")
            false
        }
    }

    private fun tryPathB(event: MotionEvent): Boolean {
        val method = pathB_injectMethod ?: return false
        val im = inputManagerInstance ?: return false
        return try {
            val result = method.invoke(im, event, 0) as? Boolean ?: false
            if (!result) Log.w("GameMapper", "Path B: injectInputEvent returned false")
            result
        } catch (e: Exception) {
            Log.w("GameMapper", "Path B: exception: ${e.message}")
            false
        }
    }

    private fun switchToNextPath(currentPath: String) {
        pathFailCount.set(0)
        activePath = when (currentPath) {
            "A" -> {
                Log.i("GameMapper", "Switching from Path A → Path B")
                "B"
            }
            "B" -> {
                Log.i("GameMapper", "Switching from Path B → Path C (shell fallback)")
                "C"
            }
            else -> "C"
        }
    }

    /**
     * Handle injection via shell commands. Only works for single-tap (DOWN+UP).
     * Multi-touch and MOVE are silently ignored (cannot be done via shell).
     */
    private fun handleShellInjection(action: Int, pointerCoords: Array<MotionEvent.PointerCoords>,
                                      pointerCount: Int): Boolean {
        val x = pointerCoords[0].x
        val y = pointerCoords[0].y
        // For shell, treat DOWN as a complete tap (DOWN + UP combined).
        // UP events are ignored (already handled by the tap).
        return when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                shellInputTap(x, y)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                true // Already tapped on DOWN
            }
            MotionEvent.ACTION_MOVE -> {
                false // Cannot do move via shell — silently fail
            }
            else -> false
        }
    }

    // ========================================================================
    // SECTION 5: Pointer state management + touch methods
    // ========================================================================

    private val pointers = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L

    class PointerState {
        var x: Float = 0f
        var y: Float = 0f
        var isDown: Boolean = false
    }

    private var currentToolType = MotionEvent.TOOL_TYPE_FINGER
    private var currentInputSource = InputDevice.SOURCE_TOUCHSCREEN
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
                else -> InputDevice.SOURCE_TOUCHSCREEN
            }
            isAntiBanEnabled = obj.optBoolean("antiBanEnabled", false)
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to parse config", e)
        }
    }

    private fun getCompactedIndex(targetPointerId: Int): Int {
        var compactedIdx = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) {
                if (pointers.keyAt(i) == targetPointerId) return compactedIdx
                compactedIdx++
            }
        }
        return -1
    }

    private fun buildAndInject(action: Int, actionIndex: Int): Boolean {
        var pointerCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) pointerCount++
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

        return injectMotionEvent(action, actionIndex, pointerCount, pointerProperties, pointerCoords)
    }

    private fun gaussianRandom(mean: Float, stdDev: Float): Float {
        var u1 = Math.random()
        if (u1 <= 0.0) u1 = Double.MIN_VALUE
        val u2 = Math.random()
        val z0 = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)
        return (z0 * stdDev + mean).toFloat()
    }

    override fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        synchronized(pointers) {
            Log.d("GameMapper", "touchDown: id=$pointerId x=$x y=$y path=${activePath ?: "?"}")
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
                buildAndInject(MotionEvent.ACTION_DOWN, 0)
            } else {
                val compactedIdx = getCompactedIndex(pointerId)
                if (compactedIdx < 0) return false
                val action = MotionEvent.ACTION_POINTER_DOWN or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                buildAndInject(action, compactedIdx)
            }
        }
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        synchronized(pointers) {
            val state = pointers.get(pointerId) ?: return false
            state.x = x
            state.y = y
            if (state.isDown) {
                return buildAndInject(MotionEvent.ACTION_MOVE, 0)
            }
            return false
        }
    }

    override fun touchUp(pointerId: Int): Boolean {
        synchronized(pointers) {
            val state = pointers.get(pointerId) ?: return false
            val compactedIdx = getCompactedIndex(pointerId)
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
                val res = buildAndInject(MotionEvent.ACTION_UP, 0)
                pointers.clear()
                res
            } else {
                val action = MotionEvent.ACTION_POINTER_UP or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                val res = buildAndInject(action, compactedIdx)
                pointers.remove(pointerId)
                state.isDown = false
                res
            }
            return result
        }
    }

    override fun releaseAllPointers(): Boolean {
        synchronized(pointers) {
            var anyReleased = false
            val activeIds = (0 until pointers.size())
                .map { pointers.keyAt(it) }
                .filter { pointers.valueAt(it).isDown }

            for (pointerId in activeIds) {
                val state = pointers.get(pointerId) ?: continue
                val compactedIdx = getCompactedIndex(pointerId)
                if (compactedIdx < 0) {
                    state.isDown = false
                    continue
                }

                var remainingActive = 0
                for (i in 0 until pointers.size()) {
                    if (pointers.valueAt(i).isDown) remainingActive++
                }

                if (remainingActive <= 1) {
                    buildAndInject(MotionEvent.ACTION_UP, 0)
                    pointers.clear()
                    anyReleased = true
                    break
                } else {
                    val action = MotionEvent.ACTION_POINTER_UP or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    buildAndInject(action, compactedIdx)
                    pointers.remove(pointerId)
                    state.isDown = false
                    anyReleased = true
                }
            }
            return anyReleased
        }
    }

    // ========================================================================
    // SECTION 6: Tap injection (atomic down + delayed up)
    // ========================================================================

    private val nextTapId = java.util.concurrent.atomic.AtomicInteger(100)

    private fun nextTapIdAndWrap(): Int {
        return nextTapId.getAndUpdate { cur ->
            val nxt = cur + 1
            if (nxt > 199) 100 else nxt
        }
    }

    override fun injectTap(x: Float, y: Float, duration: Long): Boolean {
        val id = nextTapIdAndWrap()
        val downRes = touchDown(id, x, y)
        // BUG-CRITICAL-6 FIX: Use explicit Thread instead of Handler.getMainLooper().postDelayed.
        // TouchDaemonService runs in Shizuku process — Looper.loop() may not be running,
        // so Handler.postDelayed would never execute → touchUp never called → pointer stuck.
        Thread {
            try {
                Thread.sleep(duration.coerceIn(16L, 1000L))
                if (isInitialized) touchUp(id)
            } catch (_: InterruptedException) {}
            catch (e: Exception) {
                Log.w("GameMapper", "injectTap touchUp failed: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
        return downRes
    }

    override fun isAlive(): Boolean {
        // FIX: Removed `pointers.size() >= 0` — SparseArray.size() is always >= 0,
        // so the check was meaningless (always true). Now returns actual init state.
        return isInitialized
    }

    // ========================================================================
    // SECTION 7: Diagnostic — test injection and report which path works
    // ========================================================================

    override fun testInjection(x: Float, y: Float): String {
        val report = org.json.JSONObject()
        try {
            report.put("coords", "[${x.toInt()}, ${y.toInt()}]")
            report.put("activePath", activePath ?: "undetermined")
            report.put("pathFailCount", pathFailCount.get())

            // Path A status
            val proxy = iInputManagerProxy
            report.put("pathA_proxy_null", proxy == null)
            report.put("pathA_injectMethod_null", pathA_injectMethod == null)

            // Path B status
            val im = inputManagerInstance
            report.put("pathB_inputManager_null", im == null)
            report.put("pathB_injectMethod_null", pathB_injectMethod == null)

            // Test real injection via touchDown + touchUp
            val testPointerId = 200
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

            // Test shell fallback independently
            var shellResult = false
            try {
                shellResult = shellInputTap(x, y)
            } catch (e: Exception) {
                report.put("shellException", e.message)
            }
            report.put("shellInputTap_result", shellResult)

            // Recommendation
            val recommendation = when {
                downResult && shellResult -> "ALL PATHS WORK. Injection functional. Active path: $activePath"
                downResult && !shellResult -> "InputManager injection works (path $activePath). Shell fallback failed (unusual)."
                !downResult && shellResult -> "InputManager paths (A/B) failed. Shell fallback (input tap) WORKS. App will auto-switch to Path C after 3 failures. Button presses will work; analog stick movement will NOT."
                !downResult && !shellResult -> "CRITICAL: All injection paths failed. Shizuku may not be running as shell uid, or device has additional restrictions. Check logcat for details."
                else -> "Injection status unclear. Check logcat."
            }
            report.put("recommendation", recommendation)

            Log.i("GameMapper", "testInjection report: $report")
        } catch (e: Exception) {
            report.put("fatalError", e.message)
        }
        return report.toString()
    }
}
