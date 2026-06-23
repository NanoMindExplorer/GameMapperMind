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
                // Get min/max first using getevent -p
                val absRanges = mutableMapOf<String, Pair<Int, Int>>()
                val gamepadDevices = mutableSetOf<String>()
                
                try {
                    val pJson = TouchInjectionPlugin.touchService?.executeShellCommand("getevent -lp") ?: "{}"
                    val pOutput = org.json.JSONObject(pJson).optString("output", "")
                    val lines = pOutput.split("\n")
                    
                    var currentDevicePath: String? = null
                    var currentDeviceIsGamepad = false
                    
                    for (line in lines) {
                        if (line.contains("add device")) {
                            if (currentDeviceIsGamepad && currentDevicePath != null) {
                                gamepadDevices.add(currentDevicePath!!)
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
                    if (currentDeviceIsGamepad && currentDevicePath != null) {
                        gamepadDevices.add(currentDevicePath)
                    }
                } catch (e: Exception) {
                    Log.e("GameMapper", "Failed to parse getevent -lp", e)
                }

                val geteventCmd = if (gamepadDevices.isNotEmpty()) {
                    "getevent -l " + gamepadDevices.joinToString(" ")
                } else {
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
                
                val nativeMapper = NativeGamepadMapper(this@GamepadListenerService)
                
                val streamListener = object : ICommandOutputListener.Stub() {
                    override fun onOutputLine(it: String?) {
                        if (!isListening || it == null) return
                        if (it.contains("EV_SYN")) {
                            if (it.contains("SYN_REPORT")) {
                                if (hasAxisChange) {
                                    val gpIdx = if (it.trim().startsWith("/dev/input/")) Math.max(0, gamepadDevices.indexOf(it.trim().split(Regex("\\s+"))[0])) else 0
                                    GamepadJniPlugin.handleAxisBatched(gpIdx, lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger)
                                    TouchInjectionPlugin.emitGamepadAxis(floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger))
                                    hasAxisChange = false
                                }
                            }
                        } else if (it.contains("EV_KEY")) {
                            val parts = it.trim().split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                val isPrefixed = parts[0].startsWith("/dev/input/")
                                val evIdx = if (isPrefixed) 1 else 0
                                val gpIdx = if (isPrefixed) Math.max(0, gamepadDevices.indexOf(parts[0])) else 0
                                if (parts.size > evIdx + 2) {
                                    val btnRaw = parts[evIdx + 1]
                                    val stateStr = parts[evIdx + 2]
                                    val isDown = if (stateStr == "DOWN") 1 else 0
                                    
                                    val btnMap = mapEvdevToButton(btnRaw)
                                    if (btnMap != "UNKNOWN") {
                                        GamepadJniPlugin.handleButtonBatched(gpIdx, btnMap, isDown == 1)
                                        TouchInjectionPlugin.emitGamepadButton(btnMap, isDown, 1.0f)
                                    }
                                }
                            }
                        } else if (it.contains("EV_FF")) {
                            // Force Feedback event detected
                            TouchInjectionPlugin.emitGamepadFeedback("RUMBLE", 1.0f, 200L)
                        } else if (it.contains("EV_ABS")) {
                            val parts = it.trim().split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                val isPrefixed = parts[0].startsWith("/dev/input/")
                                val evIdx = if (isPrefixed) 1 else 0
                                val gpIdx = if (isPrefixed) Math.max(0, gamepadDevices.indexOf(parts[0])) else 0
                                if (parts.size > evIdx + 2) {
                                    val axisType = parts[evIdx + 1]
                                    val valueHex = parts[evIdx + 2]
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
                                            val mid = min + (max - min) / 2f
                                            val half = (max - min) / 2f
                                            
                                            var finalVal = 0f
                                            if (axisType == "ABS_Z" || axisType == "ABS_RZ") {
                                                // Triggers 0 to 1
                                                finalVal = if (max > min) ((rawVal - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f) else 0f
                                            } else {
                                                // Analog sticks -1 to 1
                                                var normalizedVal = if (half > 0) (rawVal - mid) / half else 0f
                                                // Apply deadzone
                                                if (Math.abs(normalizedVal) < 0.15f) {
                                                    normalizedVal = 0f
                                                }
                                                finalVal = normalizedVal.coerceIn(-1f, 1f)
                                            }
                                            
                                            when (axisType) {
                                                "ABS_X"  -> { lStickX = finalVal; hasAxisChange = true }
                                                "ABS_Y"  -> { lStickY = finalVal; hasAxisChange = true }
                                                "ABS_RX" -> { rStickX = finalVal; hasAxisChange = true }
                                                "ABS_RY" -> { rStickY = finalVal; hasAxisChange = true }
                                                "ABS_Z", "ABS_GAS"   -> { l2Trigger = finalVal; hasAxisChange = true }
                                                "ABS_RZ", "ABS_BRAKE"-> { r2Trigger = finalVal; hasAxisChange = true }
                                            }
                                        }
                                    }
                                } catch (e: NumberFormatException) { }
                                }
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
            evdevName.contains("BTN_C") -> "C"
            evdevName.contains("BTN_Z") -> "Z"
            evdevName.contains("BTN_TL2") || evdevName.contains("BTN_L2") -> "LT"
            evdevName.contains("BTN_TR2") || evdevName.contains("BTN_R2") -> "RT"
            evdevName.contains("BTN_TL") || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR") || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_THUMBL") -> "L3"
            evdevName.contains("BTN_THUMBR") -> "R3"
            evdevName.contains("BTN_START") -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("BTN_MODE") -> "HOME"
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
