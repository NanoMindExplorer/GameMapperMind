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
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.TouchInjector

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
            val intent = Intent(context, MapperDaemonService::class.java).apply { action = ACTION_START; if (profileJson != null) putExtra(EXTRA_PROFILE_JSON, profileJson) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        @JvmStatic fun stopDaemon(context: Context) {
            val intent = Intent(context, MapperDaemonService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    private var touchInjector: TouchInjector? = null
    private var analogProcessor: AnalogProcessor? = null
    private var pipelineWorker: InputPipelineWorker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); Log.i(TAG, "onCreate"); createNotificationChannel(); isRunning = true }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("GameMapperMind Active"))
        when (intent?.action) {
            ACTION_START -> { val json = intent.getStringExtra(EXTRA_PROFILE_JSON); startPipeline(json) }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> startPipeline(null)
        }
        return START_STICKY
    }

    private fun startPipeline(profileJson: String?) {
        try {
            if (touchInjector == null) touchInjector = TouchInjector()
            if (analogProcessor == null) analogProcessor = AnalogProcessor()
            if (pipelineWorker == null) pipelineWorker = InputPipelineWorker(touchInjector!!, analogProcessor!!)
            if (profileJson != null) pipelineWorker!!.setProfileFromJson(profileJson)
            if (!pipelineWorker!!.isRunning()) pipelineWorker!!.start()
            val name = pipelineWorker?.getActiveProfile()?.packageName ?: "No profile"
            updateNotification("Active: $name")
        } catch (e: Exception) { Log.e(TAG, "Pipeline start failed", e) }
    }

    private fun stopPipeline() { try { pipelineWorker?.stop(); pipelineWorker?.clearProfile() } catch (e: Exception) { Log.e(TAG, "Stop failed", e) } }

    override fun onDestroy() { Log.i(TAG, "onDestroy"); stopPipeline(); isRunning = false; super.onDestroy() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Mapper Daemon", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps gamepad mapping active in background"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun createNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("GameMapperMind").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    private fun updateNotification(text: String) { try { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text)) } catch (e: Exception) { Log.e(TAG, "Notification update failed", e) } }
}
