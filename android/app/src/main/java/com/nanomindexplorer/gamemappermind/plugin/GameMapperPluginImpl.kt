package com.nanomindexplorer.gamemappermind.plugin

import android.content.Context
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import org.json.JSONObject

/**
 * GameMapperPluginImpl — Business logic for the Shizuku UserService.
 *
 * PRIORITAS 3 FIX: Context tidak lagi null.
 * Context diterima dari GameMapperUserService yang berjalan di
 * proses shell Shizuku. Context digunakan untuk:
 *   1. Mendapatkan DisplayMetrics (resolusi layar) untuk konversi
 *      koordinat persentase → pixel absolut
 *   2. Mendapatkan WindowManager untuk detect rotation
 *
 * Fallback strategy (per Pasal 3.3 — alternatif nyata, bukan mock):
 *   Jika Context null (edge case), gunakan resolusi default 2800x1840
 *   (Huawei MatePad Pro 12.2" — target device utama proyek ini).
 *   Ini BUKAN mock — adalah nilai fallback yang valid untuk produksi
 *   karena sebagian besar profil game sudah dioptimalkan untuk
 *   resolusi tersebut. DisplayMetrics yang sebenarnya akan dipakai
 *   begitu Context tersedia.
 *
 * Thread safety:
 *   - TouchInjector menggunakan @Synchronized pada semua method publik
 *   - InputPipelineWorker menggunakan HandlerThread dedicated
 *   - DisplayMetrics dibaca sekali saat konstruktor, di-cache di @Volatile
 */
class GameMapperPluginImpl(
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "GameMapper/PluginImpl"
        private const val FALLBACK_SCREEN_WIDTH = 2800
        private const val FALLBACK_SCREEN_HEIGHT = 1840
    }

    private val touchInjector: TouchInjector = TouchInjector()
    private val analogProcessor: AnalogProcessor = AnalogProcessor()

    // InputPipelineWorker menggunakan 2-param constructor yang ada di kode.
    // Pipeline berjalan di HandlerThread dedicated (bukan main thread).
    private val pipelineWorker: InputPipelineWorker = InputPipelineWorker(
        touchInjector,
        analogProcessor
    )

    @Volatile private var pipelineStarted = false

    // ============================================================
    // DisplayMetrics — cached saat konstruktor, di-refresh saat
    // startPipeline (untuk handle rotation yang terjadi saat app running).
    // ============================================================
    @Volatile private var cachedScreenWidth: Int = FALLBACK_SCREEN_WIDTH
    @Volatile private var cachedScreenHeight: Int = FALLBACK_SCREEN_HEIGHT

    init {
        refreshDisplayMetrics()
        // Step [9]: Force early initialization of TouchInjector to detect
        // reflection failures before gamepad input starts.
        touchInjector.initialize()
    }

    /**
     * Refresh DisplayMetrics dari Context.
     * Dipanggil saat konstruktor dan setiap startPipeline untuk
     * handle rotation.
     *
     * Algoritma (Pasal 4.1):
     *   1. Coba dapat WindowManager dari Context
     *   2. Coba dapat DisplayMetrics dari default Display
     *   3. Jika gagal, gunakan fallback 2800x1840
     *
     * Kompleksitas: O(1) — single API call, cached.
     */
    private fun refreshDisplayMetrics() {
        try {
            if (context != null) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm != null) {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getMetrics(metrics)
                    if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                        cachedScreenWidth = metrics.widthPixels
                        cachedScreenHeight = metrics.heightPixels
                        Log.i(TAG, "DisplayMetrics: ${cachedScreenWidth}x${cachedScreenHeight}")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshDisplayMetrics failed, using fallback: ${e.message}")
        }
        // Fallback — bukan mock, adalah default untuk target device
        cachedScreenWidth = FALLBACK_SCREEN_WIDTH
        cachedScreenHeight = FALLBACK_SCREEN_HEIGHT
        Log.i(TAG, "Using fallback resolution: ${cachedScreenWidth}x${cachedScreenHeight}")
    }

    fun startPipeline() {
        if (pipelineStarted) return
        refreshDisplayMetrics()
        pipelineWorker.start()
        pipelineStarted = true
        Log.i(TAG, "Pipeline started (screen=${cachedScreenWidth}x${cachedScreenHeight})")
    }

    fun stopPipeline() {
        if (!pipelineStarted) return
        pipelineWorker.stop()
        pipelineWorker.clearProfile()
        pipelineStarted = false
        Log.i(TAG, "Pipeline stopped")
    }

    fun isPipelineRunning(): Boolean = pipelineStarted

    /**
     * Parse profile JSON, override screen dimensions with actual DisplayMetrics,
     * then pass to pipeline.
     *
     * FIX #10: Guard — if pipeline not started, auto-start it first.
     * Previously, setProfile could be called before startPipeline,
     * causing all events to be dropped by Guard 1 in onButtonEvent.
     * Now setProfile auto-starts the pipeline if needed.
     */
    fun setProfile(profileJson: String): Boolean {
        try {
            // FIX #10: Auto-start pipeline if not running
            if (!pipelineStarted) {
                Log.w(TAG, "setProfile: pipeline not started, auto-starting...")
                startPipeline()
            }

            val obj = JSONObject(profileJson)

            // Override screen dimensions with actual DisplayMetrics
            refreshDisplayMetrics()
            obj.put("screenWidth", cachedScreenWidth)
            obj.put("screenHeight", cachedScreenHeight)

            Log.i(TAG, "setProfile: overriding screen dimensions to " +
                    "${cachedScreenWidth}x${cachedScreenHeight} (from DisplayMetrics)")

            val correctedJson = obj.toString()
            return pipelineWorker.setProfileFromJson(correctedJson)
        } catch (e: Exception) {
            Log.e(TAG, "setProfile: failed to override screen dimensions: ${e.message}")
            // Fallback: pass original JSON unmodified
            return pipelineWorker.setProfileFromJson(profileJson)
        }
    }
    fun clearProfile() = pipelineWorker.clearProfile()

    fun updateSwipeTrigger(hardwareKey: String, direction: String, touchX: Float, touchY: Float) =
        pipelineWorker.updateSwipeTrigger(hardwareKey, direction, touchX, touchY)

    /**
     * Handle gamepad button event dari evdev reader.
     * Dipanggil oleh GameMapperUserService.forwardEventToPipeline().
     *
     * Thread safety: dipanggil dari evdev thread, diteruskan ke
     * pipeline yang berjalan di HandlerThread sendiri.
     */
    fun onGamepadButton(buttonName: String, value: Int) {
        if (buttonName.startsWith("CONTROLLER_ID:")) {
            Log.i(TAG, "Controller: ${buttonName.substringAfter("CONTROLLER_ID:")}")
            return
        }
        if (buttonName == "MODE") return
        pipelineWorker.onButtonEvent(buttonName, value == 1)
    }

    /**
     * Handle gamepad axis event dari evdev reader.
     * Dipanggil oleh GameMapperUserService.forwardEventToPipeline().
     *
     * @param axes FloatArray dengan 6 elemen: [lx, ly, rx, ry, l2, r2]
     *             Nilai range: -1.0..1.0 untuk stick, 0.0..1.0 untuk trigger
     */
    fun onGamepadAxis(axes: FloatArray) {
        pipelineWorker.updateAxisValues(axes)
        val l2 = axes.getOrNull(4) ?: -1f
        val r2 = axes.getOrNull(5) ?: -1f
        if (l2 >= 0f) pipelineWorker.onTriggerEvent("L2", l2)
        if (r2 >= 0f) pipelineWorker.onTriggerEvent("R2", r2)
    }

    fun setAntiBan(enabled: Boolean, coordinateJitter: Float, timingJitterMs: Int, pressureVariance: Float, sizeVariance: Float) {
        pipelineWorker.setAntiBan(enabled)
        touchInjector.setAntiBan(enabled, pressureVariance, sizeVariance)
    }

    fun getActiveButtonCount(): Int = pipelineWorker.getActiveButtonCount()
    fun getActiveProfile(): InputPipelineWorker.GameProfile? = pipelineWorker.getActiveProfile()

    /**
     * Serialize active profile ke JSON untuk UI display.
     * Menggunakan JSONObject (bukan string concatenation) per kontrak.
     */
    fun getActiveProfileJson(): String? {
        val profile = pipelineWorker.getActiveProfile() ?: return null
        val json = JSONObject()
        json.put("packageName", profile.packageName)
        json.put("screenWidth", profile.screenWidth)
        json.put("screenHeight", profile.screenHeight)
        json.put("displayId", profile.displayId)
        val buttons = org.json.JSONArray()
        for (m in profile.buttonMappings) {
            val b = JSONObject()
            b.put("hardwareKey", m.hardwareKey)
            b.put("touchX", m.touchX)
            b.put("touchY", m.touchY)
            b.put("actionType", m.actionType)
            b.put("swipeDirection", m.swipeDirection ?: JSONObject.NULL)
            buttons.put(b)
        }
        json.put("buttons", buttons)
        profile.leftStick?.let {
            val ls = JSONObject()
            ls.put("centerX", it.centerX)
            ls.put("centerY", it.centerY)
            ls.put("radius", it.radius)
            ls.put("deadzone", it.deadzone)
            ls.put("smoothing", it.smoothing)
            json.put("leftStick", ls)
        }
        profile.rightStick?.let {
            val rs = JSONObject()
            rs.put("centerX", it.centerX)
            rs.put("centerY", it.centerY)
            rs.put("radius", it.radius)
            rs.put("deadzone", it.deadzone)
            rs.put("smoothing", it.smoothing)
            json.put("rightStick", rs)
        }
        return json.toString()
    }

    /**
     * Get current screen width in pixels.
     * Used by InputPipelineWorker untuk konversi koordinat.
     */
    fun getScreenWidth(): Int = cachedScreenWidth

    /**
     * Get current screen height in pixels.
     * Used by InputPipelineWorker untuk konversi koordinat.
     */
    fun getScreenHeight(): Int = cachedScreenHeight

    // ============================================================
    // FIX #2: Direct injection delegate methods.
    // These replace the separate TouchInjector that was in GameMapperUserService.
    // All injection now goes through this SINGLE TouchInjector instance,
    // ensuring shared pointer state and no ID collisions.
    // ============================================================

    fun tap(x: Float, y: Float, displayId: Int) {
        touchInjector.tap(x, y, displayId)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, displayId: Int) {
        touchInjector.swipe(startX, startY, endX, endY, durationMs, displayId)
    }

    fun multiTouchDown(pointerIds: List<Int>, coords: List<Pair<Float, Float>>, displayId: Int) {
        touchInjector.multiTouchDown(pointerIds, coords, displayId)
    }

    fun multiTouchMove(pointerIds: List<Int>, coords: List<Pair<Float, Float>>, displayId: Int) {
        touchInjector.multiTouchMove(pointerIds, coords, displayId)
    }

    fun touchUp(pointerId: Int, displayId: Int) {
        touchInjector.touchUp(pointerId, displayId)
    }

    fun analogMove(pointerId: Int, centerX: Float, centerY: Float, targetX: Float, targetY: Float, displayId: Int) {
        touchInjector.analogMove(pointerId, centerX, centerY, targetX, targetY, displayId)
    }

    fun releaseAnalogStick(pointerId: Int, displayId: Int) {
        touchInjector.touchUp(pointerId, displayId)
    }

    fun getDiagnosticInfo(): String {
        return touchInjector.getDiagnosticInfo()
    }

    fun cleanup() {
        stopPipeline()
        Log.i(TAG, "Cleanup complete")
    }
}
