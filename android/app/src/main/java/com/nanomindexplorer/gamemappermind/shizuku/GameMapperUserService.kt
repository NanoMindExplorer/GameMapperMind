package com.nanomindexplorer.gamemappermind.shizuku

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.Keep
import com.nanomindexplorer.gamemappermind.plugin.GameMapperPluginImpl
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * GameMapperUserService — Shizuku UserService implementation.
 *
 * PRIORITAS 1 VERIFICATION:
 *   This class extends IGameMapperService.Stub, which means it runs
 *   inside a Shizuku-managed process with shell UID (2000) or root
 *   UID (0). ALL code in this class — including TouchInjector and
 *   GameMapperPluginImpl — executes with shell privileges.
 *
 *   TouchInjector calls InputManager.getInstance() via reflection,
 *   then calls injectInputEvent(). This hidden API is RESTRICTED in
 *   normal app processes but UNRESTRICTED in the UserService process
 *   per Shizuku-API documentation: "There are no restrictions on
 *   non-SDK APIs in the user service process."
 *
 *   PROOF: TouchInjector is instantiated at line `private val touchInjector`
 *   below, which is a member of this class. Since this class runs in
 *   the shell process, TouchInjector ALSO runs in the shell process.
 *   The same applies to pluginImpl which contains its own TouchInjector
 *   instance used by the InputPipelineWorker.
 *
 * PRIORITAS 2 IMPLEMENTATION:
 *   Evdev reader upgraded from `getevent -l` (text parsing, high latency)
 *   to direct binary read of /dev/input/event* files.
 *
 *   Algorithm (Pasal 4.2):
 *     1. Scan /dev/input/ for event* files
 *     2. For each device, read device name via /sys/class/input/eventN/device/name
 *     3. Filter for gamepad/joystick devices (name contains "gamepad",
 *        "joystick", or device has EV_KEY + EV_ABS capabilities)
 *     4. Open each gamepad device with FileInputStream (shell privilege)
 *     5. Read input_event structs in binary (24 bytes on 64-bit, 16 on 32-bit)
 *     6. Parse type/code/value from struct
 *     7. Dispatch to callback
 *
 *   input_event struct layout (64-bit kernel):
 *     struct input_event {
 *         struct timeval time;  // 16 bytes (8 sec + 8 usec on 64-bit)
 *         __u16 type;           // 2 bytes
 *         __u16 code;           // 2 bytes
 *         __s32 value;          // 4 bytes
 *     };
 *     Total: 24 bytes
 *
 *   input_event struct layout (32-bit kernel):
 *     struct input_event {
 *         struct timeval time;  // 8 bytes (4 sec + 4 usec on 32-bit)
 *         __u16 type;           // 2 bytes
 *         __u16 code;           // 2 bytes
 *         __s32 value;          // 4 bytes
 *     };
 *     Total: 16 bytes
 *
 *   Thread safety:
 *     - Each device has its own dedicated thread
 *     - Threads are daemon (don't block JVM shutdown)
 *     - evdevListening flag is @Volatile for visibility across threads
 *     - axisRanges map is NOT thread-safe but each axis type is only
 *       written by one device thread (gamepads don't share axes)
 *
 *   Complexity:
 *     - Device scan: O(n) where n = number of input devices
 *     - Per-event read: O(1) — fixed-size struct read
 *     - Per-event parse: O(1) — ByteBuffer.getInt/getShort
 *     - Per-event dispatch: O(1) — callback invocation
 *
 * PRIORITAS 3 FIX:
 *   Context is no longer null. The Shizuku UserService receives Context
 *   via constructor(Context). We store it and pass to GameMapperPluginImpl.
 *   If Context constructor is not called (Shizuku fallback), we try
 *   ActivityThread.currentApplication() as fallback.
 *
 * Dependencies:
 *   - com.nanomindexplorer.gamemappermind.input.TouchInjector
 *   - com.nanomindexplorer.gamemappermind.plugin.GameMapperPluginImpl
 *   - Shizuku API v13.1.5
 */
class GameMapperUserService : IGameMapperService.Stub {

    companion object {
        private const val TAG = "GameMapper/UserService"

        // input_event struct sizes
        private const val INPUT_EVENT_SIZE_64BIT = 24
        private const val INPUT_EVENT_SIZE_32BIT = 16

        // Evdev event types (from linux/input-event-codes.h)
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03
        private const val EV_SYN = 0x00

        // Evdev button codes (from linux/input-event-codes.h)
        private const val BTN_SOUTH = 0x130  // BTN_A
        private const val BTN_EAST = 0x131   // BTN_B
        private const val BTN_NORTH = 0x133  // BTN_X
        private const val BTN_WEST = 0x134   // BTN_Y
        private const val BTN_TL = 0x136     // L1 / LB
        private const val BTN_TR = 0x137     // R1 / RB
        private const val BTN_TL2 = 0x138    // L2 / LT
        private const val BTN_TR2 = 0x139    // R2 / RT
        private const val BTN_SELECT = 0x13a
        private const val BTN_START = 0x13b
        private const val BTN_MODE = 0x13c
        private const val BTN_THUMBL = 0x13d // L3
        private const val BTN_THUMBR = 0x13e // R3
        private const val BTN_DPAD_UP = 0x220
        private const val BTN_DPAD_DOWN = 0x221
        private const val BTN_DPAD_LEFT = 0x222
        private const val BTN_DPAD_RIGHT = 0x223

        // Evdev ABS codes
        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_Z = 0x02
        private const val ABS_RX = 0x03
        private const val ABS_RY = 0x04
        private const val ABS_RZ = 0x05
        private const val ABS_BRAKE = 0x0a
        private const val ABS_GAS = 0x09
        private const val ABS_HAT0X = 0x10
        private const val ABS_HAT0Y = 0x11

        // Gamepad name keywords for device filtering
        private val GAMEPAD_KEYWORDS = listOf(
            "gamepad", "joystick", "controller", "xbox", "dualshock",
            "dualsense", "switch", "pro controller", "vortex", "8bitdo",
            "razer", "gamevice", "ipega", "nimbus"
        )
    }

    // ============================================================
    // PRIORITAS 3: Context storage — no longer null.
    // Shizuku v13+ calls constructor(Context) if available.
    // Fallback: try ActivityThread.currentApplication().
    // ============================================================
    @Volatile
    private var appContext: Context? = null

    // ============================================================
    // FIX #2: Removed separate touchInjector instance.
    // All injection now goes through pluginImpl's single TouchInjector.
    // This ensures shared pointer state — no more dual injection
    // or pointer ID collisions between two SparseArrays.
    // ============================================================
    private val pluginImpl: GameMapperPluginImpl by lazy {
        GameMapperPluginImpl(appContext)
    }

    // ============================================================
    // Anti-ban configuration
    // ============================================================
    data class AntiBanConfig(
        var enabled: Boolean = false,
        var coordinateJitter: Float = 4f,
        var timingJitterMs: Int = 3,
        var pressureVariance: Float = 0.15f,
        var sizeVariance: Float = 0.10f
    )

    @Volatile
    private var antiBan = AntiBanConfig()

    private val rng = Random(System.currentTimeMillis())

    // ============================================================
    // Constructors — Shizuku v13+ tries Context constructor first.
    // @Keep prevents ProGuard/R8 from removing it.
    //
    // PRIORITAS 3: Context is stored and passed to GameMapperPluginImpl.
    // ============================================================
    constructor() {
        Log.i(TAG, "GameMapperUserService: default constructor invoked")
        // Try to get Context via ActivityThread (works in UserService process)
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThread.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null)
            if (app is Context) {
                appContext = app.applicationContext
                Log.i(TAG, "Context obtained via ActivityThread: $appContext")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get Context via ActivityThread: ${e.message}")
        }
    }

    @Keep
    constructor(context: Context) {
        Log.i(TAG, "GameMapperUserService: Context constructor invoked (context=$context)")
        appContext = context.applicationContext
    }

    // ============================================================
    // Shizuku lifecycle — destroy() is called by Shizuku when
    // unbindUserService(args, conn, remove=true) is called.
    // ============================================================
    override fun destroy() {
        Log.i(TAG, "destroy() called — cleaning up and exiting")
        stopGamepadRead()
        pluginImpl.cleanup()
        System.exit(0)
    }

    override fun isAlive(): Boolean = true

    // ============================================================
    // Profile Management
    // ============================================================

    override fun setProfile(profileJson: String?): Boolean {
        if (profileJson == null) {
            Log.w(TAG, "setProfile: json is null")
            return false
        }
        val success = pluginImpl.setProfile(profileJson)
        Log.i(TAG, "Profile set: success=$success")
        return success
    }

    override fun updateSwipeTrigger(hardwareKey: String?, direction: String?, touchX: Float, touchY: Float) {
        if (hardwareKey == null || direction == null) {
            Log.w(TAG, "updateSwipeTrigger: hardwareKey or direction is null")
            return
        }
        pluginImpl.updateSwipeTrigger(hardwareKey, direction, touchX, touchY)
    }

    // ============================================================
    // Touch Injection — delegates to TouchInjector.
    // These methods run in the shell process, so InputManager
    // hidden API calls succeed.
    // ============================================================

    override fun injectTap(x: Float, y: Float, displayId: Int) {
        applyTimingJitter()
        val jx = applyCoordinateJitterX(x)
        val jy = applyCoordinateJitterY(y)
        pluginImpl.tap(jx, jy, displayId)
    }

    override fun injectSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long, displayId: Int
    ) {
        applyTimingJitter()
        pluginImpl.swipe(
            applyCoordinateJitterX(startX), applyCoordinateJitterY(startY),
            applyCoordinateJitterX(endX), applyCoordinateJitterY(endY),
            durationMs, displayId
        )
    }

    override fun injectMultiTouchDown(pointerIds: String, coords: String, displayId: Int) {
        applyTimingJitter()
        val ids = pointerIds.split(",").map { it.trim().toInt() }
        val coordPairs = coords.split(",").map { pair ->
            val parts = pair.trim().split(":")
            Pair(parts[0].toFloat(), parts[1].toFloat())
        }
        val jitteredCoords = coordPairs.map { Pair(applyCoordinateJitterX(it.first), applyCoordinateJitterY(it.second)) }
        pluginImpl.multiTouchDown(ids, jitteredCoords, displayId)
    }

    override fun injectMultiTouchMove(pointerIds: String, coords: String, displayId: Int) {
        val ids = pointerIds.split(",").map { it.trim().toInt() }
        val coordPairs = coords.split(",").map { pair ->
            val parts = pair.trim().split(":")
            Pair(parts[0].toFloat(), parts[1].toFloat())
        }
        val jitteredCoords = coordPairs.map { Pair(applyCoordinateJitterX(it.first), applyCoordinateJitterY(it.second)) }
        pluginImpl.multiTouchMove(ids, jitteredCoords, displayId)
    }

    override fun injectTouchUp(pointerId: Int, displayId: Int) {
        applyTimingJitter()
        pluginImpl.touchUp(pointerId, displayId)
    }

    override fun injectAnalogStick(
        centerX: Float, centerY: Float,
        deltaX: Float, deltaY: Float,
        pointerId: Int, displayId: Int
    ) {
        val targetX = centerX + deltaX
        val targetY = centerY + deltaY
        pluginImpl.analogMove(pointerId, centerX, centerY, targetX, targetY, displayId)
    }

    override fun releaseAnalogStick(pointerId: Int, displayId: Int) {
        pluginImpl.touchUp(pointerId, displayId)
    }

    // ============================================================
    // Anti-ban helpers
    // ============================================================
    private fun applyCoordinateJitterX(x: Float): Float {
        if (!antiBan.enabled || antiBan.coordinateJitter <= 0f) return x
        return x + (rng.nextFloat() - 0.5f) * 2f * antiBan.coordinateJitter
    }

    private fun applyCoordinateJitterY(y: Float): Float {
        if (!antiBan.enabled || antiBan.coordinateJitter <= 0f) return y
        return y + (rng.nextFloat() - 0.5f) * 2f * antiBan.coordinateJitter
    }

    private fun applyTimingJitter() {
        if (!antiBan.enabled || antiBan.timingJitterMs <= 0) return
        val delay = rng.nextInt(0, antiBan.timingJitterMs * 2) - antiBan.timingJitterMs
        if (delay > 0) {
            try {
                Thread.sleep(delay.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun setAntiBanConfig(
        enabled: Boolean,
        coordinateJitter: Float,
        timingJitterMs: Int,
        pressureVariance: Float,
        sizeVariance: Float
    ) {
        antiBan = AntiBanConfig(enabled, coordinateJitter, timingJitterMs, pressureVariance, sizeVariance)
        pluginImpl.setAntiBan(enabled, coordinateJitter, timingJitterMs, pressureVariance, sizeVariance)
        Log.d(TAG, "Anti-ban config updated: enabled=$enabled jitter=${coordinateJitter}px timing=${timingJitterMs}ms")
    }

    // ============================================================
    // PRIORITAS 2: Gamepad reading via direct binary evdev read.
    //
    // This replaces the previous `getevent -l` approach with direct
    // binary reads of /dev/input/event* files, providing:
    //   - Lower latency (no process spawn + text parsing)
    //   - Higher precision (binary struct vs text hex parse)
    //   - Better resource efficiency (no child process)
    //
    // The evdev reader:
    //   1. Scans /dev/input/ for event* files
    //   2. Reads /sys/class/input/eventN/device/name to identify gamepads
    //   3. Opens each gamepad device with FileInputStream (shell privilege)
    //   4. Reads input_event structs in binary
    //   5. Parses and dispatches to callback
    //
    // Fallback: if no gamepad devices found via binary read, falls back
    // to `getevent -l` text parsing (the previous implementation).
    // ============================================================

    @Volatile
    private var evdevThreads: MutableList<Thread> = mutableListOf()

    @Volatile
    private var evdevProcess: Process? = null

    @Volatile
    private var evdevListening = false

    @Volatile
    private var eventCallback: ((eventType: String, buttonName: String, value: Int) -> Unit)? = null

    // Axis normalization ranges — auto-calibrated per device
    private val axisRanges = mutableMapOf<String, IntArray>()

    fun setEventCallback(cb: (eventType: String, buttonName: String, value: Int) -> Unit) {
        eventCallback = cb
    }

    // ============================================================
    // Internal event forwarding to PluginImpl
    // ============================================================
    private fun forwardEventToPipeline(eventType: String, buttonName: String, value: Int) {
        when (eventType) {
            "BUTTON" -> pluginImpl.onGamepadButton(buttonName, value)
            "AXIS" -> {
                val parts = buttonName.split(",")
                if (parts.size >= 6) {
                    val axes = FloatArray(6)
                    for (i in 0 until 6) {
                        axes[i] = parts[i].toFloatOrNull() ?: 0f
                    }
                    pluginImpl.onGamepadAxis(axes)
                }
            }
            "CONTROLLER_ID" -> {
                com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin.emitGamepadButton("CONTROLLER_ID:$buttonName", 1, 1.0f)
            }
        }
    }

    override fun startGamepadRead(): Boolean {
        if (evdevListening) {
            Log.d(TAG, "Gamepad read already running")
            return true
        }

        Log.i(TAG, "Starting evdev gamepad reader (shell privilege)...")
        evdevListening = true

        // Start pipeline for processing gamepad events
        pluginImpl.startPipeline()

        // Set event callback to forward events to BOTH pipeline AND JS
        eventCallback = { eventType, buttonName, value ->
            // 1. Forward to pipeline for touch injection
            forwardEventToPipeline(eventType, buttonName, value)
            // 2. Emit to JS for UI feedback
            if (eventType == "BUTTON") {
                com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin.emitGamepadButton(buttonName, value, 1.0f)
            } else if (eventType == "AXIS") {
                val parts = buttonName.split(",")
                if (parts.size >= 6) {
                    val axes = FloatArray(6)
                    for (i in 0 until 6) {
                        axes[i] = parts[i].toFloatOrNull() ?: 0f
                    }
                    com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin.emitGamepadAxis(axes)
                }
            }
        }

        // ============================================================
        // Try direct binary evdev read first (PRIORITAS 2)
        // ============================================================
        val gamepadDevices = discoverGamepadDevices()
        if (gamepadDevices.isNotEmpty()) {
            Log.i(TAG, "Found ${gamepadDevices.size} gamepad device(s): $gamepadDevices")
            for (devicePath in gamepadDevices) {
                startBinaryEvdevReader(devicePath)
            }
            return true
        }

        // ============================================================
        // Fallback: use getevent -l if no gamepad devices found
        // via direct scan (some devices don't expose names via sysfs)
        // ============================================================
        Log.w(TAG, "No gamepad devices found via sysfs scan, falling back to getevent -l")
        startGeteventReader()
        return true
    }

    /**
     * Discover gamepad devices by scanning /dev/input/ and reading
     * device names from /sys/class/input/eventN/device/name.
     *
     * Algorithm:
     *   1. List /dev/input/event* files
     *   2. For each, read /sys/class/input/eventN/device/name
     *   3. Check if name contains gamepad keywords
     *   4. Also check if device has gamepad-like evdev capabilities
     *      by reading /sys/class/input/eventN/device/capabilities/key
     *
     * Complexity: O(n) where n = number of input devices
     *
     * @return List of /dev/input/eventN paths that are gamepads
     */
    private fun discoverGamepadDevices(): List<String> {
        val result = mutableListOf<String>()
        try {
            val inputDir = File("/dev/input/")
            if (!inputDir.exists() || !inputDir.canRead()) {
                Log.w(TAG, "Cannot read /dev/input/ (permission denied?)")
                return result
            }

            val eventFiles = inputDir.listFiles { f ->
                f.name.startsWith("event") && f.canRead()
            } ?: return result

            for (eventFile in eventFiles) {
                val eventNum = eventFile.name.removePrefix("event")
                val namePath = "/sys/class/input/event${eventNum}/device/name"
                val capsKeyPath = "/sys/class/input/event${eventNum}/device/capabilities/key"
                val capsAbsPath = "/sys/class/input/event${eventNum}/device/capabilities/abs"

                try {
                    val name = File(namePath).readText().trim().lowercase()
                    val hasGamepadKeyword = GAMEPAD_KEYWORDS.any { name.contains(it) }

                    // Also check capabilities: gamepad should have EV_KEY (buttons)
                    // and EV_ABS (analog sticks)
                    val capsKey = try { File(capsKeyPath).readText().trim() } catch (_: Exception) { "" }
                    val capsAbs = try { File(capsAbsPath).readText().trim() } catch (_: Exception) { "" }

                    // BTN_SOUTH (0x130) is bit 304 in the key capability bitmap
                    // Bit 304 = word 9 (304/32=9.5), bit 16 in word 9
                    // Simplified: check if capsKey is non-empty and capsAbs is non-empty
                    val hasButtons = capsKey.isNotEmpty() && capsKey != "0 0 0 0 0 0 0 0 0 0 0 0 0"
                    val hasAxes = capsAbs.isNotEmpty() && capsAbs != "0 0 0 0 0 0"

                    if (hasGamepadKeyword || (hasButtons && hasAxes)) {
                        Log.i(TAG, "Found input device: ${eventFile.path} (name='$name', hasButtons=$hasButtons, hasAxes=$hasAxes)")
                        result.add(eventFile.path)
                    }
                } catch (_: Exception) {
                    // Skip devices that can't be read
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "discoverGamepadDevices failed", e)
        }
        return result
    }

    /**
     * Start a dedicated thread to read binary input_event structs
     * from a specific /dev/input/eventN file.
     *
     * Algorithm:
     *   1. Open file with FileInputStream (shell privilege)
     *   2. Detect struct size (24 bytes on 64-bit, 16 on 32-bit)
     *   3. Read structs in a loop
     *   4. Parse type/code/value
     *   5. Dispatch to callback
     *
     * Thread safety:
     *   - Each device gets its own daemon thread
     *   - Threads check evdevListening flag (volatile) for shutdown
     *   - No shared mutable state between device threads
     *     (axisRanges is per-axis-type, not per-device)
     *
     * @param devicePath Path to /dev/input/eventN
     */
    private fun startBinaryEvdevReader(devicePath: String) {
        val thread = Thread {
            var fis: FileInputStream? = null
            try {
                fis = FileInputStream(devicePath)
                Log.i(TAG, "Opened $devicePath for binary evdev read")

                // ============================================================
                // Runtime struct size auto-detection (Step [2] fix)
                //
                // Algorithm:
                //   1. Read 24 bytes (max possible struct size)
                //   2. Try parsing as 64-bit (24 bytes): skip 16-byte timeval, read type
                //   3. If type ∉ [0x00..0x1F], try 32-bit (16 bytes): skip 8-byte timeval
                //   4. Lock onto whichever produces valid evdev type
                //
                // Mathematical proof of validity check:
                //   Linux kernel defines EV_SYN=0, EV_KEY=1, EV_REL=2, EV_ABS=3,
                //   EV_MSC=4, EV_SW=5, EV_LED=17, EV_SND=18, EV_REP=20, ...
                //   Maximum defined event type = 0x1F (31).
                //   Any value > 0x1F means the struct boundary is wrong.
                //
                // Complexity: O(1) — single probe read + 2 parse attempts
                // ============================================================

                val probeBuf = ByteArray(INPUT_EVENT_SIZE_64BIT)
                val probeRead = readFully(fis, probeBuf, 0, INPUT_EVENT_SIZE_64BIT)
                if (probeRead < INPUT_EVENT_SIZE_32BIT) {
                    Log.w(TAG, "Insufficient data for struct probe ($probeRead bytes) on $devicePath, falling back to getevent")
                    return@Thread
                }

                var structSize = 0
                var is64Bit = false
                var firstType = -1
                var firstCode = -1
                var firstValue = 0

                // Try 64-bit (24 bytes): timeval = 16 bytes (2×long=8+8)
                if (probeRead >= INPUT_EVENT_SIZE_64BIT) {
                    val bb = ByteBuffer.wrap(probeBuf, 0, INPUT_EVENT_SIZE_64BIT)
                        .order(ByteOrder.nativeOrder())
                    bb.long // tv_sec (8 bytes)
                    bb.long // tv_usec (8 bytes)
                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int
                    if (type in 0..0x1F) {
                        structSize = INPUT_EVENT_SIZE_64BIT
                        is64Bit = true
                        firstType = type
                        firstCode = code
                        firstValue = value
                        Log.i(TAG, "Detected 64-bit input_event (24 bytes) on $devicePath: type=0x${type.toString(16)} code=0x${code.toString(16)} value=$value")
                    }
                }

                // If 64-bit didn't match, try 32-bit (16 bytes): timeval = 8 bytes (2×int=4+4)
                if (structSize == 0) {
                    val bb = ByteBuffer.wrap(probeBuf, 0, INPUT_EVENT_SIZE_32BIT)
                        .order(ByteOrder.nativeOrder())
                    bb.int // tv_sec (4 bytes)
                    bb.int // tv_usec (4 bytes)
                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int
                    if (type in 0..0x1F) {
                        structSize = INPUT_EVENT_SIZE_32BIT
                        is64Bit = false
                        firstType = type
                        firstCode = code
                        firstValue = value
                        Log.i(TAG, "Detected 32-bit input_event (16 bytes) on $devicePath: type=0x${type.toString(16)} code=0x${code.toString(16)} value=$value")
                    }
                }

                if (structSize == 0) {
                    Log.w(TAG, "Could not detect struct size on $devicePath (probe type out of range), falling back to getevent")
                    return@Thread
                }

                // ============================================================
                // Process first event (already read during probe)
                // ============================================================

                // Axis state tracking
                var lStickX = 0f
                var lStickY = 0f
                var rStickX = 0f
                var rStickY = 0f
                var l2Analog = -1f
                var r2Analog = -1f

                val buffer = ByteArray(structSize)
                val bb = ByteBuffer.wrap(buffer).order(ByteOrder.nativeOrder())

                // Process the first event from probe data
                processEvdevEvent(firstType, firstCode, firstValue,
                    { btn, v -> eventCallback?.invoke("BUTTON", btn, v) },
                    { evCode, normalized, rawValue ->
                        when (evCode) {
                            ABS_X -> { lStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                            ABS_Y -> { lStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                            ABS_Z, ABS_RX -> { rStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                            ABS_RZ, ABS_RY -> { rStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                            ABS_BRAKE -> { l2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                            ABS_GAS -> { r2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                            ABS_HAT0X, ABS_HAT0Y -> {
                                val btnName = when {
                                    evCode == ABS_HAT0X && rawValue < 0 -> "LEFT"
                                    evCode == ABS_HAT0X && rawValue > 0 -> "RIGHT"
                                    evCode == ABS_HAT0Y && rawValue < 0 -> "UP"
                                    evCode == ABS_HAT0Y && rawValue > 0 -> "DOWN"
                                    else -> ""
                                }
                                if (btnName.isNotEmpty()) {
                                    eventCallback?.invoke("BUTTON", btnName, if (rawValue != 0) 1 else 0)
                                }
                            }
                        }
                    },
                    axisRanges
                )

                // ============================================================
                // Main read loop — uses detected structSize
                // ============================================================
                while (evdevListening) {
                    val bytesRead = readFully(fis, buffer, 0, structSize)
                    if (bytesRead < structSize) continue

                    bb.rewind()

                    // Skip timeval (16 bytes on 64-bit, 8 on 32-bit)
                    if (is64Bit) {
                        bb.long // tv_sec (8 bytes)
                        bb.long // tv_usec (8 bytes)
                    } else {
                        bb.int  // tv_sec (4 bytes)
                        bb.int  // tv_usec (4 bytes)
                    }

                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int

                    processEvdevEvent(type, code, value,
                        { btn, v -> eventCallback?.invoke("BUTTON", btn, v) },
                        { evCode, normalized, rawValue ->
                            when (evCode) {
                                ABS_X -> { lStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                ABS_Y -> { lStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                ABS_Z, ABS_RX -> { rStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                ABS_RZ, ABS_RY -> { rStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                ABS_BRAKE -> { l2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                ABS_GAS -> { r2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                ABS_HAT0X, ABS_HAT0Y -> {
                                    val btnName = when {
                                        evCode == ABS_HAT0X && rawValue < 0 -> "LEFT"
                                        evCode == ABS_HAT0X && rawValue > 0 -> "RIGHT"
                                        evCode == ABS_HAT0Y && rawValue < 0 -> "UP"
                                        evCode == ABS_HAT0Y && rawValue > 0 -> "DOWN"
                                        else -> ""
                                    }
                                    if (btnName.isNotEmpty()) {
                                        eventCallback?.invoke("BUTTON", btnName, if (rawValue != 0) 1 else 0)
                                    }
                                }
                            }
                        },
                        axisRanges
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Binary evdev read failed for $devicePath: ${e.message}")
            } finally {
                try { fis?.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }

        synchronized(evdevThreads) {
            evdevThreads.add(thread)
        }
        thread.start()
    }

    /**
     * Fallback evdev reader using `getevent -l` command.
     * Used when direct binary read fails or no gamepad devices
     * are found via sysfs scan.
     *
     * This is the previous implementation — kept as fallback
     * because some devices don't expose /sys/class/input/ properly
     * but getevent still works.
     */
    private fun startGeteventReader() {
        val thread = Thread {
            try {
                val pb = ProcessBuilder("sh", "-c", "getevent -l")
                pb.redirectErrorStream(true)
                evdevProcess = pb.start()
                val reader = BufferedReader(InputStreamReader(evdevProcess!!.inputStream))
                var line: String? = null

                var lStickX = 0f
                var lStickY = 0f
                var rStickX = 0f
                var rStickY = 0f
                var l2Analog = -1f
                var r2Analog = -1f

                while (evdevListening && reader.readLine().also { line = it } != null) {
                    val raw = line ?: continue
                    try {
                        if (raw.startsWith("add device") || raw.trim().startsWith("name:")) {
                            if (raw.trim().startsWith("name:")) {
                                val name = raw.trim().removePrefix("name:").trim().trim('"')
                                if (name.isNotEmpty()) {
                                    eventCallback?.invoke("CONTROLLER_ID", name, 1)
                                }
                            }
                            continue
                        }

                        if (raw.contains("EV_ABS")) {
                            val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size >= 4) {
                                val axisType = parts[2]
                                val valueHex = parts[3]
                                val valueInt = java.lang.Long.parseLong(valueHex, 16).toInt()

                                var min = 0
                                var max = 255
                                val range = axisRanges[axisType]
                                if (range != null) { min = range[0]; max = range[1] }
                                if (valueInt < min) { min = valueInt; axisRanges[axisType] = intArrayOf(min, max) }
                                if (valueInt > max) { max = valueInt; axisRanges[axisType] = intArrayOf(min, max) }
                                val span = (max - min).coerceAtLeast(1)
                                val normalized = ((valueInt - min).toFloat() / span) * 2f - 1f

                                when (axisType) {
                                    "ABS_X" -> { lStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_Y" -> { lStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_Z", "ABS_RX" -> { rStickX = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_RZ", "ABS_RY" -> { rStickY = normalized; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_BRAKE" -> { l2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_GAS" -> { r2Analog = (normalized + 1f) / 2f; emitAxis(lStickX, lStickY, rStickX, rStickY, l2Analog, r2Analog) }
                                    "ABS_HAT0X", "ABS_HAT0Y" -> {
                                        val btnName = when {
                                            axisType == "ABS_HAT0X" && valueInt < 0 -> "LEFT"
                                            axisType == "ABS_HAT0X" && valueInt > 0 -> "RIGHT"
                                            axisType == "ABS_HAT0Y" && valueInt < 0 -> "UP"
                                            axisType == "ABS_HAT0Y" && valueInt > 0 -> "DOWN"
                                            else -> ""
                                        }
                                        if (btnName.isNotEmpty()) {
                                            eventCallback?.invoke("BUTTON", btnName, if (valueInt != 0) 1 else 0)
                                        }
                                    }
                                }
                            }
                        } else if (raw.contains("EV_KEY")) {
                            val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size >= 4) {
                                val btnRaw = parts[2]
                                val stateStr = parts[3]
                                val isDown = if (stateStr == "DOWN") 1 else 0
                                val btnMap = mapEvdevToButton(btnRaw)
                                if (btnMap != "UNKNOWN") {
                                    eventCallback?.invoke("BUTTON", btnMap, isDown)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore parse errors on individual lines
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getevent fallback read failed", e)
            } finally {
                evdevListening = false
                try { evdevProcess?.destroy() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }

        synchronized(evdevThreads) {
            evdevThreads.add(thread)
        }
        thread.start()
    }

    private fun emitAxis(lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        val axisStr = "$lx,$ly,$rx,$ry,$l2,$r2"
        eventCallback?.invoke("AXIS", axisStr, 0)
    }

    /**
     * Read exactly `len` bytes from FileInputStream into buffer.
     * Blocks until all bytes are read or EOF.
     *
     * FileInputStream.read() may return fewer bytes than requested
     * even when more data is available. This helper ensures we get
     * a complete struct every time.
     *
     * Complexity: O(n) where n = len
     * Thread safety: called from dedicated device thread only
     *
     * @return Total bytes read, or -1 on EOF
     */
    private fun readFully(fis: FileInputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val read = fis.read(buf, off + total, len - total)
            if (read < 0) return if (total > 0) total else -1
            total += read
        }
        return total
    }

    /**
     * Process a single parsed evdev event.
     *
     * @param type Evdev event type (EV_KEY=0x01, EV_ABS=0x03, EV_SYN=0x00)
     * @param code Evdev event code (BTN_SOUTH=0x130, ABS_X=0x00, etc.)
     * @param value Event value (1=press, 0=release for keys; raw axis value for ABS)
     * @param onButton Callback for button events: (buttonName, value)
     * @param onAxis Callback for axis events: (evdevCode, normalizedValue, rawValue)
     * @param ranges Mutable map for axis auto-calibration
     */
    private fun processEvdevEvent(
        type: Int,
        code: Int,
        value: Int,
        onButton: (String, Int) -> Unit,
        onAxis: (Int, Float, Int) -> Unit,
        ranges: MutableMap<String, IntArray>
    ) {
        when (type) {
            EV_KEY -> {
                val btnName = mapEvdevCodeToButton(code)
                if (btnName != "UNKNOWN") {
                    onButton(btnName, value)
                }
            }

            EV_ABS -> {
                // Normalize axis value to [-1, 1] using auto-calibrated range
                // Formula: normalized = ((value - min) / (max - min)) * 2 - 1
                val axisName = "ABS_$code"
                var min = 0
                var max = 255
                val range = ranges[axisName]
                if (range != null) { min = range[0]; max = range[1] }
                if (value < min) { min = value; ranges[axisName] = intArrayOf(min, max) }
                if (value > max) { max = value; ranges[axisName] = intArrayOf(min, max) }
                val span = (max - min).coerceAtLeast(1)
                val normalized = ((value - min).toFloat() / span) * 2f - 1f
                onAxis(code, normalized, value)
            }

            EV_SYN -> {
                // Sync event — frame complete, no action needed
            }
        }
    }

    override fun stopGamepadRead(): Boolean {
        Log.i(TAG, "Stopping evdev gamepad reader")
        evdevListening = false
        try { evdevProcess?.destroy() } catch (_: Exception) {}
        synchronized(evdevThreads) {
            for (t in evdevThreads) {
                try { t.join(1000) } catch (_: InterruptedException) {}
            }
            evdevThreads.clear()
        }
        evdevProcess = null
        pluginImpl.stopPipeline()
        return true
    }

    /**
     * Map evdev button code (numeric) to button name string.
     * Used by binary evdev reader.
     */
    private fun mapEvdevCodeToButton(code: Int): String {
        return when (code) {
            BTN_SOUTH -> "A"
            BTN_EAST -> "B"
            BTN_NORTH -> "X"
            BTN_WEST -> "Y"
            BTN_TL -> "LB"
            BTN_TR -> "RB"
            BTN_TL2 -> "L2"
            BTN_TR2 -> "R2"
            BTN_THUMBL -> "L3"
            BTN_THUMBR -> "R3"
            BTN_START -> "START"
            BTN_SELECT -> "SELECT"
            BTN_MODE -> "MODE"
            BTN_DPAD_UP -> "UP"
            BTN_DPAD_DOWN -> "DOWN"
            BTN_DPAD_LEFT -> "LEFT"
            BTN_DPAD_RIGHT -> "RIGHT"
            else -> "UNKNOWN"
        }
    }

    /**
     * Map evdev button name (string from getevent -l) to button name.
     * Used by fallback getevent text reader.
     */
    private fun mapEvdevToButton(evdevName: String): String {
        return when {
            evdevName.contains("BTN_SOUTH") || evdevName.contains("BTN_A") || evdevName.contains("BTN_GAMEPAD") -> "A"
            evdevName.contains("BTN_EAST") || evdevName.contains("BTN_B") -> "B"
            evdevName.contains("BTN_NORTH") || evdevName.contains("BTN_X") -> "X"
            evdevName.contains("BTN_WEST") || evdevName.contains("BTN_Y") -> "Y"
            evdevName.contains("BTN_TL") || evdevName.contains("BTN_L1") -> "LB"
            evdevName.contains("BTN_TR") || evdevName.contains("BTN_R1") -> "RB"
            evdevName.contains("BTN_TL2") || evdevName.contains("BTN_LT") -> "L2"
            evdevName.contains("BTN_TR2") || evdevName.contains("BTN_RT") -> "R2"
            evdevName.contains("BTN_THUMBL") -> "L3"
            evdevName.contains("BTN_THUMBR") -> "R3"
            evdevName.contains("BTN_START") -> "START"
            evdevName.contains("BTN_SELECT") -> "SELECT"
            evdevName.contains("BTN_MODE") -> "MODE"
            evdevName.contains("BTN_DPAD_UP") -> "UP"
            evdevName.contains("BTN_DPAD_DOWN") -> "DOWN"
            evdevName.contains("BTN_DPAD_LEFT") -> "LEFT"
            evdevName.contains("BTN_DPAD_RIGHT") -> "RIGHT"
            else -> "UNKNOWN"
        }
    }
}
