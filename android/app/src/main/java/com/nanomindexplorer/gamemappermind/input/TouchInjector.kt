package com.nanomindexplorer.gamemappermind.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputManager
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * TouchInjector — multi-pointer InputManager.injectInputEvent wrapper.
 *
 * FASE 2.2 — Uses PointerPool (100 slots, IDs 10-109) for button taps and
 * swipes. Analog sticks still use dedicated reserved slots 0-1 (sticky,
 * never evicted) because their pointer lifecycle is tied to the analog
 * stick deflection, not discrete button presses.
 *
 * Architecture:
 *   - PointerPool manages button tap/swipe pointers (IDs 10..109)
 *     with LRU eviction + 3000 ms stale timeout.
 *   - Analog slots (0, 1) managed directly here — acquire/release tied
 *     to analog stick activity.
 *   - All InputManager access via reflection (hidden API, works in
 *     Shizuku UserService shell process).
 *   - Thread-safe via ReentrantLock on the analog path.
 *
 * Public API (called by InputPipelineWorker):
 *   - acquirePointer(): Int      → get a pointer ID from pool (-1 if exhausted)
 *   - releasePointer(id: Int)   → return pointer to pool
 *   - tap(id, x, y)             → tap at (x, y) using pointer id
 *   - swipe(id, x1, y1, x2, y2, durationMs)  → swipe gesture
 *   - analogMove(slot, x, y, pressure)  → continuous touch for analog
 *   - analogUp(slot)                    → release analog pointer
 *   - releaseAll()                      → release every active pointer
 *   - pendingQueueDepth(): Int          → active count (for backpressure)
 *   - evictStalePointers(timeoutMs)     → trigger pool GC
 *
 * @param getScreenWidth  Lambda returning current screen width (re-read every
 *                        call to handle rotation).
 * @param getScreenHeight Lambda returning current screen height.
 */
class TouchInjector(
    private val getScreenWidth: () -> Int,
    private val getScreenHeight: () -> Int
) {
    companion object {
        private const val TAG = "TouchInjector"

        // Analog stick slots (reserved, not managed by PointerPool).
        const val ANALOG_SLOT_LEFT  = 0
        const val ANALOG_SLOT_RIGHT = 1
        const val RESERVED_ANALOG_END = 2

        // MotionEvent source for injected events — matches Android gamepad event stream.
        private const val EVENT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN
        private const val EVENT_FLAGS  = 0
    }

    // ───────── Pointer pool for button taps/swipes ─────────
    private val pointerPool = PointerPool(staleTimeoutMs = 3_000L)

    // ───────── Reflection handles ─────────
    private val inputManager: InputManager = InputManager.getInstance()
    private val injectMethod: Method? = try {
        InputManager::class.java.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    } catch (t: Throwable) {
        Log.e(TAG, "injectInputEvent not accessible — touches will silently fail", t)
        null
    }

    // Mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC (non-blocking).
    private val INJECT_MODE_ASYNC = 0

    // ───────── Screen metrics (re-read on every call to handle rotation) ─────────
    val screenWidthPx: Int  get() = getScreenWidth()
    val screenHeightPx: Int get() = getScreenHeight()

    // ───────── Analog slot state (slots 0-1, not in PointerPool) ─────────
    private enum class AnalogState { FREE, DOWN, MOVE }

    private data class AnalogSlot(
        var state: AnalogState = AnalogState.FREE,
        var pointerId: Int = 0,
        var x: Float = 0f,
        var y: Float = 0f,
        var pressure: Float = 0f,
        var size: Float = 0f,
        var lastUsedNs: Long = 0L
    )

    private val analogSlots = Array(RESERVED_ANALOG_END) { AnalogSlot() }
    private val analogLock = ReentrantLock(false)

    // Monotonic pointer-id generator for analog slots — never reused within a 30 s window.
    private val nextAnalogPointerId = AtomicInteger(1)

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API — Pointer pool operations (button taps/swipes)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Acquire a pointer from the pool.
     * @return Pointer ID in [10..109], or -1 if pool exhausted.
     */
    fun acquirePointer(): Int = pointerPool.acquirePointer()

    /**
     * Release a pointer back to the pool.
     * @param pointerId The ID returned by acquirePointer().
     */
    fun releasePointer(pointerId: Int) = pointerPool.releasePointer(pointerId)

    /**
     * Tap (down + up) at (x, y) using the given pointer ID.
     * Caller must have acquired [pointerId] via acquirePointer() and is
     * responsible for releasing it after this call returns.
     *
     * @param pointerId Pool-allocated pointer ID (10..109)
     * @param x         Absolute X pixel coordinate
     * @param y         Absolute Y pixel coordinate
     */
    fun tap(pointerId: Int, x: Int, y: Int) {
        if (!validatePoolPointerId(pointerId)) return
        // Clamp coordinates to screen bounds (strict input validation).
        val sx = x.coerceIn(0, screenWidthPx - 1)
        val sy = y.coerceIn(0, screenHeightPx - 1)
        try {
            injectEvent(pointerId, MotionEvent.ACTION_DOWN, sx.toFloat(), sy.toFloat(), pressure = 1f, size = 1f)
            // Brief hold so the target app registers a complete tap (≥1 frame @ 60 Hz).
            SystemClock.sleep(16)
            injectEvent(pointerId, MotionEvent.ACTION_UP, sx.toFloat(), sy.toFloat(), pressure = 0f, size = 0f)
        } catch (t: Throwable) {
            Log.e(TAG, "tap failed at ($sx, $sy) id=$pointerId: ${t.message}")
        }
    }

    /**
     * Swipe from (x1,y1) to (x2,y2) over [durationMs] using the given pointer ID.
     * Interpolates linearly with a ~16 ms step (60 Hz event stream).
     *
     * Caller must have acquired [pointerId] and is responsible for releasing it.
     *
     * @param pointerId  Pool-allocated pointer ID (10..109)
     * @param x1, y1     Start coordinates (absolute pixels)
     * @param x2, y2     End coordinates (absolute pixels)
     * @param durationMs Swipe duration in milliseconds
     */
    fun swipe(
        pointerId: Int,
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long
    ) {
        if (!validatePoolPointerId(pointerId)) return
        if (durationMs <= 0L) return
        // Clamp all coordinates to screen bounds.
        val sx1 = x1.coerceIn(0, screenWidthPx - 1)
        val sy1 = y1.coerceIn(0, screenHeightPx - 1)
        val sx2 = x2.coerceIn(0, screenWidthPx - 1)
        val sy2 = y2.coerceIn(0, screenHeightPx - 1)
        try {
            injectEvent(pointerId, MotionEvent.ACTION_DOWN, sx1.toFloat(), sy1.toFloat(), pressure = 1f, size = 1f)
            val steps = (durationMs / 16L).toInt().coerceIn(1, 240)
            val stepMs = durationMs / steps
            val startUptime = SystemClock.uptimeMillis()
            for (i in 1 until steps) {
                val t = i.toFloat() / steps
                val xi = (sx1 + (sx2 - sx1) * t).toInt()
                val yi = (sy1 + (sy2 - sy1) * t).toInt()
                // Sleep to maintain consistent step cadence.
                val targetUptime = startUptime + (i * stepMs)
                val sleep = targetUptime - SystemClock.uptimeMillis()
                if (sleep > 0) SystemClock.sleep(sleep)
                injectEvent(pointerId, MotionEvent.ACTION_MOVE, xi.toFloat(), yi.toFloat(), pressure = 1f, size = 1f)
            }
            injectEvent(pointerId, MotionEvent.ACTION_UP, sx2.toFloat(), sy2.toFloat(), pressure = 0f, size = 0f)
        } catch (t: Throwable) {
            Log.e(TAG, "swipe failed id=$pointerId: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API — Analog stick operations (slots 0-1, not in pool)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Continuous-touch entry for analog sticks. Acquires slot (or upgrades FREE → DOWN).
     * Pipeline calls this every tick; we MOVE only when (x,y) moved beyond deadzone.
     *
     * @param pointerSlot 0 for left analog, 1 for right analog
     * @param x           Absolute X pixel coordinate
     * @param y           Absolute Y pixel coordinate
     * @param pressure    Touch pressure (0..1)
     */
    fun analogMove(pointerSlot: Int, x: Float, y: Float, pressure: Float) {
        if (pointerSlot !in 0 until RESERVED_ANALOG_END) return
        analogLock.lock()
        try {
            val s = analogSlots[pointerSlot]
            val now = SystemClock.elapsedRealtimeNanos()
            if (s.state == AnalogState.FREE) {
                s.state = AnalogState.DOWN
                s.pointerId = allocateAnalogPointerId()
                s.x = x; s.y = y; s.pressure = pressure; s.size = 1f
                s.lastUsedNs = now
                emitAnalogEvent(pointerSlot, MotionEvent.ACTION_DOWN, x, y, pressure, 1f)
            } else {
                // Coalesce small moves to avoid flooding InputManager.
                val dx = x - s.x; val dy = y - s.y
                if (dx * dx + dy * dy < 1.0f && s.state == AnalogState.MOVE) {
                    s.lastUsedNs = now
                    return
                }
                s.state = AnalogState.MOVE
                s.x = x; s.y = y; s.pressure = pressure
                s.lastUsedNs = now
                emitAnalogEvent(pointerSlot, MotionEvent.ACTION_MOVE, x, y, pressure, 1f)
            }
        } finally {
            analogLock.unlock()
        }
    }

    /** Release an analog slot (sends ACTION_UP). */
    fun analogUp(pointerSlot: Int) {
        if (pointerSlot !in 0 until RESERVED_ANALOG_END) return
        analogLock.lock()
        try {
            val s = analogSlots[pointerSlot]
            if (s.state == AnalogState.FREE) return
            val x = s.x; val y = s.y
            s.state = AnalogState.FREE
            s.lastUsedNs = SystemClock.elapsedRealtimeNanos()
            emitAnalogEvent(pointerSlot, MotionEvent.ACTION_UP, x, y, 0f, 0f)
        } finally {
            analogLock.unlock()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API — Pool maintenance + diagnostics
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Release every active pointer — both pool-managed and analog slots.
     * Used by pipeline.stop() and on panic.
     */
    fun releaseAll() {
        // 0) Release multi-touch session mappings.
        try { releaseMultiTouchSession() } catch (t: Throwable) {
            Log.e(TAG, "releaseMultiTouchSession() failed: ${t.message}")
        }
        // 1) Reset the pointer pool (releases all button tap/swipe pointers).
        try { pointerPool.reset() } catch (t: Throwable) {
            Log.e(TAG, "pointerPool.reset() failed: ${t.message}")
        }
        // 2) Release analog slots.
        analogLock.lock()
        try {
            for (slot in 0 until RESERVED_ANALOG_END) {
                val s = analogSlots[slot]
                if (s.state == AnalogState.FREE) continue
                val x = s.x; val y = s.y
                s.state = AnalogState.FREE
                s.lastUsedNs = SystemClock.elapsedRealtimeNanos()
                try {
                    emitAnalogEvent(slot, MotionEvent.ACTION_UP, x, y, 0f, 0f)
                } catch (_: Throwable) { /* keep releasing other slots */ }
            }
        } finally {
            analogLock.unlock()
        }
    }

    /**
     * Number of active pointers (pool + analog). Used for backpressure
     * signaling to InputPipelineWorker.
     */
    fun pendingQueueDepth(): Int {
        return pointerPool.activeCount() + analogActiveCount()
    }

    /**
     * Evict stale pointers from the pool. Called periodically by
     * InputPipelineWorker.
     */
    fun evictStalePointers(timeoutMs: Long): Int {
        return pointerPool.evictStalePointers(timeoutMs)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun validatePoolPointerId(pointerId: Int): Boolean {
        if (pointerId < PointerPool.ID_OFFSET || pointerId > PointerPool.MAX_ID) {
            Log.w(TAG, "Invalid pointer ID $pointerId (valid: ${PointerPool.ID_OFFSET}..${PointerPool.MAX_ID})")
            return false
        }
        return true
    }

    private fun analogActiveCount(): Int {
        analogLock.lock()
        try {
            var n = 0
            for (s in analogSlots) if (s.state != AnalogState.FREE) n++
            return n
        } finally {
            analogLock.unlock()
        }
    }

    /**
     * PointerId allocator for analog slots with a 30 s anti-reuse window.
     * Android's InputDispatcher rejects pointer IDs that are still considered
     * "active" by stale windows, so we monotonically increase and wrap at
     * 0x7FFFFFFF (signed-int safe).
     */
    private fun allocateAnalogPointerId(): Int {
        return nextAnalogPointerId.getAndIncrement().and(0x7FFFFFFF)
    }

    /**
     * Inject a MotionEvent for a pool-managed pointer (IDs 10-109).
     * Converts the pointer ID to a MotionEvent pointer index by subtracting
     * the pool ID_OFFSET (so pool IDs 10..109 map to MotionEvent pointer
     * indices 0..99).
     */
    private fun injectEvent(
        pointerId: Int,
        action: Int,
        x: Float, y: Float,
        pressure: Float,
        size: Float
    ) {
        val m = injectMethod ?: return
        val now = SystemClock.uptimeMillis()

        val props = MotionEvent.PointerProperties().apply {
            id = pointerId
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            this.size = size
            // Required fields — set to sane defaults.
            touchMajor = pressure * 8f
            touchMinor = pressure * 8f
            orientation = 0f
        }

        val event = try {
            MotionEvent.obtain(
                /* downTime     */ now,
                /* eventTime    */ now,
                /* action       */ action,
                /* pointerCount */ 1,
                /* pointerProps */ arrayOf(props),
                /* pointerCoords*/ arrayOf(coords),
                /* metaState    */ 0,
                /* buttonState  */ 0,
                /* xPrecision   */ 1f,
                /* yPrecision   */ 1f,
                /* deviceId     */ 0,
                /* edgeFlags    */ 0,
                /* source       */ EVENT_SOURCE,
                /* flags        */ EVENT_FLAGS
            )
        } catch (t: Throwable) {
            Log.w(TAG, "MotionEvent.obtain failed: ${t.message}")
            return
        }

        try {
            m.invoke(inputManager, event, INJECT_MODE_ASYNC)
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Backward-compatible wrappers for GameMapperUserService
    // ═══════════════════════════════════════════════════════════════════
    // These methods maintain the old API signatures so existing callers
    // (GameMapperUserService) continue to work without modification.
    // Internally they use the new PointerPool for ID management.

    /**
     * Backward-compatible tap: acquires a pool pointer internally,
     * performs the tap, then releases the pointer.
     *
     * @param x         X coordinate (Float — jittered by caller)
     * @param y         Y coordinate (Float — jittered by caller)
     * @param displayId Display ID (ignored — injection is always on default display)
     */
    fun tap(x: Float, y: Float, displayId: Int = 0) {
        val pid = acquirePointer()
        if (pid < 0) {
            Log.w(TAG, "tap(wrapper): pool exhausted, dropping tap at ($x, $y)")
            return
        }
        try {
            tap(pid, x.toInt(), y.toInt())
        } finally {
            releasePointer(pid)
        }
    }

    /**
     * Backward-compatible swipe: acquires a pool pointer internally,
     * performs the swipe, then releases the pointer.
     */
    fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long, displayId: Int = 0
    ) {
        val pid = acquirePointer()
        if (pid < 0) {
            Log.w(TAG, "swipe(wrapper): pool exhausted, dropping swipe")
            return
        }
        try {
            swipe(pid, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durationMs)
        } finally {
            releasePointer(pid)
        }
    }

    /**
     * Backward-compatible touchUp: releases a pool pointer by caller-provided ID.
     * In the old API, pointerId was caller-managed. Here we treat it as a pool ID.
     */
    fun touchUp(pointerId: Int, displayId: Int = 0) {
        releasePointer(pointerId)
    }

    /**
     * Backward-compatible analogMove: maps pointerId to analog slot 0 or 1
     * using pointerId % 2 heuristic. Left stick typically uses even IDs,
     * right stick uses odd IDs.
     */
    fun analogMove(
        pointerId: Int,
        centerX: Float, centerY: Float,
        targetX: Float, targetY: Float,
        displayId: Int = 0
    ) {
        val slot = pointerId % RESERVED_ANALOG_END  // → 0 or 1
        analogMove(slot, targetX, targetY, 1.0f)
    }

    // ───── Multi-touch session tracking (for backward compat) ─────
    // Maps caller-provided pointer IDs to pool-allocated IDs.
    private val multiTouchMap = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val multiTouchLock = ReentrantLock(false)

    /**
     * Backward-compatible multiTouchDown: acquires pool pointers for each
     * caller-provided ID and sends ACTION_DOWN for each.
     */
    fun multiTouchDown(ids: List<Int>, coords: List<Pair<Float, Float>>, displayId: Int = 0) {
        if (ids.size != coords.size) {
            Log.w(TAG, "multiTouchDown: ids.size=${ids.size} != coords.size=${coords.size}")
            return
        }
        multiTouchLock.lock()
        try {
            for (i in ids.indices) {
                val callerId = ids[i]
                val (x, y) = coords[i]
                var poolId = multiTouchMap[callerId]
                if (poolId == null) {
                    poolId = acquirePointer()
                    if (poolId < 0) {
                        Log.w(TAG, "multiTouchDown: pool exhausted at index $i")
                        continue
                    }
                    multiTouchMap[callerId] = poolId
                }
                injectEvent(poolId, MotionEvent.ACTION_DOWN, x, y, 1f, 1f)
            }
        } finally {
            multiTouchLock.unlock()
        }
    }

    /**
     * Backward-compatible multiTouchMove: sends ACTION_MOVE for each
     * previously-acquired pointer.
     */
    fun multiTouchMove(ids: List<Int>, coords: List<Pair<Float, Float>>, displayId: Int = 0) {
        if (ids.size != coords.size) {
            Log.w(TAG, "multiTouchMove: ids.size=${ids.size} != coords.size=${coords.size}")
            return
        }
        multiTouchLock.lock()
        try {
            for (i in ids.indices) {
                val callerId = ids[i]
                val (x, y) = coords[i]
                val poolId = multiTouchMap[callerId] ?: continue
                injectEvent(poolId, MotionEvent.ACTION_MOVE, x, y, 1f, 1f)
            }
        } finally {
            multiTouchLock.unlock()
        }
    }

    /**
     * Release all multi-touch session pointers and clear the mapping.
     * Called by releaseAll() to ensure no leaked pool pointers.
     */
    fun releaseMultiTouchSession() {
        multiTouchLock.lock()
        try {
            for ((_, poolId) in multiTouchMap) {
                try { releasePointer(poolId) } catch (_: Throwable) {}
            }
            multiTouchMap.clear()
        } finally {
            multiTouchLock.unlock()
        }
    }

    /**
     * Build and inject a MotionEvent for an analog slot pointer.
     * Called with analogLock held.
     */
    private fun emitAnalogEvent(
        pointerSlot: Int,
        action: Int,
        x: Float, y: Float,
        pressure: Float,
        size: Float
    ) {
        val m = injectMethod ?: return
        if (pointerSlot !in 0 until RESERVED_ANALOG_END) return
        val s = analogSlots[pointerSlot]
        val pointerId = if (s.pointerId != 0) s.pointerId else pointerSlot
        val now = SystemClock.uptimeMillis()

        val props = MotionEvent.PointerProperties().apply {
            id = pointerId
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            this.size = size
            touchMajor = pressure * 8f
            touchMinor = pressure * 8f
            orientation = 0f
        }

        val event = try {
            MotionEvent.obtain(
                now, now, action, 1,
                arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, EVENT_SOURCE, EVENT_FLAGS
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Analog MotionEvent.obtain failed: ${t.message}")
            return
        }

        try {
            m.invoke(inputManager, event, INJECT_MODE_ASYNC)
        } catch (t: Throwable) {
            Log.w(TAG, "Analog inject failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }
}
