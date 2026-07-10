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

    // FIX (bug report: "LT/RT tidak bereaksi", "analog kiri nyangkut/gak smooth"):
    // normalizeAxis()/normalizeTrigger() previously assumed a hardcoded signed
    // -32768..32767 range for EVERY axis, including triggers. Real controllers report
    // wildly different raw ranges per axis (sticks are often -32768..32767, but triggers
    // are very commonly unsigned 0..255 or 0..1023 — some sticks aren't ±32767 either).
    // With the old hardcoded formula, a 0..255 trigger would normalize to a near-constant
    // ~0.50 regardless of press state, permanently tripping the press threshold once and
    // then never changing again — exactly "LT/RT tidak bereaksi". detectGamepadDevice()
    // already runs `getevent -lp`, which prints each axis's real min/max — previously
    // that text was fetched and then thrown away, keeping only the device path. Now it's
    // parsed and kept so normalization matches the actual hardware.
    private var detectedAxisRanges: Map<String, Pair<Int, Int>> = emptyMap()

    // FIX (bug report: "analog kanan mati" + "LT/RT tidak bereaksi" together): the code
    // assumed the Xbox/xpad convention (ABS_RX/RY = right stick, ABS_Z/RZ = triggers). Many
    // generic/cheap Bluetooth gamepads instead report the right stick on ABS_Z/ABS_RZ and
    // send triggers as plain digital buttons (BTN_TL2/BTN_TR2), not as any analog axis at
    // all. Under the old fixed assumption, that combination reads as "right stick frozen at
    // whatever the trigger happens to report" AND "triggers never analog-cross the press
    // threshold" simultaneously — matching both symptoms at once. Decided once per connected
    // device from its actual capability dump, not guessed globally.
    private var rightStickUsesZRZ = false

    private fun detectGamepadDevice(): String? {
        return try {
            val result = TouchInjectionPlugin.touchService?.executeShellCommand("getevent -lp") ?: return null
            val output = org.json.JSONObject(result).optString("output", "")
            val lines = output.lines()

            val devices = mutableListOf<String>()
            val deviceAxisRanges = mutableMapOf<String, MutableMap<String, Pair<Int, Int>>>()
            var currentPath: String? = null
            var isGamepad = false
            val axisLineRegex = Regex("(ABS_\\w+)\\s*:.*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")

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

                val axisMatch = axisLineRegex.find(line)
                if (axisMatch != null && currentPath != null) {
                    val (axisName, minStr, maxStr) = axisMatch.destructured
                    val min = minStr.toIntOrNull()
                    val max = maxStr.toIntOrNull()
                    if (min != null && max != null && max > min) {
                        deviceAxisRanges.getOrPut(currentPath!!) { mutableMapOf() }[axisName] = Pair(min, max)
                    }
                }
            }

            if (isGamepad && currentPath != null && !devices.contains(currentPath)) {
                devices.add(currentPath)
            }

            val chosen = devices.firstOrNull()
            detectedAxisRanges = if (chosen != null) deviceAxisRanges[chosen] ?: emptyMap() else emptyMap()
            rightStickUsesZRZ = !detectedAxisRanges.containsKey("ABS_RX") &&
                !detectedAxisRanges.containsKey("ABS_RY") &&
                detectedAxisRanges.containsKey("ABS_Z") &&
                detectedAxisRanges.containsKey("ABS_RZ")
            Log.i("GameMapper", "Detected axis ranges for $chosen: $detectedAxisRanges (rightStickUsesZRZ=$rightStickUsesZRZ)")
            chosen
        } catch (e: Exception) {
            Log.e("GameMapper", "detectGamepadDevice failed", e)
            detectedAxisRanges = emptyMap()
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
                    "ABS_X" -> { lStickX = normalizeAxis(axisType, rawVal); hasAxisChange = true }
                    "ABS_Y" -> { lStickY = normalizeAxis(axisType, rawVal); hasAxisChange = true }
                    "ABS_RX" -> { rStickX = normalizeAxis(axisType, rawVal); hasAxisChange = true }
                    "ABS_RY" -> { rStickY = normalizeAxis(axisType, rawVal); hasAxisChange = true }
                    "ABS_Z" -> {
                        if (rightStickUsesZRZ) { rStickX = normalizeAxis(axisType, rawVal) }
                        else { l2Trigger = normalizeTrigger(axisType, rawVal) }
                        hasAxisChange = true
                    }
                    "ABS_RZ" -> {
                        if (rightStickUsesZRZ) { rStickY = normalizeAxis(axisType, rawVal) }
                        else { r2Trigger = normalizeTrigger(axisType, rawVal) }
                        hasAxisChange = true
                    }
                    "ABS_GAS" -> { l2Trigger = normalizeTrigger(axisType, rawVal); hasAxisChange = true }
                    "ABS_BRAKE" -> { r2Trigger = normalizeTrigger(axisType, rawVal); hasAxisChange = true }
                    // FIX: D-pad on most controllers reports as ABS_HAT0X/ABS_HAT0Y (-1/0/1), not
                    // as BTN_DPAD_* keys. This was previously unhandled here entirely — harmless
                    // while testing on the main app screen (GamepadPlugin's onGenericMotionEvent
                    // path covers it there), but during actual gameplay MainActivity has no window
                    // focus so THIS listener is the only path receiving input, and D-pad silently
                    // did nothing. Mirrors the same DPAD_* button convention GamepadPlugin uses,
                    // so downstream mapping/dedup in NativeGamepadMapper.handleButton works as-is.
                    "ABS_HAT0X" -> {
                        GamepadJniPlugin.handleButtonBatched(0, "DPAD_LEFT", rawVal < 0)
                        GamepadJniPlugin.handleButtonBatched(0, "DPAD_RIGHT", rawVal > 0)
                        TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", if (rawVal < 0) 1 else 0, 1.0f)
                        TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", if (rawVal > 0) 1 else 0, 1.0f)
                    }
                    "ABS_HAT0Y" -> {
                        GamepadJniPlugin.handleButtonBatched(0, "DPAD_UP", rawVal < 0)
                        GamepadJniPlugin.handleButtonBatched(0, "DPAD_DOWN", rawVal > 0)
                        TouchInjectionPlugin.emitGamepadButton("DPAD_UP", if (rawVal < 0) 1 else 0, 1.0f)
                        TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", if (rawVal > 0) 1 else 0, 1.0f)
                    }
                }
            } catch (_: Exception) {}
        }

        // FIX: previously hardcoded to a signed -32768..32767 range for every axis
        // (`raw / 32767f`), which silently breaks any controller whose stick doesn't use
        // exactly that range. Now uses the real min/max detectGamepadDevice() parsed from
        // `getevent -lp` for this specific device/axis (centers on the range's actual
        // midpoint, not an assumed 0), falling back to the old constant only if that
        // specific axis wasn't found in the capability dump.
        private fun normalizeAxis(axisName: String, raw: Int): Float {
            val range = detectedAxisRanges[axisName]
            if (range != null) {
                val (min, max) = range
                val half = (max - min) / 2f
                if (half > 0f) {
                    val mid = (min + max) / 2f
                    return ((raw - mid) / half).coerceIn(-1f, 1f)
                }
            }
            return (raw / 32767f).coerceIn(-1f, 1f)
        }

        // FIX (root cause of "LT/RT tidak bereaksi"): previously assumed the same signed
        // -32768..32767 range as sticks (`(raw + 32768) / 65535f`). Triggers are very
        // commonly unsigned 0..255 or 0..1023 instead — under the old formula a 0..255
        // trigger would always normalize to roughly 0.50 (both released AND fully pressed),
        // permanently tripping the 0.08 press threshold once on the first tiny jitter and
        // then never crossing it again. Now scales from the real detected min (released) to
        // max (fully pressed) for this exact axis, whatever range the hardware actually uses.
        private fun normalizeTrigger(axisName: String, raw: Int): Float {
            val range = detectedAxisRanges[axisName]
            if (range != null) {
                val (min, max) = range
                val span = (max - min).toFloat()
                if (span > 0f) {
                    return ((raw - min) / span).coerceIn(0f, 1f)
                }
            }
            // Fallback: most common convention for triggers on generic HID gamepads.
            return (raw / 255f).coerceIn(0f, 1f)
        }
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_A") || evdevName.contains("BTN_SOUTH") -> "A"
            evdevName.contains("BTN_B") || evdevName.contains("BTN_EAST") -> "B"
            evdevName.contains("BTN_X") || evdevName.contains("BTN_NORTH") -> "X"
            evdevName.contains("BTN_Y") || evdevName.contains("BTN_WEST") -> "Y"
            // FIX (root cause candidate for "LT/RT tidak bereaksi"): BTN_TL2/BTN_TR2 is the
            // standard evdev name for digital trigger buttons on many generic/cheap gamepads
            // (as opposed to an analog trigger axis). MUST be checked before the plain
            // BTN_TL/BTN_TR checks below — "BTN_TL2".contains("BTN_TL") is true in Kotlin, so
            // the old check order silently swallowed every trigger press as a bumper (LB/RB)
            // press instead, and the real LT/RT mapping never received anything.
            evdevName.contains("BTN_TL2") -> "LT"
            evdevName.contains("BTN_TR2") -> "RT"
            evdevName.contains("BTN_TL") || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR") || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_THUMBL") || evdevName == "BTN_THUMB" -> "L3"
            evdevName.contains("BTN_THUMBR") || evdevName == "BTN_THUMB2" -> "R3"
            evdevName.contains("BTN_START") -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("BTN_MODE") -> "HOME"
            // FIX: fallback for controllers that send D-pad as discrete keys instead of
            // ABS_HAT0X/Y (handled separately in handleAbsEvent). Rare but seen on some
            // generic/cheap HID gamepads.
            evdevName.contains("BTN_DPAD_UP") -> "DPAD_UP"
            evdevName.contains("BTN_DPAD_DOWN") -> "DPAD_DOWN"
            evdevName.contains("BTN_DPAD_LEFT") -> "DPAD_LEFT"
            evdevName.contains("BTN_DPAD_RIGHT") -> "DPAD_RIGHT"
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