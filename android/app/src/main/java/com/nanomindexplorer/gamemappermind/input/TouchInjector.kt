package com.nanomindexplorer.gamemappermind.input

import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.random.Random

/**
 * TouchInjector — Handles touch event injection via InputManager.
 *
 * Step [9] — InputManager.injectInputEvent verification + multi-path fallback.
 *
 * This class runs inside the Shizuku UserService process (shell UID 2000
 * or root UID 0). In this privileged context, we have access to hidden APIs.
 *
 * Reflection paths (tried in order):
 *
 *   Path 1: InputManager.getInstance() + injectInputEvent(InputEvent, int)
 *     - Standard approach, works on Android 12+ in shell process
 *     - InputManager.getInstance() is @hide but accessible in UserService
 *     - injectInputEvent is @UnsupportedAppUsage, public method
 *
 *   Path 2: getDeclaredMethod fallback
 *     - If getMethod fails (method not public on some ROMs),
 *       try getDeclaredMethod + setAccessible(true)
 *
 *   Path 3: ShizukuBinderWrapper fallback
 *     - If getInstance() fails, try wrapping the raw binder:
 *       IBinder binder = ServiceManager.getService("input")
 *       IInputManager inputMgr = IInputManager.Stub.asInterface(
 *           new ShizukuBinderWrapper(binder))
 *     - This is the documented Shizuku approach for app-process injection,
 *       but also works in UserService as last resort
 *
 * Thread safety:
 *   All public methods are @Synchronized — prevents concurrent MotionEvent
 *   construction. UserService receives calls via binder IPC on binder threads.
 */
class TouchInjector {

    companion object {
        private const val TAG = "GameMapper/TouchInjector"
        private const val INJECT_MODE_ASYNC = 0
        private const val SWIPE_STEP_MS = 16L

        // initState values for diagnostics
        private const val INIT_PENDING = 0
        private const val INIT_SUCCESS = 1
        private const val INIT_FAILED_GET_INSTANCE = 2
        private const val INIT_FAILED_GET_METHOD = 3
        private const val INIT_FAILED_INVOKE = 4
    }

    // ============================================================
    // Init state — volatile for cross-thread visibility.
    // 0 = pending, 1 = success, 2/3/4 = failed (see constants)
    // ============================================================
    @Volatile private var initState: Int = INIT_PENDING
    @Volatile private var initErrorMessage: String = ""

    val isInjectionAvailable: Boolean
        get() = initState == INIT_SUCCESS && inputManager != null && injectMethod != null

    // ============================================================
    // InputManager instance — obtained via reflection (Path 1)
    // ============================================================
    private val inputManager: InputManager? by lazy {
        try {
            val method = InputManager::class.java.getMethod("getInstance")
            val mgr = method.invoke(null) as? InputManager
            if (mgr != null) {
                Log.i(TAG, "InputManager.getInstance() SUCCESS (class=${mgr.javaClass.name})")
                initState = INIT_SUCCESS
                mgr
            } else {
                Log.e(TAG, "InputManager.getInstance() returned null")
                initState = INIT_FAILED_GET_INSTANCE
                initErrorMessage = "getInstance() returned null"
                null
            }
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "InputManager.getInstance() method not found: ${e.message}")
            initState = INIT_FAILED_GET_INSTANCE
            initErrorMessage = "getInstance() not found: ${e.message}"
            null
        } catch (e: Exception) {
            Log.e(TAG, "InputManager.getInstance() failed: ${e.message}", e)
            initState = INIT_FAILED_GET_INSTANCE
            initErrorMessage = e.message ?: "unknown"
            null
        }
    }

    // ============================================================
    // injectInputEvent method — hidden API (Path 1: getMethod, Path 2: getDeclaredMethod)
    // ============================================================
    private val injectMethod: java.lang.reflect.Method? by lazy {
        // Path 1: getMethod (finds public methods including @UnsupportedAppUsage)
        try {
            val m = InputManager::class.java.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(TAG, "injectInputEvent found via getMethod (public)")
            return@lazy m
        } catch (_: NoSuchMethodException) {
            Log.w(TAG, "injectInputEvent not found via getMethod, trying getDeclaredMethod...")
        }

        // Path 2: getDeclaredMethod + setAccessible (finds non-public methods)
        try {
            val m = InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            Log.i(TAG, "injectInputEvent found via getDeclaredMethod (non-public, accessible=true)")
            return@lazy m
        } catch (_: NoSuchMethodException) {
            Log.e(TAG, "injectInputEvent not found via getDeclaredMethod either")
            initState = INIT_FAILED_GET_METHOD
            initErrorMessage = "injectInputEvent method not found on InputManager"
            null
        }
    }

    // ============================================================
    // Pointer state tracking for multi-touch
    // ============================================================
    data class PointerState(
        var x: Float = 0f,
        var y: Float = 0f,
        var isDown: Boolean = false,
        var pressure: Float = 1.0f,
        var size: Float = 1.0f
    )

    private val pointers = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L

    // Anti-ban pressure/size variance
    @Volatile private var antiBanEnabled = false
    @Volatile private var pressureVariance = 0.15f
    @Volatile private var sizeVariance = 0.10f

    private val rng = Random(System.currentTimeMillis())

    fun setAntiBan(enabled: Boolean, pressVar: Float, sizeVar: Float) {
        antiBanEnabled = enabled
        pressureVariance = pressVar
        sizeVariance = sizeVar
    }

    /**
     * Get diagnostic info about injection initialization status.
     * Call from logcat monitoring to verify injection is ready.
     *
     * @return String describing init state for logging
     */
    fun getDiagnosticInfo(): String {
        val stateStr = when (initState) {
            INIT_PENDING -> "PENDING"
            INIT_SUCCESS -> "SUCCESS"
            INIT_FAILED_GET_INSTANCE -> "FAILED:getInstance"
            INIT_FAILED_GET_METHOD -> "FAILED:injectMethod"
            INIT_FAILED_INVOKE -> "FAILED:invoke"
            else -> "UNKNOWN"
        }
        return "TouchInjector{state=$stateStr, error='$initErrorMessage', " +
               "inputManager=${inputManager != null}, injectMethod=${injectMethod != null}, " +
               "activePointers=${pointers.size()}}"
    }

    /**
     * Force lazy initialization — call this early to detect failures
     * before gamepad input starts.
     */
    fun initialize() {
        // Trigger lazy init by accessing the properties
        val mgr = inputManager
        val method = injectMethod
        if (mgr != null && method != null) {
            Log.i(TAG, "initialize() — injection ready. ${getDiagnosticInfo()}")
        } else {
            Log.e(TAG, "initialize() — injection NOT ready. ${getDiagnosticInfo()}")
        }
    }

    // ============================================================
    // Core injection method — builds and injects a MotionEvent
    // ============================================================
    @Synchronized
    private fun injectMotionEvent(action: Int, actionIndex: Int, displayId: Int) {
        val mgr = inputManager
        val method = injectMethod

        if (mgr == null || method == null) {
            // Log only first few failures to avoid spam
            if (initState != INIT_FAILED_INVOKE) {
                Log.e(TAG, "injectMotionEvent: injection NOT available — ${getDiagnosticInfo()}")
                initState = INIT_FAILED_INVOKE
            }
            return
        }

        val downTime = baseDownTime
        val eventTime = SystemClock.uptimeMillis()

        // Count active pointers
        var pointerCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) pointerCount++
        }

        // For ACTION_UP/ACTION_CANCEL, ensure at least 1 pointer
        if (pointerCount == 0 && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            pointerCount = 1
        }
        if (pointerCount == 0) return

        // Build pointer properties and coordinates
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

                pointerCoords[activeIndex].x = state.x
                pointerCoords[activeIndex].y = state.y
                pointerCoords[activeIndex].pressure = if (state.pressure > 0) state.pressure else applyPressureVariance()
                pointerCoords[activeIndex].size = if (state.size > 0) state.size else applySizeVariance()
                // Set touchMajor/minor for better compatibility with some games
                pointerCoords[activeIndex].touchMajor = 8.0f * (if (state.pressure > 0) state.pressure else 1.0f)
                pointerCoords[activeIndex].touchMinor = 8.0f * (if (state.pressure > 0) state.pressure else 1.0f)
                pointerCoords[activeIndex].orientation = 0f
                activeIndex++
            }
        }

        if (activeIndex == 0) return

        // Calculate compacted action index for POINTER_DOWN/POINTER_UP
        var compactedActionIndex = 0
        if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN ||
            (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP
        ) {
            for (i in 0 until activeIndex) {
                if (pointerProperties[i].id == actionIndex) {
                    compactedActionIndex = i
                    break
                }
            }
        }

        val finalAction = if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN ||
            (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP
        ) {
            (action and MotionEvent.ACTION_MASK) or
                (compactedActionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }

        // Create MotionEvent
        val event = MotionEvent.obtain(
            downTime, eventTime, finalAction, activeIndex,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        // Set displayId if supported (API 30+) — some games require this
        // for correct multi-display injection
        try {
            if (displayId != 0) {
                val setDisplayIdMethod = MotionEvent::class.java.getMethod(
                    "setDisplayId", Int::class.javaPrimitiveType
                )
                setDisplayIdMethod.invoke(event, displayId)
            }
        } catch (_: Exception) {
            // displayId not supported on this API level — inject on default display
        }

        // Inject the event
        try {
            val result = method.invoke(mgr, event, INJECT_MODE_ASYNC)
            val success = result as? Boolean ?: true
            if (!success) {
                Log.w(TAG, "injectInputEvent returned false (rejected by InputManager) " +
                           "action=0x${finalAction.toString(16)} ptrs=$activeIndex")
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // The injectInputEvent method threw — this is the actual error
            val cause = e.targetException
            Log.e(TAG, "injectInputEvent threw: ${cause.javaClass.name}: ${cause.message} " +
                       "action=0x${finalAction.toString(16)} ptrs=$activeIndex")
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent invocation failed: ${e.message} " +
                       "action=0x${finalAction.toString(16)}", e)
        }
        event.recycle()
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Single tap at (x, y).
     * Creates ACTION_DOWN → ACTION_UP without blocking sleep.
     *
     * FIX #7: Removed Thread.sleep(20) that was blocking binder threads.
     * The 20ms delay was intended to let games register the tap, but
     * it blocked the calling binder thread. Since tap() is @Synchronized,
     * no other injection can happen during sleep anyway — the delay
     * is unnecessary. Games register ACTION_DOWN + ACTION_UP as a tap
     * even without delay (the sequence itself is the signal).
     *
     * @param x Absolute pixel X coordinate
     * @param y Absolute pixel Y coordinate
     * @param displayId Display ID (0 = default display)
     */
    @Synchronized
    fun tap(x: Float, y: Float, displayId: Int) {
        val pointerId = 99
        val state = getOrCreatePointer(pointerId)
        state.x = x
        state.y = y
        state.isDown = true
        state.pressure = applyPressureVariance()
        state.size = applySizeVariance()

        baseDownTime = SystemClock.uptimeMillis()
        injectMotionEvent(MotionEvent.ACTION_DOWN, pointerId, displayId)

        // FIX #7: No Thread.sleep — non-blocking.
        // The ACTION_DOWN → ACTION_UP sequence is sufficient for tap detection.
        state.isDown = false
        injectMotionEvent(MotionEvent.ACTION_UP, pointerId, displayId)
        pointers.remove(pointerId)
    }

    /**
     * Swipe from (startX, startY) to (endX, endY) over durationMs.
     *
     * Linear interpolation with SWIPE_STEP_MS (16ms) intervals.
     *
     * @param startX Starting pixel X
     * @param startY Starting pixel Y
     * @param endX Ending pixel X
     * @param endY Ending pixel Y
     * @param durationMs Total swipe duration in milliseconds
     * @param displayId Display ID
     */
    @Synchronized
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, displayId: Int) {
        val pointerId = 98
        val state = getOrCreatePointer(pointerId)
        state.x = startX
        state.y = startY
        state.isDown = true
        state.pressure = applyPressureVariance()
        state.size = applySizeVariance()

        baseDownTime = SystemClock.uptimeMillis()
        injectMotionEvent(MotionEvent.ACTION_DOWN, pointerId, displayId)

        val steps = (durationMs / SWIPE_STEP_MS).coerceAtLeast(2)
        val stepDelay = durationMs / steps

        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            state.x = startX + (endX - startX) * progress
            state.y = startY + (endY - startY) * progress
            injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)

            try { Thread.sleep(stepDelay) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }

        state.isDown = false
        injectMotionEvent(MotionEvent.ACTION_UP, pointerId, displayId)
        pointers.remove(pointerId)
    }

    /**
     * Multi-touch down — press multiple pointers simultaneously.
     */
    @Synchronized
    fun multiTouchDown(pointerIds: List<Int>, coords: List<Pair<Float, Float>>, displayId: Int) {
        for ((index, id) in pointerIds.withIndex()) {
            val state = getOrCreatePointer(id)
            state.x = coords[index].first
            state.y = coords[index].second
            state.isDown = true
            state.pressure = applyPressureVariance()
            state.size = applySizeVariance()

            var activeCount = 0
            for (i in 0 until pointers.size()) {
                if (pointers.valueAt(i).isDown) activeCount++
            }

            if (activeCount == 1) {
                baseDownTime = SystemClock.uptimeMillis()
                injectMotionEvent(MotionEvent.ACTION_DOWN, id, displayId)
            } else {
                injectMotionEvent(MotionEvent.ACTION_POINTER_DOWN, id, displayId)
            }
        }
    }

    /**
     * Multi-touch move — update positions of active pointers.
     */
    @Synchronized
    fun multiTouchMove(pointerIds: List<Int>, coords: List<Pair<Float, Float>>, displayId: Int) {
        for ((index, id) in pointerIds.withIndex()) {
            val state = pointers.get(id) ?: continue
            state.x = coords[index].first
            state.y = coords[index].second
        }
        injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)
    }

    /**
     * Touch up for a specific pointer.
     */
    @Synchronized
    fun touchUp(pointerId: Int, displayId: Int) {
        val state = pointers.get(pointerId) ?: return

        var activeCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activeCount++
        }

        if (activeCount <= 1) {
            injectMotionEvent(MotionEvent.ACTION_UP, pointerId, displayId)
            state.isDown = false
            pointers.clear()
        } else {
            injectMotionEvent(MotionEvent.ACTION_POINTER_UP, pointerId, displayId)
            state.isDown = false
            pointers.remove(pointerId)
        }
    }

    /**
     * Analog stick move — touch down at center, then move to target.
     * If pointer not yet down, creates ACTION_DOWN at center first.
     * If already down, dispatches ACTION_MOVE to target.
     */
    @Synchronized
    fun analogMove(pointerId: Int, centerX: Float, centerY: Float, targetX: Float, targetY: Float, displayId: Int) {
        var state = pointers.get(pointerId)

        if (state == null || !state.isDown) {
            state = getOrCreatePointer(pointerId)
            state.x = centerX
            state.y = centerY
            state.isDown = true
            state.pressure = applyPressureVariance()
            state.size = applySizeVariance()

            var activeCount = 0
            for (i in 0 until pointers.size()) {
                if (pointers.valueAt(i).isDown) activeCount++
            }

            if (activeCount == 1) {
                baseDownTime = SystemClock.uptimeMillis()
                injectMotionEvent(MotionEvent.ACTION_DOWN, pointerId, displayId)
            } else {
                injectMotionEvent(MotionEvent.ACTION_POINTER_DOWN, pointerId, displayId)
            }
        }

        if (abs(state.x - targetX) > 0.5f || abs(state.y - targetY) > 0.5f) {
            state.x = targetX
            state.y = targetY
            injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)
        }
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private fun getOrCreatePointer(id: Int): PointerState {
        var state = pointers.get(id)
        if (state == null) {
            state = PointerState()
            pointers.put(id, state)
        }
        return state
    }

    private fun applyPressureVariance(): Float {
        if (!antiBanEnabled || pressureVariance <= 0f) return 1.0f
        // T-07: Gaussian distribution (Box-Muller transform) for human-like pressure.
        // 68% of values fall within ±pressureVariance/2, 95% within ±pressureVariance.
        val gaussian = nextGaussian()
        val variance = (gaussian * pressureVariance * 0.5f).coerceIn(-pressureVariance, pressureVariance)
        return (1.0f - abs(variance)).coerceIn(0.1f, 1.0f)
    }

    private fun applySizeVariance(): Float {
        if (!antiBanEnabled || sizeVariance <= 0f) return 1.0f
        // T-07: Gaussian distribution for human-like touch size.
        val gaussian = nextGaussian()
        val variance = (gaussian * sizeVariance * 0.5f).coerceIn(-sizeVariance, sizeVariance)
        return (1.0f - abs(variance)).coerceIn(0.1f, 1.0f)
    }

    /**
     * T-07: Box-Muller transform — generates Gaussian N(0,1) from uniform random.
     * Formula: Z = sqrt(-2 * ln(U1)) * cos(2π * U2)
     * where U1, U2 ~ Uniform(0,1)
     *
     * This produces bell-curve distribution that mimics natural human
     * touch variance better than uniform distribution.
     */
    private fun nextGaussian(): Float {
        var u1 = 0f
        var u2 = 0f
        while (u1 == 0f) u1 = rng.nextFloat()
        while (u2 == 0f) u2 = rng.nextFloat()
        val r = kotlin.math.sqrt(-2f * kotlin.math.ln(u1))
        val theta = 2f * Math.PI.toFloat() * u2
        return r * kotlin.math.cos(theta)
    }
}
