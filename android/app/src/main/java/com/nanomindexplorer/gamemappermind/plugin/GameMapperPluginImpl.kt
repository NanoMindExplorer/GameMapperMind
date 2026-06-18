package com.nanomindexplorer.gamemappermind.plugin

import android.content.Context
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.util.HarmonyOSHelper
import com.nanomindexplorer.gamemappermind.util.HarmonyOSSafeAreaHelper
import org.json.JSONObject

/**
 * GameMapperPluginImpl — Business logic for the Shizuku UserService.
 *
 * GMM-AEC-002 §9.2 enhancement: Auto-switch injection behavior untuk HarmonyOS
 *   - Jika isHarmonyOS() true → auto-enable efootballMode (multi-step + Gaussian delay)
 *   - Gunakan HarmonyOSSafeAreaHelper untuk coordinate probe
 *   - Log platform-specific behavior untuk debugging
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

    private val pipelineWorker: InputPipelineWorker = InputPipelineWorker(
        touchInjector,
        analogProcessor
    )

    @Volatile private var pipelineStarted = false

    @Volatile private var cachedScreenWidth: Int = FALLBACK_SCREEN_WIDTH
    @Volatile private var cachedScreenHeight: Int = FALLBACK_SCREEN_HEIGHT

    // GMM-AEC-002 §9.1: Cache HarmonyOS detection
    private val isHarmonyOS: Boolean by lazy { HarmonyOSHelper.isHarmonyOS() }

    init {
        refreshDisplayMetrics()
        touchInjector.initialize()

        // ============================================================
        // GMM-AEC-002 §9.2: Auto-switch injection behavior untuk HarmonyOS
        // ============================================================
        // Saat HarmonyOS terdeteksi, auto-enable efootballMode untuk
        // mengaktifkan FLAG_VIRTUAL + pressure variation + multi-step MOVE.
        // Ini karena Konami engine (dan banyak game Huawei) menolak
        // MotionEvent yang terlihat seperti synthetic event.
        // ============================================================
        if (isHarmonyOS) {
            Log.i(TAG, "HarmonyOS detected — auto-enabling efootballMode for compatibility")
            touchInjector.setEfootballMode(true)
        }
    }

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
                        Log.i(TAG, "DisplayMetrics: " + cachedScreenWidth + "x" + cachedScreenHeight)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshDisplayMetrics failed, using fallback: " + e.message)
        }
        cachedScreenWidth = FALLBACK_SCREEN_WIDTH
        cachedScreenHeight = FALLBACK_SCREEN_HEIGHT
        Log.i(TAG, "Using fallback resolution: " + cachedScreenWidth + "x" + cachedScreenHeight)
    }

    fun startPipeline() {
        if (pipelineStarted) return
        refreshDisplayMetrics()
        pipelineWorker.start()
        pipelineStarted = true
        Log.i(TAG, "Pipeline started (screen=" + cachedScreenWidth + "x" + cachedScreenHeight +
                   ", harmonyOS=" + isHarmonyOS + ")")
    }

    fun stopPipeline() {
        if (!pipelineStarted) return
        pipelineWorker.stop()
        pipelineWorker.clearProfile()
        pipelineStarted = false
        Log.i(TAG, "Pipeline stopped")
    }

    fun isPipelineRunning(): Boolean = pipelineStarted

    fun setProfile(profileJson: String): Boolean {
        try {
            if (!pipelineStarted) {
                Log.w(TAG, "setProfile: pipeline not started, auto-starting...")
                startPipeline()
            }

            val obj = JSONObject(profileJson)
            refreshDisplayMetrics()
            obj.put("screenWidth", cachedScreenWidth)
            obj.put("screenHeight", cachedScreenHeight)

            Log.i(TAG, "setProfile: overriding screen dimensions to " +
                    cachedScreenWidth + "x" + cachedScreenHeight + " (from DisplayMetrics, harmonyOS=" +
                    isHarmonyOS + ")")

            val correctedJson = obj.toString()

            // GMM-AEC-002 §11.2: Auto-enable efootballMode jika profile adalah eFootball
            val packageName = obj.optString("packageName", "")
            if (packageName.contains("konami") || packageName.contains("pesam") ||
                packageName.contains("efootball")) {
                Log.i(TAG, "eFootball profile detected — enabling efootballMode")
                touchInjector.setEfootballMode(true)
            }

            return pipelineWorker.setProfileFromJson(correctedJson)
        } catch (e: Exception) {
            Log.e(TAG, "setProfile: failed to override screen dimensions: " + e.message)
            return pipelineWorker.setProfileFromJson(profileJson)
        }
    }

    fun clearProfile() = pipelineWorker.clearProfile()

    fun updateSwipeTrigger(hardwareKey: String, direction: String, touchX: Float, touchY: Float) =
        pipelineWorker.updateSwipeTrigger(hardwareKey, direction, touchX, touchY)

    fun onGamepadButton(buttonName: String, value: Int) {
        if (buttonName.startsWith("CONTROLLER_ID:")) {
            Log.i(TAG, "Controller: " + buttonName.substringAfter("CONTROLLER_ID:"))
            return
        }
        if (buttonName == "MODE") return
        pipelineWorker.onButtonEvent(buttonName, value == 1)
    }

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

    // GMM-AEC-002 §11.2: Expose setEfootballMode untuk JS-side control
    fun setEfootballMode(enabled: Boolean) {
        touchInjector.setEfootballMode(enabled)
    }

    fun getActiveButtonCount(): Int = pipelineWorker.getActiveButtonCount()
    fun getActiveProfile(): InputPipelineWorker.GameProfile? = pipelineWorker.getActiveProfile()

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

    fun getScreenWidth(): Int = cachedScreenWidth
    fun getScreenHeight(): Int = cachedScreenHeight

    // GMM-AEC-002 §9.1: Expose HarmonyOS info untuk UI display
    fun isHarmonyOS(): Boolean = isHarmonyOS
    fun getHarmonyMajorVersion(): Int = HarmonyOSHelper.getHarmonyMajorVersion()
    fun getRecommendedPollingHz(): Int = HarmonyOSHelper.getRecommendedPollingHz()

    // Direct injection delegate methods
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
        return touchInjector.getDiagnosticInfo() + " | " + pipelineWorker.getDiagnosticInfo()
    }

    fun cleanup() {
        stopPipeline()
        Log.i(TAG, "Cleanup complete")
    }
}
