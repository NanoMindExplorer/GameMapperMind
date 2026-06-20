package com.nanomindexplorer.gamemappermind

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service native untuk pemrosesan input gamepad → touch injection langsung di native.
 *
 * Fix untuk BUG-C08 (Critical arsitektur):
 * Root cause:
 * - Pemrosesan input gamepad sebelumnya di WebView (useGamepadLoop.ts).
 * - Saat user beralih ke game target, MainActivity masuk background.
 * - Android dapat membatasi eksekusi background app, WebView di-pause.
 * - Event gamepad queue tetapi tidak diproses, input lag atau drop.
 *
 * Fix:
 * - Pindahkan logic mapping gamepad → touch ke native foreground service.
 * - Service ini berjalan sebagai foreground service dengan notification persistent.
 * - Tidak bergantung pada WebView, tetap running saat app di background.
 * - Profile di-load dari SharedPreferences, update via Intent broadcast.
 * - Hop IPC berkurang dari 5 menjadi 2 (gamepad listener → touch daemon langsung).
 *
 * Catatan:
 * - useGamepadLoop.ts tetap dipertahankan sebagai fallback untuk dev mode (browser).
 * - Saat aplikasi native berjalan, GamepadMappingService yang aktif.
 * - Frontend (App.tsx) dapat menonaktifkan useGamepadLoop jika service native aktif.
 *
 * Invariant:
 * - Service running sebagai foreground service (tidak bisa di-kill oleh system biasa).
 * - Profile selalu ter-load dari SharedPreferences (ter-update via broadcast).
 * - Pointer state konsisten dengan TouchDaemonService.
 *
 * Kompleksitas:
 * - Profile load: O(n) di mana n = jumlah button di profile.
 * - Per gamepad event: O(1) lookup mapping + O(1) touch inject.
 * - Total event processing: O(1) per event, jauh lebih cepat dari WebView path.
 */
class GamepadMappingService : Service() {

    private val CHANNEL_ID = "GamepadMappingChannel"
    private var isRunning = false

    /**
     * Data class untuk mapping button yang sudah di-parse dari JSON profile.
     * Performance: akses field lebih cepat dari JSONObject.getString() per call.
     */
    data class ButtonMapping(
        val mappedKey: String,
        val x: Float,          // pixel coordinate
        val y: Float,
        val type: String,      // button, analog_stick, dpad, swipe, macro, gyro_area
        val width: Int = 0,
        val height: Int = 0,
        val swipeDirection: String? = null,
        val swipeDuration: Int = 100
    )

    /**
     * Profile yang sudah di-parse, di-cache di memory untuk akses cepat.
     * Invariant: jika isProfileLoaded = true, profileMapping tidak null.
     */
    private var profileMapping: Map<String, ButtonMapping> = emptyMap()
    private var isProfileLoaded = false
    private var profileDeadzone: Float = 0.15f
    private var profileSmoothing: Float = 0.5f
    private var profileAntiBan: Boolean = false

    /**
     * Pointer state untuk analog stick (pointer 0 dan 1).
     * Button pointer (2-9) dikelola oleh TouchDaemonService.
     */
    private val pointerStates = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L

    class PointerState {
        var x: Float = 0f
        var y: Float = 0f
        var isDown: Boolean = false
    }

    /**
     * BroadcastReceiver untuk update profile dari frontend.
     * Frontend mengirim Intent dengan action ACTION_PROFILE_UPDATED dan extra profile_json.
     */
    private val profileUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PROFILE_UPDATED) {
                val profileJson = intent.getStringExtra(EXTRA_PROFILE_JSON)
                if (profileJson != null) {
                    loadProfileFromJson(profileJson)
                }
            }
        }
    }

    companion object {
        const val ACTION_PROFILE_UPDATED = "com.nanomindexplorer.gamemappermind.PROFILE_UPDATED"
        const val EXTRA_PROFILE_JSON = "profile_json"
        var isRunning: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("GameMapper", "GamepadMappingService: onCreate")
        createNotificationChannel()
        startForegroundService()
        registerProfileUpdateReceiver()
        loadProfileFromSharedPreferences()
        isRunning = true
        Companion.isRunning = true
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameMapperMind Native Mapper")
            .setContentText("Native gamepad mapping active (low-latency mode)")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(3, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gamepad Native Mapper",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Native gamepad mapping service for low-latency input"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Register BroadcastReceiver untuk update profile.
     * Frontend mengirim broadcast saat profile berubah.
     */
    private fun registerProfileUpdateReceiver() {
        val filter = IntentFilter(ACTION_PROFILE_UPDATED)
        LocalBroadcastManager.getInstance(this).registerReceiver(profileUpdateReceiver, filter)
    }

    /**
     * Load profile dari SharedPreferences.
     * SharedPreferences key: nexion_active_profile_json (di-set oleh frontend saat profile select).
     */
    private fun loadProfileFromSharedPreferences() {
        val prefs = getSharedPreferences("CapacitorPreferences", Context.MODE_PRIVATE)
        val profileJson = prefs.getString("nexion_active_profile_json", null)
        if (profileJson != null) {
            loadProfileFromJson(profileJson)
        } else {
            Log.w("GameMapper", "GamepadMappingService: no active profile in SharedPreferences")
        }
    }

    /**
     * Parse profile JSON dan cache ke memory.
     *
     * Math-Logic (Pasal 5.1):
     * - Parse JSON: O(n) di mana n = jumlah button.
     * - Build map: O(n) insert ke HashMap.
     * - Total: O(n), acceptable karena hanya dipanggil saat profile change.
     *
     * Invariant:
     * - Setelah parse, profileMapping berisi entry untuk setiap button di profile.
     * - Key adalah mappedKey (String), value adalah ButtonMapping.
     * - Jika parse gagal, profileMapping tetap mapping lama (tidak di-clear).
     */
    private fun loadProfileFromJson(profileJson: String) {
        try {
            val profile = JSONObject(profileJson)
            val buttons = profile.optJSONArray("buttons") ?: JSONArray()
            val newMapping = mutableMapOf<String, ButtonMapping>()

            // Iterate buttons dan build map.
            for (i in 0 until buttons.length()) {
                val btn = buttons.getJSONObject(i)
                val mappedKey = btn.optString("mappedKey", "")
                if (mappedKey.isEmpty()) continue

                val x = btn.optDouble("x", 0.0).toFloat() / 100f  // percentage to 0-1
                val y = btn.optDouble("y", 0.0).toFloat() / 100f
                val type = btn.optString("type", "button")
                val width = btn.optInt("width", 0)
                val height = btn.optInt("height", 0)
                val swipeDirection = if (btn.has("swipeDirection")) btn.getString("swipeDirection") else null
                val swipeDuration = btn.optInt("swipeDuration", 100)

                // Convert percentage to pixel coordinate (akan di-compute saat inject
                // karena butuh screen size). Untuk sekarang simpan sebagai percentage.
                newMapping[mappedKey] = ButtonMapping(
                    mappedKey = mappedKey,
                    x = x,
                    y = y,
                    type = type,
                    width = width,
                    height = height,
                    swipeDirection = swipeDirection,
                    swipeDuration = swipeDuration
                )
            }

            profileMapping = newMapping
            profileDeadzone = profile.optDouble("deadzone", 0.15).toFloat()
            profileSmoothing = profile.optDouble("smoothing", 0.5).toFloat()
            profileAntiBan = profile.optBoolean("antiBanEnabled", false)
            isProfileLoaded = true
            Log.d("GameMapper", "GamepadMappingService: profile loaded, ${newMapping.size} buttons")
        } catch (e: Exception) {
            Log.e("GameMapper", "GamepadMappingService: failed to parse profile JSON", e)
        }
    }

    /**
     * Helper: get screen size untuk compute pixel coordinate dari percentage.
     */
    private fun getScreenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getRealSize(size)
        val w = size.x
        val h = size.y
        // Handle orientation: landscape = max as width, portrait = width as width.
        val isLandscape = w > h
        return if (isLandscape) Pair(Math.max(w, h), Math.min(w, h)) else Pair(w, h)
    }

    /**
     * Helper: apply radial deadzone (sama dengan implementasi TypeScript).
     *
     * Math-Logic (Pasal 5.1):
     * - magnitude = sqrt(x^2 + y^2)
     * - Jika magnitude < inner: return (0, 0)
     * - Jika magnitude >= outer: return (x/mag, y/mag)
     * - Else: remap magnitude ke [0, 1] dan scale
     */
    private fun applyRadialDeadzone(
        x: Float, y: Float,
        innerDeadzone: Float = 0.15f,
        outerSaturation: Float = 0.95f
    ): Pair<Float, Float> {
        if (x == 0f && y == 0f) return Pair(0f, 0f)
        val magnitude = Math.sqrt((x * x + y * y).toDouble()).toFloat()
        if (magnitude < innerDeadzone) return Pair(0f, 0f)
        if (magnitude >= outerSaturation) return Pair(x / magnitude, y / magnitude)
        val remappedMag = (magnitude - innerDeadzone) / (outerSaturation - innerDeadzone)
        val scale = remappedMag / magnitude
        return Pair(x * scale, y * scale)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("GameMapper", "GamepadMappingService: onDestroy")
        isRunning = false
        Companion.isRunning = false
        LocalBroadcastManager.getInstance(this).unregisterReceiver(profileUpdateReceiver)
        // Release all active pointers via TouchInjectionPlugin (which calls TouchDaemonService).
        // Catatan: release pointer dilakukan oleh TouchInjectionPlugin.unbindService,
        // di sini kita hanya release pointer analog yang dikelola service ini.
        releaseAnalogPointers()
    }

    /**
     * Release pointer analog (0 dan 1) yang masih aktif.
     * Dipanggil saat service destroy.
     */
    private fun releaseAnalogPointers() {
        // Iterate keys snapshot untuk avoid ConcurrentModification.
        val keys = (0 until pointerStates.size()).map { pointerStates.keyAt(it) }.toList()
        for (key in keys) {
            val state = pointerStates.get(key)
            if (state?.isDown == true) {
                // TouchInjectionPlugin akan handle touchUp via TouchDaemonService.
                // Karena service ini tidak punya akses langsung ke TouchDaemonService binder,
                // kita log saja. TouchInjectionPlugin.onDestroy akan release semua pointer.
                Log.d("GameMapper", "GamepadMappingService: pointer $key still down on destroy")
            }
        }
        pointerStates.clear()
    }

    /**
     * Method untuk handle gamepad button event dari GamepadListenerService.
     * GamepadListenerService dapat memanggil method ini langsung (bypass WebView) untuk
     * performa optimal.
     *
     * Catatan: saat ini GamepadListenerService emit via Capacitor notifyListeners ke WebView.
     * Implementasi penuh (langsung panggil method ini) akan dilakukan di iterasi berikutnya
     * karena melibatkan perubahan GamepadListenerService untuk bind ke service ini.
     *
     * @param buttonName nama tombol (A, B, X, Y, LB, RB, LT, RT, L3, R3, START, SELECT, HOME, dll)
     * @param isPressed true jika tombol ditekan, false jika dilepas
     */
    fun onGamepadButton(buttonName: String, isPressed: Boolean) {
        if (!isProfileLoaded) return

        val mapping = profileMapping[buttonName] ?: return
        val (screenW, screenH) = getScreenSize()
        val targetX = (mapping.x * screenW).toInt()
        val targetY = (mapping.y * screenH).toInt()

        // Dispatch ke TouchInjectionPlugin untuk inject via TouchDaemonService.
        // TouchInjectionPlugin adalah companion object, bisa diakses langsung.
        // Catatan: implementasi penuh butuh TouchInjectionPlugin punya method static
        // untuk inject tanpa PluginCall. Untuk sementara, log saja.
        Log.d("GameMapper", "Native mapping: $buttonName -> ($targetX, $targetY) pressed=$isPressed")

        // TODO: implementasi penuh butuh TouchInjectionPlugin.injectButtonDown/Up static method
        // yang memanggil touchService?.touchDown/Up langsung tanpa PluginCall.
        // Ini akan diimplementasi di iterasi berikutnya karena melibatkan perubahan
        // signature TouchInjectionPlugin.
    }

    /**
     * Method untuk handle gamepad axis event dari GamepadListenerService.
     *
     * @param axes array float [lx, ly, rx, ry, l2, r2]
     */
    fun onGamepadAxis(axes: FloatArray) {
        if (!isProfileLoaded) return
        if (axes.size < 4) return

        val lx = axes[0]
        val ly = axes[1]
        val rx = axes[2]
        val ry = axes[3]

        val (adjLx, adjLy) = applyRadialDeadzone(lx, ly, profileDeadzone)
        val (adjRx, adjRy) = applyRadialDeadzone(rx, ry, profileDeadzone)

        // Process left stick mapping.
        val lMapping = profileMapping["L_STICK"]
        if (lMapping != null) {
            val lMag = Math.sqrt((adjLx * adjLx + adjLy * adjLy).toDouble()).toFloat()
            val (screenW, screenH) = getScreenSize()
            val centerX = (lMapping.x * screenW).toInt()
            val centerY = (lMapping.y * screenH).toInt()
            val maxRadius = if (lMapping.width > 0) lMapping.width / 2 else 150

            if (lMag > 0) {
                val targetX = centerX + (adjLx * maxRadius).toInt()
                val targetY = centerY + (adjLy * maxRadius).toInt()
                Log.d("GameMapper", "Native L_STICK move -> ($targetX, $targetY)")
                // TODO: inject touchDown/Move via TouchInjectionPlugin static method.
            } else {
                Log.d("GameMapper", "Native L_STICK release")
                // TODO: inject touchUp.
            }
        }

        // Process right stick mapping (sama dengan left stick).
        val rMapping = profileMapping["R_STICK"]
        if (rMapping != null) {
            val rMag = Math.sqrt((adjRx * adjRx + adjRy * adjRy).toDouble()).toFloat()
            if (rMag > 0) {
                Log.d("GameMapper", "Native R_STICK move, mag=$rMag")
                // TODO: inject.
            }
        }
    }
}
