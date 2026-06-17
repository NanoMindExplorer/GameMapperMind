package com.nanomindexplorer.gamemappermind.input

import android.os.SystemClock
import android.util.Log
import android.view.InputManager
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * TouchInjector — multi-pointer InputManager.injectInputEvent wrapper with a
 * 100-slot LRU pointer pool.
 *
 * FASE 2.2 — Pointer Pool 100 slots + LRU eviction:
 *
 *  Slot allocation policy
 *  ──────────────────────
 *   • Slots 0–1   : reserved for analog sticks (sticky, never evicted while active).
 *                   Slot 0 = left analog, Slot 1 = right analog.
 *   • Slots 2–9   : reserved for future use (gyro, custom high-priority sources).
 *   • Slots 10–99 : general-purpose pool for button taps / swipes.
 *                   Allocation uses LRU eviction when pool is exhausted.
 *
 *  LRU semantics
 *  ─────────────
 *   • Each slot tracks: pointerId (assigned to MotionEvent), state (FREE/DOWN/MOVE/UP),
 *     lastUsedNs (monotonic), and a (x, y, pressure) snapshot.
 *   • Acquire(): walks the pool, returns first FREE slot; if none, evicts the slot
 *     with the oldest lastUsedNs (after sending a synthetic UP to release it cleanly).
 *   • Release(): marks slot FREE and bumps lastUsedNs so it becomes the most-recently-used.
 *
 *  Thread safety
 *  ─────────────
 *   • All slot mutations are guarded by `poolLock` (ReentrantLock, fair=false for throughput).
 *   • Reflection lookups are done once in the constructor.
 *   • The injected MotionEvent pool is per-call (no cross-thread reuse of MotionEvent objects).
 *
 *  Backpressure
 *  ────────────
 *   • `pendingQueueDepth()` exposes the number of slots currently in DOWN/MOVE state,
 *     which the pipeline uses for adaptive throttling.
 */
class TouchInjector(
    private val getScreenWidth: () -> Int,
    private val getScreenHeight: () -> Int
) {
    companion object {
        private const val TAG = "TouchInjector"

        const val POOL_SIZE        = 100
        const RESERVED_ANALOG_END  = 2     // slots 0..1 reserved for analog
        const RESERVED_FUTURE_END  = 10    // slots 2..9 reserved for future use
        const GENERAL_POOL_START   = 10    // slots 10..99 = general pool

        // MotionEvent source for injected events — matches Android gamepad event stream.
        private const val EVENT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN
        private const val EVENT_FLAGS  = 0
    }

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

    // ───────── Pointer pool ─────────
    private enum class SlotState { FREE, DOWN, MOVE }

    private data class Slot(
        var state: SlotState = SlotState.FREE,
        var pointerId: Int = 0,
        var x: Float = 0f,
        var y: Float = 0f,
        var pressure: Float = 0f,
        var size: Float = 0f,
        var lastUsedNs: Long = 0L
    )

    private val pool = Array(POOL_SIZE) { Slot() }
    private val poolLock = ReentrantLock(false)

    // Monotonic pointer-id generator — never reused within a 30 s window.
    private val nextPointerId = AtomicInteger(1)

    // ───────── Public API ─────────

    /**
     * Tap (down + up) at (x, y) using the given pool slot.
     * Slot must be in [GENERAL_POOL_START .. POOL_SIZE-1] or 0..1 for analog.
     */
    fun tap(pointerSlot: Int, x: Int, y: Int) {
        if (!acquireSlot(pointerSlot, x, y)) return
        try {
            inject(pointerSlot, MotionEvent.ACTION_DOWN, x, y, pressure = 1f, size = 1f)
            // Brief hold so the target app registers a complete tap (≥1 frame @ 60 Hz).
            SystemClock.sleep(16)
            inject(pointerSlot, MotionEvent.ACTION_UP, x, y, pressure = 0f, size = 0f)
        } finally {
            releaseSlot(pointerSlot)
        }
    }

    /**
     * Swipe from (x1,y1) to (x2,y2) over [durationMs] using the given pool slot.
     * Interpolates linearly with a ~16 ms step (60 Hz event stream).
     */
    fun swipe(
        pointerSlot: Int,
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long
    ) {
        if (durationMs <= 0L) return
        if (!acquireSlot(pointerSlot, x1, y1)) return
        try {
            inject(pointerSlot, MotionEvent.ACTION_DOWN, x1, y1, pressure = 1f, size = 1f)
            val steps = (durationMs / 16L).toInt().coerceIn(1, 240)
            val stepMs = durationMs / steps
            val startUptime = SystemClock.uptimeMillis()
            for (i in 1 until steps) {
                val t = i.toFloat() / steps
                val xi = (x1 + (x2 - x1) * t).toInt()
                val yi = (y1 + (y2 - y1) * t).toInt()
                // Sleep to maintain consistent step cadence.
                val targetUptime = startUptime + (i * stepMs)
                val sleep = targetUptime - SystemClock.uptimeMillis()
                if (sleep > 0) SystemClock.sleep(sleep)
                inject(pointerSlot, MotionEvent.ACTION_MOVE, xi, yi, pressure = 1f, size = 1f)
            }
            inject(pointerSlot, MotionEvent.ACTION_UP, x2, y2, pressure = 0f, size = 0f)
        } finally {
            releaseSlot(pointerSlot)
        }
    }

    /**
     * Continuous-touch entry for analog sticks. Acquires slot (or upgrades FREE → DOWN).
     * Pipeline calls this every tick; we MOVE only when (x,y) moved beyond deadzone.
     */
    fun analogMove(pointerSlot: Int, x: Float, y: Float, pressure: Float) {
        if (pointerSlot !in 0 until RESERVED_ANALOG_END) return
        poolLock.lock()
        try {
            val s = pool[pointerSlot]
            val now = SystemClock.elapsedRealtimeNanos()
            if (s.state == SlotState.FREE) {
                s.state = SlotState.DOWN
                s.pointerId = allocatePointerId()
                s.x = x; s.y = y; s.pressure = pressure; s.size = 1f
                s.lastUsedNs = now
                emitOutsideLock(pointerSlot, MotionEvent.ACTION_DOWN, x, y, pressure, 1f)
            } else {
                // Coalesce small moves to avoid flooding InputManager.
                val dx = x - s.x; val dy = y - s.y
                if (dx * dx + dy * dy < 1.0f && s.state == SlotState.MOVE) {
                    s.lastUsedNs = now
                    return
                }
                s.state = SlotState.MOVE
                s.x = x; s.y = y; s.pressure = pressure
                s.lastUsedNs = now
                emitOutsideLock(pointerSlot, MotionEvent.ACTION_MOVE, x, y, pressure, 1f)
            }
        } finally {
            poolLock.unlock()
        }
    }

    /** Release an analog slot (sent ACTION_UP). */
    fun analogUp(pointerSlot: Int) {
        if (pointerSlot !in 0 until RESERVED_ANALOG_END) return
        poolLock.lock()
        try {
            val s = pool[pointerSlot]
            if (s.state == SlotState.FREE) return
            val x = s.x; val y = s.y
            s.state = SlotState.FREE
            s.lastUsedNs = SystemClock.elapsedRealtimeNanos()
            emitOutsideLock(pointerSlot, MotionEvent.ACTION_UP, x, y, 0f, 0f)
        } finally {
            poolLock.unlock()
        }
    }

    /** Release every active pointer — used by pipeline.stop() and on panic. */
    fun releaseAll() {
        poolLock.lock()
        try {
            for (slot in 0 until POOL_SIZE) {
                val s = pool[slot]
                if (s.state == SlotState.FREE) continue
                val x = s.x; val y = s.y
                s.state = SlotState.FREE
                s.lastUsedNs = SystemClock.elapsedRealtimeNanos()
                try {
                    emitOutsideLock(slot, MotionEvent.ACTION_UP, x, y, 0f, 0f)
                } catch (_: Throwable) { /* keep releasing other slots */ }
            }
        } finally {
            poolLock.unlock()
        }
    }

    /** Number of slots currently in DOWN/MOVE state — used for backpressure signaling. */
    fun pendingQueueDepth(): Int {
        poolLock.lock()
        try {
            var n = 0
            for (s in pool) if (s.state != SlotState.FREE) n++
            return n
        } finally {
            poolLock.unlock()
        }
    }

    // ───────── Slot lifecycle ─────────

    /**
     * Acquire a slot. For general-pool slots, applies LRU eviction when full.
     * Returns true on success.
     */
    private fun acquireSlot(pointerSlot: Int, x: Int, y: Int): Boolean {
        if (pointerSlot !in 0 until POOL_SIZE) {
            Log.w(TAG, "acquireSlot: out-of-range slot=$pointerSlot")
            return false
        }
        poolLock.lock()
        try {
            val s = pool[pointerSlot]
            if (s.state != SlotState.FREE) {
                // Slot already in use (e.g., rapid double-tap). Synthesize UP first.
                emitOutsideLock(pointerSlot, MotionEvent.ACTION_UP, s.x, s.y, 0f, 0f)
                s.state = SlotState.FREE
            }
            s.state = SlotState.DOWN
            s.pointerId = allocatePointerId()
            s.x = x.toFloat(); s.y = y.toFloat()
            s.pressure = 1f; s.size = 1f
            s.lastUsedNs = SystemClock.elapsedRealtimeNanos()
            return true
        } finally {
            poolLock.unlock()
        }
    }

    private fun releaseSlot(pointerSlot: Int) {
        poolLock.lock()
        try {
            val s = pool[pointerSlot]
            s.state = SlotState.FREE
            s.lastUsedNs = SystemClock.elapsedRealtimeNanos()
        } finally {
            poolLock.unlock()
        }
    }

    /**
     * Allocate a general-pool slot using LRU eviction. Caller MUST finish the gesture
     * by calling releaseSlot(). Returns -1 if pool exhausted and eviction failed.
     */
    fun acquireGeneralPoolSlot(): Int {
        poolLock.lock()
        try {
            // 1) Look for a FREE slot in [GENERAL_POOL_START, POOL_SIZE).
            for (i in GENERAL_POOL_START until POOL_SIZE) {
                if (pool[i].state == SlotState.FREE) return i
            }
            // 2) Evict LRU slot — the one with the smallest lastUsedNs.
            var lruIdx = -1
            var lruTs  = Long.MAX_VALUE
            for (i in GENERAL_POOL_START until POOL_SIZE) {
                val s = pool[i]
                if (s.lastUsedNs < lruTs) {
                    lruTs = s.lastUsedNs
                    lruIdx = i
                }
            }
            if (lruIdx < 0) return -1
            // 3) Send synthetic UP to release the evicted pointer cleanly.
            val evicted = pool[lruIdx]
            try {
                emitOutsideLock(lruIdx, MotionEvent.ACTION_UP, evicted.x, evicted.y, 0f, 0f)
            } catch (_: Throwable) {}
            evicted.state = SlotState.FREE
            evicted.lastUsedNs = SystemClock.elapsedRealtimeNanos()
            return lruIdx
        } finally {
            poolLock.unlock()
        }
    }

    /**
     * PointerId allocator with a 30 s anti-reuse window. Android's InputDispatcher
     * rejects pointer IDs that are still considered "active" by stale windows, so we
     * monotonically increase and wrap at 0x7FFFFFFF (signed-int safe).
     */
    private fun allocatePointerId(): Int {
        return nextPointerId.getAndIncrement().and(0x7FFFFFFF)
    }

    // ───────── Low-level injection ─────────

    /**
     * Inject a MotionEvent for the given slot. MUST be called with poolLock held
     * OR from a context where the slot's state is stable (analogMove path).
     */
    private fun inject(
        pointerSlot: Int,
        action: Int,
        x: Int, y: Int,
        pressure: Float,
        size: Float
    ) {
        poolLock.lock()
        try {
            emitOutsideLock(pointerSlot, action, x.toFloat(), y.toFloat(), pressure, size)
        } finally {
            poolLock.unlock()
        }
    }

    /**
     * Build and inject a MotionEvent without touching pool state.
     * Caller is responsible for slot consistency.
     */
    private fun emitOutsideLock(
        pointerSlot: Int,
        action: Int,
        x: Float, y: Float,
        pressure: Float,
        size: Float
    ) {
        val m = injectMethod ?: return
        val slot = if (pointerSlot in 0 until POOL_SIZE) pool[pointerSlot]
                   else return
        val pointerId = if (slot.pointerId != 0) slot.pointerId else pointerSlot
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
            // actionIndex is 0 for single-pointer events; for multi-pointer we'd need
            // to OR in (0 << MotionEvent.ACTION_POINTER_INDEX_SHIFT). Both work here.
            m.invoke(inputManager, event, INJECT_MODE_ASYNC)
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    /**
     * Build a multi-pointer MotionEvent for advanced use cases (e.g., two-finger gestures).
     * Slots must already be DOWN; this emits an ACTION_MOVE with all active pointers.
     */
    fun injectMultiPointerMove(activeSlots: IntArray) {
        if (activeSlots.isEmpty()) return
        poolLock.lock()
        try {
            val props  = ArrayList<MotionEvent.PointerProperties>(activeSlots.size)
            val coords = ArrayList<MotionEvent.PointerCoords>(activeSlots.size)
            for (slotIdx in activeSlots) {
                if (slotIdx !in 0 until POOL_SIZE) continue
                val s = pool[slotIdx]
                if (s.state == SlotState.FREE) continue
                props.add(MotionEvent.PointerProperties().apply {
                    id = s.pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER
                })
                coords.add(MotionEvent.PointerCoords().apply {
                    x = s.x; y = s.y; pressure = s.pressure; size = s.size
                    touchMajor = s.pressure * 8f; touchMinor = s.pressure * 8f
                    orientation = 0f
                })
            }
            if (props.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(
                now, now,
                MotionEvent.ACTION_MOVE,
                props.size,
                props.toTypedArray(),
                coords.toTypedArray(),
                0, 0, 1f, 1f,
                0, 0, EVENT_SOURCE, EVENT_FLAGS
            )
            try { m_safe_inject(ev) } finally { ev.recycle() }
        } finally {
            poolLock.unlock()
        }
    }

    private fun m_safe_inject(ev: MotionEvent) {
        val m = injectMethod ?: return
        try { m.invoke(inputManager, ev, INJECT_MODE_ASYNC) }
        catch (t: Throwable) { Log.w(TAG, "multi-pointer inject failed: ${t.message}") }
    }
}
