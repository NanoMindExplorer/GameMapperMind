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

    /**
     * REC-01: Map buttonName → pointerId untuk track button press.
     * Diperlukan karena PointerState tidak punya field untuk buttonName.
     * Invariant: jika buttonPointerMap[pointerId] = buttonName, maka pointerStates[pointerId].isDown = true.
     */
    private val buttonPointerMap: MutableMap<Int, String> = mutableMapOf()

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

        // REC-23: Initialize logger.
        GamepadLogger.init(this)
        GamepadLogger.log(GamepadLogger.Level.INFO, "GamepadMappingService", "Service onCreate")

        createNotificationChannel()
        startForegroundService()
        registerProfileUpdateReceiver()
        loadProfileFromSharedPreferences()
        isRunning = true
        Companion.isRunning = true
    }

    /**
     * REC-25: Background input guarantee via foreground service priority.
     *
     * START_STICKY: jika service di-kill oleh system (misal low memory),
     * system akan restart service otomatis. Intent yang dikirim ke restart
     * akan null, sehingga service perlu handle onCreate() untuk re-init state.
     *
     * Invariant:
     * - Service akan selalu di-restart oleh system jika killed
     * - State (profile, pointer) perlu re-init di onCreate()
     * - Notification persistent agar user aware service running
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GamepadLogger.log(GamepadLogger.Level.INFO, "GamepadMappingService",
            "onStartCommand, intent=${intent?.action ?: "null"}")

        // Reload profile dari SharedPreferences (jika service di-restart, profile perlu re-load).
        loadProfileFromSharedPreferences()

        // START_STICKY: system akan restart service jika killed.
        return START_STICKY
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
        GamepadLogger.log(GamepadLogger.Level.INFO, "GamepadMappingService", "Service onDestroy")
        isRunning = false
        Companion.isRunning = false
        LocalBroadcastManager.getInstance(this).unregisterReceiver(profileUpdateReceiver)
        // Release all active pointers via TouchInjectionPlugin (which calls TouchDaemonService).
        // Catatan: release pointer dilakukan oleh TouchInjectionPlugin.unbindService,
        // di sini kita hanya release pointer analog yang dikelola service ini.
        releaseAnalogPointers()

        // REC-23: Close logger.
        GamepadLogger.close()
    }

    /**
     * Release pointer analog (0 dan 1) yang masih aktif.
     * Dipanggil saat service destroy.
     *
     * REC-01: Sekarang panggil TouchInjectionPlugin.injectButtonUp untuk release pointer
     * yang masih down, lalu injectReleaseAllPointers sebagai safety net.
     */
    private fun releaseAnalogPointers() {
        // Snapshot keys untuk avoid ConcurrentModification (sama seperti BUG-N03 fix).
        val keys = (0 until pointerStates.size()).map { pointerStates.keyAt(it) }.toList()
        for (key in keys) {
            val state = pointerStates.get(key)
            if (state?.isDown == true) {
                TouchInjectionPlugin.injectButtonUp(key)
                state.isDown = false
                Log.d("GameMapper", "GamepadMappingService: released pointer $key on destroy")
            }
        }
        // Clear button pointer map juga.
        buttonPointerMap.clear()
        pointerStates.clear()

        // Safety net: release semua pointer di TouchDaemonService.
        TouchInjectionPlugin.injectReleaseAllPointers()
    }

    /**
     * Method untuk handle gamepad button event dari GamepadListenerService.
     *
     * REC-01: Sekarang menggunakan TouchInjectionPlugin.injectButtonDown/Up static methods
     * untuk inject touch langsung ke TouchDaemonService tanpa melewati WebView.
     *
     * Hop IPC: GamepadListenerService → GamepadMappingService → TouchInjectionPlugin → TouchDaemonService
     * Total hop: 2 (jauh lebih cepat dari 5 hop via WebView).
     *
     * Math-Logic (Pasal 5.1):
     * - Lookup mapping: O(1) HashMap lookup
     * - Inject: O(1) binder IPC
     * - Total: O(1) per event, latency ~2-3ms
     *
     * Invariant:
     * - isProfileLoaded harus true (profile sudah di-load)
     * - TouchInjectionPlugin.isTouchServiceReady() harus true (touchService sudah bound)
     * - Pointer ID untuk button: 2-9 (0 dan 1 reserved untuk analog stick)
     *
     * @param buttonName nama tombol (A, B, X, Y, LB, RB, LT, RT, L3, R3, START, SELECT, HOME, dll)
     * @param isPressed true jika tombol ditekan, false jika dilepas
     */
    fun onGamepadButton(buttonName: String, isPressed: Boolean) {
        if (!isProfileLoaded) return
        if (!TouchInjectionPlugin.isTouchServiceReady()) {
            Log.w("GameMapper", "GamepadMappingService: touchService not ready, skip $buttonName")
            return
        }

        val mapping = profileMapping[buttonName] ?: return
        val (screenW, screenH) = getScreenSize()
        val targetX = (mapping.x * screenW).toFloat()
        val targetY = (mapping.y * screenH).toFloat()

        // Apply anti-ban randomization jika enabled.
        val (finalX, finalY) = if (profileAntiBan) {
            applyAntiBanRandomization(targetX, targetY)
        } else {
            Pair(targetX, targetY)
        }

        // Pointer pool: 0-1 untuk analog, 2-9 untuk button.
        // Cari pointer slot yang available untuk button press.
        if (isPressed) {
            // Cek apakah button sudah pressed (dedup).
            val existingPointer = findActiveButtonPointer(buttonName)
            if (existingPointer != null) {
                // Sudah pressed, skip (avoid double touchDown).
                return
            }
            // Cari slot kosong (ID 2-9).
            val pointerId = findFreeButtonPointerSlot() ?: return
            val state = pointerStates.get(pointerId) ?: PointerState().also {
                pointerStates.put(pointerId, it)
            }
            state.x = finalX
            state.y = finalY
            state.isDown = true
            // Simpan buttonName di tag (gunakan map terpisah karena PointerState tidak punya field).
            buttonPointerMap[pointerId] = buttonName

            val success = TouchInjectionPlugin.injectButtonDown(pointerId, finalX, finalY)
            if (!success) {
                state.isDown = false
                buttonPointerMap.remove(pointerId)
                Log.w("GameMapper", "injectButtonDown failed for $buttonName (pointer $pointerId)")
            } else {
                Log.d("GameMapper", "Native mapping: $buttonName DOWN -> ($finalX, $finalY) pointer=$pointerId")
            }
        } else {
            // Button released: cari pointer dengan buttonName ini, send touchUp.
            val pointerId = findActiveButtonPointer(buttonName)
            if (pointerId != null) {
                val success = TouchInjectionPlugin.injectButtonUp(pointerId)
                val state = pointerStates.get(pointerId)
                state?.isDown = false
                buttonPointerMap.remove(pointerId)
                if (success) {
                    Log.d("GameMapper", "Native mapping: $buttonName UP pointer=$pointerId")
                } else {
                    Log.w("GameMapper", "injectButtonUp failed for $buttonName (pointer $pointerId)")
                }
            }
        }
    }

    /**
     * Method untuk handle gamepad axis event dari GamepadListenerService.
     *
     * REC-01: Implementasi penuh dengan inject touch ke TouchDaemonService.
     *
     * Math-Logic (Pasal 5.1):
     * - applyRadialDeadzone: O(1) per axis
     * - Inject: O(1) binder IPC
     * - Total: O(1) per axis event
     *
     * Pointer mapping:
     * - L_STICK → pointer ID 0
     * - R_STICK → pointer ID 1
     *
     * Invariant:
     * - Jika magnitude > 0: touchDown (jika belum down) + touchMove
     * - Jika magnitude == 0 dan pointer down: touchUp
     * - State pointer 0 dan 1 di pointerStates
     *
     * @param axes array float [lx, ly, rx, ry, l2, r2]
     */
    fun onGamepadAxis(axes: FloatArray) {
        if (!isProfileLoaded) return
        if (!TouchInjectionPlugin.isTouchServiceReady()) return
        if (axes.size < 4) return

        val lx = axes[0]
        val ly = axes[1]
        val rx = axes[2]
        val ry = axes[3]

        val (adjLx, adjLy) = applyRadialDeadzone(lx, ly, profileDeadzone)
        val (adjRx, adjRy) = applyRadialDeadzone(rx, ry, profileDeadzone)

        // Process left stick mapping (pointer 0).
        val lMapping = profileMapping["L_STICK"]
        if (lMapping != null) {
            val lMag = Math.sqrt((adjLx * adjLx + adjLy * adjLy).toDouble()).toFloat()
            val (screenW, screenH) = getScreenSize()
            val centerX = (lMapping.x * screenW).toFloat()
            val centerY = (lMapping.y * screenH).toFloat()
            val maxRadius = if (lMapping.width > 0) lMapping.width / 2f else 150f

            processAnalogStick(pointerId = 0, magnitude = lMag, adjX = adjLx, adjY = adjLy,
                centerX = centerX, centerY = centerY, maxRadius = maxRadius)
        }

        // Process right stick mapping (pointer 1).
        val rMapping = profileMapping["R_STICK"]
        if (rMapping != null) {
            val rMag = Math.sqrt((adjRx * adjRx + adjRy * adjRy).toDouble()).toFloat()
            val (screenW, screenH) = getScreenSize()
            val centerX = (rMapping.x * screenW).toFloat()
            val centerY = (rMapping.y * screenH).toFloat()
            val maxRadius = if (rMapping.width > 0) rMapping.width / 2f else 150f

            processAnalogStick(pointerId = 1, magnitude = rMag, adjX = adjRx, adjY = adjRy,
                centerX = centerX, centerY = centerY, maxRadius = maxRadius)
        }
    }

    /**
     * Helper: process analog stick untuk satu pointer.
     *
     * Math-Logic (Pasal 5.1):
     * - Jika magnitude > 0 dan pointer belum down: touchDown di center
     * - Jika magnitude > 0 dan pointer sudah down: touchMove ke (center + axis * radius)
     * - Jika magnitude == 0 dan pointer down: touchUp
     *
     * Invariant:
     * - Pointer state konsisten dengan TouchDaemonService
     * - Setelah release, pointer.isDown = false
     */
    private fun processAnalogStick(
        pointerId: Int, magnitude: Float, adjX: Float, adjY: Float,
        centerX: Float, centerY: Float, maxRadius: Float
    ) {
        val state = pointerStates.get(pointerId) ?: PointerState().also {
            pointerStates.put(pointerId, it)
        }

        if (magnitude > 0) {
            val targetX = centerX + (adjX * maxRadius)
            val targetY = centerY + (adjY * maxRadius)

            // Apply anti-ban randomization.
            val (finalX, finalY) = if (profileAntiBan) {
                applyAntiBanRandomization(targetX, targetY)
            } else {
                Pair(targetX, targetY)
            }

            if (!state.isDown) {
                // First touch down di center.
                val (downX, downY) = if (profileAntiBan) {
                    applyAntiBanRandomization(centerX, centerY)
                } else {
                    Pair(centerX, centerY)
                }
                val success = TouchInjectionPlugin.injectButtonDown(pointerId, downX, downY)
                if (success) {
                    state.isDown = true
                    state.x = downX
                    state.y = downY
                } else {
                    Log.w("GameMapper", "Analog stick $pointerId touchDown failed")
                    return
                }
            }

            // Touch move ke target.
            val moveSuccess = TouchInjectionPlugin.injectAxisMove(pointerId, finalX, finalY)
            if (moveSuccess) {
                state.x = finalX
                state.y = finalY
            }
        } else if (state.isDown) {
            // Release stick.
            val success = TouchInjectionPlugin.injectButtonUp(pointerId)
            if (success) {
                state.isDown = false
                Log.d("GameMapper", "Analog stick $pointerId released")
            } else {
                Log.w("GameMapper", "Analog stick $pointerId release failed")
            }
        }
    }

    /**
     * Helper: apply anti-ban randomization (Gaussian offset ±3px).
     * Sama dengan implementasi TypeScript di useGamepadLoop.
     *
     * Math-Logic (Pasal 5.1):
     * - Box-Muller transform: z = sqrt(-2 * ln(u1)) * cos(2 * pi * u2)
     * - sigma = 1.5, offset clamped ke [-3, 3]
     * - Invariant: offset max 3px, direction tidak berubah signifikan
     */
    private fun applyAntiBanRandomization(x: Float, y: Float): Pair<Float, Float> {
        val u1 = Math.max(1e-10, Math.random())
        val u2 = Math.random()
        val z1 = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)
        val z2 = Math.sqrt(-2.0 * Math.log(u1)) * Math.sin(2.0 * Math.PI * u2)

        val sigma = 1.5
        val offsetX = Math.max(-3.0, Math.min(3.0, z1 * sigma)).toFloat()
        val offsetY = Math.max(-3.0, Math.min(3.0, z2 * sigma)).toFloat()

        return Pair(x + offsetX, y + offsetY)
    }

    /**
     * Helper: cari pointer slot kosong untuk button (ID 2-9).
     * @return pointer ID jika ada slot kosong, null jika penuh.
     */
    private fun findFreeButtonPointerSlot(): Int? {
        for (id in 2..9) {
            val state = pointerStates.get(id)
            if (state == null || !state.isDown) {
                return id
            }
        }
        return null
    }

    /**
     * Helper: cari pointer ID yang sedang aktif untuk buttonName tertentu.
     * @return pointer ID jika ditemukan, null jika tidak.
     */
    private fun findActiveButtonPointer(buttonName: String): Int? {
        for ((pointerId, name) in buttonPointerMap) {
            if (name == buttonName) {
                val state = pointerStates.get(pointerId)
                if (state?.isDown == true) {
                    return pointerId
                }
            }
        }
        return null
    }
}
