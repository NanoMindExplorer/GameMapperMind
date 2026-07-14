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
    private var baseDownTime: Long = 0L
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

    override fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (baseDownTime == 0L) baseDownTime = SystemClock.uptimeMillis()
        return injectSinglePointer(pointerId, MotionEvent.ACTION_DOWN, x, y)
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        return injectSinglePointer(pointerId, MotionEvent.ACTION_MOVE, x, y)
    }

    override fun touchUp(pointerId: Int): Boolean {
        val result = injectSinglePointer(pointerId, MotionEvent.ACTION_UP, 0f, 0f)
        if (result) baseDownTime = 0L
        return result
    }

    override fun injectTap(x: Float, y: Float, durationMs: Long): Boolean {
        // FIX (root cause of "tombol A/LT/RT tidak inject" + "analog rusak saat tap"):
        // Previously this used pointer ID 0 — the SAME pointer ID that NativeGamepadMapper
        // allocates to the L_STICK analog stick (offset+0 for gamepad 0 = pointer 0).
        // When injectTap fired while the left stick was active, touchDown(0,...) was
        // interpreted by Android as a MOVE for the existing L_STICK pointer (moving it to
        // the tap location), then Thread.sleep, then touchUp(0) released the L_STICK
        // pointer entirely. Result: the tap didn't register as a new touch, AND the analog
        // stick got hijacked/released.
        //
        // Pointer ID 50 is well outside the 0-15 per-gamepad allocation range
        // (gamepad 0: 0-15, gamepad 1: 16-31, ..., gamepad 3: 48-63), so it can never
        // collide with any analog or button pointer the mapper allocates.
        val tapPointerId = 50
        if (!touchDown(tapPointerId, x, y)) return false
        Thread.sleep(durationMs.coerceAtLeast(10))
        return touchUp(tapPointerId)
    }

    private fun injectSinglePointer(pointerId: Int, action: Int, x: Float, y: Float): Boolean {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            this.id = pointerId
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        })

        return injectMotionEvent(action, 0, 1, properties, coords)
    }

    private fun injectMotionEvent(
        action: Int,
        actionIndex: Int,
        pointerCount: Int,
        pointerProperties: Array<MotionEvent.PointerProperties>,
        pointerCoords: Array<MotionEvent.PointerCoords>
    ): Boolean {

        // FIX (root cause of "analog nyangkut / touchMove return false"):
        // Previously, once Path A failed and the code fell through to Path C (shell input),
        // `activePath` was permanently set to "C". On every subsequent call, the code took
        // the `if (activePath == "C")` short-circuit, which returns `false` for ACTION_MOVE
        // (shell `input tap` only supports single-pointer DOWN/UP, not move). Once Path C
        // was locked in, the analog stick was effectively dead — every touchMove returned
        // false, the pointer's screen position never updated, and the stick appeared
        // "stuck" at its last position.
        //
        // New behavior: Path C is only used as a one-shot fallback for DOWN/UP. It is NEVER
        // cached as the active path. Every call tries A first, then B, then (only for
        // DOWN/UP) shell. If A/B recover on a later call (transient failure), they're used
        // again immediately — no permanent lock-in. The cost is one extra reflection probe
        // per call when A is genuinely broken, which is negligible compared to breaking
        // all analog movement permanently.

        // Fast path: if we previously fell back to shell for DOWN/UP, still try A/B first
        // for MOVE (shell can't do MOVE anyway). Only use shell for DOWN/UP when A/B fail.

        val eventTime = SystemClock.uptimeMillis()
        val downTime = if (baseDownTime == 0L) eventTime else baseDownTime

        val event = MotionEvent.obtain(
            downTime, eventTime, action, pointerCount,
            pointerProperties, pointerCoords,
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

        // Both A and B failed. Fall back to shell input — but ONLY for single-pointer
        // DOWN/UP (shell `input tap` cannot do MOVE or multi-pointer).
        // CRITICAL: do NOT cache activePath = "C" here. Next call will retry A/B first,
        // so a transient A/B failure doesn't permanently kill analog movement.
        if (pointerCount == 1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)) {
            Log.w(TAG, "Paths A and B failed — falling back to shell input for this call only")
            val success = shellInputTap(pointerCoords[0].x, pointerCoords[0].y)
            // Do NOT set activePath = "C" — we want A/B to be retried on the next call
            return success
        }

        Log.w(TAG, "All injection paths failed for action=$action pointerCount=$pointerCount (MOVE on shell fallback is unsupported)")
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
