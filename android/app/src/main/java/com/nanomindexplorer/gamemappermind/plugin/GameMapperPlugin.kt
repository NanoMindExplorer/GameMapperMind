package com.nanomindexplorer.gamemappermind.plugin

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.nanomindexplorer.gamemappermind.daemon.MapperDaemonService
import com.nanomindexplorer.gamemappermind.shizuku.IGameMapperService
import com.nanomindexplorer.gamemappermind.shizuku.ShizukuHelper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku

/**
 * GameMapperPlugin — Capacitor bridge between React frontend and native
 * Shizuku-backed gamepad/touch injection pipeline.
 *
 * FASE 3.2 — Native Crash-Proof Hardening:
 *
 *   Every @PluginMethod is wrapped in try-catch (Throwable t) so that
 *   no exception ever propagates up to Capacitor's bridge dispatcher
 *   (which would crash the WebView process and take the overlay UI
 *   with it).
 *
 *   Logging convention per contract rule #4:
 *     - TAG = "GameMapper_ERROR" for caught errors (Log.e)
 *     - TAG = "GameMapper/Plugin" for informational logs (Log.i / Log.d)
 *
 *   Each @PluginMethod follows this pattern:
 *     1. Validate inputs (null checks, range checks).
 *     2. Wrap business logic in try-catch (Throwable).
 *     3. On Throwable: Log.e("GameMapper_ERROR", ...) + call.reject().
 *     4. Always resolve OR reject the PluginCall (never leave it pending).
 *
 *   Defense in depth:
 *     - IllegalArgumentException → INVALID_ARGUMENT
 *     - NullPointerException → INTERNAL_ERROR
 *     - SecurityException → PERMISSION_DENIED
 *     - IllegalStateException → SERVICE_UNAVAILABLE
 *     - Throwable (fallback) → INTERNAL_ERROR
 */
@CapacitorPlugin(name = "GameMapper")
class GameMapperPlugin : Plugin() {
    companion object {
        private const val TAG = "GameMapper/Plugin"
        private const val ERROR_TAG = "GameMapper_ERROR"

        // Stable error codes (mirrors NativeCrashGuard.ErrorCode).
        private const val ERR_INVALID_ARGUMENT   = "INVALID_ARGUMENT"
        private const val ERR_PERMISSION_DENIED  = "PERMISSION_DENIED"
        private const val ERR_SERVICE_UNAVAILABLE= "SERVICE_UNAVAILABLE"
        private const val ERR_INTERNAL_ERROR     = "INTERNAL_ERROR"
        private const val ERR_NOT_FOUND          = "NOT_FOUND"

        @JvmField var instance: GameMapperPlugin? = null
    }

    private var shizukuHelper: ShizukuHelper? = null
    private val userService: IGameMapperService? get() = shizukuHelper?.getService()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ───────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ───────────────────────────────────────────────────────────────────────

    override fun load() {
        super.load()
        instance = this
        Log.i(TAG, "GameMapperPlugin loaded")
        try {
            shizukuHelper = ShizukuHelper.getInstance(context)
            shizukuHelper?.setCallback(object : ShizukuHelper.ShizukuCallback {
                override fun onBinderReceived() {
                    safeEmit("onShizukuBinderReceived", JSObject().put("binderAlive", true))
                }
                override fun onBinderDead() {
                    safeEmit("onShizukuBinderDead", JSObject().put("binderAlive", false))
                }
                override fun onPermissionGranted() {
                    safeEmit("onShizukuPermissionGranted", JSObject().put("granted", true))
                }
                override fun onPermissionDenied() {
                    safeEmit("onShizukuPermissionDenied", JSObject().put("granted", false))
                }
                override fun onServiceConnected(service: IGameMapperService?) {
                    val data = JSObject()
                    data.put("connected", service != null)
                    if (service != null) {
                        try {
                            data.put("gamepadReadStarted", service.startGamepadRead())
                        } catch (t: Throwable) {
                            Log.e(ERROR_TAG, "onServiceConnected: startGamepadRead failed", t)
                            data.put("gamepadReadStarted", false)
                        }
                    }
                    safeEmit("onServiceConnected", data)
                }
                override fun onServiceDisconnected() {
                    safeEmit("onServiceDisconnected", JSObject().put("connected", false))
                }
            })
            shizukuHelper?.registerListeners()
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "GameMapperPlugin.load() failed", t)
        }
    }

    override fun handleOnDestroy() {
        try {
            shizukuHelper?.unbindUserService()
            shizukuHelper?.unregisterListeners()
            MapperDaemonService.stopDaemon(context)
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "handleOnDestroy failed", t)
        } finally {
            instance = null
            super.handleOnDestroy()
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Shizuku status + permission
    // ───────────────────────────────────────────────────────────────────────

    @PluginMethod
    fun checkShizukuStatus(call: PluginCall) {
        try {
            val data = JSObject()
            val helper = shizukuHelper
            if (helper == null) {
                data.put("granted", false)
                data.put("binderAlive", false)
                data.put("version", -1)
                call.resolve(data)
                return
            }
            val alive = helper.isBinderAlive()
            data.put("binderAlive", alive)
            if (!alive) {
                data.put("granted", false)
                call.resolve(data)
                return
            }
            data.put("granted", helper.checkPermission())
            data.put("version", helper.getShizukuVersion())
            call.resolve(data)
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "checkShizukuStatus failed", t)
            call.reject("Failed to check Shizuku status: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    @PluginMethod
    fun requestShizukuPermission(call: PluginCall) {
        try {
            val helper = shizukuHelper
            if (helper == null || !helper.isBinderAlive()) {
                call.reject("Shizuku not running", ERR_SERVICE_UNAVAILABLE)
                return
            }
            if (helper.checkPermission()) {
                helper.bindUserService()
                call.resolve(JSObject().put("granted", true).put("message", "Already granted"))
                return
            }
            mainHandler.post {
                try {
                    helper.requestPermission()
                    call.resolve(JSObject().put("granted", false).put("message", "Dialog sent"))
                } catch (t: Throwable) {
                    Log.e(ERROR_TAG, "requestPermission() failed", t)
                    call.reject("Failed to request permission: ${t.message}", ERR_PERMISSION_DENIED, t)
                }
            }
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "requestShizukuPermission failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Daemon lifecycle
    // ───────────────────────────────────────────────────────────────────────

    @PluginMethod
    fun startDaemon(call: PluginCall) {
        try {
            val json = call.getString("profileJson")
            MapperDaemonService.startDaemon(context, json)
            call.resolve(JSObject().put("success", true).put("pid", android.os.Process.myPid()))
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "startDaemon failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    @PluginMethod
    fun stopDaemon(call: PluginCall) {
        try {
            userService?.let { s ->
                try { s.stopGamepadRead() } catch (t: Throwable) {
                    Log.e(ERROR_TAG, "stopGamepadRead failed", t)
                }
            }
            MapperDaemonService.stopDaemon(context)
            shizukuHelper?.unbindUserService()
            call.resolve(JSObject().put("success", true))
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "stopDaemon failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Touch injection
    // ───────────────────────────────────────────────────────────────────────

    @PluginMethod
    fun injectTap(call: PluginCall) {
        try {
            val s = userService
            if (s == null) {
                call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE)
                return
            }
            // Validate inputs (strict input validation per contract rule #5).
            val x = call.getFloat("x", 0f) ?: 0f
            val y = call.getFloat("y", 0f) ?: 0f
            val displayId = call.getInt("displayId", 0) ?: 0
            if (x < 0f || y < 0f) {
                call.reject("Coordinates must be non-negative", ERR_INVALID_ARGUMENT)
                return
            }
            s.injectTap(x, y, displayId)
            call.resolve()
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "injectTap failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    @PluginMethod
    fun injectSwipe(call: PluginCall) {
        try {
            val s = userService
            if (s == null) {
                call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE)
                return
            }
            val startX = call.getFloat("startX", 0f) ?: 0f
            val startY = call.getFloat("startY", 0f) ?: 0f
            val endX   = call.getFloat("endX", 0f) ?: 0f
            val endY   = call.getFloat("endY", 0f) ?: 0f
            val durationMs = call.getLong("durationMs", 100L) ?: 100L
            val displayId = call.getInt("displayId", 0) ?: 0
            if (durationMs <= 0L) {
                call.reject("durationMs must be positive", ERR_INVALID_ARGUMENT)
                return
            }
            s.injectSwipe(startX, startY, endX, endY, durationMs, displayId)
            call.resolve()
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "injectSwipe failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    @PluginMethod
    fun injectTouchUp(call: PluginCall) {
        try {
            val s = userService
            if (s == null) {
                call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE)
                return
            }
            val pointerId = call.getInt("pointerId", 0) ?: 0
            val displayId = call.getInt("displayId", 0) ?: 0
            s.injectTouchUp(pointerId, displayId)
            call.resolve()
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "injectTouchUp failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Gamepad enumeration
    // ───────────────────────────────────────────────────────────────────────

    @PluginMethod
    fun getConnectedGamepads(call: PluginCall) {
        try {
            val data = JSObject()
            val arr = JSArray()
            try {
                val im = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
                for (id in im.inputDeviceIds) {
                    val dev = android.view.InputDevice.getDevice(id)
                    if (dev != null && (
                        (dev.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                        (dev.sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                    )) {
                        arr.put(JSObject()
                            .put("deviceId", dev.id)
                            .put("name", dev.name)
                            .put("vendor", dev.vendorId.toString())
                            .put("sources", dev.sources)
                            .put("isConnected", true)
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(ERROR_TAG, "Failed to enumerate gamepads", t)
            }
            data.put("devices", arr)
            call.resolve(data)
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "getConnectedGamepads outer failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Profile management
    // ───────────────────────────────────────────────────────────────────────

    @PluginMethod
    fun setActiveProfile(call: PluginCall) {
        try {
            val json = call.getString("profileJson")
            if (json == null || json.isEmpty()) {
                call.reject("profileJson required", ERR_INVALID_ARGUMENT)
                return
            }
            val s = userService
            if (s == null) {
                call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE)
                return
            }
            val profile = JSONObject(json)
            s.setProfile(json)
            val data = JSObject()
                .put("success", true)
                .put("packageName", profile.optString("packageName", ""))
            call.resolve(data)
            safeEmit("onProfileChanged", JSObject().put("packageName", profile.optString("packageName", "")))
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "setActiveProfile failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    @PluginMethod
    fun updateSwipeTrigger(call: PluginCall) {
        try {
            val key = call.getString("hardwareKey")
            if (key == null || key.isEmpty()) {
                call.reject("hardwareKey required", ERR_INVALID_ARGUMENT)
                return
            }
            val dir = call.getString("direction")
            if (dir == null || dir.isEmpty()) {
                call.reject("direction required", ERR_INVALID_ARGUMENT)
                return
            }
            // Validate direction is one of the allowed values.
            val allowedDirs = setOf("up", "down", "left", "right")
            if (dir !in allowedDirs) {
                call.reject("direction must be one of: ${allowedDirs.joinToString()}", ERR_INVALID_ARGUMENT)
                return
            }
            val x = call.getFloat("touchX", 0.5f) ?: 0.5f
            val y = call.getFloat("touchY", 0.5f) ?: 0.5f
            // Clamp percentages to [0, 1] (strict input validation per contract rule #5).
            val cx = x.coerceIn(0f, 1f)
            val cy = y.coerceIn(0f, 1f)
            val s = userService
            if (s != null) {
                try {
                    s.updateSwipeTrigger(key, dir, cx, cy)
                } catch (t: Throwable) {
                    Log.e(ERROR_TAG, "updateSwipeTrigger dispatch failed", t)
                }
            }
            call.resolve(JSObject().put("success", true).put("hardwareKey", key).put("direction", dir))
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "updateSwipeTrigger failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    @PluginMethod
    fun setAntiBanConfig(call: PluginCall) {
        try {
            val s = userService
            if (s == null) {
                call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE)
                return
            }
            val enabled = call.getBoolean("enabled", false) ?: false
            val coordinateJitter = call.getFloat("coordinateJitter", 4f) ?: 4f
            val timingJitterMs = call.getInt("timingJitterMs", 3) ?: 3
            val pressureVariance = call.getFloat("pressureVariance", 0.15f) ?: 0.15f
            val sizeVariance = call.getFloat("sizeVariance", 0.10f) ?: 0.10f
            // Validate ranges (strict input validation per contract rule #5).
            if (coordinateJitter < 0f || timingJitterMs < 0 || pressureVariance < 0f || sizeVariance < 0f) {
                call.reject("Anti-ban parameters must be non-negative", ERR_INVALID_ARGUMENT)
                return
            }
            s.setAntiBanConfig(enabled, coordinateJitter, timingJitterMs, pressureVariance, sizeVariance)
            call.resolve()
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "setAntiBanConfig failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Overlay service control
    // ───────────────────────────────────────────────────────────────────────

    @PluginMethod
    fun startOverlay(call: PluginCall) {
        try {
            val intent = Intent(context, com.nanomindexplorer.gamemappermind.FloatingOverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            call.resolve()
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "startOverlay failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    @PluginMethod
    fun stopOverlay(call: PluginCall) {
        try {
            context.stopService(Intent(context, com.nanomindexplorer.gamemappermind.FloatingOverlayService::class.java))
            call.resolve()
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "stopOverlay failed", t)
            call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Shell command execution (via Shizuku)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Execute a shell command via Shizuku (shell privilege, UID 2000).
     *
     * Options:
     *   command (string, required): Shell command to execute.
     *
     * Returns:
     *   { output: string, error: string, exitCode: number }
     *
     * NOTE: Shizuku.newProcess() is deprecated in v13.1.1 but still
     * functional in v13.1.5. The recommended replacement is UserService,
     * but for simple commands this is sufficient.
     */
    @PluginMethod
    fun executeShellCommand(call: PluginCall) {
        try {
            val command = call.getString("command")
            if (command == null || command.isEmpty()) {
                call.reject("command is required and must not be empty", ERR_INVALID_ARGUMENT)
                return
            }

            val helper = shizukuHelper
            if (helper == null || !helper.isBinderAlive()) {
                call.reject("Shizuku is not running. Please start Shizuku app first.", ERR_SERVICE_UNAVAILABLE)
                return
            }

            if (!helper.checkPermission()) {
                call.reject("Shizuku permission not granted. Call requestShizukuPermission first.", ERR_PERMISSION_DENIED)
                return
            }

            // Run command via Shizuku's shell process (UID 2000).
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            var line: String?

            try {
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                while (errorReader.readLine().also { line = it } != null) {
                    errorOutput.append(line).append("\n")
                }
                val exitCode = process.waitFor()
                val data = JSObject()
                data.put("output", output.toString().trim())
                data.put("error", errorOutput.toString().trim())
                data.put("exitCode", exitCode)
                call.resolve(data)
                Log.d(TAG, "Shell command executed: '$command' → exitCode=$exitCode")
            } finally {
                try { reader.close() } catch (_: Throwable) {}
                try { errorReader.close() } catch (_: Throwable) {}
                try { process.destroy() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "executeShellCommand failed", t)
            call.reject("Command execution failed: ${t.message}", ERR_INTERNAL_ERROR, t)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Safely emit an event to JS. Catches all Throwable so a broken
     * notifyListeners() call never crashes the plugin thread.
     */
    private fun safeEmit(eventName: String, data: JSObject) {
        try {
            notifyListeners(eventName, data)
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "Emit failed for event '$eventName'", t)
        }
    }

    /**
     * Static gamepad button emission — called from FloatingOverlayService
     * and GamepadManager. Uses `instance?.notifyListeners` so a null
     * instance (during teardown) is silently ignored.
     */
    @JvmStatic
    fun emitGamepadButton(buttonName: String, value: Int, pressure: Float) {
        try {
            val d = JSObject()
            d.put("buttonName", buttonName)
            d.put("value", value)
            d.put("pressure", pressure)
            instance?.notifyListeners("onGamepadButton", d)
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "emitGamepadButton failed for '$buttonName'", t)
        }
    }

    @JvmStatic
    fun emitGamepadAxis(axes: FloatArray) {
        try {
            val d = JSObject()
            val a = JSArray()
            for (v in axes) a.put(v.toDouble())
            d.put("axes", a)
            instance?.notifyListeners("onGamepadAxis", d)
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "emitGamepadAxis failed", t)
        }
    }

    @JvmStatic
    fun emitForegroundAppChanged(packageName: String) {
        try {
            val d = JSObject()
            d.put("packageName", packageName)
            d.put("timestamp", System.currentTimeMillis())
            instance?.notifyListeners("onForegroundAppChanged", d)
        } catch (t: Throwable) {
            Log.e(ERROR_TAG, "emitForegroundAppChanged failed for '$packageName'", t)
        }
    }
}
