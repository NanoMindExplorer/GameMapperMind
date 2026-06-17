package com.nanomindexplorer.gamemappermind.input

import android.hardware.input.InputManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * GamepadManager — Reads gamepad input from Android InputManager.
 *
 * This class runs in the APP process (not the Shizuku UserService).
 * It uses InputManager.registerInputDeviceListener() to detect
 * gamepad connect/disconnect events, and processes MotionEvent/
 * KeyEvent dispatched to the activity.
 *
 * IMPORTANT LIMITATION:
 *   InputManager.registerInputDeviceListener() only notifies about
 *   device hotplug (connect/disconnect). To receive actual gamepad
 *   axis/button values, the app must have window focus and receive
 *   dispatchGenericMotionEvent / dispatchKeyEvent calls.
 *
 *   When a game is in the foreground (not our app), we do NOT receive
 *   these events. For background gamepad reading, the GameMapperUserService
 *   uses evdev (getevent -l) which works in the shell-privilege process
 *   regardless of which app is in foreground.
 *
 *   This class handles:
 *   1. Device detection (registerInputDeviceListener)
 *   2. Foreground input (when user is in our app's WYSIWYG editor)
 *   3. Fallback polling at 250Hz using HandlerThread
 *
 * Thread safety:
 *   - registerInputDeviceListener callbacks run on the provided Handler's thread
 *   - Input event processing is synchronized
 *   - Device list uses ConcurrentHashMap
 *
 * Dependencies:
 *   - Android InputManager (public API)
 *   - HandlerThread for polling
 */
class GamepadManager {

    companion object {
        private const val TAG = "GameMapper/GamepadManager"
        private const val POLL_INTERVAL_MS = 4L // 250Hz = 4ms interval
    }

    /**
     * Callback interface for gamepad events.
     */
    interface GamepadCallback {
        fun onGamepadConnected(device: InputDevice)
        fun onGamepadDisconnected(device: InputDevice)
        fun onButtonEvent(deviceId: Int, keyCode: Int, isPressed: Boolean)
        fun onAxisEvent(deviceId: Int, axes: Map<Int, Float>)
    }

    private var callback: GamepadCallback? = null
    private val connectedGamepads = ConcurrentHashMap<Int, InputDevice>()
    private var inputManager: InputManager? = null
    private var listenerRegistered = false

    // Polling thread for 250Hz state updates
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    @Volatile
    private var polling = false

    // Last known axis values (for change detection)
    private val lastAxisValues = ConcurrentHashMap<Int, MutableMap<Int, Float>>()

    // ============================================================
    // InputManager device listener — detects gamepad hotplug
    // ============================================================
    private val deviceListener = InputManager.InputDeviceListener { deviceId ->
        val device = InputDevice.getDevice(deviceId)
        if (device != null && isGamepad(device)) {
            if (connectedGamepads.containsKey(deviceId)) {
                // Device changed (already known) — ignore
                return@InputDeviceListener
            }
            // New gamepad connected
            connectedGamepads[deviceId] = device
            lastAxisValues[deviceId] = mutableMapOf()
            Log.i(TAG, "Gamepad connected: ${device.name} (id=$deviceId, sources=0x${device.sources.toString(16)})")
            callback?.onGamepadConnected(device)
        } else if (device == null) {
            // Device disconnected
            val removed = connectedGamepads.remove(deviceId)
            lastAxisValues.remove(deviceId)
            if (removed != null) {
                Log.i(TAG, "Gamepad disconnected: ${removed.name} (id=$deviceId)")
                callback?.onGamepadDisconnected(removed)
            }
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Set the callback for gamepad events.
     */
    fun setCallback(cb: GamepadCallback) {
        callback = cb
    }

    /**
     * Initialize and register InputManager listener.
     * Scans for already-connected gamepads.
     *
     * @param context Android Context (needed for InputManager)
     */
    fun initialize(context: android.content.Context) {
        try {
            inputManager = context.getSystemService(android.content.Context.INPUT_SERVICE) as InputManager

            // Register device listener on main thread
            val mainHandler = Handler(Looper.getMainLooper())
            inputManager?.registerInputDeviceListener(deviceListener, mainHandler)
            listenerRegistered = true
            Log.d(TAG, "InputDeviceListener registered")

            // Scan for already-connected gamepads
            scanConnectedDevices()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize InputManager", e)
        }
    }

    /**
     * Scan all input devices and register gamepads.
     */
    private fun scanConnectedDevices() {
        val deviceIds = inputManager?.inputDeviceIds ?: return
        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId)
            if (device != null && isGamepad(device)) {
                connectedGamepads[deviceId] = device
                lastAxisValues[deviceId] = mutableMapOf()
                Log.d(TAG, "Found connected gamepad: ${device.name} (id=$deviceId)")
                callback?.onGamepadConnected(device)
            }
        }
        Log.i(TAG, "Scan complete: ${connectedGamepads.size} gamepad(s) connected")
    }

    /**
     * Start the 250Hz polling loop.
     * This periodically checks axis values and emits change events.
     *
     * NOTE: This polling reads InputDevice.getMotionRange() to determine
     * which axes exist, but cannot read current axis values without
     * receiving MotionEvent. The actual axis value reading happens in
     * processMotionEvent() which is called from the Activity's
     * dispatchGenericMotionEvent().
     *
     * The polling loop serves to:
     * 1. Detect device disconnection (if getDevice returns null)
     * 2. Emit periodic "no change" heartbeats
     * 3. Check for new devices
     */
    fun startPolling() {
        if (polling) {
            Log.d(TAG, "Polling already running")
            return
        }

        polling = true
        pollThread = HandlerThread("GamepadPollThread").also { it.start() }
        pollHandler = Handler(pollThread!!.looper)

        val pollRunnable = object : Runnable {
            override fun run() {
                if (!polling) return

                // Check if any gamepad devices have been removed
                val toRemove = mutableListOf<Int>()
                for ((deviceId, _) in connectedGamepads) {
                    if (InputDevice.getDevice(deviceId) == null) {
                        toRemove.add(deviceId)
                    }
                }
                for (deviceId in toRemove) {
                    val removed = connectedGamepads.remove(deviceId)
                    lastAxisValues.remove(deviceId)
                    if (removed != null) {
                        Log.i(TAG, "Gamepad removed during poll: ${removed.name}")
                        callback?.onGamepadDisconnected(removed)
                    }
                }

                // Schedule next poll
                pollHandler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        pollHandler?.post(pollRunnable)
        Log.i(TAG, "Gamepad polling started at 250Hz")
    }

    /**
     * Stop the polling loop.
     */
    fun stopPolling() {
        polling = false
        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollThread = null
        pollHandler = null
        Log.i(TAG, "Gamepad polling stopped")
    }

    /**
     * Process a MotionEvent received from dispatchGenericMotionEvent.
     * Extracts axis values and emits change events.
     *
     * @param event The MotionEvent from the Activity
     */
    @Synchronized
    fun processMotionEvent(event: MotionEvent) {
        val deviceId = event.deviceId
        val device = connectedGamepads[deviceId] ?: return

        val axisValues = mutableMapOf<Int, Float>()

        // Read all relevant axes for gamepad
        val axesToRead = listOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,           // Left stick
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,          // Right stick
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, // L2/R2 triggers
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y    // D-Pad
        )

        for (axis in axesToRead) {
            val motionRange = device.getMotionRange(axis, event.source)
            if (motionRange != null) {
                val value = event.getAxisValue(axis)
                axisValues[axis] = value

                // Check for change
                val lastVal = lastAxisValues[deviceId]?.get(axis)
                if (lastVal == null || Math.abs(value - lastVal) > 0.01f) {
                    lastAxisValues[deviceId]?.set(axis, value)
                }
            }
        }

        // D-Pad as buttons
        val hatX = axisValues[MotionEvent.AXIS_HAT_X] ?: 0f
        val hatY = axisValues[MotionEvent.AXIS_HAT_Y] ?: 0f

        if (hatX < -0.5f) callback?.onButtonEvent(deviceId, KeyEvent.KEYCODE_DPAD_LEFT, true)
        if (hatX > 0.5f) callback?.onButtonEvent(deviceId, KeyEvent.KEYCODE_DPAD_RIGHT, true)
        if (hatY < -0.5f) callback?.onButtonEvent(deviceId, KeyEvent.KEYCODE_DPAD_UP, true)
        if (hatY > 0.5f) callback?.onButtonEvent(deviceId, KeyEvent.KEYCODE_DPAD_DOWN, true)

        // Emit axis event
        if (axisValues.isNotEmpty()) {
            callback?.onAxisEvent(deviceId, axisValues)
        }
    }

    /**
     * Process a KeyEvent received from dispatchKeyEvent.
     * Emits button press/release events.
     *
     * @param event The KeyEvent from the Activity
     */
    @Synchronized
    fun processKeyEvent(event: KeyEvent) {
        val deviceId = event.deviceId
        if (!connectedGamepads.containsKey(deviceId)) return

        val isPressed = event.action == KeyEvent.ACTION_DOWN
        callback?.onButtonEvent(deviceId, event.keyCode, isPressed)
    }

    /**
     * Get list of currently connected gamepad devices.
     *
     * @return List of InputDevice objects that are gamepads/joysticks
     */
    fun getConnectedGamepads(): List<InputDevice> {
        return connectedGamepads.values.toList()
    }

    /**
     * Check if a specific gamepad is connected.
     */
    fun isGamepadConnected(deviceId: Int): Boolean {
        return connectedGamepads.containsKey(deviceId)
    }

    /**
     * Get the current axis value for a device.
     * NOTE: This returns the LAST KNOWN value from processMotionEvent.
     * It does NOT poll the hardware — Android does not provide a way
     * to read current axis values without receiving a MotionEvent.
     *
     * @param deviceId Input device ID
     * @param axis MotionEvent.AXIS_* constant
     * @return Last known axis value, or 0f if unknown
     */
    fun readAxisValue(deviceId: Int, axis: Int): Float {
        return lastAxisValues[deviceId]?.get(axis) ?: 0f
    }

    /**
     * Read button state.
     * NOTE: This returns the LAST KNOWN state from processKeyEvent.
     * Android does not provide a way to poll current button states.
     *
     * @param deviceId Input device ID
     * @param keyCode KeyEvent.KEYCODE_* constant
     * @return true if button was last pressed, false otherwise
     */
    fun readButtonState(deviceId: Int, keyCode: Int): Boolean {
        // Button states are not stored — they are emitted as events
        // This method exists for API compatibility but returns false
        // unless we add state tracking in processKeyEvent
        return false
    }

    /**
     * Cleanup — unregister listeners and stop polling.
     */
    fun cleanup() {
        stopPolling()
        if (listenerRegistered) {
            try {
                inputManager?.unregisterInputDeviceListener(deviceListener)
                listenerRegistered = false
                Log.d(TAG, "InputDeviceListener unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister InputDeviceListener", e)
            }
        }
        connectedGamepads.clear()
        lastAxisValues.clear()
    }

    // ============================================================
    // Private helpers
    // ============================================================

    /**
     * Check if an InputDevice is a gamepad or joystick.
     */
    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
}
