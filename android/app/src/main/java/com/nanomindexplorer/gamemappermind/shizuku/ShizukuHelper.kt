package com.nanomindexplorer.gamemappermind.shizuku

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku

/**
 * ShizukuHelper — Centralized Shizuku lifecycle management.
 *
 * Responsibilities:
 * 1. Register/unregister Shizuku binder listeners
 * 2. Check if Shizuku is running (pingBinder)
 * 3. Check and request Shizuku permission
 * 4. Bind/unbind UserService (GameMapperUserService)
 * 5. Provide callback interface for service state changes
 *
 * Thread safety: All Shizuku API calls are made from the main thread
 * (binder listeners run on main thread by default). Service binding
 * is asynchronous via ServiceConnection callbacks.
 *
 * Dependencies:
 * - dev.rikka.shizuku:api:13.1.5
 * - dev.rikka.shizuku:provider:13.1.5
 */
class ShizukuHelper(private val context: Context) {

    companion object {
        private const val TAG = "GameMapper/ShizukuHelper"
        private const val REQUEST_CODE_PERMISSION = 1001

        @JvmStatic
        private var instance: ShizukuHelper? = null

        @JvmStatic
        fun getInstance(context: Context): ShizukuHelper {
            if (instance == null) {
                instance = ShizukuHelper(context.applicationContext)
            }
            return instance!!
        }
    }

    /**
     * Callback interface for Shizuku state changes.
     * Called on the main thread.
     */
    interface ShizukuCallback {
        fun onBinderReceived()
        fun onBinderDead()
        fun onPermissionGranted()
        fun onPermissionDenied()
        fun onServiceConnected(service: IGameMapperService?)
        fun onServiceDisconnected()
    }

    private var callback: ShizukuCallback? = null
    private var gameMapperService: IGameMapperService? = null
    private var listenersRegistered = false

    // ============================================================
    // UserServiceArgs — defines which service class to bind
    // and process name suffix. Shizuku uses this to start
    // the service in a separate process with shell/root UID.
    // ============================================================
    private val userServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                "com.nanomindexplorer.gamemappermind",
                "com.nanomindexplorer.gamemappermind.shizuku.GameMapperUserService"
            )
        )
            .daemon(false)
            .processNameSuffix("game_mapper_service")
            .version(1)
            .debuggable(false)

    // ============================================================
    // ServiceConnection — receives callback when UserService
    // is connected or disconnected.
    // ============================================================
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, "UserService connected: ${name.className}")
            if (binder != null && binder.pingBinder()) {
                gameMapperService = IGameMapperService.Stub.asInterface(binder)
                Log.d(TAG, "IGameMapperService binder obtained successfully")
                callback?.onServiceConnected(gameMapperService)
            } else {
                Log.e(TAG, "UserService binder is null or dead")
                callback?.onServiceConnected(null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "UserService disconnected")
            gameMapperService = null
            callback?.onServiceDisconnected()
        }
    }

    // ============================================================
    // Shizuku binder lifecycle listeners
    // ============================================================
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received — Shizuku is running")
        // T-03: Auto-recovery — when binder comes back, auto-rebind if we had permission before
        if (checkPermission()) {
            Log.i(TAG, "T-03: Shizuku recovered, auto-rebinding UserService...")
            bindUserService()
        }
        callback?.onBinderReceived()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead — Shizuku stopped or crashed")
        gameMapperService = null
        // T-03: Show user notification about Shizuku death
        showShizukuDeathNotification()
        callback?.onBinderDead()
    }

    // ============================================================
    // Permission result listener — called when user approves/denies
    // the Shizuku permission dialog.
    // ============================================================
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.d(TAG, "Permission result: requestCode=$requestCode grantResult=$grantResult")
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Log.d(TAG, "Shizuku permission GRANTED by user")
                callback?.onPermissionGranted()
                // Auto-bind service immediately after permission is granted
                bindUserService()
            } else {
                Log.w(TAG, "Shizuku permission DENIED by user")
                callback?.onPermissionDenied()
            }
        }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Set the callback for Shizuku state changes.
     * Call this before registerListeners().
     */
    fun setCallback(cb: ShizukuCallback) {
        callback = cb
    }

    /**
     * Register all Shizuku listeners.
     * Must be called in plugin load() or activity onCreate().
     * Uses addBinderReceivedListenerSticky to immediately fire
     * if binder is already alive.
     */
    fun registerListeners() {
        if (listenersRegistered) {
            Log.d(TAG, "Listeners already registered, skipping")
            return
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
            Log.d(TAG, "Shizuku listeners registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Shizuku listeners", e)
        }
    }

    /**
     * Unregister all Shizuku listeners.
     * Call this in plugin handleOnDestroy() or activity onDestroy().
     */
    fun unregisterListeners() {
        if (!listenersRegistered) return
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister Shizuku listeners", e)
        }
        listenersRegistered = false
    }

    /**
     * Check if Shizuku binder is alive (Shizuku app is running).
     * This does NOT check permission — use checkPermission() for that.
     *
     * @return true if Shizuku is running
     */
    fun isBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "pingBinder failed", e)
            false
        }
    }

    /**
     * Check if Shizuku is pre-v11 (too old, unsupported).
     *
     * @return true if Shizuku version is too old
     */
    fun isPreV11(): Boolean {
        return try {
            Shizuku.isPreV11()
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Check if Shizuku permission is granted.
     * Caller must verify isBinderAlive() == true first.
     *
     * @return true if permission is granted
     */
    fun checkPermission(): Boolean {
        if (!isBinderAlive()) {
            Log.w(TAG, "checkPermission: binder not alive")
            return false
        }
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "checkSelfPermission failed", e)
            false
        }
    }

    /**
     * Request Shizuku permission.
     * Shows a system dialog to the user.
     * Result is delivered via permissionResultListener → callback.onPermissionGranted/Denied.
     *
     * Must be called on the main thread (UI thread) for the dialog to appear.
     *
     * @return true if request was sent, false if binder not alive or already granted
     */
    fun requestPermission(): Boolean {
        if (!isBinderAlive()) {
            Log.w(TAG, "requestPermission: binder not alive, cannot request")
            return false
        }

        if (checkPermission()) {
            Log.d(TAG, "Permission already granted, auto-binding service")
            callback?.onPermissionGranted()
            bindUserService()
            return true
        }

        // Check if user previously denied with "don't ask again"
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Log.w(TAG, "User denied permission previously (rationale required)")
            return false
        }

        // Request permission — shows Shizuku dialog on UI thread
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
                Log.d(TAG, "Shizuku.requestPermission() called on main thread")
            } catch (e: Exception) {
                Log.e(TAG, "requestPermission failed on main thread", e)
            }
        }
        return true
    }

    /**
     * Bind the GameMapperUserService via Shizuku.
     * This starts the service in a separate process with shell UID.
     *
     * Prerequisites:
     * - Shizuku binder must be alive
     * - Permission must be granted
     *
     * @return true if bind was initiated, false if prerequisites not met
     */
    fun bindUserService(): Boolean {
        if (!isBinderAlive()) {
            Log.w(TAG, "bindUserService: binder not alive")
            return false
        }

        if (!checkPermission()) {
            Log.w(TAG, "bindUserService: permission not granted")
            return false
        }

        return try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            Log.d(TAG, "bindUserService initiated successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed", e)
            false
        }
    }

    /**
     * Unbind the GameMapperUserService.
     * The service process will NOT be killed automatically —
     * the service's destroy() method must call System.exit(0).
     *
     * @param remove true to call destroy() on the service before unbinding
     */
    fun unbindUserService(remove: Boolean = true) {
        try {
            // Stop gamepad reading before unbinding
            gameMapperService?.stopGamepadRead()
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, remove)
            gameMapperService = null
            Log.d(TAG, "UserService unbound (remove=$remove)")
        } catch (e: Exception) {
            Log.e(TAG, "unbindUserService failed", e)
        }
    }

    /**
     * Get the currently bound IGameMapperService.
     * Returns null if service is not connected.
     *
     * @return IGameMapperService instance or null
     */
    fun getService(): IGameMapperService? {
        return gameMapperService
    }

    /**
     * Check if the UserService is currently connected.
     *
     * @return true if service binder is available
     */
    fun isServiceConnected(): Boolean {
        return gameMapperService != null
    }

    /**
     * Get Shizuku version code.
     *
     * @return version code, or -1 if unavailable
     */
    fun getShizukuVersion(): Int {
        return try {
            Shizuku.getVersion()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * T-03: Show notification when Shizuku binder dies.
     * Uses Android NotificationManager to inform user that Shizuku
     * has stopped and needs to be restarted.
     */
    private fun showShizukuDeathNotification() {
        try {
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "shizuku_status"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Shizuku Status", NotificationManager.IMPORTANCE_HIGH)
                channel.description = "Notifies when Shizuku connection is lost or restored"
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle("GameMapperMind: Shizuku Disconnected")
                .setContentText("Shizuku has stopped. Touch injection is paused. Restart Shizuku to resume.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            nm.notify(2001, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show Shizuku death notification: ${e.message}")
        }
    }
}
