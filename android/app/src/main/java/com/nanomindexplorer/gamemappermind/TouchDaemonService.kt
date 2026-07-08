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
                return createErrorJson("Command timeout")
            }

            createResultJson(output.toString(), errorOutput.toString(), process.exitValue())
        } catch (e: Exception) {
            createErrorJson(e.localizedMessage ?: "Unknown error")
        }
    }

    private fun createErrorJson(msg: String) = org.json.JSONObject()
        .put("output", "").put("error", msg).put("exitCode", -1).toString()

    private fun createResultJson(output: String, error: String, code: Int) = org.json.JSONObject()
        .put("output", output).put("error", error).put("exitCode", code).toString()

    // ==================== STREAM COMMAND ====================

    @Volatile private var streamProcess: Process? = null
    @Volatile private var streamThread: Thread? = null
    private val streamLock = Any()

    override fun executeStreamCommand(command: String, listener: ICommandOutputListener) {
        synchronized(streamLock) {
            stopStreamCommandInternal()
            if (!command.startsWith("getevent -l /dev/input/event")) {
                try { listener.onOutputLine("ERROR: Only getevent -l allowed"); listener.onExit(-1) } catch (_: Exception) {}
                return
            }
            streamThread = Thread {
                try {
                    streamProcess = Runtime.getRuntime().exec(command.split(" ").toTypedArray())
                    val reader = streamProcess!!.inputStream.bufferedReader()
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        try { listener.onOutputLine(line) } catch (_: Exception) { break }
                    }
                    listener.onExit(try { streamProcess?.waitFor() ?: -1 } catch (_: Exception) { -1 })
                } catch (e: Exception) {
                    try { listener.onOutputLine("ERROR: ${e.message}"); listener.onExit(-1) } catch (_: Exception) {}
                }
            }.apply { isDaemon = true }
            streamThread?.start()
        }
    }

    override fun stopStreamCommand() {
        synchronized(streamLock) { stopStreamCommandInternal() }
    }

    private fun stopStreamCommandInternal() {
        try { streamProcess?.destroyForcibly() } catch (_: Exception) {}
        streamProcess = null
        try { streamThread?.interrupt() } catch (_: Exception) {}
        streamThread = null
    }

    // ==================== INJECTION ====================

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
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f
        })
        return injectMotionEvent(action, 0, 1, props, coords)
    }

    private fun injectMotionEvent(
        action: Int, actionIndex: Int, pointerCount: Int,
        pointerProperties: Array<MotionEvent.PointerProperties>,
        pointerCoords: Array<MotionEvent.PointerCoords>
    ): Boolean {

        if (activePath == "C") {
            return if (pointerCount == 1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP))
                shellInputTap(pointerCoords[0].x, pointerCoords[0].y) else false
        }

        val eventTime = SystemClock.uptimeMillis()
        val downTime = if (baseDownTime == 0L) eventTime else baseDownTime

        val event = MotionEvent.obtain(
            downTime, eventTime, action, actionIndex,
            pointerProperties, pointerCoords, 0, 0, 1f, 1f, -1, 0, currentInputSource, 0
        )

        // Path A
        if ((activePath == null || activePath == "A") && tryPathA(event)) {
            activePath = "A"; pathFailCount.set(0); event.recycle(); return true
        }
        // Path B
        if ((activePath == null || activePath == "B") && tryPathB(event)) {
            activePath = "B"; pathFailCount.set(0); event.recycle(); return true
        }

        event.recycle()
        val success = shellInputTap(pointerCoords[0].x, pointerCoords[0].y)
        if (success) activePath = "C"
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
            pathFailCount.incrementAndGet(); false
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
            pathFailCount.incrementAndGet(); false
        }
    }

    private fun shellInputTap(x: Float, y: Float): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString()))
            val ok = p.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!ok) p.destroyForcibly()
            p.exitValue() == 0
        } catch (e: Exception) { false }
    }

    // ==================== PATH A & B (LENGKAP - TIDAK DIRINGKAS) ====================

    private val iInputManagerProxy: Any? by lazy {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
            if (binder == null) return@lazy null
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get IInputManager proxy", e); null
        }
    }

    private val pathA_injectMethod: Method? by lazy {
        try {
            iInputManagerProxy?.javaClass?.getMethod("injectInputEvent", MotionEvent::class.java, Int::class.javaPrimitiveType)
                ?.apply { isAccessible = true }
        } catch (e: Exception) { null }
    }

    private val inputManagerInstance: InputManager? by lazy {
        try {
            ctx?.getSystemService(Context.INPUT_SERVICE) as? InputManager
                ?: InputManager::class.java.getMethod("getInstance").invoke(null) as? InputManager
        } catch (e: Exception) { null }
    }

    private val pathB_injectMethod: Method? by lazy {
        try {
            InputManager::class.java.getMethod("injectInputEvent", MotionEvent::class.java, Int::class.javaPrimitiveType)
                ?.apply { isAccessible = true }
        } catch (e: Exception) { null }
    }

    override fun releaseAllPointers() {}
    override fun destroy() {
        isInitialized = false
        releaseAllPointers()
        stopStreamCommand()
        System.exit(0)
    }
}