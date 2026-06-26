package com.nanomindexplorer.gamemappermind

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader

class GamepadListenerService : Service() {

    private val CHANNEL_ID = "GamepadListenerChannel"
    private var evdevProcess: Process? = null
    private var isListening = false

    companion object {
        var isRunning = false
        var activeProfileJson: String? = null // Set from React when profile changes
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isListening) {
            startGetEventCapture()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("GameMapper", "GamepadListenerService: onCreate")
        createNotificationChannel()
        startForegroundService()
        isRunning = true
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gamepad Listener Active")
            .setContentText("Listening for raw evdev inputs (No-Focus-Steal)")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Gamepad Listener", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startGetEventCapture() {
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            Log.w("GameMapper", "Shizuku binder tidak aktif")
            TouchInjectionPlugin.emitGamepadButton("ERROR_SHIZUKU_NOT_RUNNING", 0, 0f)
            return
        }
        if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("GameMapper", "Permission Shizuku belum diberikan")
            TouchInjectionPlugin.emitGamepadButton("ERROR_SHIZUKU_NO_PERMISSION", 0, 0f)
            return
        }
        if (TouchInjectionPlugin.touchService == null) {
            Log.w("GameMapper", "TouchService tidak terhubung")
            TouchInjectionPlugin.emitGamepadButton("ERROR_SHIZUKU_NOT_RUNNING", 0, 0f)
            return
        }
        isListening = true
        Thread {
            try {
                // BUG-FIX #1: Create NativeGamepadMapper IMMEDIATELY, before getevent -lp.
                // Previously, NativeGamepadMapper was created AFTER getevent -lp (which takes
                // 100-500ms). During that time, all button events were silently dropped
                // (instance was null). Also, if getevent -lp found no gamepad, the thread
                // returned early and NativeGamepadMapper was NEVER created.
                // Now: Create first, then parse getevent, then start stream.
                val nativeMapper = NativeGamepadMapper(this@GamepadListenerService)

                // Get min/max first using getevent -p
                val absRanges = mutableMapOf<String, Pair<Int, Int>>()
                // BUG-C6 FIX: Use MutableList instead of MutableSet so indexOf() actually works.
                val gamepadDevices = mutableListOf<String>()
                
                try {
                    val pJson = TouchInjectionPlugin.touchService?.executeShellCommand("getevent -lp") ?: "{}"
                    val pOutput = org.json.JSONObject(pJson).optString("output", "")
                    val lines = pOutput.split("\n")
                    
                    var currentDevicePath: String? = null
                    var currentDeviceIsGamepad = false
                    
                    for (line in lines) {
                        if (line.contains("add device")) {
                            if (currentDeviceIsGamepad && currentDevicePath != null &&
                                !gamepadDevices.contains(currentDevicePath)) {
                                gamepadDevices.add(currentDevicePath)
                                // BUG-LOG2 FIX: Log detected gamepad for debugging.
                                Log.i("GameMapper", "Gamepad detected: $currentDevicePath (index=${gamepadDevices.size - 1})")
                            }
                            val pathMatch = Regex("/dev/input/event\\d+").find(line)
                            currentDevicePath = pathMatch?.value
                            currentDeviceIsGamepad = false
                        } else if (line.contains("BTN_A") || line.contains("BTN_GAMEPAD") || line.contains("ABS_HAT0X") ||
                                   line.contains("BTN_SOUTH") || line.contains("BTN_EAST") || line.contains("BTN_X") || line.contains("BTN_Y") ||
                                   line.contains("BTN_NORTH") || line.contains("BTN_WEST")) {
                            currentDeviceIsGamepad = true
                        }
                        
                        if (line.contains("ABS_")) {
                            val parts = line.trim().split(Regex("\\s+"))
                            val axisName = parts.find { it.startsWith("ABS_") }
                            if (axisName != null) {
                                val minMatch = Regex("min\\s+(-?\\d+)").find(line)
                                val maxMatch = Regex("max\\s+(-?\\d+)").find(line)
                                if (minMatch != null && maxMatch != null) {
                                    val min = minMatch.groupValues[1].toInt()
                                    val max = maxMatch.groupValues[1].toInt()
                                    if (max > min) {
                                        absRanges[axisName] = Pair(min, max)
                                    }
                                }
                            }
                        }
                    }
                    if (currentDeviceIsGamepad && currentDevicePath != null &&
                        !gamepadDevices.contains(currentDevicePath)) {
                        gamepadDevices.add(currentDevicePath)
                        Log.i("GameMapper", "Gamepad detected: $currentDevicePath (index=${gamepadDevices.size - 1})")
                    }
                } catch (e: Exception) {
                    Log.e("GameMapper", "Failed to parse getevent -lp", e)
                }

                val geteventCmd = if (gamepadDevices.isNotEmpty()) {
                    "getevent -l " + gamepadDevices.joinToString(" ")
                } else {
                    // BUG-FIX #2: Don't return early if no gamepad found.
                    // NativeGamepadMapper is already created (instance is set).
                    // If gamepad connects later, user can restart daemon.
                    // Also, native path (GamepadPlugin) still works for button events.
                    Log.w("GameMapper", "No gamepad device detected in getevent -lp. NativeGamepadMapper still created for native path.")
                    TouchInjectionPlugin.emitGamepadButton("ERROR_NO_GAMEPAD", 0, 0f)
                    isListening = false
                    return@Thread
                }

                var lStickX = 0f
                var lStickY = 0f
                var rStickX = 0f
                var rStickY = 0f
                var l2Trigger = 0f
                var r2Trigger = 0f
                var hasAxisChange = false
                
                // nativeMapper already created at top of thread (BUG-FIX #1)
                val streamListener = object : ICommandOutputListener.Stub() {
                    override fun onOutputLine(it: String?) {
                        if (!isListening || it == null) return
                        // BUG-C1 FIX: Parse getevent line correctly.
                        // Format: "[   12345.678901] /dev/input/event4: EV_KEY BTN_SOUTH DOWN"
                        // Or:     "/dev/input/event4: EV_KEY BTN_SOUTH DOWN"
                        val deviceMatch = Regex("(/dev/input/event\\d+)").find(it)
                        val isPrefixed = deviceMatch != null
                        val gpIdx = if (isPrefixed) {
                            val devPath = deviceMatch!!.value
                            val idx = gamepadDevices.indexOf(devPath)
                            // BUG-C1 FIX: If device not found in list, default to 0 (don't crash).
                            if (idx < 0) 0 else idx
                        } else 0
                        
                        if (it.contains("EV_SYN")) {
                            if (it.contains("SYN_REPORT")) {
                                if (hasAxisChange) {
                                    GamepadJniPlugin.handleAxisBatched(gpIdx, lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger)
                                    TouchInjectionPlugin.emitGamepadAxis(floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger))
                                    hasAxisChange = false
                                }
                            }
                        } else if (it.contains("EV_KEY")) {
                            val parts = it.trim().split(Regex("\\s+"))
                            // Find BTN_ token (skip timestamp/device path)
                            val btnIdx = parts.indexOfFirst { p -> p.startsWith("BTN_") }
                            val stateIdx = parts.indexOfLast { p -> p == "DOWN" || p == "UP" }
                            if (btnIdx >= 0 && stateIdx > btnIdx) {
                                val btnRaw = parts[btnIdx]
                                val isDown = parts[stateIdx] == "DOWN"
                                
                                val btnMap = mapEvdevToButton(btnRaw)
                                if (btnMap != "UNKNOWN") {
                                    GamepadJniPlugin.handleButtonBatched(gpIdx, btnMap, isDown)
                                    TouchInjectionPlugin.emitGamepadButton(btnMap, if (isDown) 1 else 0, 1.0f)
                                }
                            }
                        } else if (it.contains("EV_FF")) {
                            // Force Feedback event detected
                            TouchInjectionPlugin.emitGamepadFeedback("RUMBLE", 1.0f, 200L)
                        } else if (it.contains("EV_ABS")) {
                            val parts = it.trim().split(Regex("\\s+"))
                            // Find ABS_ token
                            val absIdx = parts.indexOfFirst { p -> p.startsWith("ABS_") }
                            if (absIdx >= 0 && absIdx + 1 < parts.size) {
                                val axisType = parts[absIdx]
                                val valueHex = parts[absIdx + 1]
                                try {
                                    val hexNum = valueHex.toLong(16)
                                    val rawVal = if (hexNum > 0x7FFFFFFF) (hexNum - 0x100000000L).toInt() else hexNum.toInt()
                                    when (axisType) {
                                        "ABS_HAT0Y" -> {
                                            when (rawVal) {
                                                -1 -> { 
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_UP", true)
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_DOWN", false)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 1, 1f)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 0, 0f) 
                                                }
                                                1  -> { 
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_DOWN", true)
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_UP", false)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 1, 1f)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 0, 0f) 
                                                }
                                                0  -> { 
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_UP", false)
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_DOWN", false)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 0, 0f)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 0, 0f) 
                                                }
                                            }
                                        }
                                        "ABS_HAT0X" -> {
                                            when (rawVal) {
                                                -1 -> { 
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_LEFT", true)
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_RIGHT", false)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 1, 1f)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 0, 0f) 
                                                }
                                                1  -> { 
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_RIGHT", true)
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_LEFT", false)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 1, 1f)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 0, 0f) 
                                                }
                                                0  -> { 
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_LEFT", false)
                                                    GamepadJniPlugin.handleButtonBatched(gpIdx, "DPAD_RIGHT", false)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 0, 0f)
                                                    TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 0, 0f) 
                                                }
                                            }
                                        }
                                        else -> {
                                            val range = absRanges[axisType]
                                            val min = range?.first ?: -32768
                                            val max = range?.second ?: 32767
                                            // BUG-MATH FIX: Use Float division throughout for precision.
                                            // Previous code used Int division (max - min) / 2f which is OK,
                                            // but mid computation min + (max-min)/2f can lose precision
                                            // when min is large negative and max is large positive.
                                            // Use Float arithmetic explicitly.
                                            val span = (max - min).toFloat()
                                            val mid = min + span / 2f
                                            val half = span / 2f

                                            var finalVal = 0f
                                            // CACAT #4 FIX: ABS_GAS/ABS_BRAKE (dan ABS_THROTTLE/ABS_RUDDER) adalah
                                            // trigger analog yang range-nya [0, max], bukan [-max, max].
                                            // Sebelumnya hanya ABS_Z/ABS_RZ yang masuk cabang trigger [0,1];
                                            // ABS_GAS/ABS_BRAKE jatuh ke cabang analog stick [-1,1] lalu disimpan
                                            // ke l2Trigger/r2Trigger — akibatnya trigger pada controller yang pakai
                                            // GAS/BRAKE (banyak Xbox via Bluetooth) tidak responsif di setengah tekan.
                                            //
                                            // Trigger axis list (semua di-map ke [0, 1]):
                                            //   ABS_Z, ABS_RZ       — Xbox/standar modern
                                            //   ABS_GAS, ABS_BRAKE  — Xbox via Bluetooth, beberapa generic
                                            //   ABS_THROTTLE, ABS_RUDDER — joystick/HOTAS (defensive)
                                            val isTriggerAxis = axisType == "ABS_Z" || axisType == "ABS_RZ" ||
                                                                axisType == "ABS_GAS" || axisType == "ABS_BRAKE" ||
                                                                axisType == "ABS_THROTTLE" || axisType == "ABS_RUDDER"
                                            if (isTriggerAxis) {
                                                // Triggers: map [min, max] → [0, 1]
                                                finalVal = if (half > 0f) ((rawVal - min).toFloat() / span).coerceIn(0f, 1f) else 0f
                                            } else {
                                                // Analog sticks: map [min, max] → [-1, 1]
                                                // BUG-EVDEV-DZ FIX: Do NOT apply per-axis deadzone here.
                                                // Per-axis deadzone creates a cross-shaped dead zone, which
                                                // distorts diagonal direction (e.g. (0.1, 0.17) becomes (0, 0.17)).
                                                // Radial deadzone is applied correctly in NativeGamepadMapper.processStick
                                                // using sqrt(lx² + ly²), which forms a circular dead zone and preserves
                                                // direction. Also, hardcoding 0.15 here ignored the user's profile deadzone.
                                                finalVal = if (half > 0f) ((rawVal - mid) / half).coerceIn(-1f, 1f) else 0f
                                            }

                                            when (axisType) {
                                                "ABS_X"  -> { lStickX = finalVal; hasAxisChange = true }
                                                "ABS_Y"  -> { lStickY = finalVal; hasAxisChange = true }
                                                "ABS_RX" -> { rStickX = finalVal; hasAxisChange = true }
                                                "ABS_RY" -> { rStickY = finalVal; hasAxisChange = true }
                                                "ABS_Z", "ABS_GAS", "ABS_THROTTLE"   -> { l2Trigger = finalVal; hasAxisChange = true }
                                                "ABS_RZ", "ABS_BRAKE", "ABS_RUDDER"  -> { r2Trigger = finalVal; hasAxisChange = true }
                                            }
                                        }
                                    }
                                } catch (e: NumberFormatException) { }
                            }
                        }
                    }
                    override fun onExit(code: Int) {
                        isListening = false
                    }
                }

                TouchInjectionPlugin.touchService?.executeStreamCommand(geteventCmd, streamListener)

            } catch (e: Exception) {
                Log.e("GameMapper", "getevent loop failed", e)
                TouchInjectionPlugin.emitGamepadButton("ERROR_SHIZUKU_EXCEPTION", 0, 0f)
                isListening = false
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_A") || evdevName.contains("BTN_SOUTH") -> "A"
            evdevName.contains("BTN_B") || evdevName.contains("BTN_EAST") -> "B"
            evdevName.contains("BTN_X") || evdevName.contains("BTN_NORTH") -> "X"
            evdevName.contains("BTN_Y") || evdevName.contains("BTN_WEST") -> "Y"
            // BUG-C5 FIX: Removed BTN_TL2/BTN_TR2 mapping — analog triggers (ABS_Z/ABS_RZ) are the
            // single source of truth for LT/RT in NativeGamepadMapper.handleAxes (line 281-291).
            // Digital BTN_TL2/BTN_TR2 caused trigger flicker when both event types fired simultaneously.
            evdevName.contains("BTN_TL") || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR") || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_THUMBL") -> "L3"
            evdevName.contains("BTN_THUMBR") -> "R3"
            evdevName.contains("BTN_START") -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("BTN_MODE") -> "HOME"
            // BUG-C4 FIX: Removed BTN_C/BTN_Z (legacy N64 codes, never emitted by modern gamepads).
            else -> "UNKNOWN"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("GameMapper", "GamepadListenerService: onDestroy")
        isRunning = false
        isListening = false
        TouchInjectionPlugin.touchService?.releaseAllPointers()
        try {
            TouchInjectionPlugin.touchService?.stopStreamCommand()
        } catch (e: Exception) {}
    }
}
