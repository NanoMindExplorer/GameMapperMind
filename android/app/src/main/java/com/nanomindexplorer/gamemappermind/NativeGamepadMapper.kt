package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "GameMapper"

class NativeGamepadMapper(private val context: Context) {

    companion object {
        @Volatile var instance: NativeGamepadMapper? = null
        val syncLock = Any()

        // FIX (root cause of "RB tekan → analog berhenti", general delay, "analog nyangkut"):
        // every touchDown/touchMove/touchUp/injectTap call is a SYNCHRONOUS cross-process
        // Binder call into the Shizuku daemon. Previously these ran INLINE on the single
        // thread that also decides button/axis state (GamepadJniPlugin's injectionThread) —
        // so one slow injection (very commonly a button, since those go through more
        // interaction-type branching) blocked ALL subsequent processing on that thread,
        // including fresh stick-movement updates, until the Binder call returned. A held
        // button could stall stick input for its entire hold duration.
        // Fix: dispatch the actual AIDL call onto its own dedicated background thread,
        // decoupled from the decision-making thread, which now only does fast in-memory
        // work (pointer allocation, deadzone/smoothing math, lastState edge-detection) and
        // is never blocked waiting on IPC.

        // FIX v2 (root cause of "analog berhenti sejenak saat tombol lain ditekan"):
        // The previous fix used a SINGLE aidlThread for ALL touch calls (analog move AND
        // button down/up). Since Binder calls are 5-15ms each and the queue is strict FIFO,
        // a burst of button events (DOWN + multiple BTN_GAMEPAD meta events + UP) would
        // pile up in front of pending touchMove calls — the stick appeared to "freeze" for
        // 50-100ms every time a button was tapped during movement.
        //
        // Solution: TWO separate HandlerThreads.
        //   - stickAidlHandler: HIGH priority, processes touchDown/touchMove/touchUp for
        //     analog pointers only. Coalesces consecutive moves (only the latest move per
        //     pointer is actually sent — intermediate positions are dropped, since a fast-
        //     moving stick only cares about the final position per frame anyway).
        //   - buttonAidlHandler: LOWER priority, processes touchDown/touchUp/injectTap for
        //     button pointers. A 10-20ms delay on a button press is imperceptible; a 10-20ms
        //     delay on stick movement is VERY perceptible (the "pemain diam sejenak" bug).
        //
        // Both threads can execute Binder calls in parallel — Android's InputManager accepts
        // concurrent injectInputEvent calls for different pointer IDs without serialization.
        private val stickAidlThread = android.os.HandlerThread("StickAidlDispatch").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        private val stickAidlHandler = Handler(stickAidlThread.looper)

        private val buttonAidlThread = android.os.HandlerThread("ButtonAidlDispatch").also {
            it.priority = Thread.NORM_PRIORITY
            it.start()
        }
        private val buttonAidlHandler = Handler(buttonAidlThread.looper)

        // Coalescing: at most one pending move per pointer ID. When a new move is posted
        // for a pointer that already has a pending move queued, the pending one is removed
        // and replaced — so only the LATEST position reaches the daemon. This caps stick
        // move latency at one Binder call regardless of how fast getevent fires.
        private val pendingMoveRunnables = mutableMapOf<Int, Runnable>()

        // Generational epoch counter per pointer ID (64 pointers max: 4 gamepads * 16 pointers).
        // Incremented on every cancelPendingStickMove() and touchUp().
        // Guarantees that any in-flight or dequeued stick move runnable aborts immediately if its
        // epoch does not match, completely preventing stale touchMove calls from executing after release.
        private val stickEpochs = java.util.concurrent.atomic.AtomicLongArray(64)

        fun dispatchStickCall(block: () -> Unit) {
            stickAidlHandler.post(block)
        }

        fun dispatchButtonCall(block: () -> Unit) {
            buttonAidlHandler.post(block)
        }

        // Coalesced stick move: replaces any pending move for the same pointer ID with
        // this newer one. Guarantees the daemon only sees the most recent position per
        // pointer, eliminating the "stale moves piling up in the queue" delay.
        fun dispatchStickMove(pointerId: Int, block: () -> Unit) {
            val epoch = if (pointerId in 0 until 64) stickEpochs.get(pointerId) else 0L
            synchronized(pendingMoveRunnables) {
                pendingMoveRunnables[pointerId]?.let { stickAidlHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (pointerId in 0 until 64 && stickEpochs.get(pointerId) != epoch) {
                        synchronized(pendingMoveRunnables) { pendingMoveRunnables.remove(pointerId) }
                        return@Runnable
                    }
                    synchronized(pendingMoveRunnables) { pendingMoveRunnables.remove(pointerId) }
                    block()
                }
                pendingMoveRunnables[pointerId] = runnable
                stickAidlHandler.post(runnable)
            }
        }

        // Cancels any queued stick move for this pointer ID so that it doesn't execute
        // after touchUp (e.g. when returning to deadzone or resetting pointers).
        fun cancelPendingStickMove(pointerId: Int) {
            if (pointerId in 0 until 64) {
                stickEpochs.incrementAndGet(pointerId)
            }
            synchronized(pendingMoveRunnables) {
                pendingMoveRunnables.remove(pointerId)?.let { stickAidlHandler.removeCallbacks(it) }
            }
        }

        // Legacy entry point — keep for back-compat with existing callers. Routes to the
        // button queue by default (most existing call sites are button touchDown/touchUp).
        fun dispatchTouchCall(block: () -> Unit) {
            dispatchButtonCall(block)
        }

        fun resetAll() {
            synchronized(syncLock) {
                instance?.stopGyroListener()
                instance?.cancelAllActiveInteractions()
                instance?.pointers?.forEach {
                    if (it.isActive) {
                        it.isActive = false
                        val pid = it.id
                        if (it.type == "analog") {
                            cancelPendingStickMove(pid)
                        }
                        val handler = if (it.type == "analog") ::dispatchStickCall else ::dispatchButtonCall
                        handler {
                            try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { instance?.logInjectFailure("touchUp", pid, e) }
                        }
                    }
                }
                instance?.lastState?.clear()
                instance?.smoothedAxes?.forEach { it.fill(0f) }
                instance?.buildMapCache()
            }
        }
    }

    // FIX: isActive/virtualKey are now written from BOTH the decision thread (allocation) and
    // the background aidlThread (failure rollback after a dispatched call fails) — @Volatile
    // for correct cross-thread visibility. No compound check-then-act race is introduced since
    // allocation decisions (the only place these are read-then-written together) stay
    // exclusively on the single decision thread; the background thread only ever does a plain
    // rollback write (isActive = false) on failure.
    class PointerState(val id: Int, @Volatile var isActive: Boolean, val type: String, @Volatile var virtualKey: String? = null)

    val pointers = mutableListOf<PointerState>().apply {
        for (gp in 0..3) {
            val offset = gp * 16
            add(PointerState(offset + 0, false, "analog"))
            add(PointerState(offset + 1, false, "analog"))
            for (i in 2..15) add(PointerState(offset + i, false, "button"))
        }
    }

    private val pointersById: Array<PointerState?> = Array(64) { null }

    init {
        for (p in pointers) pointersById[p.id] = p
    }

    val lastState = mutableMapOf<String, Boolean>()
    private val smoothedAxes = Array(4) { FloatArray(4) }

    // Tracks whether the last touchMove for a pointer failed, so the hot path
    // (processStick) logs a failure once per streak instead of every frame.
    private val moveFailWarned = mutableMapOf<Int, Boolean>()

    private fun logInjectFailure(action: String, pointerId: Int, e: Exception) {
        Log.w(TAG, "Touch injection failed: $action pointer=$pointerId (${e.javaClass.simpleName}: ${e.message})")
    }

    var buttonMapCache = mutableMapOf<String, JSONObject>()
    private var triggerMapCache = mutableMapOf<String, MutableList<JSONObject>>()

    private val turboRunnables = mutableMapOf<String, Runnable>()
    private val toggleState = mutableMapOf<String, Boolean>()
    private val chargeTimestamps = mutableMapOf<String, Long>()
    private val activeMacros = mutableMapOf<String, Runnable>()
    private val activeMacroPointers = mutableMapOf<String, Int>()
    private val activeSwipes = mutableMapOf<String, Runnable>()
    private val activeSwipePointers = mutableMapOf<String, Int>()
    private val activeGestures = mutableMapOf<String, Runnable>()
    private val activeGesturePointers = mutableMapOf<String, Int>()

    private val macroDefinitions = mutableMapOf<String, JSONObject>()
    private val macroTriggerMap = mutableMapOf<String, MutableList<JSONObject>>()
    private var lastRecordEventTime = 0L

    // Anti-snapback & stick release damping per pointer (64 slots)
    private val lastStickReleaseTime = LongArray(64)
    private val lastHighDeflection = BooleanArray(64)

    fun cancelAllActiveInteractions() {
        turboRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        turboRunnables.clear()

        activeMacros.values.forEach { mainHandler.removeCallbacks(it) }
        activeMacros.clear()
        activeMacroPointers.values.forEach { pid ->
            pointersById[pid]?.let {
                it.isActive = false
                it.virtualKey = null
            }
            dispatchButtonCall {
                try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (_: Exception) {}
            }
        }
        activeMacroPointers.clear()

        activeSwipes.values.forEach { mainHandler.removeCallbacks(it) }
        activeSwipes.clear()
        activeSwipePointers.values.forEach { pid ->
            pointersById[pid]?.let {
                it.isActive = false
                it.virtualKey = null
            }
            dispatchButtonCall {
                try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (_: Exception) {}
            }
        }
        activeSwipePointers.clear()

        activeGestures.values.forEach { mainHandler.removeCallbacks(it) }
        activeGestures.clear()
        activeGesturePointers.values.forEach { pid ->
            pointersById[pid]?.let {
                it.isActive = false
                it.virtualKey = null
            }
            dispatchButtonCall {
                try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (_: Exception) {}
            }
        }
        activeGesturePointers.clear()
    }

    // Macro Recording
    private var isRecordingMacro = false
    private var currentRecordingId: String? = null
    private val recordedMacros = mutableMapOf<String, MutableList<JSONObject>>()

    // Profile & Scene
    var currentProfileId: String = "default"
    var currentScene: String = "default"

    // Screen calibration cache — read once per buildMapCache() call, applied in
    // getScreenCoords(). Defaults to 0 (legacy full-screen behavior, no change for anyone
    // who hasn't calibrated).
    @Volatile private var screenInsetTop = 0.0
    @Volatile private var screenInsetBottom = 0.0
    @Volatile private var screenInsetLeft = 0.0
    @Volatile private var screenInsetRight = 0.0

    // ==================== NATIVE GYRO AIMING ====================
    @Volatile var gyroSensitivity: Float = 0f
    @Volatile var gyroInvertX: Boolean = false
    @Volatile var gyroInvertY: Boolean = false
    @Volatile var gyroDeadzone: Float = 0.02f
    @Volatile private var latestGyroX: Float = 0f
    @Volatile private var latestGyroY: Float = 0f
    @Volatile private var currentRawRx: Float = 0f
    @Volatile private var currentRawRy: Float = 0f
    @Volatile private var isGyroActive: Boolean = false

    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    @Volatile private var isGyroRegistered = false
    private val sensorLock = Any()

    private val gyroEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
                val vals = event.values ?: return
                if (vals.size >= 3) {
                    handleGyroSensorEvent(vals[0], vals[1], vals[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startGyroListener() {
        synchronized(sensorLock) {
            if (isGyroRegistered) return
            try {
                if (sensorManager == null) {
                    sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                }
                if (gyroSensor == null) {
                    gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                }
                if (gyroSensor != null) {
                    val registered = sensorManager?.registerListener(
                        gyroEventListener,
                        gyroSensor,
                        SensorManager.SENSOR_DELAY_GAME,
                        stickAidlHandler
                    ) ?: false
                    isGyroRegistered = registered
                    Log.i(TAG, "Native gyro sensor registered on stickAidlHandler: $registered (sensitivity=$gyroSensitivity)")
                } else {
                    Log.w(TAG, "Native gyro sensor not available on this device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register native gyro listener", e)
            }
        }
    }

    fun stopGyroListener() {
        synchronized(sensorLock) {
            if (!isGyroRegistered) return
            try {
                sensorManager?.unregisterListener(gyroEventListener)
                isGyroRegistered = false
                latestGyroX = 0f
                latestGyroY = 0f
                isGyroActive = false
                Log.i(TAG, "Native gyro sensor unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister native gyro listener", e)
            }
        }
    }

    fun getOrientationAdjustedGyro(gx: Float, gy: Float, gz: Float, rotation: Int): Pair<Float, Float> {
        return when (rotation) {
            Surface.ROTATION_90 -> Pair(-gy, gx)   // Standard Landscape: -Y is turn right (X+), +X is tilt down (Y+)
            Surface.ROTATION_270 -> Pair(gy, -gx)  // Reverse Landscape
            Surface.ROTATION_180 -> Pair(-gx, -gy) // Reverse Portrait
            else -> Pair(gz, gx)                  // Portrait
        }
    }

    private fun handleGyroSensorEvent(gx: Float, gy: Float, gz: Float) {
        if (gyroSensitivity <= 0f) return

        val rotation = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_90
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_90
            }
        } catch (_: Exception) {
            Surface.ROTATION_90
        }

        val (screenGyroX, screenGyroY) = getOrientationAdjustedGyro(gx, gy, gz, rotation)
        val rawMag = sqrt(screenGyroX * screenGyroX + screenGyroY * screenGyroY)

        if (rawMag < gyroDeadzone) {
            latestGyroX = 0f
            latestGyroY = 0f
            if (isGyroActive) {
                isGyroActive = false
                dispatchGyroStickUpdate()
            }
            return
        }

        val signX = if (gyroInvertX) -1f else 1f
        val signY = if (gyroInvertY) -1f else 1f

        latestGyroX = (screenGyroX * gyroSensitivity * signX).coerceIn(-1f, 1f)
        latestGyroY = (screenGyroY * gyroSensitivity * signY).coerceIn(-1f, 1f)
        isGyroActive = true

        dispatchGyroStickUpdate()
    }

    private fun findGyroAreaMapping(): JSONObject? {
        for (b in buttonMapCache.values) {
            if (b.optString("type") == "gyro_area") {
                return b
            }
        }
        return null
    }

    private fun dispatchGyroStickUpdate() {
        synchronized(syncLock) {
            if (TouchInjectionPlugin.touchService == null) return
            val rMap = findButtonMapping("R_STICK") ?: findGyroAreaMapping() ?: return
            val rAlpha = 1f - (rMap.optDouble("smoothing", 0.0).toFloat()).coerceIn(0f, 0.95f)

            val combX = (currentRawRx + latestGyroX).coerceIn(-1f, 1f)
            val combY = (currentRawRy + latestGyroY).coerceIn(-1f, 1f)

            processStick(
                combX,
                combY,
                rMap,
                smoothedAxes[0],
                2,
                rAlpha,
                pointersById[1] ?: pointers[1],
                150f
            )
        }
    }

    fun buildMapCache() {
        buttonMapCache.clear()
        triggerMapCache.clear()
        val jsonStr = GamepadListenerService.activeProfileJson ?: return
        if (jsonStr.isEmpty() || jsonStr == "{}") return

        try {
            val root = JSONObject(jsonStr)
            screenInsetTop = root.optDouble("screenInsetTop", 0.0).coerceIn(0.0, 45.0)
            screenInsetBottom = root.optDouble("screenInsetBottom", 0.0).coerceIn(0.0, 45.0)
            screenInsetLeft = root.optDouble("screenInsetLeft", 0.0).coerceIn(0.0, 45.0)
            screenInsetRight = root.optDouble("screenInsetRight", 0.0).coerceIn(0.0, 45.0)

            gyroSensitivity = root.optDouble("gyroSensitivity", 0.0).toFloat().coerceIn(0f, 10f)
            gyroInvertX = root.optBoolean("gyroInvertX", false)
            gyroInvertY = root.optBoolean("gyroInvertY", false)
            gyroDeadzone = root.optDouble("gyroDeadzone", 0.02).toFloat().coerceIn(0.001f, 0.5f)

            if (gyroSensitivity > 0f) {
                startGyroListener()
            } else {
                stopGyroListener()
            }

            macroDefinitions.clear()
            macroTriggerMap.clear()
            val macrosArray = root.optJSONArray("macros")
            if (macrosArray != null) {
                for (mIdx in 0 until macrosArray.length()) {
                    val mObj = macrosArray.optJSONObject(mIdx) ?: continue
                    val mId = mObj.optString("id")
                    if (mId.isNotEmpty()) {
                        macroDefinitions[mId] = mObj
                    }
                    val triggerKey = mObj.optString("triggerKey", "").trim()
                    if (triggerKey.isNotEmpty()) {
                        val upKey = triggerKey.uppercase()
                        macroTriggerMap.getOrPut(triggerKey) { mutableListOf() }.add(mObj)
                        macroTriggerMap.getOrPut(upKey) { mutableListOf() }.add(mObj)
                        if (!upKey.startsWith("BUTTON_")) {
                            macroTriggerMap.getOrPut("BUTTON_$upKey") { mutableListOf() }.add(mObj)
                        } else {
                            macroTriggerMap.getOrPut(upKey.removePrefix("BUTTON_")) { mutableListOf() }.add(mObj)
                        }
                    }
                }
            }

            val buttons = root.optJSONArray("buttons") ?: return

            for (i in 0 until buttons.length()) {
                val b = buttons.optJSONObject(i) ?: continue
                val key = b.optString("mappedKey")
                if (key.isNotEmpty() && key != "null") {
                    buttonMapCache[key] = b
                }

                val trigger = b.optJSONObject("trigger")
                if (trigger != null) {
                    val inputs = trigger.optJSONArray("inputs")
                    inputs?.let {
                        for (j in 0 until it.length()) {
                            val input = it.optString(j)
                            if (input.isNotEmpty()) {
                                triggerMapCache.getOrPut(input) { mutableListOf() }.add(b)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildMapCache failed", e)
        }
    }

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val mapperInjectionThread = android.os.HandlerThread("MapperInjection").also { it.start() }
    private val mainHandler = Handler(mapperInjectionThread.looper)

    init {
        instance?.let { old ->
            old.turboRunnables.values.forEach { old.mainHandler.removeCallbacks(it) }
            old.turboRunnables.clear()
            old.stopGyroListener()
        }
        instance = this
        buildMapCache()
    }

    // FIX: previously mapped percentages directly onto the full physical screen bounds with
    // no way to compensate for a game that doesn't render truly edge-to-edge (visible status
    // bar, letterboxing, aspect-ratio mismatch) — a button placed at "90% from top" in the
    // editor could land at a visibly different relative position inside the actual game.
    // screenInsetTop/Bottom/Left/Right (calibrated per-profile, see GameSelector.tsx) now
    // remap the 0-100% editor space onto the actual game play-area rectangle within the
    // screen. All insets default to 0, so uncalibrated profiles behave exactly as before.
    private fun getScreenCoords(pctX: Double, pctY: Double): Pair<Float, Float> {
        return try {
            val bounds = windowManager.currentWindowMetrics.bounds
            val usableWidthFrac = (100.0 - screenInsetLeft - screenInsetRight) / 100.0
            val usableHeightFrac = (100.0 - screenInsetTop - screenInsetBottom) / 100.0
            val effectivePctX = screenInsetLeft + (pctX / 100.0) * usableWidthFrac * 100.0
            val effectivePctY = screenInsetTop + (pctY / 100.0) * usableHeightFrac * 100.0
            Pair(
                ((effectivePctX / 100.0) * bounds.width()).toFloat(),
                ((effectivePctY / 100.0) * bounds.height()).toFloat()
            )
        } catch (e: Exception) {
            Pair(1080f, 1920f)
        }
    }

    // FIX: previously referenced by GamepadListenerService's notification "Test Tap" action
    // and by TouchDaemonService.testInjection(), but never actually defined anywhere — a
    // straight-up compile error once a real Kotlin build was run (this sandbox can only
    // typecheck the TS side, not compile Kotlin, so it slipped through prior patches).
    // Runs a real diagnostic tap at screen center via the AIDL binder and returns the
    // daemon's structured JSON report (success / active injection path / error).
    fun runDiagnosticTestTap(): String {
        return try {
            val ts = TouchInjectionPlugin.touchService
                ?: return "{\"error\":\"Shizuku/TouchService not connected\"}"
            val (x, y) = getScreenCoords(50.0, 50.0)
            ts.testInjection(x, y)
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
    }

    private fun findButtonMapping(mappedKey: String): JSONObject? = buttonMapCache[mappedKey]

    private fun getAntiBanOffset(enabled: Boolean): Pair<Float, Float> {
        if (!enabled) return Pair(0f, 0f)
        val radius = Random.nextFloat() * 8f
        val angle = Random.nextFloat() * (2 * Math.PI).toFloat()
        return Pair((radius * cos(angle.toDouble())).toFloat(), (radius * sin(angle.toDouble())).toFloat())
    }

    // ==================== RADIAL DEADZONE ====================

    private fun applyRadialDeadzone(x: Float, y: Float, deadzone: Float): Pair<Float, Float> {
        val magnitude = sqrt(x * x + y * y)
        if (magnitude <= deadzone) return Pair(0f, 0f)
        val scale = (magnitude - deadzone) / (1f - deadzone)
        return Pair((x / magnitude) * scale, (y / magnitude) * scale)
    }

    private fun processStick(
        rawX: Float, rawY: Float,
        mapping: JSONObject?,
        smoothBuffer: FloatArray,
        smoothOffset: Int,
        alpha: Float,
        pointer: PointerState,
        defaultRadius: Float
    ) {
        if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
            if (pointer.isActive) {
                pointer.isActive = false
                val pid = pointer.id
                // FIX: analog pointer release goes on the stick queue (high priority) so a
                // stick release isn't delayed behind queued button events.
                dispatchStickCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
            }
            return
        }

        val deadzone = mapping.optDouble("deadzone", 0.12).toFloat()
        // If pointer is right stick (id == 1) and physical stick is in deadzone while gyro is active,
        // use gyroDeadzone to allow ultra-fine sniper micro-aiming without requiring large tilts.
        val effectiveDeadzone = if (pointer.id == 1 && currentRawRx == 0f && currentRawRy == 0f && isGyroActive) {
            gyroDeadzone
        } else {
            deadzone
        }

        // FIX (root cause of "analog nyangkut ke bawah"): deadzone check previously ran on
        // the SMOOTHED magnitude. When the stick was released (raw → 0), the smoothed value
        // decayed exponentially over several frames before dropping below deadzone — during
        // that decay the touch position kept drifting toward center (visually "stuck moving
        // downward" if the stick had been pushed up). Checking deadzone on the RAW input
        // makes release immediate: the moment the physical stick returns inside the deadzone
        // circle, touchUp fires and the smoothing buffer is reset to zero. Smoothing is now
        // only applied to non-deadzone input, so it never creates release lag.
        val rawInputMag = sqrt(rawX * rawX + rawY * rawY)
        val pid = pointer.id

        // Schmitt-trigger deadzone hysteresis:
        // When active, require stick to fall below 88% of deadzone to disengage.
        // Prevents rapid high-frequency flutter on the deadzone boundary due to potentiometer noise.
        val releaseThreshold = if (pointer.isActive) {
            (effectiveDeadzone * 0.88f).coerceAtLeast(0.005f)
        } else {
            effectiveDeadzone
        }

        if (rawInputMag <= releaseThreshold) {
            if (pointer.isActive) {
                // Cancel any pending coalesced move for this pointer before dispatching touchUp
                cancelPendingStickMove(pid)
                // FIX: analog pointer release goes on the stick queue (high priority).
                dispatchStickCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
                pointer.isActive = false
                if (pid in 0 until 64) {
                    lastStickReleaseTime[pid] = android.os.SystemClock.uptimeMillis()
                }
            }
            smoothBuffer[smoothOffset] = 0f
            smoothBuffer[smoothOffset + 1] = 0f
            return
        }

        // Anti-snapback filter: if stick was just released from high deflection (< 40ms ago),
        // suppress mechanical spring bounce-back in the opposite quadrant.
        if (!pointer.isActive && pid in 0 until 64) {
            val elapsedSinceRelease = android.os.SystemClock.uptimeMillis() - lastStickReleaseTime[pid]
            if (elapsedSinceRelease < 40L && rawInputMag < (effectiveDeadzone * 1.6f) && lastHighDeflection[pid]) {
                lastHighDeflection[pid] = false
                return
            }
        }
        if (rawInputMag > 0.65f && pid in 0 until 64) {
            lastHighDeflection[pid] = true
        }

        // Apply smoothing only for non-deadzone input — keeps movement smooth without
        // delaying release.
        smoothBuffer[smoothOffset] = alpha * rawX + (1 - alpha) * smoothBuffer[smoothOffset]
        smoothBuffer[smoothOffset + 1] = alpha * rawY + (1 - alpha) * smoothBuffer[smoothOffset + 1]

        val sx = smoothBuffer[smoothOffset]
        val sy = smoothBuffer[smoothOffset + 1]
        val rawMag = sqrt(sx * sx + sy * sy)

        val (dzX, dzY) = applyRadialDeadzone(sx, sy, effectiveDeadzone)
        val rescaledMag = sqrt(dzX * dzX + dzY * dzY).coerceIn(0f, 1f)

        val curve = mapping.optString("sensitivityCurve", "linear")
        val curvedMag = applyCurve(rescaledMag, curve, mapping.optJSONArray("curvePoints"))

        val sensitivity = mapping.optDouble("sensitivity", 1.0).toFloat().coerceIn(0.1f, 5.0f)
        val finalMag = (curvedMag * sensitivity).coerceIn(0f, 1f)

        val (cX, cY) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))

        val (tX, tY) = if (mapping.optString("stickMode", "joystick") == "drag") {
            val (screenW, screenH) = try {
                val b = windowManager.currentWindowMetrics.bounds
                Pair(b.width().toFloat(), b.height().toFloat())
            } catch (_: Exception) { Pair(1080f, 1920f) }
            Pair(
                (cX + dzX * finalMag * screenW * 0.35f).coerceIn(0f, screenW),
                (cY + dzY * finalMag * screenH * 0.35f).coerceIn(0f, screenH)
            )
        } else {
            val invMag = if (rawMag > 0.0001f) 1f / rawMag else 0f
            val outX = (dzX * invMag) * finalMag * mapping.optDouble("radius", defaultRadius.toDouble()).toFloat()
            val outY = (dzY * invMag) * finalMag * mapping.optDouble("radius", defaultRadius.toDouble()).toFloat()
            Pair(cX + outX, cY + outY)
        }

        if (!pointer.isActive) {
            pointer.isActive = true
            val pid = pointer.id
            val downX = cX + ox; val downY = cY + oy
            // FIX: analog pointer DOWN goes on the stick queue (high priority).
            dispatchStickCall {
                try {
                    TouchInjectionPlugin.touchService?.touchDown(pid, downX, downY)
                } catch (e: Exception) {
                    pointer.isActive = false
                    logInjectFailure("touchDown", pid, e)
                }
            }
        }
        if (pointer.isActive) {
            val pid = pointer.id
            val moveX = tX + ox; val moveY = tY + oy
            // FIX v2 (root cause of "pemain diam sejenak saat tombol lain ditekan"):
            // COALESCED dispatch — only the LATEST pending move per pointer reaches the
            // daemon. Previously every getevent axis sample queued a separate Binder call;
            // at 100-200Hz stick reporting that built up a 5-15 call backlog, each blocking
            // the next. Now if a new move arrives before the previous one was processed,
            // the older one is removed from the queue and only the newest position is sent.
            // This caps stick move latency at one Binder call (~10ms) regardless of input
            // rate, and decouples analog responsiveness from whatever button events are
            // happening concurrently on the separate buttonAidlHandler queue.
            dispatchStickMove(pid) {
                try {
                    val moved = TouchInjectionPlugin.touchService?.touchMove(pid, moveX, moveY) ?: false
                    if (moved) {
                        if (moveFailWarned[pid] == true) moveFailWarned[pid] = false
                    } else if (moveFailWarned[pid] != true) {
                        moveFailWarned[pid] = true
                        Log.w(TAG, "Touch injection returned false: touchMove pointer=$pid (stick drag likely stuck on shell-fallback Path C)")
                    }
                } catch (e: Exception) {
                    if (moveFailWarned[pid] != true) {
                        moveFailWarned[pid] = true
                        logInjectFailure("touchMove", pid, e)
                    }
                }
            }
        }
    }

    // ==================== IMPROVED TRIGGER ====================

    private fun handleTrigger(gamepadIndex: Int, triggerName: String, value: Float) {
        val wasActive = lastState[triggerName + gamepadIndex] ?: false
        val pressThreshold = 0.08f
        val releaseThreshold = 0.04f

        val isActive = if (wasActive) value > releaseThreshold else value > pressThreshold
        if (isActive != wasActive) {
            // FIX: Emit button event so canvas/UI can highlight LT/RT when pressed.
            // Previously only emitGamepadAxis was called (axis data), so the WYSIWYG
            // canvas never knew when triggers were "pressed" — only analog stick
            // movement was visible. Now emit a button event with value 1 (pressed)
            // or 0 (released) so the canvas lights up, same as A/B/X/Y buttons.
            TouchInjectionPlugin.emitGamepadButton(triggerName, if (isActive) 1 else 0, value)
            handleButton(gamepadIndex, triggerName, isActive)
        }
    }

    fun handleAxes(gamepadIndex: Int, lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        synchronized(syncLock) {
            if (gamepadIndex !in 0..3) return
            if (TouchInjectionPlugin.touchService == null) return

            val offset = (gamepadIndex % 4) * 16
            val lMap = findButtonMapping("L_STICK")
            val rMap = findButtonMapping("R_STICK") ?: findGyroAreaMapping()

            val lAlpha = 1f - (lMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 0.95f)
            val rAlpha = 1f - (rMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 0.95f)

            processStick(lx, ly, lMap, smoothedAxes[gamepadIndex], 0, lAlpha, pointersById[offset] ?: pointers[0], 100f)

            if (gamepadIndex == 0) {
                currentRawRx = rx
                currentRawRy = ry
                val effRx = (rx + latestGyroX).coerceIn(-1f, 1f)
                val effRy = (ry + latestGyroY).coerceIn(-1f, 1f)
                processStick(effRx, effRy, rMap, smoothedAxes[gamepadIndex], 2, rAlpha, pointersById[offset + 1] ?: pointers[1], 150f)
            } else {
                processStick(rx, ry, rMap, smoothedAxes[gamepadIndex], 2, rAlpha, pointersById[offset + 1] ?: pointers[1], 150f)
            }

            handleTrigger(gamepadIndex, "LT", l2)
            handleTrigger(gamepadIndex, "RT", r2)
        }
    }

    // ==================== HOTPLUG ====================

    fun resetGamepad(gamepadIndex: Int) {
        if (gamepadIndex !in 0..3) return
        synchronized(syncLock) {
            val offset = gamepadIndex * 16
            for (i in 0 until 16) {
                val p = pointersById[offset + i]
                if (p != null && p.isActive) {
                    val pid = p.id
                    if (p.type == "analog") {
                        cancelPendingStickMove(pid)
                    }
                    val handler = if (p.type == "analog") ::dispatchStickCall else ::dispatchButtonCall
                    handler {
                        try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                    }
                    p.isActive = false
                    p.virtualKey = null
                }
            }
            lastState.keys.removeAll { it.endsWith(gamepadIndex.toString()) }
            smoothedAxes[gamepadIndex].fill(0f)
            Log.i(TAG, "Gamepad $gamepadIndex reset (hotplug)")
        }
    }

    // ==================== HANDLE BUTTON ====================

    fun handleButton(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        synchronized(syncLock) {
            if (gamepadIndex !in 0..3) return
            val offset = gamepadIndex * 16
            // Guard: bail out early if the Shizuku touch service isn't bound. All actual
            // touch calls below go through dispatchTouchCall, which reads
            // TouchInjectionPlugin.touchService directly (not this local), so we only need
            // the null check here — no need to keep a reference.
            if (TouchInjectionPlugin.touchService == null) return

            val wasDown = lastState[buttonName + gamepadIndex] ?: false
            lastState[buttonName + gamepadIndex] = isDown

            if (triggerMapCache.containsKey(buttonName)) {
                evaluateTriggerMappings(buttonName, gamepadIndex, isDown, offset)
                val legacy = findButtonMapping(buttonName)
                if (legacy?.optJSONObject("trigger") == null) {
                    lastState[buttonName + gamepadIndex] = wasDown
                } else return
            }

            // Check standalone macros bound to physical gamepad buttons via triggerKey
            val macroList = macroTriggerMap[buttonName]
            if (macroList != null && isDown) {
                for (mObj in macroList) {
                    handleMacro(mObj, offset)
                }
            }

            val mapping = findButtonMapping(buttonName)
            if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
                if (!isDown && wasDown) {
                    val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                        .find { it.isActive && it.virtualKey == buttonName }
                    if (p != null) {
                        p.isActive = false
                        p.virtualKey = null
                        val pid = p.id
                        // FIX: async dispatch — previously synchronous ts.touchUp blocked
                        // the decision thread (same thread that processes axis events),
                        // causing "analog berhenti saat dikombo dengan tombol lain".
                        dispatchTouchCall {
                            try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                        }
                    }
                }
                return
            }

            // FIX (root cause of "tombol A, LT, RT tidak menginjeksi sentuhan sama sekali"):
            // The old code only honored `interactionType` (tap/turbo/toggle/charge/macro)
            // for buttons that had a `trigger` object — buttons without a trigger always
            // fell through to a hardcoded "hold" path (or, if tapDuration>0, a synchronous
            // `ts.injectTap()` that internally used pointer ID 0 — the SAME pointer used by
            // L_STICK — hijacking the analog stick's pointer and breaking both the tap AND
            // the stick in one shot).
            //
            // The UI lets users set interactionType on ANY button without requiring a
            // trigger, so the native side must honor it universally. Now every button
            // dispatches through `dispatchInteraction`, which routes to handleTap /
            // handleTurbo / handleToggle / handleCharge / handleMacro / handleHoldInteraction
            // based on `interactionType`. All of those handlers already use `dispatchTouchCall`
            // for async injection, so button presses no longer block the decision thread
            // (fixing "analog berhenti saat dikombo dengan tombol lain").
            dispatchInteraction(mapping, gamepadIndex, isDown, offset)
        }
    }

    // ==================== INTERACTION HANDLERS ====================

    private fun evaluateTriggerMappings(buttonName: String, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        val mappings = triggerMapCache[buttonName] ?: return
        for (mapping in mappings) {
            val nodeId = mapping.optString("id", "")
            if (nodeId.isEmpty()) continue

            val trigger = mapping.optJSONObject("trigger")
            val isChord = trigger != null && trigger.optString("type") == "chord"

            if (isDown) {
                if (isChord && !isChordActive(mapping, gamepadIndex)) continue
                dispatchInteraction(mapping, gamepadIndex, true, offset)
            } else {
                dispatchInteraction(mapping, gamepadIndex, false, offset)
            }
        }
    }

    private fun isChordActive(mapping: JSONObject, gamepadIndex: Int): Boolean {
        val trigger = mapping.optJSONObject("trigger") ?: return true
        val inputs = trigger.optJSONArray("inputs") ?: return true
        for (i in 0 until inputs.length()) {
            if (!(lastState[inputs.optString(i) + gamepadIndex] ?: false)) return false
        }
        return true
    }

    private fun dispatchInteraction(mapping: JSONObject, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        when (mapping.optString("interactionType", "hold")) {
            "tap" -> if (isDown) handleTap(mapping, offset)
            "turbo" -> handleTurbo(mapping, mapping.optString("id"), isDown, gamepadIndex)
            "toggle" -> handleToggle(mapping, mapping.optString("id"), isDown, offset)
            "charge" -> handleCharge(mapping, mapping.optString("id"), isDown, offset)
            "swipe" -> if (isDown) handleSwipe(mapping, offset)
            "gesture" -> if (isDown) handleGesture(mapping, offset)
            "macro" -> if (isDown) handleMacro(mapping, offset)
            else -> handleHoldInteraction(mapping, isDown, offset)
        }
    }

    private fun handleTap(mapping: JSONObject, offset: Int) {
        val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        val tapDuration = mapping.optLong("tapDuration", 60L)
        dispatchTouchCall {
            try {
                TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
            } catch (e: Exception) {
                Log.w(TAG, "handleTap failed: ${e.message}")
            }
        }
    }

    private fun handleTurbo(mapping: JSONObject, nodeId: String, isDown: Boolean, gamepadIndex: Int) {
        if (isDown) {
            turboRunnables[nodeId]?.let { mainHandler.removeCallbacks(it) }
            val intervalMs = mapping.optLong("repeatIntervalMs", 50L)
            val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
            val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
            val tapDuration = mapping.optLong("tapDuration", 30L)

            val runnable = object : Runnable {
                override fun run() {
                    // FIX: previously called injectTap() directly on mainHandler (the UI
                    // thread) — since injectTap blocks for its full duration on the daemon
                    // side per AIDL call, this meant every turbo repeat briefly blocked the
                    // UI thread too, on top of the same "blocks other input" problem shared
                    // with every other direct touchService call in this file.
                    dispatchTouchCall {
                        try {
                            TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                        } catch (e: Exception) {
                            Log.w(TAG, "turbo failed: ${e.message}")
                        }
                    }
                    mainHandler.postDelayed(this, intervalMs)
                }
            }
            turboRunnables[nodeId] = runnable
            runnable.run()
        } else {
            turboRunnables[nodeId]?.let { mainHandler.removeCallbacks(it) }
            turboRunnables.remove(nodeId)
        }
    }

    private fun handleToggle(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        if (!isDown) return
        val isToggled = toggleState[nodeId] ?: false
        val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))

        if (!isToggled) {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { !it.isActive && it.type == "button" }
            if (p != null) {
                p.isActive = true
                p.virtualKey = "toggle_$nodeId"
                val pid = p.id
                dispatchButtonCall {
                    try { TouchInjectionPlugin.touchService?.touchDown(pid, x + ox, y + oy) } catch (e: Exception) { logInjectFailure("touchDown", pid, e) }
                }
            }
            toggleState[nodeId] = true
        } else {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { it.isActive && it.virtualKey == "toggle_$nodeId" }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                val pid = p.id
                dispatchButtonCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
            }
            toggleState[nodeId] = false
        }
    }

    private fun handleCharge(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        if (isDown) {
            chargeTimestamps[nodeId] = System.currentTimeMillis()
        } else {
            val pressTime = chargeTimestamps[nodeId] ?: return
            val heldMs = System.currentTimeMillis() - pressTime
            val threshold = mapping.optLong("chargeThresholdMs", 500L)
            chargeTimestamps.remove(nodeId)

            if (heldMs >= threshold) {
                val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
                val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
                val tapDuration = mapping.optLong("tapDuration", 60L)
                dispatchTouchCall {
                    try {
                        TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                    } catch (e: Exception) {
                        Log.w(TAG, "charge tap failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleHoldInteraction(mapping: JSONObject, isDown: Boolean, offset: Int) {
        val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        // FIX: use BOTH id and mappedKey in the nodeId to guarantee uniqueness across
        // buttons. Previously only `mapping.id` was used — if a profile had a button with
        // empty/missing id, nodeId became "hold_" and multiple such buttons would share the
        // same virtualKey, causing the UP event of one to release the pointer of another.
        // Including mappedKey (which is always present for mapped buttons) disambiguates.
        val mappedKey = mapping.optString("mappedKey", "")
        val nodeId = "hold_${mapping.optString("id", "")}_$mappedKey"

        if (isDown) {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { !it.isActive && it.type == "button" }
            if (p != null) {
                p.isActive = true
                p.virtualKey = nodeId
                val pid = p.id
                // FIX: this is the single most impactful site for "RB tekan → analog kiri/
                // kanan berhenti" — RB (and most ordinary buttons) use the default "hold"
                // interaction type, so THIS touchDown was almost certainly the exact call
                // blocking the shared decision thread while a stick was actively moving.
                dispatchButtonCall {
                    try { TouchInjectionPlugin.touchService?.touchDown(pid, x + ox, y + oy) } catch (e: Exception) {
                        p.isActive = false
                        logInjectFailure("touchDown", pid, e)
                    }
                }
            }
        } else {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { it.isActive && it.virtualKey == nodeId }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                val pid = p.id
                dispatchButtonCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
            }
        }
    }

    // ==================== SWIPE & GESTURE SYSTEM ====================

    private fun handleSwipe(mapping: JSONObject, offset: Int) {
        val swipeId = mapping.optString("id", "")
        if (swipeId.isEmpty()) return

        activeSwipes[swipeId]?.let {
            mainHandler.removeCallbacks(it)
            activeSwipes.remove(swipeId)
        }
        activeSwipePointers.remove(swipeId)?.let { oldPid ->
            pointersById[oldPid]?.let {
                it.isActive = false
                it.virtualKey = null
            }
            dispatchButtonCall {
                try { TouchInjectionPlugin.touchService?.touchUp(oldPid) } catch (_: Exception) {}
            }
        }

        val p = (offset + 2..offset + 15).mapNotNull { pointersById[it] }.find { !it.isActive } ?: return
        p.isActive = true
        p.virtualKey = "swipe_$swipeId"
        val pid = p.id
        activeSwipePointers[swipeId] = pid

        val startPctX = mapping.getDouble("x")
        val startPctY = mapping.getDouble("y")
        val (startX, startY) = getScreenCoords(startPctX, startPctY)
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))

        val endPctX = if (mapping.has("swipeEndX")) mapping.getDouble("swipeEndX") else {
            when (mapping.optString("swipeDirection", "UP").uppercase()) {
                "LEFT" -> (startPctX - 25.0).coerceAtLeast(0.0)
                "RIGHT" -> (startPctX + 25.0).coerceAtMost(100.0)
                else -> startPctX
            }
        }
        val endPctY = if (mapping.has("swipeEndY")) mapping.getDouble("swipeEndY") else {
            when (mapping.optString("swipeDirection", "UP").uppercase()) {
                "UP" -> (startPctY - 25.0).coerceAtLeast(0.0)
                "DOWN" -> (startPctY + 25.0).coerceAtMost(100.0)
                else -> startPctY
            }
        }
        val (endX, endY) = getScreenCoords(endPctX, endPctY)
        val durationMs = mapping.optLong("swipeDuration", 200L).coerceIn(50L, 2000L)
        val swipeReturn = mapping.optBoolean("swipeReturn", false)

        val totalFrames = (durationMs / 16L).coerceAtLeast(4L).toInt()
        var currentFrame = 0

        dispatchButtonCall {
            try {
                TouchInjectionPlugin.touchService?.touchDown(pid, startX + ox, startY + oy)
            } catch (e: Exception) {
                p.isActive = false
                p.virtualKey = null
                activeSwipePointers.remove(swipeId)
                logInjectFailure("touchDown", pid, e)
            }
        }

        val swipeRunnable = object : Runnable {
            override fun run() {
                if (!p.isActive) {
                    activeSwipes.remove(swipeId)
                    activeSwipePointers.remove(swipeId)
                    return
                }
                currentFrame++
                val maxFrames = if (swipeReturn) totalFrames * 2 else totalFrames
                if (currentFrame <= totalFrames) {
                    val t = currentFrame.toFloat() / totalFrames.toFloat()
                    val curX = startX + (endX - startX) * t
                    val curY = startY + (endY - startY) * t
                    dispatchButtonCall {
                        try { TouchInjectionPlugin.touchService?.touchMove(pid, curX + ox, curY + oy) }
                        catch (e: Exception) { logInjectFailure("touchMove", pid, e) }
                    }
                    mainHandler.postDelayed(this, 16L)
                } else if (swipeReturn && currentFrame <= maxFrames) {
                    val returnFrame = currentFrame - totalFrames
                    val t = returnFrame.toFloat() / totalFrames.toFloat()
                    val curX = endX + (startX - endX) * t
                    val curY = endY + (startY - endY) * t
                    dispatchButtonCall {
                        try { TouchInjectionPlugin.touchService?.touchMove(pid, curX + ox, curY + oy) }
                        catch (e: Exception) { logInjectFailure("touchMove", pid, e) }
                    }
                    mainHandler.postDelayed(this, 16L)
                } else {
                    dispatchButtonCall {
                        try { TouchInjectionPlugin.touchService?.touchUp(pid) }
                        catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                    }
                    p.isActive = false
                    p.virtualKey = null
                    activeSwipes.remove(swipeId)
                    activeSwipePointers.remove(swipeId)
                }
            }
        }
        activeSwipes[swipeId] = swipeRunnable
        mainHandler.postDelayed(swipeRunnable, 16L)
    }

    private fun handleGesture(mapping: JSONObject, offset: Int) {
        val gestureId = mapping.optString("id", "")
        if (gestureId.isEmpty()) return

        activeGestures[gestureId]?.let {
            mainHandler.removeCallbacks(it)
            activeGestures.remove(gestureId)
        }
        activeGesturePointers.remove(gestureId)?.let { oldPid ->
            pointersById[oldPid]?.let {
                it.isActive = false
                it.virtualKey = null
            }
            dispatchButtonCall {
                try { TouchInjectionPlugin.touchService?.touchUp(oldPid) } catch (_: Exception) {}
            }
        }

        val points = mapping.optJSONArray("gesturePoints") ?: return
        if (points.length() == 0) return

        val p = (offset + 2..offset + 15).mapNotNull { pointersById[it] }.find { !it.isActive } ?: return
        p.isActive = true
        p.virtualKey = "gesture_$gestureId"
        val pid = p.id
        activeGesturePointers[gestureId] = pid

        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        var pointIndex = 0

        val firstPoint = points.getJSONObject(0)
        val (firstX, firstY) = getScreenCoords(firstPoint.getDouble("x"), firstPoint.getDouble("y"))
        dispatchButtonCall {
            try {
                TouchInjectionPlugin.touchService?.touchDown(pid, firstX + ox, firstY + oy)
            } catch (e: Exception) {
                p.isActive = false
                p.virtualKey = null
                activeGesturePointers.remove(gestureId)
                logInjectFailure("touchDown", pid, e)
            }
        }

        val gestureRunnable = object : Runnable {
            override fun run() {
                if (!p.isActive) {
                    activeGestures.remove(gestureId)
                    activeGesturePointers.remove(gestureId)
                    return
                }
                pointIndex++
                if (pointIndex < points.length()) {
                    val pt = points.getJSONObject(pointIndex)
                    val (x, y) = getScreenCoords(pt.getDouble("x"), pt.getDouble("y"))
                    val delay = pt.optLong("delayMs", 50L).coerceAtLeast(16L)
                    dispatchButtonCall {
                        try { TouchInjectionPlugin.touchService?.touchMove(pid, x + ox, y + oy) }
                        catch (e: Exception) { logInjectFailure("touchMove", pid, e) }
                    }
                    mainHandler.postDelayed(this, delay)
                } else {
                    dispatchButtonCall {
                        try { TouchInjectionPlugin.touchService?.touchUp(pid) }
                        catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                    }
                    p.isActive = false
                    p.virtualKey = null
                    activeGestures.remove(gestureId)
                    activeGesturePointers.remove(gestureId)
                }
            }
        }
        activeGestures[gestureId] = gestureRunnable
        val initialDelay = firstPoint.optLong("delayMs", 50L).coerceAtLeast(16L)
        mainHandler.postDelayed(gestureRunnable, initialDelay)
    }

    // ==================== UNIFIED MACRO SYSTEM ====================

    private fun handleMacro(mapping: JSONObject, offset: Int) {
        val macroId = mapping.optString("id", "")
        if (macroId.isEmpty()) return

        // Toggle off if currently running
        if (activeMacros.containsKey(macroId)) {
            activeMacros[macroId]?.let { mainHandler.removeCallbacks(it) }
            activeMacros.remove(macroId)
            activeMacroPointers.remove(macroId)?.let { oldPid ->
                pointersById[oldPid]?.let {
                    it.isActive = false
                    it.virtualKey = null
                }
                dispatchButtonCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(oldPid) } catch (_: Exception) {}
                }
            }
            return
        }

        // Resolve steps: check embedded macroSteps first, then referenced macroId
        var playbackSpeed = 1.0
        val steps = if (mapping.has("macroSteps")) {
            mapping.optJSONArray("macroSteps")
        } else {
            val refMacroId = mapping.optString("macroId", "")
            val macroDef = if (refMacroId.isNotEmpty()) macroDefinitions[refMacroId] else null
            playbackSpeed = macroDef?.optDouble("playbackSpeed", 1.0) ?: 1.0
            macroDef?.optJSONArray("actions")
        }

        if (steps == null || steps.length() == 0) return

        val p = (offset + 2..offset + 15).mapNotNull { pointersById[it] }.find { !it.isActive } ?: return
        p.isActive = true
        p.virtualKey = "macro_$macroId"
        val pid = p.id
        activeMacroPointers[macroId] = pid

        var currentStep = 0
        val effectiveSpeed = if (playbackSpeed > 0.0) playbackSpeed else 1.0

        val macroRunnable = object : Runnable {
            override fun run() {
                if (currentStep >= steps.length() || !p.isActive) {
                    dispatchButtonCall {
                        try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (_: Exception) {}
                    }
                    p.isActive = false
                    p.virtualKey = null
                    activeMacros.remove(macroId)
                    activeMacroPointers.remove(macroId)
                    return
                }

                val step = steps.optJSONObject(currentStep) ?: return
                val actionType = step.optString("type", step.optString("action", "tap")).lowercase()
                val rawX = step.optDouble("x", 50.0)
                val rawY = step.optDouble("y", 50.0)
                // Normalize 0-1000 scale to percentage 0-100
                val normX = if (rawX > 100.0) rawX / 10.0 else rawX
                val normY = if (rawY > 100.0) rawY / 10.0 else rawY

                val (screenX, screenY) = getScreenCoords(normX, normY)
                val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))

                val rawDelay = step.optLong("delayMs", 100L)
                val effectiveDelay = (rawDelay / effectiveSpeed).toLong().coerceAtLeast(16L)
                val duration = step.optLong("durationMs", 60L)

                when (actionType) {
                    "touch_down", "down" -> {
                        dispatchButtonCall {
                            try {
                                TouchInjectionPlugin.touchService?.touchDown(pid, screenX + ox, screenY + oy)
                            } catch (e: Exception) {
                                logInjectFailure("touchDown", pid, e)
                            }
                        }
                    }
                    "touch_move", "move" -> {
                        dispatchButtonCall {
                            try {
                                TouchInjectionPlugin.touchService?.touchMove(pid, screenX + ox, screenY + oy)
                            } catch (e: Exception) {
                                logInjectFailure("touchMove", pid, e)
                            }
                        }
                    }
                    "touch_up", "up" -> {
                        dispatchButtonCall {
                            try {
                                TouchInjectionPlugin.touchService?.touchUp(pid)
                            } catch (e: Exception) {
                                logInjectFailure("touchUp", pid, e)
                            }
                        }
                    }
                    "tap" -> {
                        dispatchButtonCall {
                            try {
                                TouchInjectionPlugin.touchService?.injectTap(screenX + ox, screenY + oy, duration)
                            } catch (e: Exception) {
                                logInjectFailure("injectTap", pid, e)
                            }
                        }
                    }
                    "delay" -> {
                        // Timing pause only
                    }
                }

                currentStep++
                mainHandler.postDelayed(this, effectiveDelay)
            }
        }

        activeMacros[macroId] = macroRunnable
        mainHandler.post(macroRunnable)
    }

    // ==================== MACRO RECORDING (for UI) ====================

    fun handleMacroPublic(mapping: JSONObject, offset: Int = 0) {
        handleMacro(mapping, offset)
    }

    fun startMacroRecording(macroId: String) {
        isRecordingMacro = true
        currentRecordingId = macroId
        recordedMacros[macroId] = mutableListOf()
        lastRecordEventTime = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "Started recording macro: $macroId")
    }

    fun stopMacroRecording(): org.json.JSONArray {
        isRecordingMacro = false
        val recId = currentRecordingId
        currentRecordingId = null
        val list = if (recId != null) recordedMacros.remove(recId) else null
        val arr = org.json.JSONArray()
        list?.forEach { arr.put(it) }
        Log.i(TAG, "Stopped macro recording: ${arr.length()} actions captured")
        return arr
    }

    fun recordMacroAction(type: String, x: Double, y: Double, delayMs: Long = 33L, pointerId: Int = 1) {
        val recId = currentRecordingId ?: return
        if (!isRecordingMacro) return
        val list = recordedMacros.getOrPut(recId) { mutableListOf() }
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = if (lastRecordEventTime > 0L) (now - lastRecordEventTime).coerceIn(16L, 5000L) else delayMs
        lastRecordEventTime = now
        val action = JSONObject().apply {
            put("id", "act_${System.currentTimeMillis()}_${list.size}")
            put("type", type)
            put("x", x)
            put("y", y)
            put("delayMs", elapsed)
            put("pointerId", pointerId)
        }
        list.add(action)
    }

    private fun applyCurve(x: Float, curveType: String?, curvePoints: org.json.JSONArray?): Float {
        if (curveType == null) return x
        val sign = kotlin.math.sign(x)
        val absX = kotlin.math.abs(x)
        return sign * when (curveType.lowercase()) {
            "exponential", "expo" -> {
                val k = 3.0
                ((Math.exp(k * absX.toDouble()) - 1) / (Math.exp(k) - 1)).toFloat()
            }
            "parabolic", "para" -> absX * absX
            "concave" -> kotlin.math.sqrt(absX)
            else -> absX
        }
    }
}
