package com.nanomindexplorer.gamemappermind.daemon

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class InputPipelineWorker(
    private val touchInjector: TouchInjector,
    private val analogProcessor: AnalogProcessor
) {
    companion object {
        private const val TAG = "GameMapper/PipelineWorker"
        private const val POLL_INTERVAL_MS = 4L
        private const val SWIPE_DURATION_MS = 80L
    }

    data class ButtonMapping(val hardwareKey: String, val touchX: Float, val touchY: Float, val actionType: String, val swipeDirection: String?)
    data class AnalogConfig(val centerX: Float, val centerY: Float, val radius: Float, val deadzone: Float, val smoothing: Float)
    data class GameProfile(val packageName: String, val screenWidth: Int, val screenHeight: Int, val displayId: Int, val buttonMappings: List<ButtonMapping>, val leftStick: AnalogConfig?, val rightStick: AnalogConfig?)

    @Volatile private var activeProfile: GameProfile? = null
    private var pipelineThread: HandlerThread? = null
    private var pipelineHandler: Handler? = null
    @Volatile private var running = false
    private val leftStickState = AnalogProcessor.AnalogState()
    private val rightStickState = AnalogProcessor.AnalogState()
    private val buttonStates = ConcurrentHashMap<String, Boolean>()
    private val buttonPointers = ConcurrentHashMap<String, Int>()
    private var nextPointerId = 10
    @Volatile private var lastAxes = FloatArray(6)

    fun setProfileFromJson(json: String): Boolean {
        try {
            val obj = JSONObject(json)
            val buttonMappings = mutableListOf<ButtonMapping>()
            val buttonsArray = obj.optJSONArray("buttons")
            if (buttonsArray != null) {
                for (i in 0 until buttonsArray.length()) {
                    val btn = buttonsArray.getJSONObject(i)
                    buttonMappings.add(ButtonMapping(btn.getString("hardwareKey"), btn.getDouble("touchX").toFloat(), btn.getDouble("touchY").toFloat(), btn.optString("actionType", "tap"), if (btn.isNull("swipeDirection") || !btn.has("swipeDirection")) null else btn.getString("swipeDirection")))
                }
            }
            val leftStick = parseAnalogConfig(obj.optJSONObject("leftStick"))
            val rightStick = parseAnalogConfig(obj.optJSONObject("rightStick"))
            val profile = GameProfile(obj.optString("packageName", ""), obj.optInt("screenWidth", 2800), obj.optInt("screenHeight", 1840), obj.optInt("displayId", 0), buttonMappings, leftStick, rightStick)
            analogProcessor.reset(leftStickState); analogProcessor.reset(rightStickState)
            buttonStates.clear(); buttonPointers.clear(); nextPointerId = 10
            activeProfile = profile
            Log.i(TAG, "Profile set: ${profile.packageName} (${buttonMappings.size} buttons)")
            return true
        } catch (e: Exception) { Log.e(TAG, "Failed to parse profile JSON", e); return false }
    }

    private fun parseAnalogConfig(obj: JSONObject?): AnalogConfig? {
        if (obj == null) return null
        return AnalogConfig(obj.getDouble("centerX").toFloat(), obj.getDouble("centerY").toFloat(), obj.getDouble("radius").toFloat(), obj.getDouble("deadzone").toFloat(), obj.getDouble("smoothing").toFloat())
    }

    fun clearProfile() { activeProfile = null; analogProcessor.reset(leftStickState); analogProcessor.reset(rightStickState); buttonStates.clear(); buttonPointers.clear() }

    fun start() { if (running) return; running = true; pipelineThread = HandlerThread("InputPipelineThread").also { it.start() }; pipelineHandler = Handler(pipelineThread!!.looper); pipelineHandler?.post(pollRunnable); Log.i(TAG, "Pipeline started at 250Hz") }
    fun stop() { running = false; pipelineHandler?.removeCallbacksAndMessages(null); pipelineThread?.quitSafely(); pipelineThread = null; pipelineHandler = null; analogProcessor.reset(leftStickState); analogProcessor.reset(rightStickState); buttonStates.clear(); buttonPointers.clear(); Log.i(TAG, "Pipeline stopped") }

    private val pollRunnable = object : Runnable {
        override fun run() { if (!running) return; val profile = activeProfile; if (profile != null) processAnalogSticks(profile); pipelineHandler?.postDelayed(this, POLL_INTERVAL_MS) }
    }

    private fun processAnalogSticks(profile: GameProfile) {
        if (profile.leftStick != null) {
            val config = AnalogProcessor.AnalogConfig(profile.leftStick.centerX * profile.screenWidth, profile.leftStick.centerY * profile.screenHeight, profile.leftStick.radius * profile.screenWidth, profile.leftStick.deadzone, profile.leftStick.smoothing)
            val (targetX, targetY, shouldInject) = analogProcessor.process(lastAxes[0], lastAxes[1], config, leftStickState)
            if (shouldInject) { if (leftStickState.isDown) touchInjector.analogMove(0, config.centerX, config.centerY, targetX, targetY, profile.displayId) else touchInjector.touchUp(0, profile.displayId) }
        }
        if (profile.rightStick != null) {
            val config = AnalogProcessor.AnalogConfig(profile.rightStick.centerX * profile.screenWidth, profile.rightStick.centerY * profile.screenHeight, profile.rightStick.radius * profile.screenWidth, profile.rightStick.deadzone, profile.rightStick.smoothing)
            val (targetX, targetY, shouldInject) = analogProcessor.process(lastAxes[2], lastAxes[3], config, rightStickState)
            if (shouldInject) { if (rightStickState.isDown) touchInjector.analogMove(1, config.centerX, config.centerY, targetX, targetY, profile.displayId) else touchInjector.touchUp(1, profile.displayId) }
        }
    }

    fun updateAxisValues(axes: FloatArray) { if (axes.size >= 6) lastAxes = axes.copyOf() }

    fun onButtonEvent(buttonName: String, isPressed: Boolean) {
        val profile = activeProfile ?: return
        val mapping = profile.buttonMappings.find { it.hardwareKey == buttonName } ?: return
        val wasPressed = buttonStates[buttonName] ?: false; buttonStates[buttonName] = isPressed
        val pixelX = mapping.touchX * profile.screenWidth; val pixelY = mapping.touchY * profile.screenHeight
        when (mapping.actionType) {
            "swipe" -> { if (isPressed && !wasPressed) { val (endX, endY) = calculateSwipeEnd(pixelX, pixelY, mapping.swipeDirection ?: "UP", profile.screenWidth, profile.screenHeight); touchInjector.swipe(pixelX, pixelY, endX, endY, SWIPE_DURATION_MS, profile.displayId) } }
            else -> {
                if (isPressed && !wasPressed) { val ptr = allocatePointer(buttonName); touchInjector.analogMove(ptr, pixelX, pixelY, pixelX, pixelY, profile.displayId) }
                else if (!isPressed && wasPressed) { val ptr = releasePointer(buttonName); if (ptr != null) touchInjector.touchUp(ptr, profile.displayId) }
            }
        }
    }

    fun onTriggerEvent(triggerName: String, value: Float) {
        val profile = activeProfile ?: return
        val mapping = profile.buttonMappings.find { it.hardwareKey == triggerName } ?: return
        val pixelX = mapping.touchX * profile.screenWidth; val pixelY = mapping.touchY * profile.screenHeight
        val isPressed = value > 0.3f; val wasPressed = buttonStates[triggerName] ?: false
        if (isPressed && !wasPressed) { buttonStates[triggerName] = true; if (mapping.actionType == "swipe") { val (endX, endY) = calculateSwipeEnd(pixelX, pixelY, mapping.swipeDirection ?: "UP", profile.screenWidth, profile.screenHeight); touchInjector.swipe(pixelX, pixelY, endX, endY, SWIPE_DURATION_MS, profile.displayId) } else { val ptr = allocatePointer(triggerName); touchInjector.analogMove(ptr, pixelX, pixelY, pixelX, pixelY, profile.displayId) } }
        else if (!isPressed && wasPressed) { buttonStates[triggerName] = false; if (mapping.actionType != "swipe") { val ptr = releasePointer(triggerName); if (ptr != null) touchInjector.touchUp(ptr, profile.displayId) } }
    }

    private fun calculateSwipeEnd(startX: Float, startY: Float, direction: String, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        val distX = screenWidth * 0.15f; val distY = screenHeight * 0.15f
        return when (direction) { "UP" -> Pair(startX, (startY - distY).coerceAtLeast(0f)); "DOWN" -> Pair(startX, (startY + distY).coerceAtMost(screenHeight.toFloat())); "LEFT" -> Pair((startX - distX).coerceAtLeast(0f), startY); "RIGHT" -> Pair((startX + distX).coerceAtMost(screenWidth.toFloat()), startY); else -> Pair(startX, startY) }
    }

    fun updateSwipeTrigger(hardwareKey: String, direction: String, touchX: Float, touchY: Float) {
        val profile = activeProfile ?: return
        val updated = profile.buttonMappings.filter { it.hardwareKey != hardwareKey }.toMutableList()
        updated.add(ButtonMapping(hardwareKey, touchX, touchY, "swipe", direction))
        activeProfile = profile.copy(buttonMappings = updated)
    }

    private fun allocatePointer(hardwareKey: String): Int { buttonPointers[hardwareKey]?.let { return it }; val ptr = nextPointerId++; buttonPointers[hardwareKey] = ptr; return ptr }
    private fun releasePointer(hardwareKey: String): Int? = buttonPointers.remove(hardwareKey)
    fun setAntiBan(enabled: Boolean) { touchInjector.setAntiBan(enabled, 0.15f, 0.10f) }
    fun isRunning(): Boolean = running
    fun getActiveProfile(): GameProfile? = activeProfile
    fun getActiveButtonCount(): Int = buttonStates.count { it.value }
}
