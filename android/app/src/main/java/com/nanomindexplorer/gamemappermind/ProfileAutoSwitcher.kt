package com.nanomindexplorer.gamemappermind

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * REC-12: Auto-switch profile berdasarkan foreground app.
 *
 * User tidak harus manual switch profile. Service ini poll foreground app
 * setiap 2 detik via UsageStatsManager, match package name dengan profile.packageName,
 * dan auto-activate profile yang cocok.
 *
 * Membutuhkan permission PACKAGE_USAGE_STATS (Settings.ACTION_USAGE_ACCESS_SETTINGS).
 *
 * Math-Logic (Pasal 5.1):
 * - Poll interval: 2 detik (configurable)
 * - Query: O(1) queryUsageStats
 * - Match: O(n) di mana n = jumlah profile (biasanya 3-5)
 * - Kompleksitas total: O(n) per poll, acceptable untuk interval 2 detik
 *
 * Invariant:
 * - Hanya aktif jika user grant PACKAGE_USAGE_STATS permission
 * - Jika foreground app tidak match profile mana pun, tetap pakai profile aktif
 * - Switch profile hanya jika package name berbeda dari current
 * - Emit broadcast PROFILE_AUTO_SWITCHED ke frontend untuk UI update
 */
class ProfileAutoSwitcher(private val context: Context) {

    private var usageStatsManager: UsageStatsManager? = null
    private var isRunning = false
    private var currentPackageName: String? = null
    private var pollIntervalMs: Long = 2000 // 2 detik

    // Map packageName -> profileId untuk quick lookup.
    private val packageToProfile: MutableMap<String, String> = mutableMapOf()

    /**
     * Set daftar profile untuk auto-switch.
     * @param profiles List of Pair(packageName, profileId)
     */
    fun setProfiles(profiles: List<Pair<String, String>>) {
        packageToProfile.clear()
        profiles.forEach { (packageName, profileId) ->
            packageToProfile[packageName] = profileId
        }
        Log.d("GameMapper", "REC-12: ${packageToProfile.size} profiles registered for auto-switch")
    }

    /**
     * Check apakah user sudah grant PACKAGE_USAGE_STATS permission.
     */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * Request PACKAGE_USAGE_STATS permission via system settings.
     * Frontend panggil method ini via plugin untuk open settings.
     */
    fun requestUsageStatsPermission() {
        val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Start auto-switch polling.
     */
    fun start() {
        if (isRunning) return
        if (!hasUsageStatsPermission()) {
            Log.w("GameMapper", "REC-12: PACKAGE_USAGE_STATS permission not granted")
            return
        }
        isRunning = true
        usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        Log.d("GameMapper", "REC-12: Auto-switcher started")

        // Start polling di background thread.
        Thread {
            while (isRunning) {
                try {
                    checkForegroundApp()
                    Thread.sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("GameMapper", "REC-12: Polling error", e)
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Stop auto-switch polling.
     */
    fun stop() {
        isRunning = false
        Log.d("GameMapper", "REC-12: Auto-switcher stopped")
    }

    /**
     * Check foreground app dan switch profile jika perlu.
     *
     * Math-Logic (Pasal 5.1):
     * - Query foreground app: O(1) queryUsageStats dengan interval 3 detik
     * - Sort by lastTimeUsed: O(n log n) di mana n = jumlah app (biasanya 10-20)
     * - Ambil yang terakhir: O(1)
     * - Lookup profile: O(1) HashMap
     * - Kompleksitas total: O(n log n) per check, acceptable untuk interval 2 detik
     */
    private fun checkForegroundApp() {
        val now = System.currentTimeMillis()
        val stats = usageStatsManager?.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            now - 3000, // 3 detik terakhir
            now
        ) ?: return

        if (stats.isEmpty()) return

        // Sort by lastTimeUsed descending, ambil yang terakhir.
        val sorted = stats.sortedByDescending { it.lastTimeUsed }
        val foregroundPackage = sorted.firstOrNull()?.packageName ?: return

        // Jika package sama dengan current, skip (tidak perlu switch).
        if (foregroundPackage == currentPackageName) return

        // Cek apakah package ada di profile map.
        val profileId = packageToProfile[foregroundPackage]
        if (profileId != null) {
            currentPackageName = foregroundPackage
            Log.d("GameMapper", "REC-12: Foreground app changed to $foregroundPackage, switch to profile $profileId")

            // Emit event ke frontend untuk switch profile.
            TouchInjectionPlugin.emitGamepadButton("PROFILE_AUTO_SWITCHED", 1, 1.0f)

            // Broadcast ke GamepadMappingService untuk update profile.
            // Frontend akan handle update profile via updateNativeProfile.
            val intent = Intent("com.nanomindexplorer.gamemappermind.PROFILE_AUTO_SWITCH")
            intent.putExtra("package_name", foregroundPackage)
            intent.putExtra("profile_id", profileId)
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(context)
                .sendBroadcast(intent)
        } else {
            // Package tidak match profile mana pun, tetap pakai profile aktif.
            currentPackageName = foregroundPackage
        }
    }
}
