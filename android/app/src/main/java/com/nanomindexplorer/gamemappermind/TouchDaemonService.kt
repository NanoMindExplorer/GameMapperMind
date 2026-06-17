package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ============================================================
// TouchDaemonService — runs as a Shizuku UserService
// (shell-privilege process, UID 2000 or UID 0 for root).
//
// Per Shizuku-API documentation:
//   "The service class must implement IBinder interface.
//    The usual usage is `public class YourService extends
//    IYouAidlInterface.Stub`."
//
// So this class extends ITouchService.Stub directly — NOT
// android.app.Service. Shizuku starts the process and binds
// to the IBinder returned by the Stub.
// ============================================================
class TouchDaemonService : ITouchService.Stub {

    data class AntiBanConfig(
        var enabled: Boolean = false,
        var coordinateJitter: Float = 4f,
        var timingJitter: Int = 3,
        var pressureVariance: Float = 0.15f,
        var sizeVariance: Float = 0.10f,
        var strokeDurationJitter: Int = 12,
        var microPauseProbability: Float = 0.02f,
        var microPauseMaxMs: Int = 45
    )

    @Volatile private var antiBan = AntiBanConfig()

    // ============================================================
    // Constructors — Shizuku v13+ tries Context constructor first.
    // @Keep prevents ProGuard from removing it.
    // ============================================================
    constructor() {
        Log.i("GameMapper", "TouchDaemonService: default constructor")
    }

    @Keep
    constructor(context: Context) {
        Log.i("GameMapper", "TouchDaemonService: constructor with Context: $context")
    }

    // ============================================================
    // Reserved destroy method — Shizuku calls this when unbindUserService
    // is called with remove=true. We MUST System.exit() to kill the
    // process (Shizuku does NOT kill it automatically).
    // ============================================================
    override fun destroy() {
        Log.i("GameMapper", "TouchDaemonService: destroy")
        stopEvdevCapture()
        System.exit(0)
    }

    override fun isAlive(): Boolean = true

    // ============================================================
    // InputManager — obtained via reflection (hidden API).
    // Works in UserService process because non-SDK API restrictions
    // do not apply there.
    // ============================================================
    private val inputManager: InputManager? by lazy {
        try {
            InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get InputManager", e); null
        }
    }

    private val injectInputEventMethod by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get injectInputEvent method", e); null
        }
    }

    class PointerState {
        var x: Float = 0f
        var y: Float = 0f
        var isDown: Boolean = false
        var pressure: Float = 1.0f
        var size: Float = 1.0f
    }

    private val pointers = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L
    private val rng = Random(System.currentTimeMillis())

    // ============================================================
    // Anti-ban helpers
    // ============================================================
    private fun applyCoordinateJitter(x: Float, y: Float): Pair<Float, Float> {
        if (!antiBan.enabled || antiBan.coordinateJitter <= 0f) return Pair(x, y)
        val angle = rng.nextFloat() * 2f * Math.PI.toFloat()
        val mag = rng.nextFloat() * antiBan.coordinateJitter
        return Pair(x + cos(angle) * mag, y + sin(angle) * mag)
    }

    private fun applyPressureVariance(): Float {
        if (!antiBan.enabled || antiBan.pressureVariance <= 0f) return 1.0f
        return 1.0f - (rng.nextFloat() * antiBan.pressureVariance)
    }

    private fun applySizeVariance(): Float {
        if (!antiBan.enabled || antiBan.sizeVariance <= 0f) return 1.0f
        return 1.0f - (rng.nextFloat() * antiBan.sizeVariance)
    }

    private fun applyTimingJitter() {
        if (!antiBan.enabled || antiBan.timingJitter <= 0) return
        val delay = rng.nextInt(0, antiBan.timingJitter * 2) - antiBan.timingJitter
        if (delay > 0) {
            try { Thread.sleep(delay.toLong()) } catch (_: InterruptedException) {}
        }
    }

    private fun maybeMicroPause() {
        if (!antiBan.enabled || antiBan.microPauseProbability <= 0f) return
        if (rng.nextFloat() < antiBan.microPauseProbability) {
            val pause = rng.nextInt(10, antiBan.microPauseMaxMs)
            try { Thread.sleep(pause.toLong()) } catch (_: InterruptedException) {}
        }
    }

    // ============================================================
    // Touch injection (MotionEvent via InputManager.injectInputEvent)
    // ============================================================
    private fun injectMotionEvent(action: Int, actionIndex: Int) {
        val downTime = baseDownTime
        val eventTime = SystemClock.uptimeMillis()

        var pointerCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) pointerCount++
        }

        if (pointerCount == 0 && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            pointerCount = 1
        }
        if (pointerCount == 0) return

        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        var activeIndex = 0
        for (i in 0 until pointers.size()) {
            val pointerId = pointers.keyAt(i)
            val state = pointers.valueAt(i)

            val isActive = state.isDown ||
                ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP && pointerId == actionIndex) ||
                ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP)

            if (isActive) {
                pointerProperties[activeIndex].id = pointerId
                pointerProperties[activeIndex].toolType = MotionEvent.TOOL_TYPE_FINGER

                val (jx, jy) = applyCoordinateJitter(state.x, state.y)
                pointerCoords[activeIndex].x = jx
                pointerCoords[activeIndex].y = jy
                pointerCoords[activeIndex].pressure = if (state.pressure > 0) state.pressure else applyPressureVariance()
                pointerCoords[activeIndex].size = if (state.size > 0) state.size else applySizeVariance()
                activeIndex++
            }
        }

        var compactedActionIndex = 0
        if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN ||
            (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
            for (i in 0 until activeIndex) {
                if (pointerProperties[i].id == actionIndex) {
                    compactedActionIndex = i; break
                }
            }
        }

        val finalAction = if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN ||
                              (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
            (action and MotionEvent.ACTION_MASK) or (compactedActionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action

        val event = MotionEvent.obtain(
            downTime, eventTime, finalAction, activeIndex,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        try {
            injectInputEventMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            Log.e("GameMapper", "Injection failed", e)
        }
        event.recycle()
    }

    override fun touchDown(pointerId: Int, x: Float, y: Float) {
        maybeMicroPause()
        applyTimingJitter()

        var state = pointers.get(pointerId)
        if (state == null) {
            state = PointerState()
            pointers.put(pointerId, state)
        }
        state.x = x
        state.y = y
        state.isDown = true
        state.pressure = applyPressureVariance()
        state.size = applySizeVariance()

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

    override fun touchMove(pointerId: Int, x: Float, y: Float) {
        applyTimingJitter()
        val state = pointers.get(pointerId) ?: return
        state.x = x
        state.y = y
        if (antiBan.enabled) {
            state.pressure = (state.pressure + (rng.nextFloat() - 0.5f) * antiBan.pressureVariance * 0.3f)
                .coerceIn(0.7f, 1.0f)
        }
        if (state.isDown) {
            injectMotionEvent(MotionEvent.ACTION_MOVE, 0)
        }
    }

    override fun touchUp(pointerId: Int) {
        applyTimingJitter()
        val state = pointers.get(pointerId) ?: return

        var activePointersCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activePointersCount++
        }

        if (activePointersCount <= 1) {
            injectMotionEvent(MotionEvent.ACTION_UP, pointerId)
            state.isDown = false
            pointers.clear()
        } else {
            injectMotionEvent(MotionEvent.ACTION_POINTER_UP, pointerId)
            state.isDown = false
            pointers.remove(pointerId)
        }
    }

    override fun injectTap(x: Float, y: Float) {
        val duration = if (antiBan.enabled) {
            (20 + rng.nextInt(0, antiBan.strokeDurationJitter * 2) - antiBan.strokeDurationJitter)
                .coerceAtLeast(8).toLong()
        } else 20L
        val id = 99
        touchDown(id, x, y)
        try { Thread.sleep(duration) } catch (_: InterruptedException) {}
        touchUp(id)
    }

    override fun setAntiBanConfig(
        enabled: Boolean,
        coordinateJitter: Float,
        timingJitter: Int,
        pressureVariance: Float,
        sizeVariance: Float,
        strokeDurationJitter: Int,
        microPauseProbability: Float,
        microPauseMaxMs: Int
    ) {
        antiBan = AntiBanConfig(
            enabled, coordinateJitter, timingJitter, pressureVariance,
            sizeVariance, strokeDurationJitter, microPauseProbability, microPauseMaxMs
        )
        Log.d("GameMapper", "Anti-ban config updated: enabled=$enabled jitter=${coordinateJitter}px")
    }

    // ============================================================
    // Evdev capture — runs in THIS process (shell privilege).
    // We read /dev/input/event* via `getevent -l` (which requires
    // shell privilege to access input devices).
    // Results are forwarded to TouchInjectionPlugin which then
    // emits them to JS via notifyListeners().
    // ============================================================
    @Volatile private var evdevThread: Thread? = null
    @Volatile private var evdevProcess: Process? = null
    @Volatile private var evdevListening = false

    override fun startEvdevCapture(): Boolean {
        if (evdevListening) {
            Log.d("GameMapper", "Evdev capture already running")
            return true
        }

        Log.i("GameMapper", "Starting evdev capture (shell privilege)...")
        evdevListening = true
        evdevThread = Thread {
            try {
                // getevent -l reads raw input events from /dev/input/event*
                // Requires shell (UID 2000) or root (UID 0) privilege.
                // UserService runs with this privilege, so this works.
                val pb = ProcessBuilder("sh", "-c", "getevent -l")
                pb.redirectErrorStream(true)
                evdevProcess = pb.start()
                val reader = BufferedReader(InputStreamReader(evdevProcess!!.inputStream))
                var line: String?

                var lStickX = 0f; var lStickY = 0f
                var rStickX = 0f; var rStickY = 0f
                var l2Analog = -1f; var r2Analog = -1f
                var gyroX = 0f; var gyroY = 0f; var gyroZ = 0f

                while (evdevListening && reader.readLine().also { line = it } != null) {
                    val raw = line ?: continue
                    try {
                        if (raw.startsWith("add device")) {
                            continue
                        }
                        if (raw.trim().startsWith("name:")) {
                            val name = raw.trim().removePrefix("name:").trim().trim('"')
                            if (name.isNotEmpty()) {
                                TouchInjectionPlugin.emitGamepadButton("CONTROLLER_ID:$name", 1, 1.0f)
                            }
                            continue
                        }

                        if (raw.contains("EV_ABS")) {
                            val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size >= 4) {
                                val axisType = parts[2]
                                val valueHex = parts[3]
                                val valueInt = java.lang.Long.parseLong(valueHex, 16).toInt()
                                val normalized = normalizeAxis(axisType, valueInt)

                                when (axisType) {
                                    "ABS_X" -> lStickX = normalized
                                    "ABS_Y" -> lStickY = normalized
                                    "ABS_Z", "ABS_RX" -> rStickX = normalized
                                    "ABS_RZ", "ABS_RY" -> rStickY = normalized
                                    "ABS_BRAKE" -> l2Analog = (normalized + 1f) / 2f
                                    "ABS_GAS"   -> r2Analog = (normalized + 1f) / 2f
                                    "ABS_HAT0X", "ABS_HAT0Y" -> {
                                        val isDown = valueInt != 0
                                        val btnName = when {
                                            axisType == "ABS_HAT0X" && valueInt < 0 -> "LEFT"
                                            axisType == "ABS_HAT0X" && valueInt > 0 -> "RIGHT"
                                            axisType == "ABS_HAT0Y" && valueInt < 0 -> "UP"
                                            axisType == "ABS_HAT0Y" && valueInt > 0 -> "DOWN"
                                            else -> ""
                                        }
                                        if (btnName.isNotEmpty()) {
                                            TouchInjectionPlugin.emitGamepadButton(btnName, if (isDown) 1 else 0, 1.0f)
                                        }
                                    }
                                    "ABS_RX2", "ABS_RY2", "ABS_RZ2" -> {
                                        when (axisType) {
                                            "ABS_RX2" -> gyroX = normalized
                                            "ABS_RY2" -> gyroY = normalized
                                            "ABS_RZ2" -> gyroZ = normalized
                                        }
                                        TouchInjectionPlugin.emitGyroData(gyroX, gyroY, gyroZ, System.currentTimeMillis())
                                    }
                                }
                                TouchInjectionPlugin.emitGamepadAxis(
                                    floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog)
                                )
                            }
                        } else if (raw.contains("EV_KEY")) {
                            val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size >= 4) {
                                val btnRaw = parts[2]
                                val stateStr = parts[3]
                                val isDown = if (stateStr == "DOWN") 1 else 0
                                val btnMap = mapEvdevToButton(btnRaw)
                                if (btnMap != "UNKNOWN") {
                                    TouchInjectionPlugin.emitGamepadButton(btnMap, isDown, 1.0f)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore parse errors on individual lines
                    }
                }
            } catch (e: Exception) {
                Log.e("GameMapper", "Evdev capture failed", e)
            } finally {
                evdevListening = false
                try { evdevProcess?.destroy() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }
        evdevThread?.start()
        return true
    }

    override fun stopEvdevCapture(): Boolean {
        Log.i("GameMapper", "Stopping evdev capture")
        evdevListening = false
        try { evdevProcess?.destroy() } catch (_: Exception) {}
        try { evdevThread?.join(1000) } catch (_: InterruptedException) {}
        evdevProcess = null
        evdevThread = null
        return true
    }

    // Axis ranges for normalization (auto-expand on first event)
    private val axisRanges = mutableMapOf<String, IntArray>()

    private fun normalizeAxis(axisName: String, rawValue: Int): Float {
        var min = 0
        var max = 255
        val range = axisRanges[axisName]
        if (range != null) { min = range[0]; max = range[1] }
        if (rawValue < min) { min = rawValue; axisRanges[axisName] = intArrayOf(min, max) }
        if (rawValue > max) { max = rawValue; axisRanges[axisName] = intArrayOf(min, max) }
        val span = (max - min).coerceAtLeast(1)
        return ((rawValue - min).toFloat() / span) * 2f - 1f
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_SOUTH") || evdevName.contains("BTN_A") || evdevName.contains("BTN_GAMEPAD") -> "A"
            evdevName.contains("BTN_EAST")  || evdevName.contains("BTN_B")  -> "B"
            evdevName.contains("BTN_NORTH") || evdevName.contains("BTN_X")  -> "X"
            evdevName.contains("BTN_WEST")  || evdevName.contains("BTN_Y")  -> "Y"
            evdevName.contains("BTN_TL")    || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR")    || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_TL2")   || evdevName.contains("BTN_LT") -> "LT"
            evdevName.contains("BTN_TR2")   || evdevName.contains("BTN_RT") -> "RT"
            evdevName.contains("BTN_THUMBL") -> "L3"
            evdevName.contains("BTN_THUMBR") -> "R3"
            evdevName.contains("BTN_START")  -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("BTN_MODE")   -> "MODE"
            evdevName.contains("BTN_DPAD_UP")    -> "UP"
            evdevName.contains("BTN_DPAD_DOWN")  -> "DOWN"
            evdevName.contains("BTN_DPAD_LEFT")  -> "LEFT"
            evdevName.contains("BTN_DPAD_RIGHT") -> "RIGHT"
            else -> "UNKNOWN"
        }
    }
}
