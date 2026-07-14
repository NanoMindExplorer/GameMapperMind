package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "GameMapper"

class TouchDaemonService : ITouchService.Stub {

    private var ctx: Context? = null
    private var currentInputSource: Int = InputDevice.SOURCE_TOUCHSCREEN

    @Volatile private var activePath: String? = null
    private val pathFailCount = AtomicInteger(0)
    private val MAX_FAIL_BEFORE_SWITCH = 3
    @Volatile private var isInitialized = true
    @Volatile private var lastInjectionError: String? = null

    constructor() : super()
    constructor(context: Context?) : super() {
        this.ctx = context
    }

    // ==================== SHELL COMMAND ====================

    override fun executeShellCommand(command: String): String {
        val ALLOWED_PREFIXES = listOf("getevent -lp", "getevent -l", "dumpsys input", "pm list packages")

        if (ALLOWED_PREFIXES.none { command.startsWith(it) }) {
            Log.w(TAG, "Shell command rejected (not allowed): $command")
            return createErrorJson("Command not allowed: $command")
        }
        if (Regex("[;|&\n<>`$]").containsMatchIn(command)) {
            return createErrorJson("Command contains forbidden characters")
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = StringBuilder()
            val errorOutput = StringBuilder()

            Thread { process.inputStream.bufferedReader().use { it.forEachLine { line -> output.appendLine(line) } } }.start()
            Thread { process.errorStream.bufferedReader().use { it.forEachLine { line -> errorOutput.appendLine(line) } } }.start()

            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return createErrorJson("Command timeout after 15s")
            }

            createResultJson(output.toString(), errorOutput.toString(), process.exitValue())
        } catch (e: Exception) {
            createErrorJson(e.localizedMessage ?: "Unknown error")
        }
    }

    private fun createErrorJson(message: String): String {
        return org.json.JSONObject()
            .put("output", "")
            .put("error", message)
            .put("exitCode", -1)
            .toString()
    }

    private fun createResultJson(output: String, error: String, exitCode: Int): String {
        return org.json.JSONObject()
            .put("output", output)
            .put("error", error)
            .put("exitCode", exitCode)
            .toString()
    }

    // ==================== STREAM COMMAND ====================

    @Volatile private var streamProcess: Process? = null
    @Volatile private var streamThread: Thread? = null
    private val streamLock = Any()

    override fun executeStreamCommand(command: String, listener: ICommandOutputListener) {
        synchronized(streamLock) {
            stopStreamCommandInternal()

            if (!command.startsWith("getevent -l /dev/input/event")) {
                try {
                    listener.onOutputLine("ERROR: Only getevent -l /dev/input/eventN is allowed")
                    listener.onExit(-1)
                } catch (_: Exception) {}
                return
            }

            streamThread = Thread {
                try {
                    val cmdArray = command.split(" ").toTypedArray()
                    streamProcess = Runtime.getRuntime().exec(cmdArray)
                    val reader = streamProcess!!.inputStream.bufferedReader()

                    while (!Thread.currentThread().isInterrupted) {
                        val line = try { reader.readLine() } catch (_: Exception) { break }
                        if (line == null) break
                        try { listener.onOutputLine(line) } catch (_: Exception) { break }
                    }

                    val exitCode = try { streamProcess?.waitFor() ?: -1 } catch (_: Exception) { -1 }
                    try { listener.onExit(exitCode) } catch (_: Exception) {}
                } catch (e: Exception) {
                    try {
                        listener.onOutputLine("ERROR: ${e.localizedMessage}")
                        listener.onExit(-1)
                    } catch (_: Exception) {}
                }
            }.apply { isDaemon = true }

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

    // ==================== TOUCH INJECTION ====================

    // FIX v3 (root cause of "analog kembali ke tengah saat tombol lain ditekan"):
    // Previously, touchDown/touchMove/touchUp each created a MotionEvent with pointerCount=1
    // containing ONLY the single pointer being acted upon, using ACTION_DOWN/ACTION_UP for
    // every pointer transition. This is WRONG for multi-touch: when pointer 0 (L_STICK) is
    // already DOWN and pointer 2 (button A) injects ACTION_DOWN with pointerCount=1, Android
    // interprets this as a NEW touch session — the previous session (stick) gets CANCELLED,
    // and the stick's touch is released. The player appears to "stop" every time a button
    // is pressed while moving.
    //
    // Correct Android multi-touch semantics:
    //   - First pointer DOWN: ACTION_DOWN, pointerCount=1
    //   - Additional pointer DOWN while others active: ACTION_POINTER_DOWN, pointerCount=ALL,
    //     actionIndex = index of the new pointer in the properties array
    //   - Any pointer MOVE: ACTION_MOVE, pointerCount=ALL, all positions updated
    //   - Pointer UP while others remain: ACTION_POINTER_UP, pointerCount=ALL,
    //     actionIndex = index of the pointer being released
    //   - Last pointer UP: ACTION_UP, pointerCount=1
    //
    // This requires tracking ALL active pointers (their IDs, positions, and the gesture's
    // original downTime) and building each MotionEvent with the full pointer set.
    private data class ActivePointer(val id: Int, @Volatile var x: Float, @Volatile var y: Float)
    private val activePointers = java.util.concurrent.ConcurrentHashMap<Int, ActivePointer>()
    private val pointersLock = Any()

    @Volatile private var gestureDownTime: Long = 0L

    override fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        synchronized(pointersLock) {
            activePointers[pointerId] = ActivePointer(pointerId, x, y)
            if (activePointers.size == 1) {
                // First pointer — starts a new gesture, records the gesture downTime
                gestureDownTime = now
            }
        }
        return injectMultiPointerEvent(pointerId, MotionEvent.ACTION_DOWN, gestureDownTime)
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        val ap = activePointers[pointerId]
        if (ap == null) {
            // Pointer not active — can't move. This can happen if touchDown failed and the
            // caller didn't handle the error. Log once to aid diagnosis.
            Log.w(TAG, "touchMove called for inactive pointer $pointerId")
            return false
        }
        ap.x = x
        ap.y = y
        return injectMultiPointerEvent(pointerId, MotionEvent.ACTION_MOVE, gestureDownTime)
    }

    override fun touchUp(pointerId: Int): Boolean {
        val ap = activePointers[pointerId]
        if (ap == null) {
            Log.w(TAG, "touchUp called for inactive pointer $pointerId")
            return false
        }
        val isLastPointer: Boolean
        synchronized(pointersLock) {
            isLastPointer = activePointers.size <= 1
        }
        val result = injectMultiPointerEvent(pointerId, MotionEvent.ACTION_UP, gestureDownTime)
        synchronized(pointersLock) {
            activePointers.remove(pointerId)
            if (activePointers.isEmpty()) {
                gestureDownTime = 0L
            }
        }
        return result
    }

    override fun injectTap(x: Float, y: Float, durationMs: Long): Boolean {
        // Pointer ID 50 is well outside the 0-15 per-gamepad allocation range, so it can
        // never collide with any analog or button pointer the mapper allocates.
        val tapPointerId = 50
        if (!touchDown(tapPointerId, x, y)) return false
        Thread.sleep(durationMs.coerceAtLeast(10))
        return touchUp(tapPointerId)
    }

    /**
     * Build and inject a MotionEvent with the CORRECT multi-pointer semantics.
     *
     * - Gathers ALL currently-active pointers (not just the one being acted upon)
     * - Determines the correct action code:
     *     ACTION_DOWN / ACTION_UP for first/last pointer
     *     ACTION_POINTER_DOWN / ACTION_POINTER_UP for intermediate pointers
     *     ACTION_MOVE for position updates
     * - Sets actionIndex to the position of the target pointer in the properties array
     * - Uses the gesture's original downTime (from the first pointer's DOWN) for consistency
     */
    private fun injectMultiPointerEvent(pointerId: Int, action: Int, downTime: Long): Boolean {
        // Snapshot all active pointers under lock to ensure consistency
        val allPointers: List<ActivePointer>
        synchronized(pointersLock) {
            allPointers = activePointers.values.sortedBy { it.id }
        }
        val pointerCount = allPointers.size
        if (pointerCount == 0) return false

        val actionIndex = allPointers.indexOfFirst { it.id == pointerId }
        if (actionIndex < 0) {
            Log.w(TAG, "injectMultiPointerEvent: pointer $pointerId not in active set")
            return false
        }

        // Build the correct action code
        val finalAction = when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (pointerCount == 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            MotionEvent.ACTION_UP -> {
                if (pointerCount == 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> MotionEvent.ACTION_MOVE
        }

        // Build properties and coords for ALL active pointers
        val properties = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().apply {
                this.id = allPointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val coords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().apply {
                this.x = allPointers[i].x
                this.y = allPointers[i].y
                // The pointer being released gets pressure=0
                pressure = if ((finalAction == MotionEvent.ACTION_UP || finalAction == MotionEvent.ACTION_POINTER_UP) && i == actionIndex) 0f else 1f
                size = 1f
            }
        }

        val eventTime = SystemClock.uptimeMillis()

        val event = MotionEvent.obtain(
            downTime, eventTime, finalAction, pointerCount,
            properties, coords,
            0, 0, 1f, 1f, -1, 0, currentInputSource, 0
        )

        try {
            // Try Path A first
            if (tryPathA(event)) {
                if (activePath != "A") {
                    activePath = "A"
                    Log.i(TAG, "Using Path A (IInputManager AIDL)")
                }
                pathFailCount.set(0)
                return true
            }

            // Try Path B
            if (tryPathB(event)) {
                if (activePath != "B") {
                    activePath = "B"
                    Log.i(TAG, "Using Path B (InputManager Reflection)")
                }
                pathFailCount.set(0)
                return true
            }
        } finally {
            event.recycle()
        }

        // Both A and B failed.
        // FIX v3: do NOT fall back to shell `input tap` when there are OTHER active pointers.
        // Shell `input tap` injects a single-pointer DOWN+UP on pointer 0, which would
        // hijack/cancel any existing multi-touch session (e.g., the L_STICK being held).
        // Only use shell fallback when this is the ONLY active pointer (pointerCount == 1
        // at the time of the snapshot, meaning no other touches are in progress).
        if (pointerCount == 1 && (finalAction == MotionEvent.ACTION_DOWN || finalAction == MotionEvent.ACTION_UP)) {
            Log.w(TAG, "Paths A and B failed — falling back to shell input (single-pointer only)")
            return shellInputTap(coords[0].x, coords[0].y)
        }

        Log.w(TAG, "Injection failed: action=0x${finalAction.toString(16)} pointerCount=$pointerCount (multi-pointer shell fallback not supported — would hijack existing touches)")
        return false
    }

    private fun tryPathA(event: MotionEvent): Boolean {
        val proxy = iInputManagerProxy ?: return false
        val method = pathA_injectMethod ?: return false

        return try {
            val result = method.invoke(proxy, event, 0) as Boolean
            if (!result) {
                pathFailCount.incrementAndGet()
                Log.w(TAG, "Path A: injectInputEvent returned false (no exception thrown, OS rejected the event)")
            }
            result
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // FIX: previously this exception (and its actual cause, e.g. SecurityException:
            // "Injecting to another application requires INJECT_EVENTS permission") was
            // silently discarded — reflection wraps the real thrown exception in
            // InvocationTargetException, so e.cause is what actually matters here.
            pathFailCount.incrementAndGet()
            lastInjectionError = "Path A: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}"
            Log.e(TAG, "Path A: injectInputEvent threw (cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message})", e.cause ?: e)
            false
        } catch (e: Exception) {
            pathFailCount.incrementAndGet()
            lastInjectionError = "Path A: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Path A: injectInputEvent failed (${e.javaClass.simpleName}: ${e.message})", e)
            false
        }
    }

    private fun tryPathB(event: MotionEvent): Boolean {
        val im = inputManagerInstance ?: return false
        val method = pathB_injectMethod ?: return false

        return try {
            val result = method.invoke(im, event, 0) as Boolean
            if (!result) {
                pathFailCount.incrementAndGet()
                Log.w(TAG, "Path B: injectInputEvent returned false (no exception thrown, OS rejected the event)")
            }
            result
        } catch (e: java.lang.reflect.InvocationTargetException) {
            pathFailCount.incrementAndGet()
            lastInjectionError = "Path B: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}"
            Log.e(TAG, "Path B: injectInputEvent threw (cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message})", e.cause ?: e)
            false
        } catch (e: Exception) {
            pathFailCount.incrementAndGet()
            lastInjectionError = "Path B: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Path B: injectInputEvent failed (${e.javaClass.simpleName}: ${e.message})", e)
            false
        }
    }

    private fun shellInputTap(x: Float, y: Float): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString()))
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "Path C: shell 'input tap' timed out after 800ms")
                return false
            }
            val exitCode = process.exitValue()
            // FIX: previously the exit code and stderr were discarded on failure, giving no
            // way to tell a permission denial (common on MIUI/ColorOS/etc even with Shizuku
            // connected) apart from any other failure mode.
            if (exitCode != 0) {
                lastInjectionError = "Path C: exit=$exitCode stderr=${stderr.take(200)}"
                Log.e(TAG, "Path C: shell 'input tap' exited $exitCode, stderr: $stderr")
            }
            exitCode == 0
        } catch (e: Exception) {
            lastInjectionError = "Path C: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Path C: shell 'input tap' threw (${e.javaClass.simpleName}: ${e.message})", e)
            false
        }
    }

    // ==================== PATH A & B ====================

    private val iInputManagerProxy: Any? by lazy {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val inputBinder = getServiceMethod.invoke(null, "input") as? IBinder ?: return@lazy null

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val proxy = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, inputBinder)
            Log.i(TAG, "Path A: IInputManager proxy obtained successfully")
            proxy
        } catch (e: Exception) {
            Log.e(TAG, "Path A: Failed to obtain IInputManager proxy", e)
            null
        }
    }

    private val pathA_injectMethod: Method? by lazy {
        try {
            iInputManagerProxy?.javaClass?.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )?.apply { isAccessible = true }
        } catch (e: Exception) {
            null
        }
    }

    private val inputManagerInstance: InputManager? by lazy {
        try {
            ctx?.getSystemService(Context.INPUT_SERVICE) as? InputManager
                ?: InputManager::class.java.getMethod("getInstance").invoke(null) as? InputManager
        } catch (e: Exception) {
            null
        }
    }

    private val pathB_injectMethod: Method? by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )?.apply { isAccessible = true }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== REQUIRED METHODS ====================

    override fun isAlive(): Boolean {
        return isInitialized
    }

    override fun releaseAllPointers(): Boolean {
        // Clear all active pointer tracking so stale entries don't accumulate across
        // profile switches / service restarts.
        synchronized(pointersLock) {
            activePointers.clear()
            gestureDownTime = 0L
        }
        return true
    }

    override fun updateConfig(json: String) {
        Log.d(TAG, "updateConfig called")
    }

    override fun testInjection(x: Float, y: Float): String {
        // FIX: previously called NativeGamepadMapper.instance?.runDiagnosticTestTap(), which
        // (a) never compiled — that method was never defined anywhere in the codebase, and
        // (b) could never have worked anyway: TouchDaemonService runs as a Shizuku UserService
        // in its own separate process, so the app-process NativeGamepadMapper singleton is
        // always null here regardless. Self-contained instead: actually exercise the real
        // Path A/B/C injection machinery with a short real tap and report what happened.
        return try {
            // After the fix, activePath is only ever "A", "B", or null (never "C" — C is
            // now a one-shot fallback that doesn't cache). So "used shell fallback" is
            // inferred from activePath being null AFTER the call + success.
            val success = injectTap(x, y, 80L)
            val pathAfter = activePath
            // Shell fallback was used if path stayed null (A/B both failed) but the call
            // still succeeded (shell tap worked).
            val usedShellFallback = success && pathAfter == null
            val effectivePath = pathAfter ?: if (usedShellFallback) "C" else "none"
            val json = org.json.JSONObject()
            json.put("success", success)
            json.put("path", effectivePath)
            json.put("failCount", pathFailCount.get())
            // FIX 2: JS side (ShizukuPanel.tsx, OnboardingWizard.tsx) expects these exact
            // field names — an earlier version only had success/path/failCount, so those
            // screens showed "undefined" for every diagnostic line despite the call working.
            json.put("inputManager_null", inputManagerInstance == null)
            json.put("injectMethod_null", pathA_injectMethod == null)
            json.put("touchDown_result", success)
            json.put("shellInputTap_result", usedShellFallback)
            json.put("useShellFallback", usedShellFallback)
            if (success) {
                json.put("recommendation", "Injection OK via Path $effectivePath")
            } else {
                json.put("error", "Test tap failed on Path A, B, and C")
                // FIX: surface the actual captured exception/exit-code instead of leaving the
                // user to dig through `adb logcat`. Very commonly a permission restriction
                // (e.g. MIUI/ColorOS/etc blocking synthetic input even with Shizuku connected)
                // rather than a code bug, and this makes that diagnosable from the app itself.
                json.put("lastError", lastInjectionError ?: "(no exception captured — OS silently rejected the event)")
                json.put("recommendation", "All injection paths failed. Check Shizuku permission and daemon status.")
            }
            json.toString()
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
    }

    override fun destroy() {
        isInitialized = false
        releaseAllPointers()
        stopStreamCommand()
        System.exit(0)
    }
}
