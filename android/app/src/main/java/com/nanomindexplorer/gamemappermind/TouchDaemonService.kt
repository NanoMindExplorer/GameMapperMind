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

/**
 * TouchDaemonService — Multi-path touch injection service via Shizuku.
 *
 * Injection paths (tried in order):
 *   Path A (Primary): IInputManager via ServiceManager AIDL
 *   Path B (Fallback): InputManager via Context + Reflection
 *   Path C (Last Resort): Shell command "input tap"
 */
class TouchDaemonService : ITouchService.Stub {

    private var ctx: Context? = null
    private var baseDownTime: Long = 0L
    private var currentInputSource: Int = InputDevice.SOURCE_TOUCHSCREEN

    @Volatile private var activePath: String? = null
    private val pathFailCount = AtomicInteger(0)
    private val MAX_FAIL_BEFORE_SWITCH = 3
    @Volatile private var isInitialized = true

    constructor() : super()
    constructor(context: Context?) : super() {
        this.ctx = context
    }

    // ==================== SHELL COMMAND ====================

    override fun executeShellCommand(command: String): String {
        val ALLOWED_PREFIXES = listOf("getevent -lp", "getevent -l", "dumpsys input", "pm list packages")

        if (ALLOWED_PREFIXES.none { command.startsWith(it) }) {
            return createErrorJson("Command not allowed by whitelist: $command")
        }
        if (Regex("[;|&\n<>`$]").containsMatchIn(command)) {
            return createErrorJson("Command contains forbidden shell metacharacters")
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = StringBuilder()
            val errorOutput = StringBuilder()

            Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { output.appendLine(it) }
                }
            }.start()

            Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { errorOutput.appendLine(it) }
                }
            }.start()

            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return createErrorJson("Command timed out after 15 seconds")
            }

            createResultJson(output.toString(), errorOutput.toString(), process.exitValue())
        } catch (e: Exception) {
            createErrorJson(e.localizedMessage ?: "Unknown error occurred")
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
                    listener.onOutputLine("ERROR: Only 'getevent -l /dev/input/eventN' is allowed")
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
                        try {
                            listener.onOutputLine(line)
                        } catch (_: Exception) { break }
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
        if (!touchDown(0, x, y)) return false
        Thread.sleep(durationMs.coerceAtLeast(10))
        return touchUp(0)
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

        if (activePath == "C") {
            if (pointerCount == 1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)) {
                return shellInputTap(pointerCoords[0].x, pointerCoords[0].y)
            }
            return false
        }

        val eventTime = SystemClock.uptimeMillis()
        val downTime = if (baseDownTime == 0L) eventTime else baseDownTime

        val event = MotionEvent.obtain(
            downTime, eventTime, action, actionIndex,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f, -1, 0, currentInputSource, 0
        )

        // Try Path A
        if ((activePath == null || activePath == "A") && tryPathA(event)) {
            activePath = "A"
            pathFailCount.set(0)
            event.recycle()
            return true
        }

        // Try Path B
        if ((activePath == null || activePath == "B") && tryPathB(event)) {
            activePath = "B"
            pathFailCount.set(0)
            event.recycle()
            return true
        }

        event.recycle()

        // Fallback to Path C
        val success = shellInputTap(pointerCoords[0].x, pointerCoords[0].y)
        if (success) {
            activePath = "C"
        }
        return success
    }

    private fun tryPathA(event: MotionEvent): Boolean {
        val proxy = iInputManagerProxy ?: return false
        val method = pathA_injectMethod ?: return false

        return try {
            val result = method.invoke(proxy, event, 0) as Boolean
            if (!result) pathFailCount.incrementAndGet()
            result
        } catch (e: Exception) {
            Log.w("GameMapper", "Path A injection failed: ${e.message}")
            pathFailCount.incrementAndGet()
            false
        }
    }

    private fun tryPathB(event: MotionEvent): Boolean {
        val im = inputManagerInstance ?: return false
        val method = pathB_injectMethod ?: return false

        return try {
            val result = method.invoke(im, event, 0) as Boolean
            if (!result) pathFailCount.incrementAndGet()
            result
        } catch (e: Exception) {
            Log.w("GameMapper", "Path B injection failed: ${e.message}")
            pathFailCount.incrementAndGet()
            false
        }
    }

    private fun shellInputTap(x: Float, y: Float): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "input", "tap",
                x.toInt().toString(),
                y.toInt().toString()
            ))
            val finished = process.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            process.exitValue() == 0
        } catch (e: Exception) {
            Log.e("GameMapper", "shellInputTap failed", e)
            false
        }
    }

    // ==================== PATH A: IInputManager via ServiceManager ====================

    private val iInputManagerProxy: Any? by lazy {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val inputBinder = getServiceMethod.invoke(null, "input") as? IBinder

            if (inputBinder == null) {
                Log.e("GameMapper", "Path A: ServiceManager.getService(\"input\") returned null")
                return@lazy null
            }

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterfaceMethod.invoke(null, inputBinder)

            Log.i("GameMapper", "Path A: Successfully obtained IInputManager proxy")
            proxy
        } catch (e: Exception) {
            Log.e("GameMapper", "Path A: Failed to obtain IInputManager proxy", e)
            null
        }
    }

    private val pathA_injectMethod: Method? by lazy {
        val proxy = iInputManagerProxy ?: return@lazy null
        try {
            val method = proxy.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            Log.i("GameMapper", "Path A: injectInputEvent method found")
            method
        } catch (e: Exception) {
            Log.e("GameMapper", "Path A: injectInputEvent method not found", e)
            null
        }
    }

    // ==================== PATH B: InputManager via Context + Reflection ====================

    private val inputManagerInstance: InputManager? by lazy {
        var context = ctx
        if (context == null) {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentApp = activityThreadClass.getMethod("currentApplication").invoke(null)
                if (currentApp is Context) {
                    context = currentApp
                    this.ctx = context
                }
            } catch (e: Exception) {
                Log.w("GameMapper", "Path B: Failed to get application context via ActivityThread", e)
            }
        }

        try {
            val im = context?.getSystemService(Context.INPUT_SERVICE) as? InputManager
            if (im != null) {
                Log.i("GameMapper", "Path B: InputManager obtained via Context.getSystemService")
                return@lazy im
            }
        } catch (e: Exception) {
            Log.w("GameMapper", "Path B: Context.getSystemService(INPUT_SERVICE) failed", e)
        }

        try {
            val im = InputManager::class.java.getMethod("getInstance").invoke(null) as? InputManager
            if (im != null) {
                Log.i("GameMapper", "Path B: InputManager obtained via getInstance() reflection")
                return@lazy im
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "Path B: All methods to obtain InputManager failed", e)
        }
        null
    }

    private val pathB_injectMethod: Method? by lazy {
        val im = inputManagerInstance ?: return@lazy null
        try {
            val method = InputManager::class.java.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            Log.i("GameMapper", "Path B: injectInputEvent method found via reflection")
            method
        } catch (e: Exception) {
            Log.e("GameMapper", "Path B: injectInputEvent method not found", e)
            null
        }
    }

    // ==================== UTILITY ====================

    override fun releaseAllPointers() {
        // Can be expanded later if needed
    }

    override fun destroy() {
        isInitialized = false
        releaseAllPointers()
        stopStreamCommand()
        System.exit(0)
    }
}