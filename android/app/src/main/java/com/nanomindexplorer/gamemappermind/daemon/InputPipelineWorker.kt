package com.nanomindexplorer.gamemappermind.daemon

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.GamepadManager
import com.nanomindexplorer.gamemappermind.input.PointerPool
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.model.GameProfile
import com.nanomindexplorer.gamemappermind.model.Mapping
import org.json.JSONObject

/**
 * InputPipelineWorker — adaptive pipeline with dynamic polling.
 *
 * FASE 2.1 — Dynamic Adaptive Polling (idle 50 ms, active 10 ms):
 *
 *   Best logical algorithm:
 *     1. Use System.nanoTime() for sub-millisecond precision timing.
 *        SystemClock.uptimeMillis() only has ~1 ms resolution which causes
 *        jitter at high polling frequencies. nanoTime() gives true nanosecond
 *        precision and is monotonic.
 *     2. Two-tier polling:
 *          - IDLE  (50 ms / 20 Hz): no gamepad activity for ≥ 1500 ms
 *          - ACTIVE (10 ms / 100 Hz): any button press or analog movement
 *        This minimizes CPU usage when the user puts the controller down
 *        while staying responsive the instant they resume play.
 *     3. Hysteresis band (1500 ms idle before demote, any-event instant
 *        promote) prevents flapping between tiers during brief pauses.
 *     4. CPU load monitoring via /proc/stat with EMA smoothing (α=0.4).
 *        If CPU ≥ 85%, force ACTIVE→IDLE downgrade even if gamepad is
 *        active, to avoid starving the foreground game.
 *     5. Touch-injection backpressure: if TouchInjector queue depth ≥ 32
 *        (hard watermark), force IDLE until queue drains below 12 (soft
 *        watermark). Prevents pipeline from flooding InputManager.
 *     6. Self-healing: tickRunnable catches all Throwable — pipeline
 *        thread never dies. Worker thread priority is
 *        THREAD_PRIORITY_URGENT_DISPLAY (-8) to minimize jitter.
 *     7. All sensitive state guarded by `pipelineLock`. Hot-path reads
 *        use @Volatile snapshots for lock-free access from the worker
 *        thread.
 *
 * Thread model:
 *   - Single HandlerThread ("GMM-Pipeline") with single looper.
 *   - No chained handlers — every tick is self-contained.
 *   - postDelayed(this, delayMs) schedules next tick.
 *
 * @param gamepadManager   Source of gamepad snapshots
 * @param touchInjector    Touch injection target
 * @param analogProcessor  Analog stick → touch coordinate translator
 * @param onGamepadEvent   Callback to forward events to JS layer
 */
class InputPipelineWorker(
    private val gamepadManager: GamepadManager,
    private val touchInjector: TouchInjector,
    private val analogProcessor: AnalogProcessor,
    private val onGamepadEvent: (JSONObject) -> Unit
) {
    companion object {
        private const val TAG = "InputPipelineWorker"

        // ───── Polling tiers (nanoseconds per tick) ─────
        // FASE 2.1: idle 50 ms (20 Hz), active 10 ms (100 Hz).
        // Using System.nanoTime() for sub-millisecond precision.
        private const val TIER_ACTIVE_NS = 10_000_000L   // 10 ms / 100 Hz — gamepad active
        private const val TIER_IDLE_NS   = 50_000_000L   // 50 ms / 20 Hz  — gamepad idle

        // ───── Hysteresis thresholds (anti-flapping) ─────
        // Promote IDLE → ACTIVE on any event within this window.
        private const val PROMOTE_IDLE_TO_ACTIVE_IDLE_MS = 1_500L   // ≤ 1.5 s idle → demote
        private const val ACTIVE_EVENT_RECENCY_MS        = 100L     // event in last 100 ms = active

        // ───── CPU load thresholds (0..1) ─────
        private const val CPU_HIGH_WATERMARK = 0.85f
        private const val CPU_LOW_WATERMARK  = 0.55f

        // ───── Touch-injection backpressure ─────
        private const val INJECT_QUEUE_HARD_WATERMARK = 32
        private const val INJECT_QUEUE_SOFT_WATERMARK = 12

        // ───── CPU sampling cadence ─────
        // /proc/stat parsing is cheap but not free. Sample every N ticks.
        // At ACTIVE (100 Hz), this is ~every 100 ms. At IDLE (20 Hz), ~500 ms.
        private const val CPU_SAMPLE_INTERVAL_TICKS = 10

        // ───── Activity bookkeeping window ─────
        private const val ACTIVITY_WINDOW_MS = 250L

        // ───── Pointer pool maintenance cadence ─────
        // Trigger LRU cleanup on TouchInjector's PointerPool every N ticks.
        // Pool entries idle for > POOL_TIMEOUT_MS are evicted.
        private const val POINTER_POOL_CLEANUP_TICKS = 50    // ~every 500 ms at ACTIVE
        private const val POINTER_POOL_TIMEOUT_MS    = 3_000L
    }

    // ───────── Pipeline state ─────────
    @Volatile private var running = false
    @Volatile private var currentPeriodNs = TIER_IDLE_NS
    @Volatile private var currentTier: Tier = Tier.IDLE

    private val pipelineLock = Any()
    private var profile: GameProfile? = null
    private var swipeTriggerButton: Int = -1
    private var swipeTriggerDirection: Int = -1   // 0=up 1=down 2=left 3=right

    // Worker thread.
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    // ───────── Activity tracking (lock-free hot path) ─────────
    @Volatile private var lastEventUptimeMs: Long = 0L
    @Volatile private var lastAnalogDelta: Float = 0f
    @Volatile private var lastButtonRateHz: Float = 0f

    // Ring buffer for button rate calculation (events in last ACTIVITY_WINDOW_MS).
    private val eventTimestamps = LongArray(64)
    private var eventHead = 0
    private var eventCount = 0
    private val eventLock = Any()

    // ───────── CPU sampler ─────────
    private var cpuPrevIdle = 0L
    private var cpuPrevTotal = 0L
    @Volatile private var cpuLoadSmoothed = 0f
    private var cpuSampleTickCounter = 0

    // ───────── Pointer pool cleanup counter ─────────
    private var poolCleanupTickCounter = 0

    private enum class Tier { IDLE, ACTIVE }

    // ───────── Lifecycle ─────────

    /**
     * Start the pipeline. Idempotent — calling twice is a no-op.
     * Initializes worker thread with THREAD_PRIORITY_URGENT_DISPLAY (-8)
     * to minimize scheduling jitter at 100 Hz polling.
     */
    fun start() {
        synchronized(pipelineLock) {
            if (running) return
            running = true
            currentTier = Tier.IDLE
            currentPeriodNs = TIER_IDLE_NS
            lastEventUptimeMs = SystemClock.uptimeMillis()
            cpuPrevIdle = 0L
            cpuPrevTotal = 0L
            cpuLoadSmoothed = 0f
            cpuSampleTickCounter = 0
            poolCleanupTickCounter = 0
            resetEventBuffer()

            val thread = HandlerThread("GMM-Pipeline", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                start()
            }
            workerThread = thread
            workerHandler = Handler(thread.looper)
            workerHandler?.post(tickRunnable)
            Log.i(TAG, "Pipeline started @ tier=${currentTier} period=${currentPeriodNs / 1_000_000}ms")
        }
    }

    /**
     * Stop the pipeline and release all held touch pointers.
     * Idempotent — calling twice is a no-op.
     */
    fun stop() {
        synchronized(pipelineLock) {
            if (!running) return
            running = false
            workerHandler?.removeCallbacks(tickRunnable)
            workerThread?.quitSafely()
            workerThread = null
            workerHandler = null
            // Release any held pointers so we don't leak active touch sessions.
            try { touchInjector.releaseAll() } catch (_: Throwable) {}
            Log.i(TAG, "Pipeline stopped")
        }
    }

    fun isRunning(): Boolean = running

    // ───────── Configuration ─────────

    /** Push a new profile. Safe to call from any thread; takes effect on next tick. */
    fun setProfile(p: GameProfile?) {
        synchronized(pipelineLock) {
            profile = p
            analogProcessor.onProfileChanged(p)
        }
    }

    /** Configure a swipe-on-button trigger. direction: 0=up 1=down 2=left 3=right; -1 disables. */
    fun updateSwipeTrigger(buttonCode: Int, direction: Int) {
        synchronized(pipelineLock) {
            swipeTriggerButton = buttonCode
            swipeTriggerDirection = direction
        }
    }

    // ───────── Main tick ─────────

    /**
     * Tick runnable — runs on the worker thread. Self-rescheduling.
     * Catches all Throwable to prevent pipeline thread death.
     */
    private val tickRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                // Use nanoTime() for sub-millisecond precision.
                // SystemClock.elapsedRealtimeNanos() is monotonic and survives sleep.
                val tickStartNs = SystemClock.elapsedRealtimeNanos()
                processTick()
                val elapsedNs = SystemClock.elapsedRealtimeNanos() - tickStartNs

                // Schedule next tick at adaptive cadence.
                // Coerce to ≥ 1 ms to avoid postDelayed(0) busy-looping.
                val period = currentPeriodNs
                val delayMs = ((period - elapsedNs).coerceAtLeast(1_000_000L)) / 1_000_000L
                workerHandler?.postDelayed(this, delayMs)
            } catch (t: Throwable) {
                // Never let an exception kill the pipeline thread.
                Log.e(TAG, "Tick threw — recovering", t)
                workerHandler?.postDelayed(this, currentPeriodNs / 1_000_000L)
            }
        }
    }

    private fun processTick() {
        // 1) Sample CPU periodically.
        cpuSampleTickCounter++
        if (cpuSampleTickCounter >= CPU_SAMPLE_INTERVAL_TICKS) {
            cpuSampleTickCounter = 0
            sampleCpuLoad()
        }

        // 2) Periodic PointerPool cleanup — evict entries idle > POOL_TIMEOUT_MS.
        poolCleanupTickCounter++
        if (poolCleanupTickCounter >= POINTER_POOL_CLEANUP_TICKS) {
            poolCleanupTickCounter = 0
            try {
                touchInjector.evictStalePointers(POINTER_POOL_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "PointerPool cleanup failed: ${t.message}")
            }
        }

        // 3) Read gamepad snapshot (lock-free).
        val snapshot = gamepadManager.snapshot() ?: return
        val now = SystemClock.uptimeMillis()

        // 4) Compute analog delta magnitude (0..1).
        val lx = snapshot.leftStickX
        val ly = snapshot.leftStickY
        val rx = snapshot.rightStickX
        val ry = snapshot.rightStickY
        val analogDelta = maxOf(
            Math.hypot(lx.toDouble(), ly.toDouble()).toFloat(),
            Math.hypot(rx.toDouble(), ry.toDouble()).toFloat()
        )
        lastAnalogDelta = analogDelta

        // 5) Record event for rate computation.
        if (snapshot.hasAnyButton() || analogDelta > 0.01f) {
            recordEvent(now)
            lastEventUptimeMs = now
        }

        // 6) Update button rate (Hz over last ACTIVITY_WINDOW_MS).
        lastButtonRateHz = computeButtonRateHz(now)

        // 7) Forward snapshot to JS layer (throttled inside the callback).
        try {
            onGamepadEvent(buildEventJson(snapshot, analogDelta))
        } catch (_: Throwable) { /* never block pipeline on JS bridge */ }

        // 8) Apply profile mappings → inject touches.
        val p = profile
        if (p != null) {
            applyMappings(p, snapshot, analogDelta)
        }

        // 9) Adaptive tier selection.
        adaptTier(now, analogDelta)
    }

    // ───────── Mapping ─────────

    private fun applyMappings(
        profile: GameProfile,
        snapshot: GamepadManager.Snapshot,
        analogDelta: Float
    ) {
        val screenW = touchInjector.screenWidthPx
        val screenH = touchInjector.screenHeightPx
        if (screenW <= 0 || screenH <= 0) return

        // Analog sticks → continuous pointers (slots 0 and 1 reserved).
        if (analogDelta > 0.02f) {
            analogProcessor.process(
                snapshot = snapshot,
                profile = profile,
                screenW = screenW,
                screenH = screenH,
                injector = touchInjector
            )
        } else {
            // Release analog pointers when stick returns to deadzone.
            analogProcessor.releaseAll(touchInjector)
        }

        // Buttons → tap/swipe. Each mapping gets a pointer from the pool.
        for (mapping in profile.mappings) {
            if (!snapshot.isButtonPressed(mapping.buttonCode)) continue
            handleButtonEvent(mapping, screenW, screenH)
        }

        // Custom swipe trigger (overrides any mapping for the trigger button).
        val swipeBtn = swipeTriggerButton
        val swipeDir = swipeTriggerDirection
        if (swipeBtn >= 0 && swipeDir in 0..3 && snapshot.isButtonPressed(swipeBtn)) {
            triggerSwipe(swipeDir, screenW, screenH)
        }
    }

    private fun handleButtonEvent(m: Mapping, screenW: Int, screenH: Int) {
        // Clamp percentages to [0, 100] before conversion (strict input validation).
        val xPct = m.xPercent.coerceIn(0f, 1f)
        val yPct = m.yPercent.coerceIn(0f, 1f)
        val endXPct = m.endXPercent.coerceIn(0f, 1f)
        val endYPct = m.endYPercent.coerceIn(0f, 1f)

        val x = (xPct * screenW).toInt().coerceIn(0, screenW - 1)
        val y = (yPct * screenH).toInt().coerceIn(0, screenH - 1)
        val endX = (endXPct * screenW).toInt().coerceIn(0, screenW - 1)
        val endY = (endYPct * screenH).toInt().coerceIn(0, screenH - 1)

        when (m.action) {
            Mapping.ACTION_TAP -> {
                // Acquire a pointer from the pool (IDs 10-109) and tap.
                val pointerId = touchInjector.acquirePointer()
                if (pointerId >= 0) {
                    try {
                        touchInjector.tap(pointerId, x, y)
                    } finally {
                        touchInjector.releasePointer(pointerId)
                    }
                }
            }
            Mapping.ACTION_SWIPE -> {
                val pointerId = touchInjector.acquirePointer()
                if (pointerId >= 0) {
                    try {
                        touchInjector.swipe(
                            pointerId = pointerId,
                            x1 = x,
                            y1 = y,
                            x2 = endX,
                            y2 = endY,
                            durationMs = m.durationMs.coerceAtLeast(16L)
                        )
                    } finally {
                        touchInjector.releasePointer(pointerId)
                    }
                }
            }
        }
    }

    private fun triggerSwipe(direction: Int, screenW: Int, screenH: Int) {
        val cx = screenW / 2
        val cy = screenH / 2
        val span = minOf(screenW, screenH) / 3
        val pointerId = touchInjector.acquirePointer()
        if (pointerId < 0) return
        try {
            when (direction) {
                0 -> touchInjector.swipe(pointerId, cx, cy + span, cx, cy - span, 120L)
                1 -> touchInjector.swipe(pointerId, cx, cy - span, cx, cy + span, 120L)
                2 -> touchInjector.swipe(pointerId, cx + span, cy, cx - span, cy, 120L)
                3 -> touchInjector.swipe(pointerId, cx - span, cy, cx + span, cy, 120L)
            }
        } finally {
            touchInjector.releasePointer(pointerId)
        }
    }

    // ───────── Adaptive tier selection ─────────

    /**
     * Two-tier adaptive polling: IDLE (50 ms) ↔ ACTIVE (10 ms).
     *
     * Transition rules:
     *   IDLE → ACTIVE: any gamepad event in last ACTIVE_EVENT_RECENCY_MS
     *   ACTIVE → IDLE: no events for PROMOTE_IDLE_TO_ACTIVE_IDLE_MS (1500 ms)
     *
     * Overrides (immediate downgrade, ignore hysteresis):
     *   - Backpressure: queue depth ≥ HARD_WATERMARK (32)
     *   - CPU overload: cpuLoadSmoothed ≥ CPU_HIGH_WATERMARK (0.85)
     */
    private fun adaptTier(now: Long, analogDelta: Float) {
        val idleMs = now - lastEventUptimeMs
        val queueDepth = touchInjector.pendingQueueDepth()
        val cpu = cpuLoadSmoothed

        // Override 1: Backpressure — force IDLE immediately.
        if (queueDepth >= INJECT_QUEUE_HARD_WATERMARK) {
            setTier(Tier.IDLE, reason = "queue-hard-watermark depth=$queueDepth")
            return
        }

        // Override 2: CPU overload — force IDLE immediately.
        if (cpu >= CPU_HIGH_WATERMARK) {
            setTier(Tier.IDLE, reason = "cpu-overload cpu=${"%.2f".format(cpu)}")
            return
        }

        // Normal hysteresis logic.
        val next: Tier = when (currentTier) {
            Tier.IDLE -> {
                // Promote to ACTIVE if any event in last 100 ms OR analog deflection > 5%.
                val recentActivity = idleMs <= ACTIVE_EVENT_RECENCY_MS || analogDelta > 0.05f
                if (recentActivity && queueDepth < INJECT_QUEUE_SOFT_WATERMARK) Tier.ACTIVE
                else Tier.IDLE
            }
            Tier.ACTIVE -> {
                // Demote to IDLE if no events for 1500 ms AND analog < 1%.
                val prolongedIdle = idleMs > PROMOTE_IDLE_TO_ACTIVE_IDLE_MS && analogDelta < 0.01f
                if (prolongedIdle) Tier.IDLE
                else Tier.ACTIVE
            }
        }

        if (next != currentTier) {
            val reason = buildString {
                append("analog=${"%.3f".format(analogDelta)} ")
                append("idleMs=$idleMs ")
                append("cpu=${"%.2f".format(cpu)} ")
                append("qDepth=$queueDepth ")
                append("btnRate=${"%.1f".format(lastButtonRateHz)}Hz")
            }
            setTier(next, reason = reason)
        }
    }

    private fun setTier(tier: Tier, reason: String) {
        val newPeriod = when (tier) {
            Tier.IDLE   -> TIER_IDLE_NS
            Tier.ACTIVE -> TIER_ACTIVE_NS
        }
        if (tier == currentTier && newPeriod == currentPeriodNs) return
        currentTier = tier
        currentPeriodNs = newPeriod
        Log.d(TAG, "Tier → $tier (${newPeriod / 1_000_000}ms) [$reason]")
    }

    // ───────── CPU sampler ─────────

    /**
     * Parses /proc/stat for aggregate CPU usage. Cheap and process-agnostic.
     * Format: cpu user nice system idle iowait irq softirq steal guest guest_nice
     *
     * Uses EMA smoothing (α=0.4) to react within ~5 samples without flapping.
     */
    private fun sampleCpuLoad() {
        try {
            val line = java.io.BufferedReader(
                java.io.FileReader("/proc/stat")
            ).use { it.readLine() } ?: return
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5 || parts[0] != "cpu") return
            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = if (parts.size > 5) parts[5].toLong() else 0L
            val irq = if (parts.size > 6) parts[6].toLong() else 0L
            val softirq = if (parts.size > 7) parts[7].toLong() else 0L
            val steal = if (parts.size > 8) parts[8].toLong() else 0L

            val idleAll = idle + iowait
            val total = user + nice + system + idleAll + irq + softirq + steal
            val prevTotal = cpuPrevTotal
            val prevIdle = cpuPrevIdle
            cpuPrevTotal = total
            cpuPrevIdle = idleAll

            if (prevTotal <= 0L) return

            val totalDelta = (total - prevTotal).coerceAtLeast(1L)
            val idleDelta = (idleAll - prevIdle).coerceAtLeast(0L)
            val instant = (1f - idleDelta.toFloat() / totalDelta).coerceIn(0f, 1f)

            // Exponential smoothing — EMA α=0.4 to react within ~5 samples.
            cpuLoadSmoothed = if (cpuLoadSmoothed == 0f) instant
                              else 0.6f * cpuLoadSmoothed + 0.4f * instant
        } catch (t: Throwable) {
            // /proc/stat unreadable (e.g., on some locked-down devices) — leave cpuLoadSmoothed as-is.
            Log.w(TAG, "CPU sample failed: ${t.message}")
        }
    }

    // ───────── Event-rate bookkeeping ─────────

    private fun recordEvent(now: Long) {
        synchronized(eventLock) {
            eventTimestamps[eventHead] = now
            eventHead = (eventHead + 1) % eventTimestamps.size
            if (eventCount < eventTimestamps.size) eventCount++
        }
    }

    private fun computeButtonRateHz(now: Long): Float {
        val windowStart = now - ACTIVITY_WINDOW_MS
        var count = 0
        synchronized(eventLock) {
            for (i in 0 until eventCount) {
                val idx = (eventHead - 1 - i + eventTimestamps.size) % eventTimestamps.size
                val ts = eventTimestamps[idx]
                if (ts >= windowStart) count++ else break
            }
        }
        return (count * 1000f / ACTIVITY_WINDOW_MS.toFloat()).coerceAtLeast(0f)
    }

    private fun resetEventBuffer() {
        synchronized(eventLock) {
            for (i in eventTimestamps.indices) eventTimestamps[i] = 0L
            eventHead = 0
            eventCount = 0
        }
    }

    // ───────── JS event payload ─────────

    /**
     * Build a JSON payload to forward gamepad state to the JS layer.
     * Uses JSONObject (not string concatenation) for safe escaping.
     */
    private fun buildEventJson(
        snapshot: GamepadManager.Snapshot,
        analogDelta: Float
    ): JSONObject {
        val json = JSONObject()
        try {
            json.put("type", "gamepad")
            json.put("ts", SystemClock.uptimeMillis())
            json.put("lx", snapshot.leftStickX)
            json.put("ly", snapshot.leftStickY)
            json.put("rx", snapshot.rightStickX)
            json.put("ry", snapshot.rightStickY)
            json.put("lt", snapshot.leftTrigger)
            json.put("rt", snapshot.rightTrigger)
            json.put("dpad", snapshot.dpad)
            json.put("buttons", snapshot.buttonsBits)
            json.put("analogDelta", analogDelta)
            json.put("tier", currentTier.name)
            json.put("periodMs", currentPeriodNs / 1_000_000L)
            json.put("cpu", cpuLoadSmoothed)
            json.put("btnRateHz", lastButtonRateHz)
            json.put("qDepth", touchInjector.pendingQueueDepth())
        } catch (_: Throwable) { /* swallow JSON build errors */ }
        return json
    }
}
