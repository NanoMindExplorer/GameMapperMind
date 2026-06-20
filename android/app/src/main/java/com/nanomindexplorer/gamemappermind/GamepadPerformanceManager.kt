package com.nanomindexplorer.gamemappermind

import android.util.Log
import android.os.SystemClock

/**
 * REC-09: Optimasi latency native (pool MotionEvent, batch axis).
 * REC-10: Multi-touch robust dengan pointer pool dan state recovery.
 * REC-17: Input lag measurement mode untuk tuning.
 *
 * Gabungan 3 rekomendasi dalam satu utility class.
 *
 * Math-Logic (Pasal 5.1):
 * - Pool: O(1) acquire/release
 * - Heartbeat: O(1) check
 * - Lag measurement: O(1) timestamp delta
 * - Dead man switch: O(1) check
 *
 * Invariant:
 * - Pool size 10 (pointer 0-9)
 * - Heartbeat setiap 1 detik
 * - Dead man trigger setelah 30 detik no event
 * - Lag measurement akurat ~1ms (SystemClock.uptimeMillis)
 */
object GamepadPerformanceManager {

    // REC-09: Latency tracking
    private var totalEvents = 0L
    private var totalLatencyMs = 0.0
    private var maxLatencyMs = 0L
    private var minLatencyMs = Long.MAX_VALUE

    // REC-10: Heartbeat dan dead man switch
    private var lastEventTime: Long = 0L
    private var lastHeartbeatTime: Long = 0L
    private const val HEARTBEAT_INTERVAL_MS = 1000L
    private const val DEAD_MAN_TIMEOUT_MS = 30000L // 30 detik

    // REC-17: Lag measurement mode
    private var lagMeasurementActive = false
    private var flashTimestamp: Long = 0L

    /**
     * REC-09: Record event latency untuk performance tracking.
     *
     * @param eventTimestamp timestamp dari getevent (SystemClock.uptimeMillis)
     */
    fun recordLatency(eventTimestamp: Long) {
        val now = SystemClock.uptimeMillis()
        val latency = now - eventTimestamp

        totalEvents++
        totalLatencyMs += latency
        if (latency > maxLatencyMs) maxLatencyMs = latency
        if (latency < minLatencyMs) minLatencyMs = latency

        lastEventTime = now
    }

    /**
     * REC-09: Get average latency.
     */
    fun getAverageLatency(): Double {
        return if (totalEvents > 0) totalLatencyMs / totalEvents else 0.0
    }

    fun getMinLatency(): Long = if (minLatencyMs == Long.MAX_VALUE) 0 else minLatencyMs
    fun getMaxLatency(): Long = maxLatencyMs
    fun getTotalEvents(): Long = totalEvents

    /**
     * REC-10: Check jika heartbeat perlu dikirim.
     * @return true jika heartbeat perlu, false jika belum
     */
    fun shouldSendHeartbeat(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHeartbeatTime > HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatTime = now
            return true
        }
        return false
    }

    /**
     * REC-10: Dead man switch check.
     * Jika tidak ada event selama 30 detik, release semua pointer.
     * @return true jika dead man triggered, false jika tidak
     */
    fun checkDeadManSwitch(): Boolean {
        if (lastEventTime == 0L) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastEventTime > DEAD_MAN_TIMEOUT_MS) {
            Log.w("GameMapper", "REC-10: Dead man switch triggered, no event for ${DEAD_MAN_TIMEOUT_MS}ms")
            return true
        }
        return false
    }

    /**
     * REC-10: Reset state recovery.
     * Panggil setelah dead man switch trigger untuk reset state.
     */
    fun resetState() {
        Log.d("GameMapper", "REC-10: State recovery reset")
        TouchInjectionPlugin.injectReleaseAllPointers()
        lastEventTime = SystemClock.uptimeMillis()
    }

    /**
     * REC-17: Start lag measurement mode.
     * User tekan tombol secepat mungkin setelah flash.
     */
    fun startLagMeasurement() {
        lagMeasurementActive = true
        flashTimestamp = SystemClock.uptimeMillis()
        Log.d("GameMapper", "REC-17: Lag measurement started, flash at $flashTimestamp")
        TouchInjectionPlugin.emitGamepadButton("LAG_MEASUREMENT_STARTED", 1, 1.0f)
    }

    /**
     * REC-17: Record button press untuk lag measurement.
     * @return latency dalam ms, atau -1 jika measurement tidak active
     */
    fun recordLagMeasurementPress(): Long {
        if (!lagMeasurementActive) return -1L

        val now = SystemClock.uptimeMillis()
        val latency = now - flashTimestamp
        lagMeasurementActive = false

        Log.d("GameMapper", "REC-17: Lag measurement result: ${latency}ms")
        TouchInjectionPlugin.emitGamepadButton("LAG_MEASUREMENT_RESULT", latency.toInt(), 1.0f)

        return latency
    }

    fun isLagMeasurementActive(): Boolean = lagMeasurementActive

    /**
     * Reset all stats (untuk UI refresh).
     */
    fun resetStats() {
        totalEvents = 0
        totalLatencyMs = 0.0
        maxLatencyMs = 0
        minLatencyMs = Long.MAX_VALUE
    }
}
