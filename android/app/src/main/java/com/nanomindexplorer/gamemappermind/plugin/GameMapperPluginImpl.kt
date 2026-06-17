package com.nanomindexplorer.gamemappermind.plugin

import android.content.Context
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.GamepadManager
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.model.GameProfile
import com.nanomindexplorer.gamemappermind.model.ProfileValidator
import org.json.JSONObject

/**
 * GameMapperPluginImpl — Business logic for the Shizuku UserService.
 *
 * FASE 2 + FASE 4 — Updated for new InputPipelineWorker (4-param constructor)
 * and new model.GameProfile type (kotlinx.serialization).
 *
 * This class runs inside the Shizuku UserService process (shell UID 2000).
 * It creates the TouchInjector with lambda-based screen metrics and the
 * InputPipelineWorker with the 4-param constructor.
 *
 * Methods called by GameMapperUserService (AIDL binder):
 *   - startPipeline() / stopPipeline() / isPipelineRunning()
 *   - setProfile(profileJson: String): Boolean
 *   - updateSwipeTrigger(hardwareKey, direction, touchX, touchY)
 *   - onGamepadButton(buttonName, value)
 *   - onGamepadAxis(axes)
 *   - setAntiBan(enabled, coordinateJitter, timingJitterMs, pressureVariance, sizeVariance)
 *   - cleanup()
 */
class GameMapperPluginImpl(
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "GameMapper/PluginImpl"
    }

    private val touchInjector: TouchInjector = TouchInjector(
        getScreenWidth = { getDisplayMetrics().widthPixels },
        getScreenHeight = { getDisplayMetrics().heightPixels }
    )

    private val analogProcessor: AnalogProcessor = AnalogProcessor()
    private val gamepadManager: GamepadManager = GamepadManager()

    private val pipelineWorker: InputPipelineWorker = InputPipelineWorker(
        gamepadManager = gamepadManager,
        touchInjector = touchInjector,
        analogProcessor = analogProcessor,
        onGamepadEvent = { event ->
            // Forward gamepad events to JS layer via the plugin's static emitters.
            // This callback runs on the pipeline worker thread.
            try {
                GameMapperPlugin.emitGamepadAxis(floatArrayOf(
                    event.optDouble("lx", 0.0).toFloat(),
                    event.optDouble("ly", 0.0).toFloat(),
                    event.optDouble("rx", 0.0).toFloat(),
                    event.optDouble("ry", 0.0).toFloat()
                ))
            } catch (t: Throwable) {
                Log.w(TAG, "onGamepadEvent forward failed: ${t.message}")
            }
        }
    )

    @Volatile private var pipelineStarted = false

    fun startPipeline() {
        if (pipelineStarted) return
        try {
            pipelineWorker.start()
            pipelineStarted = true
            Log.i(TAG, "Pipeline started")
        } catch (t: Throwable) {
            Log.e(TAG, "startPipeline failed", t)
        }
    }

    fun stopPipeline() {
        if (!pipelineStarted) return
        try {
            pipelineWorker.stop()
            pipelineStarted = false
            Log.i(TAG, "Pipeline stopped")
        } catch (t: Throwable) {
            Log.e(TAG, "stopPipeline failed", t)
        }
    }

    fun isPipelineRunning(): Boolean = pipelineStarted

    fun setProfile(profileJson: String): Boolean {
        return try {
            when (val result = ProfileValidator.parseAndValidate(profileJson)) {
                is ProfileValidator.ValidationResult.Ok -> {
                    pipelineWorker.setProfile(result.profile)
                    Log.i(TAG, "Profile set: ${result.profile.profileId}")
                    true
                }
                is ProfileValidator.ValidationResult.Err -> {
                    Log.e(TAG, "Profile validation failed: ${result.joined()}")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setProfile failed", t)
            false
        }
    }

    fun updateSwipeTrigger(hardwareKey: String, direction: String, touchX: Float, touchY: Float) {
        try {
            // Convert string direction to int code for InputPipelineWorker.
            // 0=up, 1=down, 2=left, 3=right
            val dirCode = when (direction.lowercase()) {
                "up" -> 0
                "down" -> 1
                "left" -> 2
                "right" -> 3
                else -> -1
            }
            // Convert hardwareKey string to button code.
            // Common evdev codes: BTN_SOUTH=304, BTN_EAST=305, etc.
            val buttonCode = hardwareKey.toIntOrNull() ?: -1
            if (buttonCode >= 0 && dirCode >= 0) {
                pipelineWorker.updateSwipeTrigger(buttonCode, dirCode)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "updateSwipeTrigger failed", t)
        }
    }

    fun onGamepadButton(buttonName: String, value: Int) {
        // Gamepad button events are handled by GamepadManager → InputPipelineWorker
        // internally. This method is a no-op stub for backward compatibility with
        // GameMapperUserService which calls it via AIDL.
        Log.d(TAG, "onGamepadButton: $buttonName=$value (handled by GamepadManager)")
    }

    fun onGamepadAxis(axes: FloatArray) {
        // Gamepad axis events are handled by GamepadManager → InputPipelineWorker
        // internally. This method is a no-op stub for backward compatibility.
        Log.d(TAG, "onGamepadAxis: ${axes.size} axes (handled by GamepadManager)")
    }

    fun setAntiBan(enabled: Boolean, coordinateJitter: Float, timingJitterMs: Int, pressureVariance: Float, sizeVariance: Float) {
        // Anti-ban configuration is handled inside TouchInjector via the pool.
        // This method is a stub for backward compatibility with GameMapperUserService.
        Log.i(TAG, "setAntiBan: enabled=$enabled jitter=$coordinateJitter timing=$timingJitterMs")
    }

    fun cleanup() {
        stopPipeline()
        try { touchInjector.releaseAll() } catch (t: Throwable) { Log.e(TAG, "releaseAll failed", t) }
        Log.i(TAG, "Cleanup complete")
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        return try {
            val metrics = DisplayMetrics()
            if (context != null) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.defaultDisplay?.getMetrics(metrics)
            }
            if (metrics.widthPixels <= 0) {
                metrics.widthPixels = 2800
                metrics.heightPixels = 1840
            }
            metrics
        } catch (t: Throwable) {
            Log.w(TAG, "getDisplayMetrics failed, using fallback 2800x1840: ${t.message}")
            DisplayMetrics().apply {
                widthPixels = 2800
                heightPixels = 1840
            }
        }
    }
}
