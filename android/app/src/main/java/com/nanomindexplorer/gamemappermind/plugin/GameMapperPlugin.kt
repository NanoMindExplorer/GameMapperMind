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

/**
 * GameMapperPlugin — Capacitor bridge between React frontend and native
 * Shizuku-backed gamepad/touch injection pipeline.
 *
 * FASE 3.2 — Native Crash-Proof Hardening:
 *   Every @PluginMethod is wrapped in try-catch (Throwable t).
 *   TAG = "GameMapper_ERROR" for caught errors (Log.e).
 */
@CapacitorPlugin(name = "GameMapper")
class GameMapperPlugin : Plugin() {
    companion object {
        private const val TAG = "GameMapper/Plugin"
        private const val ERROR_TAG = "GameMapper_ERROR"

        private const val ERR_INVALID_ARGUMENT   = "INVALID_ARGUMENT"
        private const val ERR_PERMISSION_DENIED  = "PERMISSION_DENIED"
        private const val ERR_SERVICE_UNAVAILABLE= "SERVICE_UNAVAILABLE"
        private const val ERR_INTERNAL_ERROR     = "INTERNAL_ERROR"
        private const val ERR_NOT_FOUND          = "NOT_FOUND"

        @JvmField var instance: GameMapperPlugin? = null

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

    private var shizukuHelper: ShizukuHelper? = null
    private val userService: IGameMapperService? get() = shizukuHelper?.getService()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun load() {
        super.load()
        instance = this
        Log.i(TAG, "GameMapperPlugin loaded")
        try {
            shizukuHelper = ShizukuHelper.getInstance(context)
            shizukuHelper?.setCallback(object : ShizukuHelper.ShizukuCallback {
                override fun onBinderReceived() { safeEmit("onShizukuBinderReceived", JSObject().put("binderAlive", true)) }
                override fun onBinderDead() { safeEmit("onShizukuBinderDead", JSObject().put("binderAlive", false)) }
                override fun onPermissionGranted() { safeEmit("onShizukuPermissionGranted", JSObject().put("granted", true)) }
                override fun onPermissionDenied() { safeEmit("onShizukuPermissionDenied", JSObject().put("granted", false)) }
                override fun onServiceConnected(service: IGameMapperService?) {
                    val data = JSObject()
                    data.put("connected", service != null)
                    if (service != null) {
                        try { data.put("gamepadReadStarted", service.startGamepadRead()) }
                        catch (t: Throwable) { Log.e(ERROR_TAG, "startGamepadRead failed", t); data.put("gamepadReadStarted", false) }
                    }
                    safeEmit("onServiceConnected", data)
                }
                override fun onServiceDisconnected() { safeEmit("onServiceDisconnected", JSObject().put("connected", false)) }
            })
            shizukuHelper?.registerListeners()
        } catch (t: Throwable) { Log.e(ERROR_TAG, "load() failed", t) }
    }

    override fun handleOnDestroy() {
        try { shizukuHelper?.unbindUserService(); shizukuHelper?.unregisterListeners(); MapperDaemonService.stopDaemon(context) }
        catch (t: Throwable) { Log.e(ERROR_TAG, "handleOnDestroy failed", t) }
        finally { instance = null; super.handleOnDestroy() }
    }

    @PluginMethod fun checkShizukuStatus(call: PluginCall) {
        try {
            val data = JSObject(); val helper = shizukuHelper
            if (helper == null) { data.put("granted", false); data.put("binderAlive", false); data.put("version", -1); call.resolve(data); return }
            val alive = helper.isBinderAlive(); data.put("binderAlive", alive)
            if (!alive) { data.put("granted", false); call.resolve(data); return }
            data.put("granted", helper.checkPermission()); data.put("version", helper.getShizukuVersion()); call.resolve(data)
        } catch (t: Throwable) { Log.e(ERROR_TAG, "checkShizukuStatus failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun requestShizukuPermission(call: PluginCall) {
        try {
            val helper = shizukuHelper
            if (helper == null || !helper.isBinderAlive()) { call.reject("Shizuku not running", ERR_SERVICE_UNAVAILABLE); return }
            if (helper.checkPermission()) { helper.bindUserService(); call.resolve(JSObject().put("granted", true).put("message", "Already granted")); return }
            mainHandler.post {
                try { helper.requestPermission(); call.resolve(JSObject().put("granted", false).put("message", "Dialog sent")) }
                catch (t: Throwable) { Log.e(ERROR_TAG, "requestPermission failed", t); call.reject("Failed: ${t.message}", ERR_PERMISSION_DENIED) }
            }
        } catch (t: Throwable) { Log.e(ERROR_TAG, "requestShizukuPermission failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun startDaemon(call: PluginCall) {
        try {
            val json = call.getString("profileJson")
            MapperDaemonService.startDaemon(context, json)
            call.resolve(JSObject().put("success", true).put("pid", android.os.Process.myPid()))
        } catch (t: Throwable) { Log.e(ERROR_TAG, "startDaemon failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun stopDaemon(call: PluginCall) {
        try {
            userService?.let { s -> try { s.stopGamepadRead() } catch (t: Throwable) { Log.e(ERROR_TAG, "stopGamepadRead failed", t) } }
            MapperDaemonService.stopDaemon(context)
            shizukuHelper?.unbindUserService()
            call.resolve(JSObject().put("success", true))
        } catch (t: Throwable) { Log.e(ERROR_TAG, "stopDaemon failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun injectTap(call: PluginCall) {
        try {
            val s = userService ?: run { call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE); return }
            val x = call.getFloat("x", 0f) ?: 0f; val y = call.getFloat("y", 0f) ?: 0f; val displayId = call.getInt("displayId", 0) ?: 0
            if (x < 0f || y < 0f) { call.reject("Coordinates must be non-negative", ERR_INVALID_ARGUMENT); return }
            s.injectTap(x, y, displayId); call.resolve()
        } catch (t: Throwable) { Log.e(ERROR_TAG, "injectTap failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun injectSwipe(call: PluginCall) {
        try {
            val s = userService ?: run { call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE); return }
            val startX = call.getFloat("startX", 0f) ?: 0f; val startY = call.getFloat("startY", 0f) ?: 0f
            val endX = call.getFloat("endX", 0f) ?: 0f; val endY = call.getFloat("endY", 0f) ?: 0f
            val durationMs = call.getLong("durationMs", 100L) ?: 100L; val displayId = call.getInt("displayId", 0) ?: 0
            if (durationMs <= 0L) { call.reject("durationMs must be positive", ERR_INVALID_ARGUMENT); return }
            s.injectSwipe(startX, startY, endX, endY, durationMs, displayId); call.resolve()
        } catch (t: Throwable) { Log.e(ERROR_TAG, "injectSwipe failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun injectTouchUp(call: PluginCall) {
        try {
            val s = userService ?: run { call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE); return }
            val pointerId = call.getInt("pointerId", 0) ?: 0; val displayId = call.getInt("displayId", 0) ?: 0
            s.injectTouchUp(pointerId, displayId); call.resolve()
        } catch (t: Throwable) { Log.e(ERROR_TAG, "injectTouchUp failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun getConnectedGamepads(call: PluginCall) {
        try {
            val data = JSObject(); val arr = JSArray()
            try {
                val im = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
                for (id in im.inputDeviceIds) {
                    val dev = android.view.InputDevice.getDevice(id)
                    if (dev != null && ((dev.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD || (dev.sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK)) {
                        arr.put(JSObject().put("deviceId", dev.id).put("name", dev.name).put("vendor", dev.vendorId.toString()).put("sources", dev.sources).put("isConnected", true))
                    }
                }
            } catch (t: Throwable) { Log.e(ERROR_TAG, "Gamepad enum failed", t) }
            data.put("devices", arr); call.resolve(data)
        } catch (t: Throwable) { Log.e(ERROR_TAG, "getConnectedGamepads failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun setActiveProfile(call: PluginCall) {
        try {
            val json = call.getString("profileJson") ?: run { call.reject("profileJson required", ERR_INVALID_ARGUMENT); return }
            val s = userService ?: run { call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE); return }
            val profile = JSONObject(json)
            s.setProfile(json)
            val data = JSObject().put("success", true).put("packageName", profile.optString("packageName", ""))
            call.resolve(data)
            safeEmit("onProfileChanged", JSObject().put("packageName", profile.optString("packageName", "")))
        } catch (t: Throwable) { Log.e(ERROR_TAG, "setActiveProfile failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun updateSwipeTrigger(call: PluginCall) {
        try {
            val key = call.getString("hardwareKey") ?: run { call.reject("hardwareKey required", ERR_INVALID_ARGUMENT); return }
            val dir = call.getString("direction") ?: run { call.reject("direction required", ERR_INVALID_ARGUMENT); return }
            val allowedDirs = setOf("up", "down", "left", "right")
            if (dir !in allowedDirs) { call.reject("direction must be one of: ${allowedDirs.joinToString()}", ERR_INVALID_ARGUMENT); return }
            val x = (call.getFloat("touchX", 0.5f) ?: 0.5f).coerceIn(0f, 1f)
            val y = (call.getFloat("touchY", 0.5f) ?: 0.5f).coerceIn(0f, 1f)
            val s = userService
            if (s != null) { try { s.updateSwipeTrigger(key, dir, x, y) } catch (t: Throwable) { Log.e(ERROR_TAG, "updateSwipeTrigger dispatch failed", t) } }
            call.resolve(JSObject().put("success", true).put("hardwareKey", key).put("direction", dir))
        } catch (t: Throwable) { Log.e(ERROR_TAG, "updateSwipeTrigger failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun setAntiBanConfig(call: PluginCall) {
        try {
            val s = userService ?: run { call.reject("Service not bound", ERR_SERVICE_UNAVAILABLE); return }
            val enabled = call.getBoolean("enabled", false) ?: false
            val coordinateJitter = call.getFloat("coordinateJitter", 4f) ?: 4f
            val timingJitterMs = call.getInt("timingJitterMs", 3) ?: 3
            val pressureVariance = call.getFloat("pressureVariance", 0.15f) ?: 0.15f
            val sizeVariance = call.getFloat("sizeVariance", 0.10f) ?: 0.10f
            if (coordinateJitter < 0f || timingJitterMs < 0 || pressureVariance < 0f || sizeVariance < 0f) { call.reject("Anti-ban parameters must be non-negative", ERR_INVALID_ARGUMENT); return }
            s.setAntiBanConfig(enabled, coordinateJitter, timingJitterMs, pressureVariance, sizeVariance); call.resolve()
        } catch (t: Throwable) { Log.e(ERROR_TAG, "setAntiBanConfig failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun startOverlay(call: PluginCall) {
        try {
            val intent = Intent(context, com.nanomindexplorer.gamemappermind.FloatingOverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            call.resolve()
        } catch (t: Throwable) { Log.e(ERROR_TAG, "startOverlay failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    @PluginMethod fun stopOverlay(call: PluginCall) {
        try { context.stopService(Intent(context, com.nanomindexplorer.gamemappermind.FloatingOverlayService::class.java)); call.resolve() }
        catch (t: Throwable) { Log.e(ERROR_TAG, "stopOverlay failed", t); call.reject("Failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    /**
     * Execute a shell command via Shizuku (shell privilege).
     * NOTE: Shizuku.newProcess() is private in v13.1.5. This method
     * uses Runtime.exec() as fallback (runs with app's own UID, not shell).
     * For true shell-privilege execution, use UserService via AIDL.
     */
    @PluginMethod fun executeShellCommand(call: PluginCall) {
        try {
            val command = call.getString("command")
            if (command == null || command.isEmpty()) { call.reject("command is required and must not be empty", ERR_INVALID_ARGUMENT); return }
            val helper = shizukuHelper
            if (helper == null || !helper.isBinderAlive()) { call.reject("Shizuku is not running. Please start Shizuku app first.", ERR_SERVICE_UNAVAILABLE); return }
            if (!helper.checkPermission()) { call.reject("Shizuku permission not granted. Call requestShizukuPermission first.", ERR_PERMISSION_DENIED); return }

            // Fallback: use Runtime.exec() (app UID, not shell UID).
            // For true shell-privilege, route commands through UserService.
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            val data = JSObject()
            data.put("output", output.trim()); data.put("error", errorOutput.trim()); data.put("exitCode", exitCode)
            call.resolve(data)
            Log.d(TAG, "Shell command executed: '$command' → exitCode=$exitCode")
        } catch (t: Throwable) { Log.e(ERROR_TAG, "executeShellCommand failed", t); call.reject("Command execution failed: ${t.message}", ERR_INTERNAL_ERROR) }
    }

    private fun safeEmit(eventName: String, data: JSObject) {
        try { notifyListeners(eventName, data) } catch (t: Throwable) { Log.e(ERROR_TAG, "Emit failed for event '$eventName'", t) }
    }
}
