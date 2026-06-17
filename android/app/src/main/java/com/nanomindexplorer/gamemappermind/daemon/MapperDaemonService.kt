package com.nanomindexplorer.gamemappermind.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * MapperDaemonService — Foreground keep-alive service.
 *
 * FIX #5: Removed TouchInjector + InputPipelineWorker + AnalogProcessor.
 *
 * This service runs in the APP process (UID app). It CANNOT do touch
 * injection because InputManager.injectInputEvent() requires shell
 * privilege (UID 2000). Previously, this service created its own
 * pipeline that silently failed on every injection attempt.
 *
 * The actual gamepad-to-touch pipeline runs in the Shizuku UserService
 * process (shell UID) via GameMapperPluginImpl:
 *   - GameMapperUserService.startGamepadRead() → pluginImpl.startPipeline()
 *   - GameMapperPluginImpl contains the ONLY TouchInjector instance
 *
 * This service's sole purpose is now:
 *   1. Display foreground notification (required by Android for background services)
 *   2. Keep the app process alive while overlay is active
 *   3. Forward START/STOP intents (START triggers Shizuku bind if needed)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("GameMapperMind Active"))
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> Log.i(TAG, "Daemon started (foreground keep-alive only, pipeline runs in Shizuku UserService)")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Mapper Daemon", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps GameMapperMind alive in background"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun createNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameMapperMind")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
}
