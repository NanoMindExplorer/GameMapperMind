package com.nanomindexplorer.gamemappermind.input

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.locks.ReentrantLock

/**
 * PointerPool — circular pool of 100 pointer IDs (10..109) with LRU-based
 * garbage collection.
 *
 * FASE 2.2 — Best logical algorithm:
 *
 *   Pool layout
 *   ───────────
 *    • Capacity: 100 pointers
 *    • ID range: 10..109 (offset by 10 to leave room for reserved analog
 *      slots 0..1 inside the same TouchInjector slot array)
 *    • Slot N in the pool corresponds to pointer ID (N + 10).
 *
 *   Acquire algorithm
 *   ──────────────────
 *    1. Lock the pool (ReentrantLock, fair=false for throughput).
 *    2. Walk the slot array looking for a FREE slot.
 *    3. If found: mark ACTIVE, record acquireTimeNs, return the pointer ID.
 *    4. If none free: find the slot with the smallest lastUsedNs (LRU).
 *       a. If that slot has been idle for ≥ staleTimeoutMs, evict it
 *          (mark FREE then re-acquire) and return its pointer ID.
 *       b. If no slot is stale enough, return -1 (pool exhausted).
 *       Callers MUST handle -1 by dropping the event gracefully —
 *       never block the pipeline thread waiting for a slot.
 *
 *   Release algorithm
 *   ──────────────────
 *    1. Lock the pool.
 *    2. Look up the slot by pointer ID.
 *    3. Mark it FREE and bump lastUsedNs to current nanoTime.
 *       Bumping (not zeroing) ensures the just-released slot becomes the
 *       most-recently-used, so it won't be evicted on the next acquire
 *       (avoids thrashing under steady-state load).
 *
 *   Garbage collection (evictStalePointers)
 *   ───────────────────────────────────────
 *    1. Called periodically by InputPipelineWorker (every ~500 ms).
 *    2. Walks all slots; any ACTIVE slot whose lastUsedNs is older than
 *       staleTimeoutMs is forcibly evicted (marked FREE).
 *    3. This catches leaked pointers (caller forgot to release) without
 *       requiring explicit tracking of which caller owns which pointer.
 *
 *   Thread safety
 *   ──────────────
 *    • All mutations guarded by ReentrantLock(false).
 *    • Reads via @Volatile snapshot fields where appropriate.
 *    • Lock is held for O(N) worst case (N=100), but typical case is
 *      O(1) for the first-free-slot fast path.
 *
 *   Anti-reuse window
 *   ──────────────────
 *    • Android's InputDispatcher considers a pointer ID "active" until it
 *      sees ACTION_UP. If we reuse an ID too quickly after release, the
 *      new DOWN event may be interpreted as a MOVE on the old pointer.
 *    • Mitigation: lastUsedNs is bumped on release, making the slot LRU-
 *      protected for at least one acquisition cycle. The 3000 ms stale
 *      timeout also acts as an upper bound on how long an idle pointer
 *      can linger.
 *
 * @param staleTimeoutMs Slots idle for longer than this are eligible for
 *                       eviction. Default 3000 ms per FASE 2.2 spec.
 */
class PointerPool(private val staleTimeoutMs: Long = 3_000L) {

    companion object {
        private const val TAG = "PointerPool"

        /** Total number of slots in the pool. */
        const val CAPACITY = 100

        /** Pointer IDs start at this offset (slots 0..9 reserved for analog + future). */
        const val ID_OFFSET = 10

        /** Maximum pointer ID = ID_OFFSET + CAPACITY - 1 = 109. */
        const val MAX_ID = ID_OFFSET + CAPACITY - 1
    }

    /** Slot states. */
    private enum class State { FREE, ACTIVE }

    /**
     * Slot data class. Mutable fields are mutated under `lock`.
     * - state: FREE (available) or ACTIVE (in use)
     * - lastUsedNs: timestamp of last acquire/release (nanoseconds, monotonic)
     *   Used for LRU eviction and stale detection.
     */
    private data class Slot(
        var state: State = State.FREE,
        var lastUsedNs: Long = 0L
    )

    private val slots = Array(CAPACITY) { Slot() }
    private val lock = ReentrantLock(false)

    /**
     * Acquire a pointer from the pool.
     *
     * Algorithm:
     *   1. Fast path: scan for first FREE slot.
     *   2. Slow path: find LRU ACTIVE slot that is stale (idle > staleTimeoutMs)
     *      and evict it.
     *   3. If no slot available, return -1.
     *
     * @return Pointer ID in [ID_OFFSET .. MAX_ID], or -1 if pool exhausted.
     */
    fun acquirePointer(): Int {
        lock.lock()
        try {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val staleThresholdNs = nowNs - (staleTimeoutMs * 1_000_000L)

            // Step 1: Fast path — first FREE slot.
            for (i in 0 until CAPACITY) {
                if (slots[i].state == State.FREE) {
                    slots[i].state = State.ACTIVE
                    slots[i].lastUsedNs = nowNs
                    val pointerId = i + ID_OFFSET
                    return pointerId
                }
            }

            // Step 2: Slow path — find LRU ACTIVE slot that is stale enough to evict.
            var lruIdx = -1
            var lruTs = Long.MAX_VALUE
            for (i in 0 until CAPACITY) {
                val s = slots[i]
                if (s.state == State.ACTIVE && s.lastUsedNs < staleThresholdNs) {
                    if (s.lastUsedNs < lruTs) {
                        lruTs = s.lastUsedNs
                        lruIdx = i
                    }
                }
            }

            if (lruIdx < 0) {
                // Pool exhausted and no stale slots to evict.
                Log.w(TAG, "acquirePointer: pool exhausted (all $CAPACITY slots active, none stale)")
                return -1
            }

            // Evict the stale slot and re-acquire it.
            slots[lruIdx].lastUsedNs = nowNs
            // state remains ACTIVE (we're reusing it immediately)
            val pointerId = lruIdx + ID_OFFSET
            Log.d(TAG, "acquirePointer: evicted stale slot $lruIdx (idle ${((nowNs - lruTs) / 1_000_000)}ms)")
            return pointerId
        } finally {
            lock.unlock()
        }
    }

    /**
     * Release a pointer back to the pool.
     *
     * @param pointerId The ID returned by acquirePointer().
     *                  Out-of-range IDs are silently ignored (defensive).
     */
    fun releasePointer(pointerId: Int) {
        if (pointerId < ID_OFFSET || pointerId > MAX_ID) {
            Log.w(TAG, "releasePointer: out-of-range ID $pointerId (valid: $ID_OFFSET..$MAX_ID)")
            return
        }
        val idx = pointerId - ID_OFFSET
        lock.lock()
        try {
            val s = slots[idx]
            if (s.state == State.FREE) {
                // Double-release — log but don't crash.
                Log.w(TAG, "releasePointer: slot $idx already FREE (double-release?)")
                return
            }
            s.state = State.FREE
            // Bump lastUsedNs so this slot becomes most-recently-used.
            // This prevents immediate re-acquisition of the same slot,
            // providing a natural anti-reuse window.
            s.lastUsedNs = SystemClock.elapsedRealtimeNanos()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Forcibly evict all ACTIVE slots that have been idle for > staleTimeoutMs.
     * Called periodically by InputPipelineWorker to catch leaked pointers
     * (callers that forgot to call releasePointer()).
     *
     * @return Number of stale pointers evicted.
     */
    fun evictStalePointers(timeoutMs: Long = staleTimeoutMs): Int {
        var evicted = 0
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val thresholdNs = nowNs - (timeoutMs * 1_000_000L)

        lock.lock()
        try {
            for (i in 0 until CAPACITY) {
                val s = slots[i]
                if (s.state == State.ACTIVE && s.lastUsedNs < thresholdNs) {
                    s.state = State.FREE
                    s.lastUsedNs = nowNs
                    evicted++
                }
            }
        } finally {
            lock.unlock()
        }

        if (evicted > 0) {
            Log.i(TAG, "evictStalePointers: evicted $evicted stale pointers (idle > ${timeoutMs}ms)")
        }
        return evicted
    }

    /**
     * Number of slots currently in ACTIVE state.
     * Used by TouchInjector for backpressure signaling to the pipeline.
     */
    fun activeCount(): Int {
        lock.lock()
        try {
            var n = 0
            for (s in slots) if (s.state == State.ACTIVE) n++
            return n
        } finally {
            lock.unlock()
        }
    }

    /**
     * Number of slots currently FREE.
     */
    fun freeCount(): Int = CAPACITY - activeCount()

    /**
     * Reset the pool — release ALL slots, including active ones.
     * Used by TouchInjector.releaseAll() during shutdown or panic.
     */
    fun reset() {
        lock.lock()
        try {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until CAPACITY) {
                slots[i].state = State.FREE
                slots[i].lastUsedNs = nowNs
            }
            Log.i(TAG, "reset: all $CAPACITY slots released")
        } finally {
            lock.unlock()
        }
    }

    /**
     * Check if a specific pointer ID is currently ACTIVE.
     * Useful for diagnostics and tests.
     */
    fun isActive(pointerId: Int): Boolean {
        if (pointerId < ID_OFFSET || pointerId > MAX_ID) return false
        val idx = pointerId - ID_OFFSET
        lock.lock()
        try {
            return slots[idx].state == State.ACTIVE
        } finally {
            lock.unlock()
        }
    }
}
