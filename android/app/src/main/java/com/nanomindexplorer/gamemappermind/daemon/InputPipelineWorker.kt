package com.nanomindexplorer.gamemappermind.daemon

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * InputPipelineWorker — Gamepad event → Touch injection pipeline.
 *
 * Step [6] fix: Lifecycle guards + thread safety + O(1) lookup.
 *
 * Thread model:
 *   - Thread A (evdev): onButtonEvent(), onTriggerEvent(), updateAxisValues()
 *   - Thread B (pipeline): pollRunnable → processAnalogSticks()
 *   - Thread C (AIDL binder): setProfileFromJson(), clearProfile(), start(), stop()
 *
 * All three threads can run concurrently. Thread safety measures:
 *   - activeProfile: @Volatile (visibility across threads)
 *   - running: @Volatile
 *   - lastAxes: @Volatile + copyOf() on write (immutable snapshot)
 *   - buttonStates: ConcurrentHashMap (thread-safe)
 *   - buttonPointers: ConcurrentHashMap (thread-safe)
 *   - nextPointerId: AtomicInteger (atomic increment, no race)
 *   - mappingLookup: ConcurrentHashMap (rebuilt on profile change, O(1) lookup)
 *
 * Lifecycle contract:
 *   1. start() → starts polling thread
 *   2. setProfileFromJson() → sets active profile (can be called before or after start)
 *   3. onButtonEvent() → processes button press (requires running + profile)
 *   4. stop() → stops polling, releases all pointers
 *
 * If onButtonEvent is called before profile is set:
 *   → Event is logged as DROPPED and returned (not crashed)
 *   → Once profile is set, subsequent events flow normally
 */
class InputPipelineWorker(
    private val touchInjector: TouchInjector,
    private val analogProcessor: AnalogProcessor
) {
    companion object {
        private const val TAG = "GameMapper/PipelineWorker"
        private const val POLL_INTERVAL_MS = 4L
        private const val SWIPE_DURATION_MS = 80L
        private const val MIN_POINTER_ID = 10
        private const val MAX_POINTER_ID = 109
    }

    // ============================================================
    // Profile data types
    // ============================================================
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

    // ============================================================
    // Pipeline state — all @Volatile or thread-safe
    // ============================================================
    @Volatile private var activeProfile: GameProfile? = null

    // O(1) button lookup — rebuilt whenever profile changes.
    // Key = hardwareKey (e.g., "A", "LB"), Value = ButtonMapping
    private val mappingLookup = ConcurrentHashMap<String, ButtonMapping>()

    private var pipelineThread: HandlerThread? = null
    private var pipelineHandler: Handler? = null
    @Volatile private var running = false

    // Analog stick states — accessed from pipeline thread only (processAnalogSticks)
    private val leftStickState = AnalogProcessor.AnalogState()
    private val rightStickState = AnalogProcessor.AnalogState()

    // Button states — thread-safe (accessed from evdev + trigger threads)
    private val buttonStates = ConcurrentHashMap<String, Boolean>()
    private val buttonPointers = ConcurrentHashMap<String, Int>()

    // Pointer ID allocator — atomic, thread-safe
    // Range: 10..109 (100 slots, matches TouchInjector capacity)
    private val nextPointerId = AtomicInteger(MIN_POINTER_ID)

    @Volatile private var lastAxes = FloatArray(6)

    // Drop counter for diagnostics — reset on profile set
    @Volatile private var droppedEvents = 0

    // ============================================================
    // Profile management
    // ============================================================

    /**
     * Parse profile JSON and set as active profile.
     *
     * Expected JSON format:
     * {
     *   "packageName": "com.tencent.ig",
     *   "screenWidth": 2800,
     *   "screenHeight": 1840,
     *   "displayId": 0,
     *   "buttons": [
     *     {"hardwareKey": "A", "touchX": 0.92, "touchY": 0.78, "actionType": "tap"},
     *     {"hardwareKey": "LB", "touchX": 0.50, "touchY": 0.50, "actionType": "swipe", "swipeDirection": "UP"}
     *   ],
     *   "leftStick": {"centerX": 0.3, "centerY": 0.7, "radius": 0.15, "deadzone": 0.15, "smoothing": 0.3},
     *   "rightStick": {"centerX": 0.7, "centerY": 0.7, "radius": 0.15, "deadzone": 0.15, "smoothing": 0.3}
     * }
     *
     * touchX/touchY are percentages [0.0..1.0] of screen dimensions.
     * centerX/centerY/radius are percentages [0.0..1.0] of screen dimensions.
     *
     * @return true if parse succeeded, false on error
     */
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

            // Reset analog states and button tracking
            analogProcessor.reset(leftStickState)
            analogProcessor.reset(rightStickState)
            buttonStates.clear()
            buttonPointers.clear()
            nextPointerId.set(MIN_POINTER_ID)
            droppedEvents = 0

            // Rebuild O(1) lookup map
            mappingLookup.clear()
            for (m in buttonMappings) {
                mappingLookup[m.hardwareKey] = m
            }

            activeProfile = profile
            Log.i(TAG, "Profile set: ${profile.packageName} (${buttonMappings.size} buttons, ${mappingLookup.size()} mapped) running=$running")
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

    // ============================================================
    // Pipeline lifecycle
    // ============================================================

    fun start() {
        if (running) {
            Log.w(TAG, "start() called but pipeline already running")
            return
        }
        running = true
        pipelineThread = HandlerThread("InputPipelineThread").also { it.start() }
        pipelineHandler = Handler(pipelineThread!!.looper)
        pipelineHandler?.post(pollRunnable)
        Log.i(TAG, "Pipeline started at ${1000 / POLL_INTERVAL_MS}Hz (profile=${activeProfile?.packageName ?: "null"})")
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
        buttonStates.clear()
        buttonPointers.clear()
        Log.i(TAG, "Pipeline stopped (dropped $droppedEvents events during session)")
    }

    // ============================================================
    // Analog stick polling — runs on pipeline thread
    // ============================================================

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val profile = activeProfile
            if (profile != null) {
                processAnalogSticks(profile)
            }
            pipelineHandler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * Process analog stick movement → touch injection.
     *
     * Coordinate calculation (§2.4):
     *   pixelX = centerX_percent × screenWidth
     *   pixelY = centerY_percent × screenHeight
     *   radius_pixels = radius_percent × screenWidth  (use width for both axes
     *                   to maintain circular movement zone)
     *
     * Example: centerX=0.3, screenWidth=2800 → pixelX = 0.3 × 2800 = 840px
     */
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

    // ============================================================
    // Gamepad event handlers — called from evdev thread
    // ============================================================

    fun updateAxisValues(axes: FloatArray) {
        if (axes.size >= 6) {
            lastAxes = axes.copyOf()
        }
    }

    /**
     * Handle gamepad button press/release.
     *
     * Lifecycle guard (Step [6]):
     *   - If pipeline not running → log warning, return
     *   - If profile not set → increment droppedEvents, return
     *   - If button not mapped → return (normal for unmapped buttons)
     *
     * Coordinate calculation (§2.4):
     *   pixelX = touchX_percent × screenWidth
     *   pixelY = touchY_percent × screenHeight
     *
     * Example: touchX=0.92, screenWidth=2800 → pixelX = 0.92 × 2800 = 2576px
     *
     * @param buttonName Evdev button name ("A", "B", "LB", "UP", etc.)
     * @param isPressed true = button down, false = button up
     */
    fun onButtonEvent(buttonName: String, isPressed: Boolean) {
        // Guard 1: Pipeline must be running
        if (!running) {
            Log.w(TAG, "onButtonEvent('$buttonName', $isPressed) — pipeline NOT running, dropping")
            return
        }

        // Guard 2: Profile must be set
        val profile = activeProfile
        if (profile == null) {
            droppedEvents++
            if (droppedEvents <= 5 || droppedEvents % 100 == 0) {
                Log.w(TAG, "onButtonEvent('$buttonName', $isPressed) — no profile set, dropped #$droppedEvents")
            }
            return
        }

        // Guard 3: Button must be mapped (O(1) lookup)
        val mapping = mappingLookup[buttonName]
        if (mapping == null) {
            // Unmapped button — normal, don't log (too noisy)
            return
        }

        // Track button state (thread-safe)
        val wasPressed = buttonStates[buttonName] ?: false
        buttonStates[buttonName] = isPressed

        // Calculate pixel coordinates from percentages
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
                // "tap" or "hold" — same behavior: DOWN on press, UP on release
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

    /**
     * Handle analog trigger (L2/R2) press.
     *
     * Trigger threshold: value > 0.3 → pressed, value ≤ 0.3 → released
     * This prevents accidental triggers from stick drift.
     */
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

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Calculate swipe end point based on direction.
     *
     * Swipe distance = 15% of screen dimension (§5.2: no magic numbers)
     *
     * Mathematical derivation:
     *   distX = screenWidth × 0.15
     *   distY = screenHeight × 0.15
     *   UP:    endY = startY - distY (clamped to ≥0)
     *   DOWN:  endY = startY + distY (clamped to ≤screenHeight)
     *   LEFT:  endX = startX - distX (clamped to ≥0)
     *   RIGHT: endX = startX + distX (clamped to ≤screenWidth)
     */
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
        // Rebuild lookup map
        mappingLookup.clear()
        for (m in updated) mappingLookup[m.hardwareKey] = m
    }

    /**
     * Allocate a pointer ID for a button.
     * Thread-safe via AtomicInteger.getAndIncrement().
     * Wraps around at MAX_POINTER_ID to prevent overflow.
     */
    private fun allocatePointer(hardwareKey: String): Int {
        buttonPointers[hardwareKey]?.let { return it }
        val ptr = nextPointerId.getAndIncrement()
        // Wrap around if exceeded max
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

    /**
     * Get count of events dropped due to missing profile.
     * Useful for diagnostics — if > 0, profile wasn't set before gamepad input started.
     */
    fun getDroppedEventCount(): Int = droppedEvents
}
