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

    data class DetectedDevice(
        val devicePath: String,
        val axisRanges: Map<String, Pair<Int, Int>>,
        val rightStickUsesZRZ: Boolean,
        val buttonNames: Set<String>
    )

    data class GamepadSlot(
        val slotIndex: Int, // 0..3
        val devicePath: String,
        val axisRanges: Map<String, Pair<Int, Int>>,
        val rightStickUsesZRZ: Boolean,
        val buttonNames: Set<String>
    )

    private val activeSlots = java.util.concurrent.ConcurrentHashMap<String, GamepadSlot>()
    private val slotLock = Any()
    @Volatile private var isListening = false
    val currentGamepadDevice: String?
        get() = activeSlots.keys.firstOrNull()

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

    // ==================== HOTPLUG (MULTI-GAMEPAD AWARE) ====================

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return
        if (isGamepadDevice(device)) {
            Log.i("GameMapper", "Gamepad connected: ${device.name}")
            startGetEventCapture()
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId)
        Log.i("GameMapper", "Input device removed: ${device?.name ?: deviceId}")
        if (isRunning) {
            // Re-sync active streams with remaining devices
            startGetEventCapture()
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
        stopAllListeners()
    }

    private fun stopAllListeners() {
        synchronized(slotLock) {
            isListening = false
            for (slot in activeSlots.values) {
                NativeGamepadMapper.instance?.resetGamepad(slot.slotIndex)
            }
            activeSlots.clear()
            try {
                TouchInjectionPlugin.touchService?.stopStreamCommand()
            } catch (_: Exception) {}
            Log.i("GameMapper", "Stopped all getevent streams")
        }
    }

    // ==================== GETEVENT LISTENER (MULTI-CONTROLLER MULTIPLEXING) ====================

    private fun startGetEventCapture() {
        if (!rikka.shizuku.Shizuku.pingBinder() ||
            rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            TouchInjectionPlugin.touchService == null
        ) {
            Log.w("GameMapper", "Shizuku or TouchService not ready")
            return
        }

        Thread {
            try {
                // Tunggu profile tersedia
                val deadline = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < deadline && activeProfileJson == null) {
                    Thread.sleep(50)
                }

                val nativeMapper = NativeGamepadMapper.instance ?: NativeGamepadMapper(this)
                nativeMapper.buildMapCache()

                val detectedDevices = detectAllGamepadDevices()
                if (detectedDevices.isEmpty()) {
                    Log.w("GameMapper", "No gamepad detected")
                    TouchInjectionPlugin.emitGamepadButton("ERROR_NO_GAMEPAD", 0, 0f)
                    return@Thread
                }

                synchronized(slotLock) {
                    // Check for disconnected devices
                    val detectedPaths = detectedDevices.map { it.devicePath }.toSet()
                    val toRemove = activeSlots.keys.filter { it !in detectedPaths }
                    for (removedPath in toRemove) {
                        val removedSlot = activeSlots.remove(removedPath)
                        if (removedSlot != null) {
                            Log.i("GameMapper", "Gamepad removed from slot ${removedSlot.slotIndex}: $removedPath")
                            NativeGamepadMapper.instance?.resetGamepad(removedSlot.slotIndex)
                        }
                    }

                    // Assign and spawn streams for newly detected devices
                    for (dev in detectedDevices) {
                        if (activeSlots.containsKey(dev.devicePath)) {
                            // Already streaming on this device
                            continue
                        }

                        // Find first free slot in 0..3
                        val occupiedSlots = activeSlots.values.map { it.slotIndex }.toSet()
                        val freeSlotIndex = (0..3).firstOrNull { it !in occupiedSlots }
                        if (freeSlotIndex == null) {
                            Log.w("GameMapper", "Max 4 gamepads already connected. Skipping ${dev.devicePath}")
                            continue
                        }

                        val slot = GamepadSlot(
                            freeSlotIndex,
                            dev.devicePath,
                            dev.axisRanges,
                            dev.rightStickUsesZRZ,
                            dev.buttonNames
                        )
                        activeSlots[dev.devicePath] = slot
                        isListening = true

                        Log.i("GameMapper", "Starting getevent on ${dev.devicePath} for gamepad slot ${slot.slotIndex}")
                        val axisNames = if (slot.axisRanges.isEmpty()) "(none)" else slot.axisRanges.keys.sorted().joinToString(", ")
                        val btnNames = if (slot.buttonNames.isEmpty()) "(none)" else slot.buttonNames.sorted().joinToString(", ")
                        TouchInjectionPlugin.emitDiagnosticLog("[GAMEPAD-DETECT] Slot ${slot.slotIndex} (${dev.devicePath}): axes: $axisNames | buttons: $btnNames | R-stick uses Z/RZ: ${slot.rightStickUsesZRZ}")

                        val listener = createStreamListener(slot)
                        TouchInjectionPlugin.touchService?.executeStreamCommand(
                            "getevent -l ${dev.devicePath}",
                            listener
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e("GameMapper", "Failed in startGetEventCapture", e)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun detectAllGamepadDevices(): List<DetectedDevice> {
        return try {
            val result = TouchInjectionPlugin.touchService?.executeShellCommand("getevent -lp") ?: return emptyList()
            val output = org.json.JSONObject(result).optString("output", "")
            val lines = output.lines()

            val devices = mutableListOf<String>()
            val deviceAxisRanges = mutableMapOf<String, MutableMap<String, Pair<Int, Int>>>()
            val deviceButtonNames = mutableMapOf<String, MutableSet<String>>()
            var currentPath: String? = null
            var isGamepad = false
            val axisLineRegex = Regex("(ABS_\\w+)\\s*:.*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")
            val btnLineRegex = Regex("(BTN_\\w+)")

            for (line in lines) {
                if (line.contains("add device")) {
                    if (isGamepad && currentPath != null && !devices.contains(currentPath)) {
                        devices.add(currentPath)
                    }
                    currentPath = Regex("/dev/input/event\\d+").find(line)?.value
                    isGamepad = false
                } else if (line.contains("BTN_A") || line.contains("BTN_GAMEPAD") ||
                           line.contains("BTN_SOUTH") || line.contains("ABS_HAT0X") ||
                           line.contains("BTN_JOYSTICK")) {
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

                if (currentPath != null && (line.trim().startsWith("BTN_") || line.contains("KEY (0001)"))) {
                    btnLineRegex.findAll(line).forEach { m ->
                        deviceButtonNames.getOrPut(currentPath!!) { mutableSetOf() }.add(m.value)
                    }
                }
            }

            if (isGamepad && currentPath != null && !devices.contains(currentPath)) {
                devices.add(currentPath)
            }

            val resultList = mutableListOf<DetectedDevice>()
            for (path in devices) {
                val ranges = deviceAxisRanges[path] ?: emptyMap()
                val btns = deviceButtonNames[path] ?: emptySet()
                val zrz = !ranges.containsKey("ABS_RX") &&
                    !ranges.containsKey("ABS_RY") &&
                    ranges.containsKey("ABS_Z") &&
                    ranges.containsKey("ABS_RZ")
                resultList.add(DetectedDevice(path, ranges, zrz, btns))
            }
            resultList
        } catch (e: Exception) {
            Log.e("GameMapper", "detectAllGamepadDevices failed", e)
            emptyList()
        }
    }

    private fun createStreamListener(slot: GamepadSlot) = object : ICommandOutputListener.Stub() {
        private var lStickX = 0f
        private var lStickY = 0f
        private var rStickX = 0f
        private var rStickY = 0f
        private var l2Trigger = 0f
        private var r2Trigger = 0f
        private var hasAxisChange = false
        private val lastKeyState = mutableMapOf<String, Boolean>()

        override fun onOutputLine(line: String?) {
            if (!isListening || line == null) return

            when {
                line.contains("EV_SYN") && line.contains("SYN_REPORT") -> {
                    if (hasAxisChange) {
                        GamepadJniPlugin.handleAxisBatched(slot.slotIndex, lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger)
                        if (slot.slotIndex == 0) {
                            TouchInjectionPlugin.emitGamepadAxis(floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger))
                        }
                        hasAxisChange = false
                    }
                }
                line.contains("EV_KEY") -> handleKeyEvent(line)
                line.contains("EV_ABS") -> handleAbsEvent(line)
            }
        }

        override fun onExit(code: Int) {
            Log.w("GameMapper", "getevent stream ended for ${slot.devicePath} slot ${slot.slotIndex} (code=$code). Trying to reconnect...")
            synchronized(slotLock) {
                activeSlots.remove(slot.devicePath)
                NativeGamepadMapper.instance?.resetGamepad(slot.slotIndex)
                if (activeSlots.isEmpty()) {
                    isListening = false
                }
            }

            if (isRunning) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isRunning && activeSlots.isEmpty()) {
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
                    if (lastKeyState[btnRaw] == isDown) return
                    lastKeyState[btnRaw] = isDown

                    GamepadJniPlugin.handleButtonBatched(slot.slotIndex, btnName, isDown)
                    if (slot.slotIndex == 0) {
                        TouchInjectionPlugin.emitGamepadButton(btnName, if (isDown) 1 else 0, 1.0f)
                    }
                } else {
                    if (lastKeyState[btnRaw] != isDown) {
                        lastKeyState[btnRaw] = isDown
                        TouchInjectionPlugin.emitDiagnosticLog("[GAMEPAD-KEY] Slot ${slot.slotIndex} Unmapped button $btnRaw ${if (isDown) "DOWN" else "UP"} — raw line: $line")
                    }
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
                        if (slot.rightStickUsesZRZ) { rStickX = normalizeAxis(axisType, rawVal) }
                        else { l2Trigger = normalizeTrigger(axisType, rawVal) }
                        hasAxisChange = true
                    }
                    "ABS_RZ" -> {
                        if (slot.rightStickUsesZRZ) { rStickY = normalizeAxis(axisType, rawVal) }
                        else { r2Trigger = normalizeTrigger(axisType, rawVal) }
                        hasAxisChange = true
                    }
                    "ABS_GAS" -> { r2Trigger = normalizeTrigger(axisType, rawVal); hasAxisChange = true }
                    "ABS_BRAKE" -> { l2Trigger = normalizeTrigger(axisType, rawVal); hasAxisChange = true }
                    "ABS_HAT0X" -> {
                        GamepadJniPlugin.handleButtonBatched(slot.slotIndex, "DPAD_LEFT", rawVal < 0)
                        GamepadJniPlugin.handleButtonBatched(slot.slotIndex, "DPAD_RIGHT", rawVal > 0)
                        if (slot.slotIndex == 0) {
                            TouchInjectionPlugin.emitGamepadButton("DPAD_LEFT", if (rawVal < 0) 1 else 0, 1.0f)
                            TouchInjectionPlugin.emitGamepadButton("DPAD_RIGHT", if (rawVal > 0) 1 else 0, 1.0f)
                        }
                    }
                    "ABS_HAT0Y" -> {
                        GamepadJniPlugin.handleButtonBatched(slot.slotIndex, "DPAD_UP", rawVal < 0)
                        GamepadJniPlugin.handleButtonBatched(slot.slotIndex, "DPAD_DOWN", rawVal > 0)
                        if (slot.slotIndex == 0) {
                            TouchInjectionPlugin.emitGamepadButton("DPAD_UP", if (rawVal < 0) 1 else 0, 1.0f)
                            TouchInjectionPlugin.emitGamepadButton("DPAD_DOWN", if (rawVal > 0) 1 else 0, 1.0f)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        private fun normalizeAxis(axisName: String, raw: Int): Float {
            val range = slot.axisRanges[axisName]
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

        private fun normalizeTrigger(axisName: String, raw: Int): Float {
            val range = slot.axisRanges[axisName]
            if (range != null) {
                val (min, max) = range
                val span = (max - min).toFloat()
                if (span > 0f) {
                    return ((raw - min) / span).coerceIn(0f, 1f)
                }
            }
            val maxGuess = when {
                raw > 4095 -> 32767f
                raw > 1023 -> 4095f
                raw > 255 -> 1023f
                else -> 255f
            }
            return (raw.toFloat() / maxGuess).coerceIn(0f, 1f)
        }
    }

    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            // FIX v3 (root cause of "Tombol A tidak menginjeksi"):
            // In Linux input.h, BTN_GAMEPAD (0x130) = BTN_SOUTH = BTN_A — they are ALL the
            // SAME code. Many generic Bluetooth gamepads report their A button as
            // "BTN_GAMEPAD" in getevent (both in capability dumps AND event streams).
            // The previous code only checked for "BTN_A" and "BTN_SOUTH" as substrings,
            // which DON'T match "BTN_GAMEPAD" — so the A button was mapped to UNKNOWN and
            // silently dropped during gameplay (when only getevent is active, not the
            // Android GamepadPlugin which uses KeyCode and correctly maps KEYCODE_BUTTON_A).
            // Now BTN_GAMEPAD is explicitly mapped to "A", matching the kernel's own
            // alias definition.
            evdevName == "BTN_GAMEPAD" || evdevName.contains("BTN_A") || evdevName.contains("BTN_SOUTH") -> "A"
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
            // FIX: some generic Bluetooth gamepads use non-standard BTN_LT/BTN_RT aliases
            // for digital triggers (instead of the standard BTN_TL2/BTN_TR2). Map them too
            // so LT/RT work without needing to extend the diagnostic log + manual fix.
            evdevName == "BTN_LT" -> "LT"
            evdevName == "BTN_RT" -> "RT"
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
        stopAllListeners()
        NativeGamepadMapper.instance?.stopGyroListener()

        inputManager.unregisterInputDeviceListener(this)

        val ts = TouchInjectionPlugin.touchService
        Thread {
            try { ts?.releaseAllPointers() } catch (_: Exception) {}
            try { ts?.stopStreamCommand() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }
}