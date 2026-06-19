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
        isListening = true
        Thread {
            try {
                // Menjalankan getevent untuk membaca input mentah dari dev/input tanpa MENCURI FOKUS!
                // Uses Shizuku to get process with root/shell privileges!
                evdevProcess = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", "getevent -l"), null as Array<String>?, null as String?)
                val reader = BufferedReader(InputStreamReader(evdevProcess!!.inputStream))
                var line: String? = null
                
                var lStickX = 0f
                var lStickY = 0f
                var rStickX = 0f
                var rStickY = 0f
                var l2Trigger = 0f
                var r2Trigger = 0f
                
                while (isListening && reader.readLine().also { line = it } != null) {
                    line?.let {
                        if (it.contains("EV_KEY")) {
                            val parts = it.trim().split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                val btnRaw = parts[2]
                                val stateStr = parts[3]
                                val isDown = if (stateStr == "DOWN") 1 else 0
                                
                                val btnMap = mapEvdevToButton(btnRaw)
                                if (btnMap != "UNKNOWN") {
                                    TouchInjectionPlugin.emitGamepadButton(btnMap, isDown, 1.0f)
                                }
                            }
                        } else if (it.contains("EV_ABS")) {
                            val parts = it.trim().split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                val axisType = parts[2]
                                val valueHex = parts[3]
                                try {
                                    val rawVal = valueHex.toLong(16).toInt()
                                    when (axisType) {
                                        "ABS_HAT0Y" -> {
                                            when (rawVal) {
                                                -1 -> { TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 1, 1f)
                                                        TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 0, 0f) }
                                                1  -> { TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 1, 1f)
                                                        TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 0, 0f) }
                                                0  -> { TouchInjectionPlugin.emitGamepadButton("DPAD_UP", 0, 0f)
                                                        TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", 0, 0f) }
                                            }
                                        }
                                        "ABS_HAT0X" -> {
                                            when (rawVal) {
                                                -1 -> { TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 1, 1f)
                                                        TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 0, 0f) }
                                                1  -> { TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 1, 1f)
                                                        TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 0, 0f) }
                                                0  -> { TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", 0, 0f)
                                                        TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", 0, 0f) }
                                            }
                                        }
                                        else -> {
                                            val normalizedVal = rawVal.toFloat() / 32767f
                                            val finalVal = normalizedVal.coerceIn(-1f, 1f)
                                            when (axisType) {
                                                "ABS_X"  -> lStickX = finalVal
                                                "ABS_Y"  -> lStickY = finalVal
                                                "ABS_RX" -> rStickX = finalVal
                                                "ABS_RY" -> rStickY = finalVal
                                                "ABS_Z"  -> l2Trigger = finalVal
                                                "ABS_RZ" -> r2Trigger = finalVal
                                            }
                                            TouchInjectionPlugin.emitGamepadAxis(floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger))
                                        }
                                    }
                                } catch (e: NumberFormatException) { }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GameMapper", "getevent loop failed", e)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_A") || evdevName.contains("BTN_SOUTH") -> "A"
            evdevName.contains("BTN_B") || evdevName.contains("BTN_EAST") -> "B"
            evdevName.contains("BTN_X") || evdevName.contains("BTN_NORTH") -> "X"
            evdevName.contains("BTN_Y") || evdevName.contains("BTN_WEST") -> "Y"
            evdevName.contains("BTN_TL") || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR") || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_TL2") || evdevName.contains("BTN_L2") -> "LT"
            evdevName.contains("BTN_TR2") || evdevName.contains("BTN_R2") -> "RT"
            evdevName.contains("BTN_THUMBL") -> "L3"
            evdevName.contains("BTN_THUMBR") -> "R3"
            evdevName.contains("BTN_START") -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("ABS_HAT0Y") -> "DPAD" // Need handling down/up
            evdevName.contains("ABS_HAT0X") -> "DPAD" // Need handling left/right
            else -> "UNKNOWN"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("GameMapper", "GamepadListenerService: onDestroy")
        isRunning = false
        isListening = false
        try {
            evdevProcess?.destroy()
        } catch (e: Exception) {}
    }
}
