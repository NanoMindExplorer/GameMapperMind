package com.nanomindexplorer.gamemappermind.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nanomindexplorer.gamemappermind.MainActivity
import com.nanomindexplorer.gamemappermind.daemon.MapperDaemonService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ShizukuBinderWatcher — Monitor status binder Shizuku setiap 30 detik.
 *
 * Implementasi klausul §10.1 GMM-AEC-002:
 *   Wajib implementasikan ShizukuBinderWatcher: service yang memantau status binder
 *   setiap 30 detik dan otomatis re-request permission + rebind jika
 *   Shizuku.checkSelfPermission() != PERMISSION_GRANTED.
 *
 * Implementasi klausul §10.2 GMM-AEC-002:
 *   Saat Shizuku permission drop terdeteksi, wajib:
 *   (1) tampilkan persistent notification dengan action "Perbaiki Sekarang",
 *   (2) log event ke file untuk debugging,
 *   (3) jangan crash — graceful degradation.
 *
 * Algorithm:
 *   1. Start HandlerThread dengan periodic check setiap 30s
 *   2. Check Shizuku.pingBinder() — jika false, binder dead
 *   3. Check Shizuku.checkSelfPermission() — jika tidak granted, permission drop
 *   4. Jika binder dead → schedule reconnect (backoff exponential)
 *   5. Jika permission drop → tampilkan persistent notification
 *   6. Jika binder alive + permission granted → clear notification, auto-rebind service
 *   7. Log setiap event ke file /data/data/<pkg>/files/shizuku_watcher.log
 *
 * Thread safety:
 *   - HandlerThread dedicated (bukan main thread)
 *   - @Volatile status fields untuk visibility
 *   - Synchronized block untuk log file writes
 *
 * Complexity:
 *   - Per check: O(1) (2 system calls)
 *   - Log write: O(1) (append-only)
 *   - Notification update: O(1) (system call)
 *
 * State machine:
 *   RUNNING → (binder drops) → RECONNECTING → (binder alive) → RUNNING
 *   RUNNING → (permission drops) → PERMISSION_LOST → (granted) → RUNNING
 *   RUNNING → (binder+permission OK) → RUNNING (no-op)
 */
class ShizukuBinderWatcher private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GameMapper/ShizukuWatcher"

        // Kontrak §10.1: check setiap 30 detik
        private const val CHECK_INTERVAL_MS = 30_000L

        // Backoff exponential untuk reconnect attempts
        private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L

        // Notification IDs and channels
        private const val CHANNEL_ID = "shizuku_status_channel"
        private const val CHANNEL_NAME = "Shizuku Status"
        private const val NOTIFICATION_ID = 2002

        // Action untuk "Perbaiki Sekarang" button
        const val ACTION_FIX_NOW = "com.nanomindexplorer.gamemappermind.FIX_SHIZUKU_NOW"

        // State machine values
        enum class WatcherState {
            IDLE,               // Watcher not started
            RUNNING,            // Binder alive + permission granted + service connected
            RECONNECTING,       // Binder dead, attempting reconnect
            PERMISSION_LOST,    // Binder alive but permission not granted
            BINDER_DEAD         // Shizuku process not running
        }

        @Volatile
        private var instance: ShizukuBinderWatcher? = null

        fun getInstance(context: Context): ShizukuBinderWatcher {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = ShizukuBinderWatcher(context.applicationContext)
                    }
                }
            }
            return instance!!
        }
    }

    // ============================================================
    // State — all @Volatile for cross-thread visibility
    // ============================================================
    @Volatile
    private var state: WatcherState = WatcherState.IDLE

    @Volatile
    private var watcherHandler: Handler? = null

    @Volatile
    private var watcherThread: android.os.HandlerThread? = null

    @Volatile
    private var isWatching = false

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var lastCheckTime: Long = 0L

    @Volatile
    private var onStateChangeListener: ((WatcherState) -> Unit)? = null

    // ============================================================
    // Watcher runnable — runs on watcherThread every CHECK_INTERVAL_MS
    // ============================================================
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isWatching) return
            performCheck()
            // Schedule next check
            watcherHandler?.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /**
     * Mulai monitoring Shizuku binder.
     * Idempotent — calling multiple times is safe.
     */
    fun start() {
        if (isWatching) {
            Log.d(TAG, "start() called but watcher already running")
            return
        }
        Log.i(TAG, "Starting ShizukuBinderWatcher (interval=${CHECK_INTERVAL_MS}ms)")

        watcherThread = android.os.HandlerThread("ShizukuWatcherThread").also { it.start() }
        watcherHandler = Handler(watcherThread!!.looper)

        isWatching = true
        setState(WatcherState.RUNNING)

        // First check immediately
        watcherHandler?.post(checkRunnable)

        logEvent("Watcher started")
    }

    /**
     * Stop monitoring.
     */
    fun stop() {
        if (!isWatching) return
        Log.i(TAG, "Stopping ShizukuBinderWatcher")

        isWatching = false
        watcherHandler?.removeCallbacksAndMessages(null)
        watcherThread?.quitSafely()
        watcherThread = null
        watcherHandler = null

        setState(WatcherState.IDLE)
        cancelNotification()
        logEvent("Watcher stopped")
    }

    /**
     * Set listener untuk state changes — dipanggil dari watcherThread.
     * Listener harus thread-safe atau post ke main thread sendiri.
     */
    fun setOnStateChangeListener(listener: (WatcherState) -> Unit) {
        onStateChangeListener = listener
    }

    /**
     * Get current state.
     */
    fun getState(): WatcherState = state

    /**
     * Get last check timestamp (ms since epoch).
     */
    fun getLastCheckTime(): Long = lastCheckTime

    /**
     * Manual trigger — user tap "Perbaiki Sekarang" di notification.
     * Dipanggil dari FixShizukuReceiver.
     */
    fun triggerFixNow() {
        Log.i(TAG, "triggerFixNow() — manual fix requested")
        logEvent("Manual fix triggered by user")
        // Force immediate check
        watcherHandler?.removeCallbacks(checkRunnable)
        watcherHandler?.post(checkRunnable)
    }

    /**
     * Perform single check of Shizuku binder + permission status.
     */
    private fun performCheck() {
        lastCheckTime = System.currentTimeMillis()

        try {
            val shizukuHelper = ShizukuHelper.getInstance(context)
            val binderAlive = shizukuHelper.isBinderAlive()

            if (!binderAlive) {
                // Shizuku process not running
                handleBinderDead()
                return
            }

            val permissionGranted = shizukuHelper.checkPermission()

            if (!permissionGranted) {
                // Permission dropped — show notification
                handlePermissionLost()
                return
            }

            // Both OK — check if service is connected
            val serviceConnected = shizukuHelper.isServiceConnected()
            if (!serviceConnected) {
                // Service not connected — attempt rebind
                Log.i(TAG, "Binder alive + permission granted but service not connected — rebinding")
                val bound = shizukuHelper.bindUserService()
                if (bound) {
                    logEvent("Service rebound successfully")
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    logEvent("Service rebind failed (attempt $consecutiveFailures)")
                }
            } else {
                // Everything OK
                if (state != WatcherState.RUNNING) {
                    Log.i(TAG, "Shizuku fully operational — clearing notification")
                    consecutiveFailures = 0
                    setState(WatcherState.RUNNING)
                    cancelNotification()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "performCheck failed: ${e.message}", e)
            logEvent("Check failed: ${e.message}")
            consecutiveFailures++
        }
    }

    /**
     * Handle case where Shizuku binder is dead (Shizuku process not running).
     */
    private fun handleBinderDead() {
        Log.w(TAG, "Shizuku binder DEAD — process not running")
        setState(WatcherState.BINDER_DEAD)
        showPersistentNotification(
            "Shizuku tidak berjalan",
            "Buka aplikasi Shizuku dan start service untuk mengembalikan touch injection"
        )
        logEvent("Binder dead (consecutive failures: $consecutiveFailures)")

        // Schedule reconnect with exponential backoff
        val delay = (INITIAL_RECONNECT_DELAY_MS * (1L shl consecutiveFailures.coerceAtMost(4)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        Log.i(TAG, "Scheduling reconnect in ${delay}ms (backoff)")

        // Note: the periodic checkRunnable will keep running every 30s anyway,
        // but we also schedule an earlier check with backoff
        watcherHandler?.postDelayed({
            if (isWatching && state == WatcherState.BINDER_DEAD) {
                performCheck()
            }
        }, delay)

        consecutiveFailures++
    }

    /**
     * Handle case where binder is alive but permission is not granted.
     */
    private fun handlePermissionLost() {
        Log.w(TAG, "Shizuku permission LOST — binder alive but checkSelfPermission != GRANTED")
        setState(WatcherState.PERMISSION_LOST)
        showPersistentNotification(
            "Izin Shizuku hilang",
            "Tap 'Perbaiki Sekarang' untuk request ulang izin Shizuku"
        )
        logEvent("Permission lost — showing fix notification")

        // Attempt to re-request permission automatically
        try {
            val shizukuHelper = ShizukuHelper.getInstance(context)
            shizukuHelper.requestPermission()
            logEvent("Auto re-request permission sent")
        } catch (e: Exception) {
            Log.e(TAG, "Auto re-request permission failed: ${e.message}", e)
            logEvent("Auto re-request failed: ${e.message}")
        }
    }

    /**
     * Update state and notify listener.
     */
    private fun setState(newState: WatcherState) {
        if (state == newState) return
        val oldState = state
        state = newState
        Log.i(TAG, "State transition: $oldState → $newState")
        onStateChangeListener?.invoke(newState)
    }

    /**
     * Show persistent notification with "Perbaiki Sekarang" action.
     *
     * Kontrak §10.2: wajib tampilkan persistent notification dengan action
     * "Perbaiki Sekarang" saat Shizuku permission drop terdeteksi.
     */
    private fun showPersistentNotification(title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // Create channel (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts ketika Shizuku disconnect atau permission hilang"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }

        // "Perbaiki Sekarang" action — triggers FixShizukuReceiver
        val fixIntent = Intent(context, FixShizukuReceiver::class.java).apply {
            action = ACTION_FIX_NOW
        }
        val fixPendingIntent = PendingIntent.getBroadcast(
            context, 0, fixIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tap notification → open MainActivity
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("⚠ $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(true)  // Persistent — cannot be swiped away
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_rotate,
                "Perbaiki Sekarang",
                fixPendingIntent
            )
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification: ${e.message}", e)
        }
    }

    /**
     * Cancel persistent notification.
     */
    private fun cancelNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        try {
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    /**
     * Log event to file untuk debugging.
     * Kontrak §10.2: log event ke file untuk debugging.
     *
     * File: /data/data/<pkg>/files/shizuku_watcher.log
     * Format: [yyyy-MM-dd HH:mm:ss] event_text
     * Rotation: keep last 500 lines
     */
    @Synchronized
    private fun logEvent(event: String) {
        try {
            val logFile = File(context.filesDir, "shizuku_watcher.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logLine = "[$timestamp] $event\n"

            // Append to file
            logFile.appendText(logLine)

            // Rotate if too large (keep last 500 lines, ~50KB)
            if (logFile.length() > 50_000) {
                val lines = logFile.readLines()
                if (lines.size > 500) {
                    logFile.writeText(lines.takeLast(500).joinToString("") { "$it\n" })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event: ${e.message}")
        }
    }

    /**
     * Get log file untuk UI Export Log button.
     * @return File object, atau null jika tidak ada
     */
    fun getLogFile(): File? {
        val logFile = File(context.filesDir, "shizuku_watcher.log")
        return if (logFile.exists()) logFile else null
    }

    /**
     * Clear log file.
     */
    fun clearLog() {
        try {
            val logFile = File(context.filesDir, "shizuku_watcher.log")
            if (logFile.exists()) logFile.delete()
            logEvent("Log cleared by user")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log: ${e.message}")
        }
    }

    /**
     * Get human-readable status string untuk UI display.
     */
    fun getStatusString(): String {
        return when (state) {
            WatcherState.IDLE -> "● Idle"
            WatcherState.RUNNING -> "● Running"
            WatcherState.RECONNECTING -> "● Reconnecting"
            WatcherState.PERMISSION_LOST -> "● Permission Lost"
            WatcherState.BINDER_DEAD -> "● Disconnected"
        }
    }

    /**
     * Get status color untuk UI (hex string).
     */
    fun getStatusColor(): String {
        return when (state) {
            WatcherState.IDLE -> "#9CA3AF"          // gray
            WatcherState.RUNNING -> "#10B981"        // green
            WatcherState.RECONNECTING -> "#F59E0B"   // amber
            WatcherState.PERMISSION_LOST -> "#F59E0B" // amber
            WatcherState.BINDER_DEAD -> "#EF4444"    // red
        }
    }
}
