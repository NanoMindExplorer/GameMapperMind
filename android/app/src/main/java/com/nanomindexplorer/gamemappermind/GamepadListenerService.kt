package com.nanomindexplorer.gamemappermind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.widget.Toast
import androidx.core.app.NotificationCompat

class GamepadListenerService : Service(), InputManager.InputDeviceListener {

    private val CHANNEL_ID = "GamepadListenerChannel"
    @Volatile private var isListening = false
    private var currentGamepadDevice: String? = null
    private lateinit var inputManager: InputManager

    companion object {
        @Volatile var isRunning = false
        @Volatile var activeProfileJson: String? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("GameMapper", "GamepadListenerService: onCreate")

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(this, null)

        createNotificationChannel()
        startForegroundService()
        isRunning = true

        // Coba mulai listener saat service pertama kali dibuat
        if (!isListening) {
            startGetEventCapture()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_TEST_TAP") {
            runNotificationTestTap()
            return START_STICKY
        }
        return START_STICKY
    }

    private fun runNotificationTestTap() {
        Thread {
            val reportJson = try {
                NativeGamepadMapper.instance?.runDiagnosticTestTap()
                    ?: "{\"error\":\"NativeGamepadMapper not initialized\"}"
            } catch (e: Exception) {
                "{\"error\":\"${e.message}\"}"
            }

            Log.i("GameMapper", "Notification Test Tap report: $reportJson")

            val summary = try {
                val obj = org.json.JSONObject(reportJson)
                when {
                    obj.has("error") -> "Test Tap GAGAL: ${obj.getString("error")}"
                    obj.has("recommendation") -> "Test Tap berhasil"
                    else -> "Test Tap selesai"
                }
            } catch (e: Exception) {
                "Test Tap selesai"
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, summary, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun startForegroundService() {
        val testTapIntent = Intent(this, GamepadListenerService::class.java).apply {
            action = "ACTION_TEST_TAP"
        }

        val testTapPendingIntent = PendingIntent.getService(
            this, 3, testTapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameMapperMind Active")
            .setContentText("Gamepad mapping service is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_send, "Test Tap", testTapPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Gamepad Listener", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Gamepad mapping service"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    // ==================== HOTPLUG (Lebih Robust) ====================

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return
        if (isGamepadDevice(device)) {
            Log.i("GameMapper", "Gamepad connected: ${device.name}")
            if (!isListening) {
                startGetEventCapture()
            }
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId)
        Log.i("GameMapper", "Input device removed: ${device?.name ?: deviceId}")

        // Jika device yang sedang dipakai disconnect, reset dan coba cari lagi
        if (currentGamepadDevice != null) {
            // Untuk sekarang kita stop dulu, nanti akan dicoba reconnect otomatis
            stopCurrentListener()
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // Bisa digunakan untuk update jika diperlukan nanti
    }

    private fun isGamepadDevice(device: InputDevice): Boolean {
        return (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun stopCurrentListener() {
        if (isListening) {
            isListening = false
            currentGamepadDevice = null
            try {
                TouchInjectionPlugin.touchService?.stopStreamCommand()
            } catch (_: Exception) {}
            Log.i("GameMapper", "Stopped current getevent listener due to device change")
        }
    }

    // ==================== GETEVENT LISTENER ====================

    private fun startGetEventCapture() {
        if (isListening) return

        if (!rikka.shizuku.Shizuku.pingBinder() ||
            rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            TouchInjectionPlugin.touchService == null
        ) {
            Log.w("GameMapper", "Shizuku or TouchService not ready")
            return
        }

        isListening = true

        Thread {
            try {
                // Tunggu profile tersedia
                val deadline = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < deadline && activeProfileJson == null) {
                    Thread.sleep(50)
                }

                val nativeMapper = NativeGamepadMapper.instance ?: NativeGamepadMapper(this)
                nativeMapper.buildMapCache()

                val gamepadDevice = detectGamepadDevice()
                if (gamepadDevice == null) {
                    Log.w("GameMapper", "No gamepad detected")
                    TouchInjectionPlugin.emitGamepadButton("ERROR_NO_GAMEPAD", 0, 0f)
                    isListening = false
                    return@Thread
                }

                currentGamepadDevice = gamepadDevice
                Log.i("GameMapper", "Starting getevent on: $gamepadDevice")

                val streamListener = createStreamListener()
                TouchInjectionPlugin.touchService?.executeStreamCommand(
                    "getevent -l $gamepadDevice",
                    streamListener
                )

            } catch (e: Exception) {
                Log.e("GameMapper", "Failed to start getevent", e)
                isListening = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun detectGamepadDevice(): String? {
        return try {
            val result = TouchInjectionPlugin.touchService?.executeShellCommand("getevent -lp") ?: return null
            val output = org.json.JSONObject(result).optString("output", "")
            val lines = output.lines()

            val devices = mutableListOf<String>()
            var currentPath: String? = null
            var isGamepad = false

            for (line in lines) {
                if (line.contains("add device")) {
                    if (isGamepad && currentPath != null && !devices.contains(currentPath)) {
                        devices.add(currentPath)
                    }
                    currentPath = Regex("/dev/input/event\\d+").find(line)?.value
                    isGamepad = false
                } else if (line.contains("BTN_A") || line.contains("BTN_GAMEPAD") ||
                           line.contains("BTN_SOUTH") || line.contains("ABS_HAT0X")) {
                    isGamepad = true
                }
            }

            if (isGamepad && currentPath != null && !devices.contains(currentPath)) {
                devices.add(currentPath)
            }

            devices.firstOrNull()
        } catch (e: Exception) {
            Log.e("GameMapper", "detectGamepadDevice failed", e)
            null
        }
    }

    private fun createStreamListener() = object : ICommandOutputListener.Stub() {
        private var lStickX = 0f
        private var lStickY = 0f
        private var rStickX = 0f
        private var rStickY = 0f
        private var l2Trigger = 0f
        private var r2Trigger = 0f
        private var hasAxisChange = false

        override fun onOutputLine(line: String?) {
            if (!isListening || line == null) return

            when {
                line.contains("EV_SYN") && line.contains("SYN_REPORT") -> {
                    if (hasAxisChange) {
                        GamepadJniPlugin.handleAxisBatched(0, lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger)
                        TouchInjectionPlugin.emitGamepadAxis(floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger))
                        hasAxisChange = false
                    }
                }
                line.contains("EV_KEY") -> handleKeyEvent(line)
                line.contains("EV_ABS") -> handleAbsEvent(line)
            }
        }

        override fun onExit(code: Int) {
            isListening = false
            currentGamepadDevice = null
            Log.w("GameMapper", "getevent stream ended (code=$code). Trying to reconnect...")

            if (isRunning) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isRunning && !isListening) {
                        startGetEventCapture()
                    }
                }, 2000)
            }
        }

        private fun handleKeyEvent(line: String) {
            val parts = line.trim().split(Regex("\\s+"))
            val btnIdx = parts.indexOfFirst { it.startsWith("BTN_") }
            val stateIdx = parts.indexOfLast { it == "DOWN" || it == "UP" }

            if (btnIdx >= 0 && stateIdx > btnIdx) {
                val btnRaw = parts[btnIdx]
                val isDown = parts[stateIdx] == "DOWN"
                val btnName = mapEvdevToButton(btnRaw)

                if (btnName != "UNKNOWN") {
                    GamepadJniPlugin.handleButtonBatched(0, btnName, isDown)
                    TouchInjectionPlugin.emitGamepadButton(btnName, if (isDown) 1 else 0, 1.0f)
                }
            }
        }

        private fun handleAbsEvent(line: String) {
            val parts = line.trim().split(Regex("\\s+"))
            val absIdx = parts.indexOfFirst { it.startsWith("ABS_") }
            if (absIdx < 0 || absIdx + 1 >= parts.size) return

            val axisType = parts[absIdx]
            val valueHex = parts[absIdx + 1]

            try {
                val rawVal = valueHex.toLong(16).toInt()
                when (axisType) {
                    "ABS_X" -> { lStickX = normalizeAxis(rawVal); hasAxisChange = true }
                    "ABS_Y" -> { lStickY = normalizeAxis(rawVal); hasAxisChange = true }
                    "ABS_RX" -> { rStickX = normalizeAxis(rawVal); hasAxisChange = true }
                    "ABS_RY" -> { rStickY = normalizeAxis(rawVal); hasAxisChange = true }
                    "ABS_Z", "ABS_GAS" -> { l2Trigger = normalizeTrigger(rawVal); hasAxisChange = true }
                    "ABS_RZ", "ABS_BRAKE" -> { r2Trigger = normalizeTrigger(rawVal); hasAxisChange = true }
                }
            } catch (_: Exception) {}
        }

        private fun normalizeAxis(raw: Int): Float = (raw / 32767f).coerceIn(-1f, 1f)
        private fun normalizeTrigger(raw: Int): Float = ((raw + 32768) / 65535f).coerceIn(0f, 1f)
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_A") || evdevName.contains("BTN_SOUTH") -> "A"
            evdevName.contains("BTN_B") || evdevName.contains("BTN_EAST") -> "B"
            evdevName.contains("BTN_X") || evdevName.contains("BTN_NORTH") -> "X"
            evdevName.contains("BTN_Y") || evdevName.contains("BTN_WEST") -> "Y"
            evdevName.contains("BTN_TL") || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR") || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_THUMBL") || evdevName == "BTN_THUMB" -> "L3"
            evdevName.contains("BTN_THUMBR") || evdevName == "BTN_THUMB2" -> "R3"
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
        currentGamepadDevice = null

        inputManager.unregisterInputDeviceListener(this)

        val ts = TouchInjectionPlugin.touchService
        Thread {
            try { ts?.releaseAllPointers() } catch (_: Exception) {}
            try { ts?.stopStreamCommand() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }
}