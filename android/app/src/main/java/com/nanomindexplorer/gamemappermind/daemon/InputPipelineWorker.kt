package com.nanomindexplorer.gamemappermind.daemon

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.GamepadManager
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.model.GameProfile
import com.nanomindexplorer.gamemappermind.model.Mapping
import org.json.JSONObject

/**
 * InputPipelineWorker — adaptive 60–250 Hz pipeline.
 *
 * FASE 2.1 — Dynamic Adaptive Polling:
 *  - Monitors CPU load (via /proc/stat) and gamepad activity (analog delta + button rate).
 *  - Dynamically switches polling period among {4 ms (250 Hz), 8 ms (125 Hz), 16 ms (60 Hz)}
 *    using a hysteresis band to avoid flapping.
 *  - Touch-injection backpressure detection: if the injector queue depth crosses a watermark,
 *    the pipeline temporarily downshifts to avoid flooding InputManager.
 *  - All sensitive state is guarded by `pipelineLock`; hot-path reads use volatile snapshots.
 *
 * Thread model:
 *  - Worker thread priority = THREAD_PRIORITY_URGENT_DISPLAY (-8) to minimize jitter.
 *  - Single looper, no chained handlers — every tick is self-contained.
 */
class InputPipelineWorker(
    private val gamepadManager: GamepadManager,
    private val touchInjector: TouchInjector,
    private val analogProcessor: AnalogProcessor,
    private val onGamepadEvent: (JSONObject) -> Unit
) {
    companion object {
        private const val TAG = "InputPipelineWorker"

        // Polling tiers (nanoseconds per tick).
        private const val TIER_HIGH_NS   = 4_000_000L   // 250 Hz — active analog
        private const val TIER_MID_NS    = 8_000_000L   // 125 Hz — buttons only / light analog
        private const val TIER_LOW_NS    = 16_000_000L  // 60 Hz  — idle / backpressure

        // Hysteresis thresholds (avoid flapping).
        private const val PROMOTE_MID_TO_HIGH_ANALOG_DELTA = 0.06f   // ≥6% deflection → high
        private const val DEMOTE_HIGH_TO_MID_ANALOG_DELTA  = 0.02f   // ≤2% for ≥300 ms → mid
        private const val DEMOTE_MID_TO_LOW_IDLE_MS        = 1_500L  // no events 1.5 s → low
        private const val PROMOTE_LOW_TO_MID_ANY_EVENT     = true    // any event → mid

        // CPU load thresholds (0..1).
        private const val CPU_HIGH_WATERMARK = 0.85f
        private const val CPU_LOW_WATERMARK  = 0.55f

        // Touch-injection backpressure.
        private const val INJECT_QUEUE_HARD_WATERMARK = 32
        private const val INJECT_QUEUE_SOFT_WATERMARK = 12

        // CPU sampling cadence — /proc/stat parsing is cheap but not free.
        private const val CPU_SAMPLE_INTERVAL_TICKS = 25  // ~every 100 ms @ 250 Hz

        // Activity bookkeeping window.
        private const val ACTIVITY_WINDOW_MS = 250L
    }

    // ───────── Pipeline state ─────────
    @Volatile private var running = false
    @Volatile private var currentPeriodNs = TIER_LOW_NS
    @Volatile private var currentTier: Tier = Tier.LOW

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

    // High-tier residency tracker (to apply hysteresis on demotion).
    @Volatile private var lowAnalogSinceMs: Long = 0L

    // ───────── CPU sampler ─────────
    private var cpuPrevIdle = 0L
    private var cpuPrevTotal = 0L
    @Volatile private var cpuLoadSmoothed = 0f
    private var cpuSampleTickCounter = 0

    private enum class Tier { LOW, MID, HIGH }

    // ───────── Lifecycle ─────────

    fun start() {
        synchronized(pipelineLock) {
            if (running) return
            running = true
            currentTier = Tier.LOW
            currentPeriodNs = TIER_LOW_NS
            lowAnalogSinceMs = SystemClock.uptimeMillis()
            cpuPrevIdle = 0L
            cpuPrevTotal = 0L
            cpuLoadSmoothed = 0f
            cpuSampleTickCounter = 0
            resetEventBuffer()

            val thread = HandlerThread("GMM-Pipeline", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                start()
            }
            workerThread = thread
            workerHandler = Handler(thread.looper)
            workerHandler?.post(tickRunnable)
            Log.i(TAG, "Pipeline started @ tier=${currentTier} period=${currentPeriodNs}ns")
        }
    }

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

    private val tickRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val tickStartNs = SystemClock.elapsedRealtimeNanos()
                processTick()
                val elapsedNs = SystemClock.elapsedRealtimeNanos() - tickStartNs

                // Schedule next tick at adaptive cadence.
                val period = currentPeriodNs
                val delayMs = ((period - elapsedNs).coerceAtLeast(1L)) / 1_000_000L
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

        // 2) Read gamepad snapshot (lock-free).
        val snapshot = gamepadManager.snapshot() ?: return
        val now = SystemClock.uptimeMillis()

        // 3) Compute analog delta magnitude (0..1).
        val lx = snapshot.leftStickX
        val ly = snapshot.leftStickY
        val rx = snapshot.rightStickX
        val ry = snapshot.rightStickY
        val analogDelta = maxOf(
            Math.hypot(lx.toDouble(), ly.toDouble()).toFloat(),
            Math.hypot(rx.toDouble(), ry.toDouble()).toFloat()
        )
        lastAnalogDelta = analogDelta

        // 4) Record event for rate computation.
        if (snapshot.hasAnyButton() || analogDelta > 0.01f) {
            recordEvent(now)
            lastEventUptimeMs = now
        }

        // 5) Update button rate (Hz over last ACTIVITY_WINDOW_MS).
        lastButtonRateHz = computeButtonRateHz(now)

        // 6) Forward snapshot to JS layer (throttled inside the callback).
        try {
            onGamepadEvent(buildEventJson(snapshot, analogDelta))
        } catch (_: Throwable) { /* never block pipeline on JS bridge */ }

        // 7) Apply profile mappings → inject touches.
        val p = profile
        if (p != null) {
            applyMappings(p, snapshot, analogDelta)
        }

        // 8) Adaptive tier selection.
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

        // Analog sticks → continuous pointers.
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

        // Buttons → tap/swipe.
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
        val x = (m.xPercent * screenW).toInt().coerceIn(0, screenW - 1)
        val y = (m.yPercent * screenH).toInt().coerceIn(0, screenH - 1)
        when (m.action) {
            Mapping.ACTION_TAP -> {
                touchInjector.tap(pointerSlot = m.id + 10, x = x, y = y)
            }
            Mapping.ACTION_SWIPE -> {
                touchInjector.swipe(
                    pointerSlot = m.id + 10,
                    x1 = x,
                    y1 = y,
                    x2 = (m.endXPercent * screenW).toInt().coerceIn(0, screenW - 1),
                    y2 = (m.endYPercent * screenH).toInt().coerceIn(0, screenH - 1),
                    durationMs = m.durationMs.coerceAtLeast(16L)
                )
            }
        }
    }

    private fun triggerSwipe(direction: Int, screenW: Int, screenH: Int) {
        val cx = screenW / 2
        val cy = screenH / 2
        val span = minOf(screenW, screenH) / 3
        when (direction) {
            0 -> touchInjector.swipe(99, cx, cy + span, cx, cy - span, 120L)
            1 -> touchInjector.swipe(99, cx, cy - span, cx, cy + span, 120L)
            2 -> touchInjector.swipe(99, cx + span, cy, cx - span, cy, 120L)
            3 -> touchInjector.swipe(99, cx - span, cy, cx + span, cy, 120L)
        }
    }

    // ───────── Adaptive tier selection ─────────

    private fun adaptTier(now: Long, analogDelta: Float) {
        val idleMs = now - lastEventUptimeMs
        val queueDepth = touchInjector.pendingQueueDepth()
        val cpu = cpuLoadSmoothed

        // Backpressure overrides — drop tier immediately, ignore hysteresis.
        if (queueDepth >= INJECT_QUEUE_HARD_WATERMARK) {
            setTier(Tier.LOW, reason = "queue-hard-watermark depth=$queueDepth")
            return
        }

        // CPU overload — clamp to MID or LOW.
        if (cpu >= CPU_HIGH_WATERMARK && currentTier == Tier.HIGH) {
            setTier(Tier.MID, reason = "cpu-high cpu=${"%.2f".format(cpu)}")
            // fall through to finer-grained logic below
        }

        val next: Tier = when (currentTier) {
            Tier.LOW -> {
                if (queueDepth >= INJECT_QUEUE_SOFT_WATERMARK) Tier.LOW
                else if (idleMs <= 50L || analogDelta > 0.05f) Tier.MID
                else Tier.LOW
            }
            Tier.MID -> {
                when {
                    cpu >= CPU_HIGH_WATERMARK -> Tier.LOW
                    idleMs > DEMOTE_MID_TO_LOW_IDLE_MS && analogDelta < 0.01f -> Tier.LOW
                    analogDelta >= PROMOTE_MID_TO_HIGH_ANALOG_DELTA &&
                        cpu < CPU_HIGH_WATERMARK &&
                        queueDepth < INJECT_QUEUE_SOFT_WATERMARK -> Tier.HIGH
                    else -> Tier.MID
                }
            }
            Tier.HIGH -> {
                if (cpu >= CPU_HIGH_WATERMARK) Tier.MID
                else if (queueDepth >= INJECT_QUEUE_SOFT_WATERMARK) Tier.MID
                else if (analogDelta <= DEMOTE_HIGH_TO_MID_ANALOG_DELTA) {
                    if (lowAnalogSinceMs == 0L) lowAnalogSinceMs = now
                    if (now - lowAnalogSinceMs >= 300L) Tier.MID else Tier.HIGH
                } else {
                    lowAnalogSinceMs = 0L
                    Tier.HIGH
                }
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
            Tier.LOW  -> TIER_LOW_NS
            Tier.MID  -> TIER_MID_NS
            Tier.HIGH -> TIER_HIGH_NS
        }
        if (tier == currentTier && newPeriod == currentPeriodNs) return
        currentTier = tier
        currentPeriodNs = newPeriod
        if (tier != Tier.HIGH) lowAnalogSinceMs = 0L
        Log.d(TAG, "Tier → $tier (${newPeriod / 1_000_000}ms) [$reason]")
    }

    // ───────── CPU sampler ─────────

    /**
     * Parses /proc/stat for aggregate CPU usage. Cheap and process-agnostic.
     * Format: cpu user nice system idle iowait irq softirq steal guest guest_nice
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
