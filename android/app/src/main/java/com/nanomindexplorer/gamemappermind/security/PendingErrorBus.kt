package com.nanomindexplorer.gamemappermind.security

import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * FASE 4.2 — Decoupling bus between NativeCrashGuard (which may run on any
 * thread, including Shizuku UserService binder threads) and GameMapperPlugin
 * (which must call notifyListeners() on its bridge's main thread).
 *
 * Path di repo:
 *   android/app/src/main/java/com.nanomindexplorer.gamemappermind/security/PendingErrorBus.kt
 *
 * Design:
 *   - Crash guard publishes structured error JSON to a thread-safe queue.
 *   - GameMapperPlugin polls the queue on a periodic Handler and forwards
 *     each entry to notifyListeners("app:error", payload).
 *   - Capacity is capped (64 entries) to bound memory under crash storms.
 *   - Older entries are dropped silently — the JS side only needs the
 *     most recent context for UI.
 *
 * Why not just call notifyListeners directly from the crash guard?
 *   - notifyListeners() must run on the Capacitor bridge's main thread.
 *   - The crash guard might be invoked from a Shizuku UserService binder
 *     thread, a HandlerThread (pipeline worker), or a BroadcastReceiver.
 *   - Marshaling across threads via Handler.post() requires holding a
 *     reference to the plugin, which the guard cannot (cleanly) have.
 *   - Decoupling via a queue is the simplest thread-safe design.
 */
object PendingErrorBus {

    private const val MAX_CAPACITY = 64

    private val queue = ConcurrentLinkedQueue<JSONObject>()

    /**
     * Publish a structured error payload. Non-blocking, thread-safe.
     * If the queue is full, the OLDEST entry is dropped (FIFO eviction).
     */
    fun publish(payload: JSONObject) {
        queue.offer(payload)
        // Trim if over capacity.
        while (queue.size > MAX_CAPACITY) {
            queue.poll()
        }
    }

    /**
     * Drain all pending errors. Called by GameMapperPlugin on its main thread.
     * Returns a list of payloads to forward to notifyListeners().
     */
    fun drain(): List<JSONObject> {
        val out = ArrayList<JSONObject>(queue.size)
        while (true) {
            val p = queue.poll() ?: break
            out.add(p)
        }
        return out
    }

    /** Number of pending errors (advisory, may be slightly stale). */
    fun size(): Int = queue.size

    /** Clear the queue (e.g., on app restart). */
    fun clear() = queue.clear()
}
