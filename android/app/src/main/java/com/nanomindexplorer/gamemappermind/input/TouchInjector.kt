package com.nanomindexplorer.gamemappermind.input

import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import com.nanomindexplorer.gamemappermind.util.HarmonyOSHelper
import kotlin.math.abs
import kotlin.random.Random

/**
 * TouchInjector — Handles touch event injection via InputManager.
 *
 * GMM-AEC-002 enhancements:
 *   §9.5  HarmonyOS MotionEvent flags — FLAG_VIRTUAL + SOURCE_TOUCHSCREEN explicit
 *         + pressure variation 0.8-1.0 + size variation
 *   §11.2 eFootball Konami engine — Gaussian random delay 8-15ms between
 *         ACTION_DOWN -> ACTION_MOVE -> ACTION_UP (bukan instant DOWN->UP)
 *   §11.3 Analog stick multi-step interpolation — min 3 step ACTION_MOVE
 *         smooth (linear) dari posisi current ke target, bukan teleport
 */
class TouchInjector {

    companion object {
        private const val TAG = "GameMapper/TouchInjector"
        private const val INJECT_MODE_ASYNC = 0
        private const val SWIPE_STEP_MS = 16L

        // GMM-AEC-002 §11.2: eFootball Gaussian delay constants
        private const val GAUSSIAN_DELAY_MIN_MS = 8L
        private const val GAUSSIAN_DELAY_MAX_MS = 15L
        private const val GAUSSIAN_DELAY_MEAN_MS = 11.5
        private const val GAUSSIAN_DELAY_STDDEV_MS = 2.0

        // GMM-AEC-002 §11.3: Multi-step interpolation constants
        private const val MULTI_STEP_MIN = 3
        private const val MULTI_STEP_MAX = 8
        private const val MULTI_STEP_INTERVAL_MS = 4L

        // GMM-AEC-002 §9.5: HarmonyOS pressure/size variation
        private const val HARMONY_PRESSURE_MIN = 0.8f
        private const val HARMONY_PRESSURE_MAX = 1.0f
        private const val HARMONY_SIZE_MIN = 0.9f
        private const val HARMONY_SIZE_MAX = 1.1f

        // GMM-AEC-002 §9.5: FLAG_VIRTUAL untuk HarmonyOS
        private const val FLAG_VIRTUAL_VALUE = 0x40000000

        // initState values for diagnostics
        private const val INIT_PENDING = 0
        private const val INIT_SUCCESS = 1
        private const val INIT_FAILED_GET_INSTANCE = 2
        private const val INIT_FAILED_GET_METHOD = 3
        private const val INIT_FAILED_INVOKE = 4
    }

    @Volatile private var initState: Int = INIT_PENDING
    @Volatile private var initErrorMessage: String = ""

    val isInjectionAvailable: Boolean
        get() = initState == INIT_SUCCESS && inputManager != null && injectMethod != null

    // GMM-AEC-002 §9.1: Cache HarmonyOS detection result
    @Volatile
    private val isHarmonyOS: Boolean by lazy { HarmonyOSHelper.isHarmonyOS() }

    // GMM-AEC-002 §11.2: eFootball mode flag
    @Volatile
    private var efootballMode: Boolean = false

    fun setEfootballMode(enabled: Boolean) {
        efootballMode = enabled
        Log.i(TAG, "eFootball mode: $enabled (HarmonyOS=$isHarmonyOS)")
    }

    // InputManager instance — obtained via reflection (Path 1)
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

    private val injectMethod: java.lang.reflect.Method? by lazy {
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

    data class PointerState(
        var x: Float = 0f,
        var y: Float = 0f,
        var isDown: Boolean = false,
        var pressure: Float = 1.0f,
        var size: Float = 1.0f
    )

    private val pointers = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L

    @Volatile private var antiBanEnabled = false
    @Volatile private var pressureVariance = 0.15f
    @Volatile private var sizeVariance = 0.10f

    private val rng = Random(System.currentTimeMillis())

    fun setAntiBan(enabled: Boolean, pressVar: Float, sizeVar: Float) {
        antiBanEnabled = enabled
        pressureVariance = pressVar
        sizeVariance = sizeVar
    }

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
               "activePointers=${pointers.size()}, harmonyOS=$isHarmonyOS, efootballMode=$efootballMode}"
    }

    fun initialize() {
        val mgr = inputManager
        val method = injectMethod
        if (mgr != null && method != null) {
            Log.i(TAG, "initialize() — injection ready. ${getDiagnosticInfo()}")
        } else {
            Log.e(TAG, "initialize() — injection NOT ready. ${getDiagnosticInfo()}")
        }
    }

    // GMM-AEC-002 §11.2: Gaussian random delay helper (Box-Muller transform)
    private fun gaussianDelayMs(): Long {
        val u1 = (rng.nextInt(10000) + 1) / 10000.0
        val u2 = (rng.nextInt(10000) + 1) / 10000.0
        val z = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        val delay = GAUSSIAN_DELAY_MEAN_MS + z * GAUSSIAN_DELAY_STDDEV_MS
        return delay.toLong().coerceIn(GAUSSIAN_DELAY_MIN_MS, GAUSSIAN_DELAY_MAX_MS)
    }

    // GMM-AEC-002 §9.5: HarmonyOS pressure/size variation
    private fun applyPressureVariance(): Float {
        if (isHarmonyOS) {
            val base = HARMONY_PRESSURE_MIN + rng.nextFloat() * (HARMONY_PRESSURE_MAX - HARMONY_PRESSURE_MIN)
            return if (antiBanEnabled && pressureVariance > 0f) {
                (base - rng.nextFloat() * pressureVariance * 0.1f).coerceIn(HARMONY_PRESSURE_MIN, HARMONY_PRESSURE_MAX)
            } else {
                base
            }
        }
        if (!antiBanEnabled || pressureVariance <= 0f) return 1.0f
        return 1.0f - (rng.nextFloat() * pressureVariance)
    }

    private fun applySizeVariance(): Float {
        if (isHarmonyOS) {
            val base = HARMONY_SIZE_MIN + rng.nextFloat() * (HARMONY_SIZE_MAX - HARMONY_SIZE_MIN)
            return if (antiBanEnabled && sizeVariance > 0f) {
                (base - rng.nextFloat() * sizeVariance * 0.1f).coerceIn(HARMONY_SIZE_MIN, HARMONY_SIZE_MAX)
            } else {
                base
            }
        }
        if (!antiBanEnabled || sizeVariance <= 0f) return 1.0f
        return 1.0f - (rng.nextFloat() * sizeVariance)
    }

    @Synchronized
    private fun injectMotionEvent(action: Int, actionIndex: Int, displayId: Int) {
        val mgr = inputManager
        val method = injectMethod

        if (mgr == null || method == null) {
            if (initState != INIT_FAILED_INVOKE) {
                Log.e(TAG, "injectMotionEvent: injection NOT available — ${getDiagnosticInfo()}")
                initState = INIT_FAILED_INVOKE
            }
            return
        }

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

                pointerCoords[activeIndex].x = state.x
                pointerCoords[activeIndex].y = state.y
                pointerCoords[activeIndex].pressure = if (state.pressure > 0) state.pressure else applyPressureVariance()
                pointerCoords[activeIndex].size = if (state.size > 0) state.size else applySizeVariance()
                pointerCoords[activeIndex].touchMajor = 8.0f * (if (state.pressure > 0) state.pressure else 1.0f)
                pointerCoords[activeIndex].touchMinor = 8.0f * (if (state.pressure > 0) state.pressure else 1.0f)
                pointerCoords[activeIndex].orientation = 0f
                activeIndex++
            }
        }

        if (activeIndex == 0) return

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

        // GMM-AEC-002 §9.5: Explicit SOURCE_TOUCHSCREEN for HarmonyOS
        val source = InputDevice.SOURCE_TOUCHSCREEN

        val event = MotionEvent.obtain(
            downTime, eventTime, finalAction, activeIndex,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f, 0, 0,
            source, 0
        )

        // GMM-AEC-002 §9.5: Set displayId if supported (API 30+)
        try {
            if (displayId != 0) {
                val setDisplayIdMethod = MotionEvent::class.java.getMethod(
                    "setDisplayId", Int::class.javaPrimitiveType
                )
                setDisplayIdMethod.invoke(event, displayId)
            }
        } catch (_: Exception) {
            // displayId not supported on this API level
        }

        // GMM-AEC-002 §9.5: Set FLAG_VIRTUAL untuk HarmonyOS
        if (isHarmonyOS) {
            try {
                val setFlagsMethod = MotionEvent::class.java.getMethod("setFlags", Int::class.javaPrimitiveType)
                setFlagsMethod.invoke(event, FLAG_VIRTUAL_VALUE)
            } catch (_: Exception) {
                try {
                    val flagsField = MotionEvent::class.java.getDeclaredField("mFlags")
                    flagsField.isAccessible = true
                    val currentFlags = flagsField.getInt(event)
                    flagsField.setInt(event, currentFlags or FLAG_VIRTUAL_VALUE)
                } catch (_: Exception) {
                    Log.w(TAG, "Could not set FLAG_VIRTUAL on MotionEvent (HarmonyOS)")
                }
            }
        }

        try {
            val result = method.invoke(mgr, event, INJECT_MODE_ASYNC)
            val success = result as? Boolean ?: true
            if (!success) {
                Log.w(TAG, "injectInputEvent returned false (rejected) action=0x" +
                           finalAction.toString(16) + " ptrs=" + activeIndex + " harmonyOS=" + isHarmonyOS)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            Log.e(TAG, "injectInputEvent threw: " + cause.javaClass.name + ": " + cause.message +
                       " action=0x" + finalAction.toString(16) + " ptrs=" + activeIndex)
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent invocation failed: " + e.message +
                       " action=0x" + finalAction.toString(16), e)
        }
        event.recycle()
    }

    // GMM-AEC-002 §11.2: eFootball tap with Gaussian delay + multi-step MOVE
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

        if (efootballMode) {
            // GMM-AEC-002 §11.2: Gaussian delay 8-15ms sebelum MOVE
            try { Thread.sleep(gaussianDelayMs()) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }

            // Multi-step ACTION_MOVE dengan micro-jitter (min 3 step)
            val steps = MULTI_STEP_MIN + rng.nextInt(MULTI_STEP_MAX - MULTI_STEP_MIN + 1)
            for (step in 1..steps) {
                val jx = x + (rng.nextFloat() - 0.5f) * 1.5f
                val jy = y + (rng.nextFloat() - 0.5f) * 1.5f
                state.x = jx
                state.y = jy
                state.pressure = applyPressureVariance()
                state.size = applySizeVariance()
                injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)
                try { Thread.sleep(MULTI_STEP_INTERVAL_MS) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            }

            // Final Gaussian delay sebelum UP
            try { Thread.sleep(gaussianDelayMs()) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }

            // Restore ke posisi akurat sebelum UP
            state.x = x
            state.y = y
        }

        state.isDown = false
        injectMotionEvent(MotionEvent.ACTION_UP, pointerId, displayId)
        pointers.remove(pointerId)
    }

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

        if (efootballMode) {
            try { Thread.sleep(gaussianDelayMs()) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
        }

        val steps = (durationMs / SWIPE_STEP_MS).coerceAtLeast(2)
        val stepDelay = durationMs / steps

        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            state.x = startX + (endX - startX) * progress
            state.y = startY + (endY - startY) * progress
            state.pressure = applyPressureVariance()
            state.size = applySizeVariance()
            injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)

            try { Thread.sleep(stepDelay) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
        }

        if (efootballMode) {
            try { Thread.sleep(gaussianDelayMs()) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
        }

        state.isDown = false
        injectMotionEvent(MotionEvent.ACTION_UP, pointerId, displayId)
        pointers.remove(pointerId)
    }

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

    @Synchronized
    fun multiTouchMove(pointerIds: List<Int>, coords: List<Pair<Float, Float>>, displayId: Int) {
        for ((index, id) in pointerIds.withIndex()) {
            val state = pointers.get(id) ?: continue
            state.x = coords[index].first
            state.y = coords[index].second
        }
        injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)
    }

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

    // GMM-AEC-002 §11.3: Analog stick multi-step interpolation
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

            // GMM-AEC-002 §11.2: Gaussian delay sebelum first MOVE (efootball mode)
            if (efootballMode) {
                try { Thread.sleep(gaussianDelayMs()) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            }
        }

        if (abs(state.x - targetX) > 0.5f || abs(state.y - targetY) > 0.5f) {
            // GMM-AEC-002 §11.3: Multi-step interpolation (efootball mode)
            if (efootballMode) {
                val startX = state.x
                val startY = state.y
                val dx = targetX - startX
                val dy = targetY - startY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)

                // Number of steps berdasarkan distance — min 3, max 8
                val steps = (distance.toInt() / 20).coerceIn(MULTI_STEP_MIN, MULTI_STEP_MAX)

                for (step in 1..steps) {
                    val progress = step.toFloat() / steps
                    state.x = startX + dx * progress
                    state.y = startY + dy * progress
                    state.pressure = applyPressureVariance()
                    state.size = applySizeVariance()
                    injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)
                    try { Thread.sleep(MULTI_STEP_INTERVAL_MS) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); return
                    }
                }
            } else {
                // Legacy: single MOVE (untuk game non-Konami)
                state.x = targetX
                state.y = targetY
                injectMotionEvent(MotionEvent.ACTION_MOVE, 0, displayId)
            }
        }
    }

    private fun getOrCreatePointer(id: Int): PointerState {
        var state = pointers.get(id)
        if (state == null) {
            state = PointerState()
            pointers.put(id, state)
        }
        return state
    }
}
