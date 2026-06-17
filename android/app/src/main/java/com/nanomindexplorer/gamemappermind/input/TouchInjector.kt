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
 * This class runs inside the Shizuku UserService process (shell UID 2000
 * or root UID 0). In this privileged context, we have direct access to
 * InputManager.getInstance() and its hidden injectInputEvent() method.
 *
 * NOTE ON ShizukuBinderWrapper:
 *   The contract mentions using ShizukuBinderWrapper(ServiceManager.getService("input")).
 *   However, ShizukuBinderWrapper is designed for use from the APP process
 *   to forward binder calls through Shizuku. Since this code runs INSIDE
 *   the Shizuku UserService (already privileged), we access InputManager
 *   directly via reflection. This is the recommended approach per the
 *   Shizuku-API README: "There are no restrictions on non-SDK APIs in
 *   the user service process."
 *
 * Thread safety:
 *   All methods are synchronized to prevent concurrent MotionEvent
 *   construction. The UserService receives calls via binder IPC,
 *   which are dispatched on binder threads — multiple calls can
 *   arrive simultaneously.
 *
 * Dependencies:
 *   - None beyond Android framework (InputManager is hidden API,
 *     accessed via reflection)
 */
class TouchInjector {

    companion object {
        private const val TAG = "GameMapper/TouchInjector"
        private const val INJECT_MODE_ASYNC = 0 // INJECT_INPUT_EVENT_MODE_ASYNC
        private const val SWIPE_STEP_MS = 16L   // ~60fps for smooth swipe
    }

    // ============================================================
    // InputManager instance — obtained via reflection.
    // InputManager.getInstance() is a hidden API but works in
    // UserService process (no non-SDK API restrictions).
    // ============================================================
    private val inputManager: InputManager? by lazy {
        try {
            val method = InputManager::class.java.getMethod("getInstance")
            method.invoke(null) as InputManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get InputManager.getInstance()", e)
            null
        }
    }

    // ============================================================
    // injectInputEvent method — hidden API, obtained via reflection.
    // Signature: injectInputEvent(InputEvent event, int mode)
    // ============================================================
    private val injectMethod by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get injectInputEvent method", e)
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
    @Volatile
    private var antiBanEnabled = false
    @Volatile
    private var pressureVariance = 0.15f
    @Volatile
    private var sizeVariance = 0.10f

    private val rng = Random(System.currentTimeMillis())

    fun setAntiBan(enabled: Boolean, pressVar: Float, sizeVar: Float) {
        antiBanEnabled = enabled
        pressureVariance = pressVar
        sizeVariance = sizeVar
    }

    // ============================================================
    // Core injection method — builds and injects a MotionEvent
    // ============================================================
    @Synchronized
    private fun injectMotionEvent(action: Int, actionIndex: Int, displayId: Int) {
        if (inputManager == null || injectMethod == null) {
            Log.e(TAG, "InputManager or injectMethod not available")
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

        // Set displayId if supported (API 30+)
        try {
            if (displayId != 0) {
                val setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                setDisplayIdMethod.invoke(event, displayId)
            }
        } catch (_: Exception) {
            // displayId not supported on this API level — inject on default display
        }

        // Inject the event
        try {
            injectMethod?.invoke(inputManager, event, INJECT_MODE_ASYNC)
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed: action=$finalAction", e)
        }
        event.recycle()
    }

    // ============================================================
    // Public API — called from GameMapperUserService
    // ============================================================

    /**
     * Single tap at (x, y).
     * Creates ACTION_DOWN → short delay → ACTION_UP.
     */
    @Synchronized
    fun tap(x: Float, y: Float, displayId: Int) {
        val pointerId = 99 // Reserved ID for single taps
        val state = getOrCreatePointer(pointerId)
        state.x = x
        state.y = y
        state.isDown = true
        state.pressure = applyPressureVariance()
        state.size = applySizeVariance()

        baseDownTime = SystemClock.uptimeMillis()
        injectMotionEvent(MotionEvent.ACTION_DOWN, pointerId, displayId)

        // Brief hold (20ms) for the tap to register
        try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

        state.isDown = false
        injectMotionEvent(MotionEvent.ACTION_UP, pointerId, displayId)
        pointers.remove(pointerId)
    }

    /**
     * Swipe from (startX, startY) to (endX, endY) over durationMs.
     * Creates a series of ACTION_DOWN → ACTION_MOVE → ACTION_UP events.
     */
    @Synchronized
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, displayId: Int) {
        val pointerId = 98 // Reserved ID for swipes
        val state = getOrCreatePointer(pointerId)
        state.x = startX
        state.y = startY
        state.isDown = true
        state.pressure = applyPressureVariance()
        state.size = applySizeVariance()

        baseDownTime = SystemClock.uptimeMillis()
        injectMotionEvent(MotionEvent.ACTION_DOWN, pointerId, displayId)

        // Interpolate movement over duration
        val steps = (durationMs / SWIPE_STEP_MS).coerceAtLeast(2)
        val stepDelay = durationMs / steps

        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            val currentX = startX + (endX - startX) * progress
            val currentY = startY + (endY - startY) * progress

            state.x = currentX
            state.y = currentY
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

            // Count active pointers before this one
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
     * If this is the last active pointer, dispatches ACTION_UP.
     * Otherwise dispatches ACTION_POINTER_UP.
     */
    @Synchronized
    fun touchUp(pointerId: Int, displayId: Int) {
        val state = pointers.get(pointerId) ?: return

        // Count active pointers BEFORE setting isDown = false
        var activeCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activeCount++
        }

        if (activeCount <= 1) {
            // Last pointer up
            injectMotionEvent(MotionEvent.ACTION_UP, pointerId, displayId)
            state.isDown = false
            pointers.clear()
        } else {
            // One of multiple pointers up
            injectMotionEvent(MotionEvent.ACTION_POINTER_UP, pointerId, displayId)
            state.isDown = false
            pointers.remove(pointerId)
        }
    }

    /**
     * Analog stick move — touch down at center, then move to target.
     * If pointer is not yet down, creates ACTION_DOWN at center first.
     * If already down, dispatches ACTION_MOVE to target.
     */
    @Synchronized
    fun analogMove(pointerId: Int, centerX: Float, centerY: Float, targetX: Float, targetY: Float, displayId: Int) {
        var state = pointers.get(pointerId)

        if (state == null || !state.isDown) {
            // Initial touch down at center
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

        // Move to target position
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
        return 1.0f - (rng.nextFloat() * pressureVariance)
    }

    private fun applySizeVariance(): Float {
        if (!antiBanEnabled || sizeVariance <= 0f) return 1.0f
        return 1.0f - (rng.nextFloat() * sizeVariance)
    }
}
