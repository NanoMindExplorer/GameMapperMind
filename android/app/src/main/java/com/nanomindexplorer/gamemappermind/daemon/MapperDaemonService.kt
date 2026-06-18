package com.nanomindexplorer.gamemappermind.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nanomindexplorer.gamemappermind.util.HarmonyOSHelper

/**
 * MapperDaemonService — Foreground keep-alive service.
 *
 * GMM-AEC-002 §9.3 enhancement: Handle PowerGenie/HiAI Huawei background kill
 *   - Sticky foreground service dengan wakeLock yang tepat
 *   - restartOnKill = true (START_STICKY + START_REDELIVER_INTENT untuk HarmonyOS)
 *   - Notification channel yang visible (IMPORTANCE_LOW tapi visible)
 *   - WakeLock PARTIAL_WAKE_LOCK selama service aktif
 */
class MapperDaemonService : Service() {
    companion object {
        private const val TAG = "GameMapper/DaemonService"
        private const val CHANNEL_ID = "MapperDaemonChannel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.nanomindexplorer.gamemappermind.START_DAEMON"
        const val ACTION_STOP = "com.nanomindexplorer.gamemappermind.STOP_DAEMON"
        const val EXTRA_PROFILE_JSON = "profile_json"
        @Volatile var isRunning = false; private set

        @JvmStatic fun startDaemon(context: Context, profileJson: String? = null) {
            val intent = Intent(context, MapperDaemonService::class.java).apply {
                action = ACTION_START
                if (profileJson != null) putExtra(EXTRA_PROFILE_JSON, profileJson)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        @JvmStatic fun stopDaemon(context: Context) {
            val intent = Intent(context, MapperDaemonService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    // GMM-AEC-002 §9.3: WakeLock untuk cegah PowerGenie kill
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        isRunning = true
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("GameMapperMind Active"))
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received")
                releaseWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "Daemon started (foreground keep-alive + wakeLock, HarmonyOS=" +
                           HarmonyOSHelper.isHarmonyOS() + ")")
            }
        }

        // GMM-AEC-002 §9.3: restartOnKill = true
        return if (HarmonyOSHelper.isHarmonyOS()) {
            Log.i(TAG, "HarmonyOS detected — using START_REDELIVER_INTENT for max resilience")
            START_REDELIVER_INTENT
        } else {
            START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // GMM-AEC-002 §9.3: Restart service saat task removed (swipe away)
        Log.i(TAG, "onTaskRemoved — restarting service (HarmonyOS=" +
                   HarmonyOSHelper.isHarmonyOS() + ")")

        if (HarmonyOSHelper.isHarmonyOS()) {
            val restartIntent = Intent(applicationContext, MapperDaemonService::class.java).apply {
                action = ACTION_START
            }
            val pendingIntent = android.app.PendingIntent.getService(
                this, 1, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
            Log.i(TAG, "Scheduled service restart via AlarmManager (1s delay)")
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    /**
     * GMM-AEC-002 §9.3: Acquire PARTIAL_WAKE_LOCK.
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GameMapperMind::MapperDaemonWakeLock"
            ).also { wl ->
                wl.acquire(0)
                Log.i(TAG, "PARTIAL_WAKE_LOCK acquired (HarmonyOS=" +
                           HarmonyOSHelper.isHarmonyOS() + ")")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakeLock: " + e.message, e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                    Log.i(TAG, "PARTIAL_WAKE_LOCK released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wakeLock: " + e.message)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Mapper Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps GameMapperMind alive in background"
                setShowBadge(true)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun createNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameMapperMind")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (HarmonyOSHelper.isHarmonyOS()) {
            builder.setTicker("GameMapperMind active (HarmonyOS mode)")
            builder.setSubText("HarmonyOS protected")
        }

        return builder.build()
    }
}
