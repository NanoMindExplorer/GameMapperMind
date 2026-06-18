package com.nanomindexplorer.gamemappermind.util

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * NativeDaemonLogger — Circular buffer logger untuk injection events.
 *
 * Implementasi klausul §12.1 + §12.2 GMM-AEC-002:
 *   §12.1: Native Daemon Log Export — circular buffer 500 entry + share
 *   §12.2: Format log [INJECT] timestamp | action | x,y | pointerId | result | latency
 *
 * Algorithm:
 *   - ConcurrentLinkedDeque untuk thread-safe append + iterate
 *   - Max 500 entries — otomatis hapus entry tertua saat penuh
 *   - Format log structured untuk easy parsing:
 *     [INJECT] 2026-06-19 10:30:45.123 | TAP | 1280,920 | 99 | OK | 5ms
 *   - Export ke file + share via Intent
 *
 * Thread safety:
 *   - ConcurrentLinkedDeque is thread-safe
 *   - All public methods dapat dipanggil dari multiple threads
 *
 * Complexity:
 *   - logInjection: O(1) amortized (deque add + occasional remove)
 *   - getLogExport: O(n) where n = number of entries
 *   - clear: O(1) (just clear the deque)
 */
object NativeDaemonLogger {
    private const val TAG = "GameMapper/DaemonLogger"

    // §12.1: Max 500 entries
    private const val MAX_ENTRIES = 500

    // Thread-safe deque untuk circular buffer
    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()

    // Date format untuk timestamp
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Log entry data class.
     */
    data class LogEntry(
        val timestamp: Long,
        val action: String,        // TAP, SWIPE, ANALOG_MOVE, TOUCH_UP, etc.
        val x: Float,
        val y: Float,
        val pointerId: Int,
        val result: String,        // OK, FAIL, REJECTED, ERROR
        val latencyMs: Long,
        val errorMessage: String? = null
    )

    /**
     * §12.2: Log injection event dengan format structured.
     *
     * @param action TAP, SWIPE, ANALOG_MOVE, TOUCH_UP, etc.
     * @param x X pixel coordinate
     * @param y Y pixel coordinate
     * @param pointerId Pointer ID yang dipakai
     * @param result OK / FAIL / REJECTED / ERROR
     * @param latencyMs Latency injection dalam milliseconds
     * @param errorMessage Optional error message jika result != OK
     */
    fun logInjection(
        action: String,
        x: Float,
        y: Float,
        pointerId: Int,
        result: String,
        latencyMs: Long,
        errorMessage: String? = null
    ) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            action = action,
            x = x,
            y = y,
            pointerId = pointerId,
            result = result,
            latencyMs = latencyMs,
            errorMessage = errorMessage
        )

        logBuffer.addLast(entry)

        // Trim jika exceed max entries (circular buffer behavior)
        while (logBuffer.size > MAX_ENTRIES) {
            logBuffer.pollFirst()
        }

        // Also log to logcat untuk real-time debugging
        val formatted = formatEntry(entry)
        when (result) {
            "OK" -> Log.d(TAG, formatted)
            "FAIL", "REJECTED" -> Log.w(TAG, formatted)
            "ERROR" -> Log.e(TAG, formatted)
        }
    }

    /**
     * Format log entry ke string sesuai §12.2 format.
     *
     * Format: [INJECT] yyyy-MM-dd HH:mm:ss.SSS | ACTION | x,y | pointerId | result | latencyMs
     */
    private fun formatEntry(entry: LogEntry): String {
        val dateStr = dateFormat.format(Date(entry.timestamp))
        val errorPart = if (entry.errorMessage != null) " | ${entry.errorMessage}" else ""
        return "[INJECT] $dateStr | ${entry.action} | ${entry.x.toInt()},${entry.y.toInt()} | " +
               "${entry.pointerId} | ${entry.result} | ${entry.latencyMs}ms$errorPart"
    }

    /**
     * Get all log entries sebagai formatted string untuk export.
     *
     * @return String berisi semua log entries (max 500), satu per baris
     */
    fun getLogExport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== GameMapperMind Native Daemon Log Export ===")
        sb.appendLine("Generated: ${dateFormat.format(Date())}")
        sb.appendLine("Entries: ${logBuffer.size}/$MAX_ENTRIES")
        sb.appendLine("===")

        for (entry in logBuffer) {
            sb.appendLine(formatEntry(entry))
        }

        sb.appendLine("=== End of Log ===")
        return sb.toString()
    }

    /**
     * Get entry count.
     */
    fun getEntryCount(): Int = logBuffer.size

    /**
     * Clear all log entries.
     */
    fun clearLog() {
        val count = logBuffer.size
        logBuffer.clear()
        Log.i(TAG, "Log cleared ($count entries removed)")
    }

    /**
     * Get last N entries (untuk UI display real-time).
     *
     * @param count Number of last entries to return (default 50)
     * @return List of formatted log strings
     */
    fun getLastEntries(count: Int = 50): List<String> {
        val result = mutableListOf<String>()
        val entries = logBuffer.toList()
        val startIndex = maxOf(0, entries.size - count)
        for (i in startIndex until entries.size) {
            result.add(formatEntry(entries[i]))
        }
        return result
    }

    /**
     * Get statistics untuk UI dashboard.
     *
     * @return Map berisi: totalEntries, okCount, failCount, errorCount, avgLatencyMs
     */
    fun getStatistics(): Map<String, Any> {
        val entries = logBuffer.toList()
        val total = entries.size
        val okCount = entries.count { it.result == "OK" }
        val failCount = entries.count { it.result == "FAIL" || it.result == "REJECTED" }
        val errorCount = entries.count { it.result == "ERROR" }
        val avgLatency = if (total > 0) entries.map { it.latencyMs }.average().toLong() else 0L

        return mapOf(
            "totalEntries" to total,
            "okCount" to okCount,
            "failCount" to failCount,
            "errorCount" to errorCount,
            "avgLatencyMs" to avgLatency,
            "maxEntries" to MAX_ENTRIES
        )
    }

    /**
     * Export log to file untuk share via Intent.
     *
     * @param context Context untuk akses filesDir
     * @return File object, atau null jika gagal
     */
    fun exportToFile(context: android.content.Context): File? {
        return try {
            val logFile = File(context.filesDir, "gamemapper_daemon_log.txt")
            logFile.writeText(getLogExport())
            Log.i(TAG, "Log exported to ${logFile.absolutePath} (${logFile.length()} bytes)")
            logFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export log to file: ${e.message}", e)
            null
        }
    }
}
