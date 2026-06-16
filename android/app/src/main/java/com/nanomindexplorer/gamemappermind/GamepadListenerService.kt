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
    @Volatile private var isListening = false

    companion object {
        var isRunning = false
        // Last detected controller id (for mode detection on the JS side)
        @Volatile var lastSeenControllerId: String = ""
        private val AXIS_RANGES = mutableMapOf<String, IntArray>()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("GameMapper", "GamepadListenerService: onCreate")
        createNotificationChannel()
        startForegroundService()
        startGetEventCapture()
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

    /** Normalize a raw evdev axis value to a [-1, 1] float with dynamic range expansion. */
    private fun normalizeAxis(axisName: String, rawValue: Int): Float {
        var min = 0
        var max = 255
        val range = AXIS_RANGES[axisName]
        if (range != null) { min = range[0]; max = range[1] }
        if (rawValue < min) { min = rawValue; AXIS_RANGES[axisName] = intArrayOf(min, max) }
        if (rawValue > max) { max = rawValue; AXIS_RANGES[axisName] = intArrayOf(min, max) }
        val span = (max - min).coerceAtLeast(1)
        return ((rawValue - min).toFloat() / span) * 2f - 1f
    }

    private fun startGetEventCapture() {
        isListening = true
        Thread {
            try {
                evdevProcess = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", "getevent -l"), null, null)
                val reader = BufferedReader(InputStreamReader(evdevProcess!!.inputStream))
                var line: String?

                var lStickX = 0f; var lStickY = 0f
                var rStickX = 0f; var rStickY = 0f
                var l2Analog = -1f; var r2Analog = -1f
                // Gyro accumulator — many controllers expose rotational rate via
                // ABS_RX (pitch), ABS_RY (roll), ABS_RZ (yaw). Values are typically
                // signed 16-bit, range ~[-32768, 32767].
                var gyroX = 0f; var gyroY = 0f; var gyroZ = 0f

                // Track add/remove events to remember device names → id strings.
                val deviceNames = mutableMapOf<Int, String>()

                while (isListening && reader.readLine().also { line = it } != null) {
                    line?.let { raw ->
                        try {
                            // Device name capture: lines like:
                            //   add device 6: /dev/input/event12
                            //     name:     "Vortex XP107"
                            if (raw.startsWith("add device")) {
                                val parts = raw.split(":")
                                if (parts.size >= 2) {
                                    val devId = parts[0].removePrefix("add device").trim().toIntOrNull() ?: -1
                                    val path = parts.slice(1..parts.lastIndex).joinToString(":").trim()
                                    deviceNames[devId] = path
                                }
                            } else if (raw.trim().startsWith("name:")) {
                                // The line AFTER "add device N: path" carries the human-readable name.
                                val name = raw.trim().removePrefix("name:").trim().trim('"')
                                if (deviceNames.isNotEmpty()) {
                                    val lastKey = deviceNames.keys.maxOrNull()
                                    if (lastKey != null) {
                                        deviceNames[lastKey] = name
                                        lastSeenControllerId = name
                                        // Emit as a synthetic button event so the JS side can detect mode.
                                        TouchInjectionPlugin.emitGamepadButton(
                                            "CONTROLLER_ID:$name", 1, 1.0f
                                        )
                                    }
                                }
                            }

                            if (raw.contains("EV_ABS")) {
                                val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                                if (parts.size >= 4) {
                                    val axisType = parts[2]
                                    val valueHex = parts[3]
                                    val valueInt = java.lang.Long.parseLong(valueHex, 16).toInt()
                                    val normalized = normalizeAxis(axisType, valueInt)

                                    when (axisType) {
                                        "ABS_X" -> lStickX = normalized
                                        "ABS_Y" -> lStickY = normalized
                                        "ABS_Z", "ABS_RX" -> rStickX = normalized
                                        "ABS_RZ", "ABS_RY" -> rStickY = normalized
                                        "ABS_BRAKE" -> l2Analog = (normalized + 1f) / 2f
                                        "ABS_GAS"   -> r2Analog = (normalized + 1f) / 2f
                                        "ABS_HAT0X", "ABS_HAT0Y" -> {
                                            val isDown = valueInt != 0
                                            val btnName = when {
                                                axisType == "ABS_HAT0X" && valueInt < 0 -> "LEFT"
                                                axisType == "ABS_HAT0X" && valueInt > 0 -> "RIGHT"
                                                axisType == "ABS_HAT0Y" && valueInt < 0 -> "UP"
                                                axisType == "ABS_HAT0Y" && valueInt > 0 -> "DOWN"
                                                else -> return@let
                                            }
                                            TouchInjectionPlugin.emitGamepadButton(btnName, if (isDown) 1 else 0, 1.0f)
                                        }
                                        // Gyroscope axes (rotational rate).
                                        // Convention: RX=pitch, RY=roll, RZ=yaw (rad/s after normalization).
                                        "ABS_RX2", "ABS_RY2", "ABS_RZ2" -> {
                                            // Some controllers expose gyro on a secondary device with these axes.
                                            when (axisType) {
                                                "ABS_RX2" -> gyroX = normalized
                                                "ABS_RY2" -> gyroY = normalized
                                                "ABS_RZ2" -> gyroZ = normalized
                                            }
                                            TouchInjectionPlugin.emitGyroData(gyroX, gyroY, gyroZ, System.currentTimeMillis())
                                        }
                                    }
                                    TouchInjectionPlugin.emitGamepadAxis(
                                        floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog)
                                    )
                                }
                            } else if (raw.contains("EV_KEY")) {
                                val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                                if (parts.size >= 4) {
                                    val btnRaw = parts[2]
                                    val stateStr = parts[3]
                                    val isDown = if (stateStr == "DOWN") 1 else 0
                                    val btnMap = mapEvdevToButton(btnRaw)
                                    if (btnMap != "UNKNOWN") {
                                        TouchInjectionPlugin.emitGamepadButton(btnMap, isDown, 1.0f)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore parse errors on individual lines
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GameMapper", "getevent loop failed", e)
            }
        }.start()
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_SOUTH") || evdevName.contains("BTN_A") || evdevName.contains("BTN_GAMEPAD") -> "A"
            evdevName.contains("BTN_EAST")  || evdevName.contains("BTN_B")  -> "B"
            evdevName.contains("BTN_NORTH") || evdevName.contains("BTN_X")  -> "X"
            evdevName.contains("BTN_WEST")  || evdevName.contains("BTN_Y")  -> "Y"
            evdevName.contains("BTN_TL")    || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR")    || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_TL2")   || evdevName.contains("BTN_LT") -> "LT"
            evdevName.contains("BTN_TR2")   || evdevName.contains("BTN_RT") -> "RT"
            evdevName.contains("BTN_THUMBL") -> "L3"
            evdevName.contains("BTN_THUMBR") -> "R3"
            evdevName.contains("BTN_START")  -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("BTN_MODE")   -> "MODE"   // Xbox Guide button — toggles controller mode on some pads
            evdevName.contains("BTN_DPAD_UP")    -> "UP"
            evdevName.contains("BTN_DPAD_DOWN")  -> "DOWN"
            evdevName.contains("BTN_DPAD_LEFT")  -> "LEFT"
            evdevName.contains("BTN_DPAD_RIGHT") -> "RIGHT"
            else -> "UNKNOWN"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("GameMapper", "GamepadListenerService: onDestroy")
        isRunning = false
        isListening = false
        try { evdevProcess?.destroy() } catch (e: Exception) {}
    }
}
