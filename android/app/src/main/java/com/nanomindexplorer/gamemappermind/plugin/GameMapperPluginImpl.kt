package com.nanomindexplorer.gamemappermind.plugin

import android.content.Context
import android.util.Log
import com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import org.json.JSONObject

class GameMapperPluginImpl(
    @Suppress("UNUSED_PARAMETER") private val context: Context? = null
) {
    companion object { private const val TAG = "GameMapper/PluginImpl" }

    private val touchInjector: TouchInjector = TouchInjector()
    private val analogProcessor: AnalogProcessor = AnalogProcessor()
    private val pipelineWorker: InputPipelineWorker = InputPipelineWorker(touchInjector, analogProcessor)
    @Volatile private var pipelineStarted = false

    fun startPipeline() { if (pipelineStarted) return; pipelineWorker.start(); pipelineStarted = true; Log.i(TAG, "Pipeline started") }
    fun stopPipeline() { if (!pipelineStarted) return; pipelineWorker.stop(); pipelineWorker.clearProfile(); pipelineStarted = false; Log.i(TAG, "Pipeline stopped") }
    fun isPipelineRunning(): Boolean = pipelineStarted
    fun setProfile(profileJson: String): Boolean = pipelineWorker.setProfileFromJson(profileJson)
    fun clearProfile() = pipelineWorker.clearProfile()
    fun updateSwipeTrigger(hardwareKey: String, direction: String, touchX: Float, touchY: Float) = pipelineWorker.updateSwipeTrigger(hardwareKey, direction, touchX, touchY)

    fun onGamepadButton(buttonName: String, value: Int) {
        if (buttonName.startsWith("CONTROLLER_ID:")) { Log.i(TAG, "Controller: ${buttonName.substringAfter("CONTROLLER_ID:")}"); return }
        if (buttonName == "MODE") return
        pipelineWorker.onButtonEvent(buttonName, value == 1)
    }

    fun onGamepadAxis(axes: FloatArray) {
        pipelineWorker.updateAxisValues(axes)
        val l2 = axes.getOrNull(4) ?: -1f; val r2 = axes.getOrNull(5) ?: -1f
        if (l2 >= 0f) pipelineWorker.onTriggerEvent("L2", l2)
        if (r2 >= 0f) pipelineWorker.onTriggerEvent("R2", r2)
    }

    fun setAntiBan(enabled: Boolean, coordinateJitter: Float, timingJitterMs: Int, pressureVariance: Float, sizeVariance: Float) {
        pipelineWorker.setAntiBan(enabled); touchInjector.setAntiBan(enabled, pressureVariance, sizeVariance)
    }

    fun getActiveButtonCount(): Int = pipelineWorker.getActiveButtonCount()
    fun getActiveProfile(): InputPipelineWorker.GameProfile? = pipelineWorker.getActiveProfile()

    fun getActiveProfileJson(): String? {
        val profile = pipelineWorker.getActiveProfile() ?: return null
        val json = JSONObject()
        json.put("packageName", profile.packageName); json.put("screenWidth", profile.screenWidth); json.put("screenHeight", profile.screenHeight); json.put("displayId", profile.displayId)
        val buttons = org.json.JSONArray()
        for (m in profile.buttonMappings) { val b = JSONObject(); b.put("hardwareKey", m.hardwareKey); b.put("touchX", m.touchX); b.put("touchY", m.touchY); b.put("actionType", m.actionType); b.put("swipeDirection", m.swipeDirection ?: JSONObject.NULL); buttons.put(b) }
        json.put("buttons", buttons)
        profile.leftStick?.let { val ls = JSONObject(); ls.put("centerX", it.centerX); ls.put("centerY", it.centerY); ls.put("radius", it.radius); ls.put("deadzone", it.deadzone); ls.put("smoothing", it.smoothing); json.put("leftStick", ls) }
        profile.rightStick?.let { val rs = JSONObject(); rs.put("centerX", it.centerX); rs.put("centerY", it.centerY); rs.put("radius", it.radius); rs.put("deadzone", it.deadzone); rs.put("smoothing", it.smoothing); json.put("rightStick", rs) }
        return json.toString()
    }

    fun cleanup() { stopPipeline(); Log.i(TAG, "Cleanup complete") }
}
