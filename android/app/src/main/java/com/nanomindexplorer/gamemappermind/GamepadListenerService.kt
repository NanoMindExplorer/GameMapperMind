package com.nanomindexplorer.gamemappermind

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader

class GamepadListenerService : Service(), InputManager.InputDeviceListener {

    private val CHANNEL_ID = "GamepadListenerChannel"
    private var evdevProcess: Process? = null
    private var isListening = false

    /**
     * REC-03: InputManager untuk detect gamepad connect/disconnect.
     * Saat gamepad BT disconnect dan reconnect, device path /dev/input/eventN
     * bisa berubah. Listener ini trigger re-scan device dan restart getevent capture.
     */
    private var inputManager: InputManager? = null

    companion object {
        var isRunning = false
        var activeProfileJson: String? = null // Set from React when profile changes
        /**
         * REC-03: Track gamepad device IDs yang terdeteksi.
         * Saat onInputDeviceAdded/Removed, bandingkan dengan set ini untuk detect change.
         */
        private val connectedGamepadIds: MutableSet<Int> = mutableSetOf()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("GameMapper", "GamepadListenerService: onCreate")
        createNotificationChannel()
        startForegroundService()

        // REC-03: Register InputDeviceListener untuk detect gamepad connect/disconnect.
        setupInputDeviceListener()

        startGetEventCapture()
        isRunning = true
    }

    /**
     * REC-03: Setup InputDeviceListener untuk handle BT reconnect.
     *
     * Saat gamepad BT disconnect dan reconnect:
     * 1. onInputDeviceRemoved dipanggil dengan deviceId lama
     * 2. onInputDeviceAdded dipanggil dengan deviceId baru
     * 3. Kita restart getevent capture untuk dapat device path baru
     *
     * Math-Logic (Pasal 5.1):
     * - isGamepad check: O(1) bitmask operation
     * - Re-scan: O(n) di mana n = jumlah device (biasanya 5-10)
     * - Restart getevent: O(1) stop old process + start new
     *
     * Invariant:
     * - Hanya device dengan SOURCE_GAMEPAD atau SOURCE_JOYSTICK yang di-track
     * - Saat gamepad added/removed, emit event ke frontend untuk UI update
     * - connectedGamepadIds selalu konsisten dengan device yang terdeteksi
     */
    private fun setupInputDeviceListener() {
        try {
            inputManager = getSystemService(INPUT_SERVICE) as? InputManager
            inputManager?.registerInputDeviceListener(this, null)

            // Initial scan: dapatkan semua gamepad yang sudah terhubung.
            val initialGamepadIds = mutableSetOf<Int>()
            inputManager?.inputDeviceIds?.forEach { deviceId ->
                val device = InputDevice.getDevice(deviceId)
                if (device != null && isGamepadDevice(device)) {
                    initialGamepadIds.add(deviceId)
                    Log.d("GameMapper", "REC-03: Initial gamepad detected: ${device.name} (id=$deviceId)")
                }
            }
            connectedGamepadIds.clear()
            connectedGamepadIds.addAll(initialGamepadIds)

            Log.d("GameMapper", "REC-03: InputDeviceListener registered, ${initialGamepadIds.size} gamepad(s) detected")
        } catch (e: Exception) {
            Log.e("GameMapper", "REC-03: Failed to setup InputDeviceListener", e)
        }
    }

    /**
     * REC-03: Helper untuk cek apakah device adalah gamepad.
     *
     * Math-Logic (Pasal 5.1):
     * - Bitmask check: (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD
     * - Kompleksitas: O(1)
     *
     * Invariant: return true hanya jika source mengandung bit GAMEPAD atau JOYSTICK.
     */
    private fun isGamepadDevice(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * REC-03: Dipanggil saat device baru terhubung (misal gamepad BT dinyalakan).
     *
     * Jika device adalah gamepad, trigger re-scan dan restart getevent capture
     * untuk dapat device path baru.
     */
    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (device != null && isGamepadDevice(device)) {
            Log.d("GameMapper", "REC-03: Gamepad added: ${device.name} (id=$deviceId)")
            connectedGamepadIds.add(deviceId)

            // Emit event ke frontend untuk UI update.
            TouchInjectionPlugin.emitGamepadButton("GAMEPAD_CONNECTED", 1, 1.0f)

            // Restart getevent capture untuk dapat device path baru.
            restartGetEventCapture()
        }
    }

    /**
     * REC-03: Dipanggil saat device disconnect (misal gamepad BT dimatikan).
     *
     * Jika device adalah gamepad yang di-track, emit event dan trigger re-scan.
     */
    override fun onInputDeviceRemoved(deviceId: Int) {
        if (connectedGamepadIds.contains(deviceId)) {
            Log.d("GameMapper", "REC-03: Gamepad removed (id=$deviceId)")
            connectedGamepadIds.remove(deviceId)

            // Emit event ke frontend untuk UI update.
            TouchInjectionPlugin.emitGamepadButton("GAMEPAD_DISCONNECTED", 0, 0.0f)

            // Restart getevent capture untuk hapus device path lama.
            restartGetEventCapture()
        }
    }

    /**
     * REC-03: Restart getevent capture.
     *
     * Dipanggil saat gamepad connect/disconnect untuk re-scan device path.
     * - Stop old getevent process
     * - Clear state
     * - Start new getevent capture dengan device path baru
     *
     * Invariant:
     * - Old process di-destroy sebelum new process start
     * - isListening tetap true (tidak interrupt service)
     * - Race condition: jika restart dipanggil berkali-kali cepat, hanya 1 yang aktif
     */
    @Synchronized
    private fun restartGetEventCapture() {
        Log.d("GameMapper", "REC-03: Restarting getevent capture due to device change")

        // Stop old process.
        try {
            evdevProcess?.destroy()
            evdevProcess = null
        } catch (e: Exception) {
            Log.w("GameMapper", "REC-03: Failed to destroy old getevent process", e)
        }

        // Start new capture (akan re-scan device path di startGetEventCapture).
        startGetEventCapture()
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
                // Get min/max first using getevent -p
                val newProcessMethod = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                newProcessMethod.isAccessible = true
                
                val absRanges = mutableMapOf<String, Pair<Int, Int>>()
                val gamepadDevices = mutableSetOf<String>()
                
                try {
                    val pProcess = newProcessMethod.invoke(null, arrayOf("sh", "-c", "getevent -lp"), null as Array<String>?, null as String?) as Process
                    val pReader = BufferedReader(InputStreamReader(pProcess.inputStream))
                    var pLine: String?
                    var currentDevicePath: String? = null
                    var currentDeviceIsGamepad = false
                    
                    while (pReader.readLine().also { pLine = it } != null) {
                        val line = pLine!!
                        if (line.contains("add device")) {
                            if (currentDeviceIsGamepad && currentDevicePath != null) {
                                gamepadDevices.add(currentDevicePath)
                            }
                            // Extract path like: add device 1: /dev/input/event4
                            val pathMatch = Regex("/dev/input/event\\d+").find(line)
                            currentDevicePath = pathMatch?.value
                            currentDeviceIsGamepad = false
                        } else if (line.contains("BTN_A") || line.contains("BTN_GAMEPAD") || line.contains("ABS_HAT0X")) {
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
                    pProcess.destroy()
                } catch (e: Exception) {
                    Log.e("GameMapper", "Failed to parse getevent -lp", e)
                }

                // Menjalankan getevent -l hanya untuk gamepad devices
                val geteventCmd = if (gamepadDevices.isNotEmpty()) {
                    "getevent -l " + gamepadDevices.joinToString(" ")
                } else {
                    val inputManager = getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager
                    val hasGamepad = inputManager.inputDeviceIds.any { id ->
                        val dev = inputManager.getInputDevice(id)
                        dev != null && ((dev.sources and android.view.InputDevice.SOURCE_GAMEPAD) != 0 || 
                                        (dev.sources and android.view.InputDevice.SOURCE_JOYSTICK) != 0)
                    }
                    if (hasGamepad) {
                        Log.w("GameMapper", "Gamepad detected via InputManager but not by getevent -lp filters. Capturing all as fallback.")
                        "getevent -l"
                    } else {
                        Log.e("GameMapper", "No gamepad found. Terminating listener to avoid capturing touch.")
                        TouchInjectionPlugin.emitGamepadButton("ERROR_NO_GAMEPAD", 0, 0f)
                        return@Thread
                    }
                }

                val geteventCmd = "getevent -l " + gamepadDevices.joinToString(" ")
                evdevProcess = newProcessMethod.invoke(null, arrayOf("sh", "-c", geteventCmd), null as Array<String>?, null as String?) as Process
                val processStream = evdevProcess?.inputStream
                if (processStream == null) {
                    Log.e("GameMapper", "getevent stream is null. newProcess silently failed.")
                    TouchInjectionPlugin.emitGamepadButton("ERROR_SHIZUKU_SILENT_FAILURE", 0, 0f)
                    return@Thread
                }
                val reader = BufferedReader(InputStreamReader(processStream))
                var line: String? = null
                
                var lStickX = 0f
                var lStickY = 0f
                var rStickX = 0f
                var rStickY = 0f
                var l2Trigger = 0f
                var r2Trigger = 0f
                
                while (isListening && reader.readLine().also { line = it } != null) {
                    line?.let {
                        if (it.contains("EV_SYN")) {
                            if (it.contains("SYN_REPORT")) {
                                TouchInjectionPlugin.emitGamepadAxis(floatArrayOf(lStickX, lStickY, rStickX, rStickY, l2Trigger, r2Trigger))
                            }
                        } else if (it.contains("EV_KEY")) {
                            // Fix untuk BUG-H11: parsing adaptif untuk baris tanpa prefix device.
                            // Beberapa versi Android output getevent tanpa prefix device path,
                            // hanya 'EV_KEY BTN_A DOWN' dengan 3 part (bukan 4).
                            // Parsing lama skip baris dengan parts.size < 4.
                            // Parsing baru: cari index EV_KEY, lalu ambil 2 part berikutnya.
                            val parts = it.trim().split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                val isPrefixed = parts[0].startsWith("/dev/input/")
                                val evIdx = if (isPrefixed) 1 else 0
                                if (parts.size > evIdx + 2) {
                                    val btnRaw = parts[evIdx + 1]
                                    val stateStr = parts[evIdx + 2]
                                    val isDown = if (stateStr == "DOWN") 1 else 0
                                    
                                    val btnMap = mapEvdevToButton(btnRaw)
                                    if (btnMap != "UNKNOWN") {
                                        TouchInjectionPlugin.emitGamepadButton(btnMap, isDown, 1.0f)
                                    }
                                }
                            }
                        } else if (it.contains("EV_ABS")) {
                            // Fix untuk BUG-H11: parsing adaptif yang sama untuk EV_ABS.
                            val parts = it.trim().split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                val isPrefixed = parts[0].startsWith("/dev/input/")
                                val evIdx = if (isPrefixed) 1 else 0
                                if (parts.size > evIdx + 2) {
                                    val axisType = parts[evIdx + 1]
                                    val valueHex = parts[evIdx + 2]
                                try {
                                    val hexNum = valueHex.toLong(16)
                                    val rawVal = if (hexNum > 0x7FFFFFFF) (hexNum - 0x100000000L).toInt() else hexNum.toInt()
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
                                            val range = absRanges[axisType]
                                            val min = range?.first ?: -32768
                                            val max = range?.second ?: 32767
                                            val mid = min + (max - min) / 2f
                                            val half = (max - min) / 2f
                                            val normalizedVal = if (half > 0) (rawVal - mid) / half else 0f
                                            val finalVal = normalizedVal.coerceIn(-1f, 1f)
                                            when (axisType) {
                                                "ABS_X"  -> lStickX = finalVal
                                                "ABS_Y"  -> lStickY = finalVal
                                                "ABS_RX" -> rStickX = finalVal
                                                "ABS_RY" -> rStickY = finalVal
                                                "ABS_Z"  -> l2Trigger = finalVal
                                                "ABS_RZ" -> r2Trigger = finalVal
                                            }
                                        }
                                    }
                                } catch (e: NumberFormatException) { }
                            }
                        }
                    }
                }
                }
            } catch (e: Exception) {
                Log.e("GameMapper", "getevent loop failed", e)
                TouchInjectionPlugin.emitGamepadButton("ERROR_SHIZUKU_EXCEPTION", 0, 0f)
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Mapping evdev button code ke nama logis aplikasi.
     *
     * Fix untuk BUG-H12: tambah mapping untuk BTN_MODE (Home/PS), BTN_C, BTN_Z.
     * Fix untuk BUG-H13: hapus ABS_HAT0X/Y yang redundan (sudah di-handle di EV_ABS branch).
     *
     * Invariant:
     * - Setiap evdev code yang relevan dengan gamepad dipetakan ke nama logis.
     * - Return 'UNKNOWN' untuk code yang tidak dikenali.
     * - ABS_HAT0X/Y tidak ada di sini (di-handle di EV_ABS branch via DPAD_*).
     *
     * Kompleksitas: O(n) di mana n = jumlah case (sekitar 15). Acceptable untuk per-event.
     */
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

        // REC-03: Unregister InputDeviceListener untuk prevent leak.
        try {
            inputManager?.unregisterInputDeviceListener(this)
        } catch (e: Exception) {
            Log.w("GameMapper", "REC-03: Failed to unregister InputDeviceListener", e)
        }

        try {
            evdevProcess?.destroy()
        } catch (e: Exception) {}
    }
}
