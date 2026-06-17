package com.nanomindexplorer.gamemappermind.shizuku

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.Keep
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * GameMapperUserService — Shizuku UserService implementation.
 *
 * Per Shizuku-API documentation:
 *   "The service class must implement IBinder interface.
 *    The usual usage is `public class YourService extends
 *    IYouAidlInterface.Stub`."
 *
 * This class extends IGameMapperService.Stub directly.
 * It runs inside a Shizuku-managed process with shell UID (2000)
 * or root UID (0), giving it access to:
 *   - InputManager.injectInputEvent() (hidden API, works in shell process)
 *   - /dev/input/event* (evdev, requires shell or root)
 *
 * Thread safety:
 *   - Touch injection methods are synchronized to prevent
 *     concurrent MotionEvent construction issues.
 *   - Gamepad reading runs on a dedicated daemon thread.
 *
 * Dependencies:
 *   - com.nanomindexplorer.gamemappermind.input.TouchInjector
 *   - Shizuku API v13.1.5
 */
class GameMapperUserService : IGameMapperService.Stub {

    companion object {
        private const val TAG = "GameMapper/UserService"
    }

    private val touchInjector: TouchInjector = TouchInjector()

    // ============================================================
    // Anti-ban configuration
    // ============================================================
    data class AntiBanConfig(
        var enabled: Boolean = false,
        var coordinateJitter: Float = 4f,
        var timingJitterMs: Int = 3,
        var pressureVariance: Float = 0.15f,
        var sizeVariance: Float = 0.10f
    )

    @Volatile
    private var antiBan = AntiBanConfig()

    private val rng = Random(System.currentTimeMillis())

    // ============================================================
    // Constructors — Shizuku v13+ tries Context constructor first.
    // @Keep prevents ProGuard/R8 from removing it.
    // ============================================================
    constructor() {
        Log.i(TAG, "GameMapperUserService: default constructor invoked")
    }

    @Keep
    constructor(context: Context) {
        Log.i(TAG, "GameMapperUserService: Context constructor invoked (context=$context)")
        // Note: Context in UserService is limited — many APIs don't work.
        // See Shizuku-API README: "even if you can acquire a Context instance,
        // many APIs such as Context#registerReceiver will not work."
    }

    // ============================================================
    // Shizuku lifecycle — destroy() is called by Shizuku when
    // unbindUserService(args, conn, remove=true) is called.
    // We MUST call System.exit(0) to kill the process.
    // ============================================================
    override fun destroy() {
        Log.i(TAG, "destroy() called — cleaning up and exiting")
        stopGamepadRead()
        System.exit(0)
    }

    override fun isAlive(): Boolean = true

    // ============================================================
    // Touch Injection — delegates to TouchInjector
    // ============================================================

    override fun injectTap(x: Float, y: Float, displayId: Int) {
        applyTimingJitter()
        val jx = applyCoordinateJitterX(x)
        val jy = applyCoordinateJitterY(y)
        touchInjector.tap(jx, jy, displayId)
    }

    override fun injectSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long, displayId: Int
    ) {
        applyTimingJitter()
        touchInjector.swipe(
            applyCoordinateJitterX(startX), applyCoordinateJitterY(startY),
            applyCoordinateJitterX(endX), applyCoordinateJitterY(endY),
            durationMs, displayId
        )
    }

    override fun injectMultiTouchDown(pointerIds: String, coords: String, displayId: Int) {
        applyTimingJitter()
        val ids = pointerIds.split(",").map { it.trim().toInt() }
        val coordPairs = coords.split(",").map { pair ->
            val parts = pair.trim().split(":")
            Pair(parts[0].toFloat(), parts[1].toFloat())
        }
        val jitteredCoords = coordPairs.map { Pair(applyCoordinateJitterX(it.first), applyCoordinateJitterY(it.second)) }
        touchInjector.multiTouchDown(ids, jitteredCoords, displayId)
    }

    override fun injectMultiTouchMove(pointerIds: String, coords: String, displayId: Int) {
        val ids = pointerIds.split(",").map { it.trim().toInt() }
        val coordPairs = coords.split(",").map { pair ->
            val parts = pair.trim().split(":")
            Pair(parts[0].toFloat(), parts[1].toFloat())
        }
        val jitteredCoords = coordPairs.map { Pair(applyCoordinateJitterX(it.first), applyCoordinateJitterY(it.second)) }
        touchInjector.multiTouchMove(ids, jitteredCoords, displayId)
    }

    override fun injectTouchUp(pointerId: Int, displayId: Int) {
        applyTimingJitter()
        touchInjector.touchUp(pointerId, displayId)
    }

    override fun injectAnalogStick(
        centerX: Float, centerY: Float,
        deltaX: Float, deltaY: Float,
        pointerId: Int, displayId: Int
    ) {
        val targetX = centerX + deltaX
        val targetY = centerY + deltaY
        touchInjector.analogMove(pointerId, centerX, centerY, targetX, targetY, displayId)
    }

    override fun releaseAnalogStick(pointerId: Int, displayId: Int) {
        touchInjector.touchUp(pointerId, displayId)
    }

    // ============================================================
    // Anti-ban helpers
    // ============================================================
    private fun applyCoordinateJitterX(x: Float): Float {
        if (!antiBan.enabled || antiBan.coordinateJitter <= 0f) return x
        return x + (rng.nextFloat() - 0.5f) * 2f * antiBan.coordinateJitter
    }

    private fun applyCoordinateJitterY(y: Float): Float {
        if (!antiBan.enabled || antiBan.coordinateJitter <= 0f) return y
        return y + (rng.nextFloat() - 0.5f) * 2f * antiBan.coordinateJitter
    }

    private fun applyTimingJitter() {
        if (!antiBan.enabled || antiBan.timingJitterMs <= 0) return
        val delay = rng.nextInt(0, antiBan.timingJitterMs * 2) - antiBan.timingJitterMs
        if (delay > 0) {
            try {
                Thread.sleep(delay.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun setAntiBanConfig(
        enabled: Boolean,
        coordinateJitter: Float,
        timingJitterMs: Int,
        pressureVariance: Float,
        sizeVariance: Float
    ) {
        antiBan = AntiBanConfig(enabled, coordinateJitter, timingJitterMs, pressureVariance, sizeVariance)
        touchInjector.setAntiBan(enabled, pressureVariance, sizeVariance)
        Log.d(TAG, "Anti-ban config updated: enabled=$enabled jitter=${coordinateJitter}px timing=${timingJitterMs}ms")
    }

    // ============================================================
    // Gamepad reading via evdev — runs in this shell-privilege process.
    // Uses getevent -l to read /dev/input/event* which requires
    // shell (UID 2000) or root (UID 0) access.
    //
    // Note: The contract mentions InputManager.registerInputDeviceListener,
    // but that API only fires for input devices that the system dispatches
    // to the app. In a UserService process, we don't receive InputDevice
    // callbacks because we're not a standard app process. Evdev (getevent)
    // is the correct approach for reading raw gamepad input in a
    // shell-privilege process.
    // ============================================================

    @Volatile
    private var evdevThread: Thread? = null

    @Volatile
    private var evdevProcess: Process? = null

    @Volatile
    private var evdevListening = false

    // Callback to emit events back to app process
    @Volatile
    private var eventCallback: ((eventType: String, buttonName: String, value: Int) -> Unit)? = null

    fun setEventCallback(cb: (eventType: String, buttonName: String, value: Int) -> Unit) {
        eventCallback = cb
    }

    override fun startGamepadRead(): Boolean {
        if (evdevListening) {
            Log.d(TAG, "Gamepad read already running")
            return true
        }

        Log.i(TAG, "Starting evdev gamepad reader (shell privilege)...")
        evdevListening = true

        evdevThread = Thread {
            try {
                val pb = ProcessBuilder("sh", "-c", "getevent -l")
                pb.redirectErrorStream(true)
                evdevProcess = pb.start()
                val reader = BufferedReader(InputStreamReader(evdevProcess!!.inputStream))
                var line: String? = null

                var lStickX = 0f
                var lStickY = 0f
                var rStickX = 0f
                var rStickY = 0f
                var l2Analog = -1f
                var r2Analog = -1f

                val axisRanges = mutableMapOf<String, IntArray>()

                while (evdevListening && reader.readLine().also { line = it } != null) {
                    val raw = line ?: continue
                    try {
                        if (raw.startsWith("add device") || raw.trim().startsWith("name:")) {
                            if (raw.trim().startsWith("name:")) {
                                val name = raw.trim().removePrefix("name:").trim().trim('"')
                                if (name.isNotEmpty()) {
                                    eventCallback?.invoke("CONTROLLER_ID", name, 1)
                                }
                            }
                            continue
                        }

                        if (raw.contains("EV_ABS")) {
                            val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size >= 4) {
                                val axisType = parts[2]
                                val valueHex = parts[3]
                                val valueInt = java.lang.Long.parseLong(valueHex, 16).toInt()

                                // Normalize axis value to [-1, 1]
                                var min = 0
                                var max = 255
                                val range = axisRanges[axisType]
                                if (range != null) { min = range[0]; max = range[1] }
                                if (valueInt < min) { min = valueInt; axisRanges[axisType] = intArrayOf(min, max) }
                                if (valueInt > max) { max = valueInt; axisRanges[axisType] = intArrayOf(min, max) }
                                val span = (max - min).coerceAtLeast(1)
                                val normalized = ((valueInt - min).toFloat() / span) * 2f - 1f

                                when (axisType) {
                                    "ABS_X" -> { lStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_Y" -> { lStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_Z", "ABS_RX" -> { rStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_RZ", "ABS_RY" -> { rStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_BRAKE" -> { l2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_GAS" -> { r2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_HAT0X", "ABS_HAT0Y" -> {
                                        val btnName = when {
                                            axisType == "ABS_HAT0X" && valueInt < 0 -> "LEFT"
                                            axisType == "ABS_HAT0X" && valueInt > 0 -> "RIGHT"
                                            axisType == "ABS_HAT0Y" && valueInt < 0 -> "UP"
                                            axisType == "ABS_HAT0Y" && valueInt > 0 -> "DOWN"
                                            else -> return@let
                                        }
                                        val isDown = valueInt != 0
                                        eventCallback?.invoke("BUTTON", btnName, if (isDown) 1 else 0)
                                    }
                                }
                            }
                        } else if (raw.contains("EV_KEY")) {
                            val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size >= 4) {
                                val btnRaw = parts[2]
                                val stateStr = parts[3]
                                val isDown = if (stateStr == "DOWN") 1 else 0
                                val btnMap = mapEvdevToButton(btnRaw)
                                if (btnMap != "UNKNOWN") {
                                    eventCallback?.invoke("BUTTON", btnMap, isDown)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore parse errors on individual lines
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Evdev gamepad read failed", e)
            } finally {
                evdevListening = false
                try { evdevProcess?.destroy() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }

        evdevThread?.start()
        return true
    }

    private fun emitAxis(lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        // Pack axis values into a string for callback
        // Format: "lx,ly,rx,ry,l2,r2"
        val axisStr = "$lx,$ly,$rx,$ry,$l2,$r2"
        eventCallback?.invoke("AXIS", axisStr, 0)
    }

    override fun stopGamepadRead(): Boolean {
        Log.i(TAG, "Stopping evdev gamepad reader")
        evdevListening = false
        try { evdevProcess?.destroy() } catch (_: Exception) {}
        try { evdevThread?.join(1000) } catch (_: InterruptedException) {}
        evdevProcess = null
        evdevThread = null
        return true
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_SOUTH") || evdevName.contains("BTN_A") || evdevName.contains("BTN_GAMEPAD") -> "A"
            evdevName.contains("BTN_EAST") || evdevName.contains("BTN_B") -> "B"
            evdevName.contains("BTN_NORTH") || evdevName.contains("BTN_X") -> "X"
            evdevName.contains("BTN_WEST") || evdevName.contains("BTN_Y") -> "Y"
            evdevName.contains("BTN_TL") || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR") || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_TL2") || evdevName.contains("BTN_LT") -> "L2"
            evdevName.contains("BTN_TR2") || evdevName.contains("BTN_RT") -> "R2"
            evdevName.contains("BTN_THUMBL") -> "L3"
            evdevName.contains("BTN_THUMBR") -> "R3"
            evdevName.contains("BTN_START") -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("BTN_MODE") -> "MODE"
            evdevName.contains("BTN_DPAD_UP") -> "UP"
            evdevName.contains("BTN_DPAD_DOWN") -> "DOWN"
            evdevName.contains("BTN_DPAD_LEFT") -> "LEFT"
            evdevName.contains("BTN_DPAD_RIGHT") -> "RIGHT"
            else -> "UNKNOWN"
        }
    }
}
