package com.nanomindexplorer.gamemappermind.shizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nanomindexplorer.gamemappermind.daemon.MapperDaemonService
import com.nanomindexplorer.gamemappermind.util.HarmonyOSHelper

/**
 * FixShizukuReceiver — BroadcastReceiver untuk action "Perbaiki Sekarang"
 * dari notification ShizukuBinderWatcher.
 *
 * Kontrak §10.2: notification harus punya action "Perbaiki Sekarang"
 * yang trigger re-request permission + rebind.
 *
 * Saat user tap "Perbaiki Sekarang":
 *   1. Trigger immediate check di ShizukuBinderWatcher
 *   2. Re-request Shizuku permission
 *   3. Attempt rebind UserService
 *   4. Update notification dengan progress
 */
class FixShizukuReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GameMapper/FixShizukuReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ShizukuBinderWatcher.ACTION_FIX_NOW) return

        Log.i(TAG, "FixShizukuReceiver triggered — user tapped 'Perbaiki Sekarang'")

        // Run on background thread to avoid ANR
        Thread {
            try {
                val watcher = ShizukuBinderWatcher.getInstance(context)
                val helper = ShizukuHelper.getInstance(context)

                // Step 1: Check if Shizuku is running at all
                if (!helper.isBinderAlive()) {
                    Log.w(TAG, "Shizuku binder still dead — user must start Shizuku app manually")
                    // Open Shizuku app
                    val shizukuIntent = context.packageManager
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    shizukuIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    shizukuIntent?.let { context.startActivity(it) }
                    return@Thread
                }

                // Step 2: Re-request permission if needed
                if (!helper.checkPermission()) {
                    Log.i(TAG, "Requesting Shizuku permission...")
                    // Request permission must be on main thread
                    val mainHandler = Handler(Looper.getMainLooper())
                    mainHandler.post {
                        helper.requestPermission()
                    }
                }

                // Step 3: Attempt rebind
                Log.i(TAG, "Attempting to rebind UserService...")
                val bound = helper.bindUserService()
                Log.i(TAG, "Rebind result: $bound")

                // Step 4: Trigger immediate check in watcher
                watcher.triggerFixNow()

                // Step 5: Start daemon service for foreground keep-alive
                MapperDaemonService.startDaemon(context)

            } catch (e: Exception) {
                Log.e(TAG, "FixShizukuReceiver failed: ${e.message}", e)
            }
        }.start()
    }
}

/**
 * BootCompletedReceiver — Auto-start service setelah device reboot.
 *
 * Kontrak §10.3: Implementasikan auto-rebind setelah reboot: gunakan
 * BootCompletedReceiver + delay 5 detik (tunggu Shizuku daemon start)
 * → auto-start service + request bind.
 *
 * Flow:
 *   1. Receive BOOT_COMPLETED broadcast
 *   2. Wait 5 seconds (let Shizuku daemon start)
 *   3. Start MapperDaemonService (foreground keep-alive)
 *   4. Start ShizukuBinderWatcher (monitoring)
 *   5. Attempt Shizuku bind (will show permission dialog if needed)
 *
 * Note: Requires RECEIVE_BOOT_COMPLETED permission in AndroidManifest.xml
 */
class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GameMapper/BootReceiver"
        private const val DELAY_AFTER_BOOT_MS = 5_000L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot/replaced event received: ${intent.action}")
                handleBootCompleted(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        // Detect HarmonyOS — different startup behavior
        val isHarmony = HarmonyOSHelper.isHarmonyOS()
        val delayMs = if (isHarmony) DELAY_AFTER_BOOT_MS * 2 else DELAY_AFTER_BOOT_MS
        Log.i(TAG, "Scheduling auto-start in ${delayMs}ms (HarmonyOS=$isHarmony)")

        // Schedule delayed start on background thread
        Thread {
            try {
                Thread.sleep(delayMs)
                Log.i(TAG, "Auto-starting services after boot delay...")

                // Step 1: Start foreground daemon service (keep-alive)
                MapperDaemonService.startDaemon(context)
                Log.i(TAG, "MapperDaemonService started")

                // Step 2: Start Shizuku binder watcher
                val watcher = ShizukuBinderWatcher.getInstance(context)
                watcher.start()
                Log.i(TAG, "ShizukuBinderWatcher started")

                // Step 3: Attempt Shizuku bind (will fail silently if Shizuku not running yet)
                try {
                    val helper = ShizukuHelper.getInstance(context)
                    if (helper.isBinderAlive()) {
                        if (helper.checkPermission()) {
                            helper.bindUserService()
                            Log.i(TAG, "Shizuku UserService bound after boot")
                        } else {
                            Log.i(TAG, "Shizuku permission not granted yet — watcher will retry")
                        }
                    } else {
                        Log.i(TAG, "Shizuku not running yet — watcher will retry every 30s")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Shizuku bind failed after boot: ${e.message}")
                    // Not fatal — watcher will retry
                }

            } catch (e: InterruptedException) {
                Log.w(TAG, "Boot auto-start interrupted")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Boot auto-start failed: ${e.message}", e)
            }
        }.also { it.isDaemon = true }.start()
    }
}
