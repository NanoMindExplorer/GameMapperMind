package com.nanomindexplorer.gamemappermind.daemon

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.util.HarmonyOSHelper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * InputPipelineWorker — Gamepad event → Touch injection pipeline.
 *
 * GMM-AEC-002 §12.4: Polling rate hard cap.
 *   - HarmonyOS: max 120Hz (interval 8ms)
 *   - Android murni: max 200Hz (interval 5ms)
 *   - Sebelumnya: 250Hz (4ms) — menyebabkan instability
 *
 * GMM-AEC-002 §11.6: Release SEMUA pointer ID saat gamepad disconnect
 * atau app pause — tidak boleh ada "ghost pointer" yang stuck di game state.
 *
 * Thread safety:
 *   - activeProfile: @Volatile
 *   - buttonStates/buttonPointers/mappingLookup: ConcurrentHashMap
 *   - nextPointerId: AtomicInteger (atomic increment)
 *   - lastAxes: @Volatile + copyOf() on write
 */
class InputPipelineWorker(
    private val touchInjector: TouchInjector,
    private val analogProcessor: AnalogProcessor
) {
    companion object {
        private const val TAG = "GameMapper/PipelineWorker"

        // GMM-AEC-002 §12.4: Polling rate hard cap
        private const val POLL_INTERVAL_MS_HARMONY = 8L   // 125Hz (headroom di atas 120Hz)
        private const val POLL_INTERVAL_MS_ANDROID = 5L   // 200Hz
        private const val POLL_INTERVAL_MS_LEGACY = 4L    // 250Hz (fallback/testing)

        private const val SWIPE_DURATION_MS = 80L
        private const val MIN_POINTER_ID = 10
        private const val MAX_POINTER_ID = 109
    }

    @Volatile
    private var pollIntervalMs: Long = POLL_INTERVAL_MS_ANDROID

    fun getPollIntervalMs(): Long = pollIntervalMs
    fun getPollingHz(): Int = (1000 / pollIntervalMs).toInt()

    data class ButtonMapping(
        val hardwareKey: String,
        val touchX: Float,
        val touchY: Float,
        val actionType: String,
        val swipeDirection: String?
    )

    data class AnalogConfig(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val deadzone: Float,
        val smoothing: Float
    )

    data class GameProfile(
        val packageName: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val displayId: Int,
        val buttonMappings: List<ButtonMapping>,
        val leftStick: AnalogConfig?,
        val rightStick: AnalogConfig?
    )

    @Volatile private var activeProfile: GameProfile? = null
    private val mappingLookup = ConcurrentHashMap<String, ButtonMapping>()
    private var pipelineThread: HandlerThread? = null
    private var pipelineHandler: Handler? = null
    @Volatile private var running = false

    private val leftStickState = AnalogProcessor.AnalogState()
    private val rightStickState = AnalogProcessor.AnalogState()

    private val buttonStates = ConcurrentHashMap<String, Boolean>()
    private val buttonPointers = ConcurrentHashMap<String, Int>()
    private val nextPointerId = AtomicInteger(MIN_POINTER_ID)

    @Volatile private var lastAxes = FloatArray(6)
    @Volatile private var droppedEvents = 0

    fun setProfileFromJson(json: String): Boolean {
        try {
            val obj = JSONObject(json)
            val buttonMappings = mutableListOf<ButtonMapping>()
            val buttonsArray = obj.optJSONArray("buttons")
            if (buttonsArray != null) {
                for (i in 0 until buttonsArray.length()) {
                    val btn = buttonsArray.getJSONObject(i)
                    buttonMappings.add(ButtonMapping(
                        btn.getString("hardwareKey"),
                        btn.getDouble("touchX").toFloat(),
                        btn.getDouble("touchY").toFloat(),
                        btn.optString("actionType", "tap"),
                        if (btn.isNull("swipeDirection") || !btn.has("swipeDirection")) null else btn.getString("swipeDirection")
                    ))
                }
            }
            val leftStick = parseAnalogConfig(obj.optJSONObject("leftStick"))
            val rightStick = parseAnalogConfig(obj.optJSONObject("rightStick"))
            val profile = GameProfile(
                obj.optString("packageName", ""),
                obj.optInt("screenWidth", 2800),
                obj.optInt("screenHeight", 1840),
                obj.optInt("displayId", 0),
                buttonMappings,
                leftStick,
                rightStick
            )

            analogProcessor.reset(leftStickState)
            analogProcessor.reset(rightStickState)
            buttonStates.clear()
            buttonPointers.clear()
            nextPointerId.set(MIN_POINTER_ID)
            droppedEvents = 0

            mappingLookup.clear()
            for (m in buttonMappings) {
                mappingLookup[m.hardwareKey] = m
            }

            activeProfile = profile
            Log.i(TAG, "Profile set: ${profile.packageName} (${buttonMappings.size} buttons, ${mappingLookup.size} mapped) running=$running")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profile JSON: ${e.message}", e)
            return false
        }
    }

    private fun parseAnalogConfig(obj: JSONObject?): AnalogConfig? {
        if (obj == null) return null
        return AnalogConfig(
            obj.getDouble("centerX").toFloat(),
            obj.getDouble("centerY").toFloat(),
            obj.getDouble("radius").toFloat(),
            obj.getDouble("deadzone").toFloat(),
            obj.getDouble("smoothing").toFloat()
        )
    }

    fun clearProfile() {
        activeProfile = null
        mappingLookup.clear()
        analogProcessor.reset(leftStickState)
        analogProcessor.reset(rightStickState)
        buttonStates.clear()
        buttonPointers.clear()
        nextPointerId.set(MIN_POINTER_ID)
        Log.i(TAG, "Profile cleared")
    }

    fun start() {
        if (running) {
            Log.w(TAG, "start() called but pipeline already running")
            return
        }

        // GMM-AEC-002 §12.4: Resolve polling interval based on platform
        val isHarmony = HarmonyOSHelper.isHarmonyOS()
        pollIntervalMs = if (isHarmony) POLL_INTERVAL_MS_HARMONY else POLL_INTERVAL_MS_ANDROID
        Log.i(TAG, "Polling interval: ${pollIntervalMs}ms (${getPollingHz()}Hz, HarmonyOS=$isHarmony)")

        running = true
        pipelineThread = HandlerThread("InputPipelineThread").also { it.start() }
        pipelineHandler = Handler(pipelineThread!!.looper)
        pipelineHandler?.post(pollRunnable)
        Log.i(TAG, "Pipeline started at ${getPollingHz()}Hz (profile=${activeProfile?.packageName ?: "null"})")
    }

    fun stop() {
        if (!running) {
            Log.w(TAG, "stop() called but pipeline not running")
            return
        }
        running = false
        pipelineHandler?.removeCallbacksAndMessages(null)
        pipelineThread?.quitSafely()
        pipelineThread = null
        pipelineHandler = null
        analogProcessor.reset(leftStickState)
        analogProcessor.reset(rightStickState)

        // GMM-AEC-002 §11.6: Release SEMUA pointer ID saat stop
        releaseAllPointersInternal()

        buttonStates.clear()
        buttonPointers.clear()
        Log.i(TAG, "Pipeline stopped (dropped $droppedEvents events during session)")
    }

    /**
     * GMM-AEC-002 §11.6: Release SEMUA pointer ID saat gamepad disconnected
     * atau app pause — tidak boleh ada "ghost pointer" yang stuck.
     *
     * Dipanggil dari:
     *   - Gamepad disconnect event (GameMapperUserService)
     *   - App onPause (MainActivity)
     *   - Profile change (untuk clean state)
     */
    fun releaseAllPointers() {
        Log.i(TAG, "releaseAllPointers() — releasing ${buttonPointers.size} active button pointers")
        releaseAllPointersInternal()

        analogProcessor.reset(leftStickState)
        analogProcessor.reset(rightStickState)

        buttonStates.clear()
        buttonPointers.clear()
        nextPointerId.set(MIN_POINTER_ID)
    }

    private fun releaseAllPointersInternal() {
        val profile = activeProfile ?: return
        val displayId = profile.displayId
        val iterator = buttonPointers.values.iterator()
        var released = 0
        while (iterator.hasNext()) {
            val ptrId = iterator.next()
            try {
                touchInjector.touchUp(ptrId, displayId)
                released++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release pointer $ptrId: ${e.message}")
            }
        }
        // Juga release analog stick pointers (id 0 dan 1)
        try {
            touchInjector.touchUp(0, displayId)
            touchInjector.touchUp(1, displayId)
            released += 2
        } catch (_: Exception) {}
        Log.i(TAG, "releaseAllPointersInternal: released $released pointers")
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val profile = activeProfile
            if (profile != null) {
                processAnalogSticks(profile)
            }
            pipelineHandler?.postDelayed(this, pollIntervalMs)
        }
    }

    private fun processAnalogSticks(profile: GameProfile) {
        if (profile.leftStick != null) {
            val config = AnalogProcessor.AnalogConfig(
                profile.leftStick.centerX * profile.screenWidth,
                profile.leftStick.centerY * profile.screenHeight,
                profile.leftStick.radius * profile.screenWidth,
                profile.leftStick.deadzone,
                profile.leftStick.smoothing
            )
            val (targetX, targetY, shouldInject) = analogProcessor.process(lastAxes[0], lastAxes[1], config, leftStickState)
            if (shouldInject) {
                if (leftStickState.isDown) {
                    touchInjector.analogMove(0, config.centerX, config.centerY, targetX, targetY, profile.displayId)
                } else {
                    touchInjector.touchUp(0, profile.displayId)
                }
            }
        }
        if (profile.rightStick != null) {
            val config = AnalogProcessor.AnalogConfig(
                profile.rightStick.centerX * profile.screenWidth,
                profile.rightStick.centerY * profile.screenHeight,
                profile.rightStick.radius * profile.screenWidth,
                profile.rightStick.deadzone,
                profile.rightStick.smoothing
            )
            val (targetX, targetY, shouldInject) = analogProcessor.process(lastAxes[2], lastAxes[3], config, rightStickState)
            if (shouldInject) {
                if (rightStickState.isDown) {
                    touchInjector.analogMove(1, config.centerX, config.centerY, targetX, targetY, profile.displayId)
                } else {
                    touchInjector.touchUp(1, profile.displayId)
                }
            }
        }
    }

    fun updateAxisValues(axes: FloatArray) {
        if (axes.size >= 6) {
            lastAxes = axes.copyOf()
        }
    }

    fun onButtonEvent(buttonName: String, isPressed: Boolean) {
        if (!running) {
            Log.w(TAG, "onButtonEvent('$buttonName', $isPressed) — pipeline NOT running, dropping")
            return
        }

        val profile = activeProfile
        if (profile == null) {
            droppedEvents++
            if (droppedEvents <= 5 || droppedEvents % 100 == 0) {
                Log.w(TAG, "onButtonEvent('$buttonName', $isPressed) — no profile set, dropped #$droppedEvents")
            }
            return
        }

        val mapping = mappingLookup[buttonName]
        if (mapping == null) {
            return
        }

        val wasPressed = buttonStates[buttonName] ?: false
        buttonStates[buttonName] = isPressed

        val pixelX = mapping.touchX * profile.screenWidth
        val pixelY = mapping.touchY * profile.screenHeight

        when (mapping.actionType) {
            "swipe" -> {
                if (isPressed && !wasPressed) {
                    val (endX, endY) = calculateSwipeEnd(
                        pixelX, pixelY,
                        mapping.swipeDirection ?: "UP",
                        profile.screenWidth, profile.screenHeight
                    )
                    touchInjector.swipe(pixelX, pixelY, endX, endY, SWIPE_DURATION_MS, profile.displayId)
                    Log.d(TAG, "SWIPE: $buttonName → ($pixelX,$pixelY)→($endX,$endY) dir=${mapping.swipeDirection}")
                }
            }
            else -> {
                if (isPressed && !wasPressed) {
                    val ptr = allocatePointer(buttonName)
                    touchInjector.analogMove(ptr, pixelX, pixelY, pixelX, pixelY, profile.displayId)
                    Log.d(TAG, "DOWN: $buttonName → ptr=$ptr ($pixelX,$pixelY)")
                } else if (!isPressed && wasPressed) {
                    val ptr = releasePointer(buttonName)
                    if (ptr != null) {
                        touchInjector.touchUp(ptr, profile.displayId)
                        Log.d(TAG, "UP: $buttonName → ptr=$ptr")
                    }
                }
            }
        }
    }

    fun onTriggerEvent(triggerName: String, value: Float) {
        if (!running) return
        val profile = activeProfile ?: return
        val mapping = mappingLookup[triggerName] ?: return

        val pixelX = mapping.touchX * profile.screenWidth
        val pixelY = mapping.touchY * profile.screenHeight
        val isPressed = value > 0.3f
        val wasPressed = buttonStates[triggerName] ?: false

        if (isPressed && !wasPressed) {
            buttonStates[triggerName] = true
            if (mapping.actionType == "swipe") {
                val (endX, endY) = calculateSwipeEnd(
                    pixelX, pixelY,
                    mapping.swipeDirection ?: "UP",
                    profile.screenWidth, profile.screenHeight
                )
                touchInjector.swipe(pixelX, pixelY, endX, endY, SWIPE_DURATION_MS, profile.displayId)
            } else {
                val ptr = allocatePointer(triggerName)
                touchInjector.analogMove(ptr, pixelX, pixelY, pixelX, pixelY, profile.displayId)
            }
        } else if (!isPressed && wasPressed) {
            buttonStates[triggerName] = false
            if (mapping.actionType != "swipe") {
                val ptr = releasePointer(triggerName)
                if (ptr != null) touchInjector.touchUp(ptr, profile.displayId)
            }
        }
    }

    private fun calculateSwipeEnd(
        startX: Float, startY: Float,
        direction: String,
        screenWidth: Int, screenHeight: Int
    ): Pair<Float, Float> {
        val distX = screenWidth * 0.15f
        val distY = screenHeight * 0.15f
        return when (direction) {
            "UP" -> Pair(startX, (startY - distY).coerceAtLeast(0f))
            "DOWN" -> Pair(startX, (startY + distY).coerceAtMost(screenHeight.toFloat()))
            "LEFT" -> Pair((startX - distX).coerceAtLeast(0f), startY)
            "RIGHT" -> Pair((startX + distX).coerceAtMost(screenWidth.toFloat()), startY)
            else -> Pair(startX, startY)
        }
    }

    fun updateSwipeTrigger(hardwareKey: String, direction: String, touchX: Float, touchY: Float) {
        val profile = activeProfile ?: return
        val updated = profile.buttonMappings.filter { it.hardwareKey != hardwareKey }.toMutableList()
        updated.add(ButtonMapping(hardwareKey, touchX, touchY, "swipe", direction))
        activeProfile = profile.copy(buttonMappings = updated)
        mappingLookup.clear()
        for (m in updated) mappingLookup[m.hardwareKey] = m
    }

    private fun allocatePointer(hardwareKey: String): Int {
        buttonPointers[hardwareKey]?.let { return it }
        val ptr = nextPointerId.getAndIncrement()
        if (ptr > MAX_POINTER_ID) {
            nextPointerId.set(MIN_POINTER_ID)
        }
        val actualPtr = if (ptr > MAX_POINTER_ID) MIN_POINTER_ID else ptr
        buttonPointers[hardwareKey] = actualPtr
        return actualPtr
    }

    private fun releasePointer(hardwareKey: String): Int? = buttonPointers.remove(hardwareKey)

    fun setAntiBan(enabled: Boolean) {
        touchInjector.setAntiBan(enabled, 0.15f, 0.10f)
    }

    fun isRunning(): Boolean = running
    fun getActiveProfile(): GameProfile? = activeProfile
    fun getActiveButtonCount(): Int = buttonStates.count { it.value }
    fun getDroppedEventCount(): Int = droppedEvents

    fun getDiagnosticInfo(): String {
        return "Pipeline{running=$running, pollingHz=${getPollingHz()}, " +
               "interval=${pollIntervalMs}ms, " +
               "profile=${activeProfile?.packageName ?: "null"}, " +
               "activeButtons=${buttonStates.count { it.value }}, " +
               "activePointers=${buttonPointers.size}, " +
               "droppedEvents=$droppedEvents}"
    }
}
