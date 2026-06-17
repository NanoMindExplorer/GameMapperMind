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

@CapacitorPlugin(name = "GameMapper")
class GameMapperPlugin : Plugin() {
    companion object {
        private const val TAG = "GameMapper/Plugin"
        @JvmField var instance: GameMapperPlugin? = null
    }

    private var shizukuHelper: ShizukuHelper? = null
    private val userService: IGameMapperService? get() = shizukuHelper?.getService()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun load() {
        super.load(); instance = this; Log.i(TAG, "GameMapperPlugin loaded")
        shizukuHelper = ShizukuHelper.getInstance(context)
        shizukuHelper?.setCallback(object : ShizukuHelper.ShizukuCallback {
            override fun onBinderReceived() { emitToJS("onShizukuBinderReceived", JSObject().put("binderAlive", true)) }
            override fun onBinderDead() { emitToJS("onShizukuBinderDead", JSObject().put("binderAlive", false)) }
            override fun onPermissionGranted() { emitToJS("onShizukuPermissionGranted", JSObject().put("granted", true)) }
            override fun onPermissionDenied() { emitToJS("onShizukuPermissionDenied", JSObject().put("granted", false)) }
            override fun onServiceConnected(service: IGameMapperService?) {
                val data = JSObject(); data.put("connected", service != null)
                if (service != null) { try { data.put("gamepadReadStarted", service.startGamepadRead()) } catch (e: Exception) { data.put("gamepadReadStarted", false) } }
                emitToJS("onServiceConnected", data)
            }
            override fun onServiceDisconnected() { emitToJS("onServiceDisconnected", JSObject().put("connected", false)) }
        })
        shizukuHelper?.registerListeners()
    }

    override fun handleOnDestroy() { shizukuHelper?.unbindUserService(); shizukuHelper?.unregisterListeners(); MapperDaemonService.stopDaemon(context); instance = null; super.handleOnDestroy() }

    @PluginMethod fun checkShizukuStatus(call: PluginCall) {
        val data = JSObject(); val helper = shizukuHelper
        if (helper == null) { data.put("granted", false); data.put("binderAlive", false); data.put("version", -1); call.resolve(data); return }
        val alive = helper.isBinderAlive(); data.put("binderAlive", alive)
        if (!alive) { data.put("granted", false); call.resolve(data); return }
        data.put("granted", helper.checkPermission()); data.put("version", helper.getShizukuVersion()); call.resolve(data)
    }

    @PluginMethod fun requestShizukuPermission(call: PluginCall) {
        val helper = shizukuHelper
        if (helper == null || !helper.isBinderAlive()) { call.reject("Shizuku not running"); return }
        if (helper.checkPermission()) { helper.bindUserService(); call.resolve(JSObject().put("granted", true).put("message", "Already granted")); return }
        mainHandler.post { helper.requestPermission(); call.resolve(JSObject().put("granted", false).put("message", "Dialog sent")) }
    }

    @PluginMethod fun startDaemon(call: PluginCall) {
        val json = call.getString("profileJson")
        try { MapperDaemonService.startDaemon(context, json); call.resolve(JSObject().put("success", true).put("pid", android.os.Process.myPid())) } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun stopDaemon(call: PluginCall) {
        try { userService?.stopGamepadRead(); MapperDaemonService.stopDaemon(context); shizukuHelper?.unbindUserService(); call.resolve(JSObject().put("success", true)) } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun injectTap(call: PluginCall) {
        val s = userService ?: run { call.reject("Service not bound"); return }
        try { s.injectTap(call.getFloat("x") ?: 0f, call.getFloat("y") ?: 0f, call.getInt("displayId", 0) ?: 0); call.resolve() } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun injectSwipe(call: PluginCall) {
        val s = userService ?: run { call.reject("Service not bound"); return }
        try { s.injectSwipe(call.getFloat("startX") ?: 0f, call.getFloat("startY") ?: 0f, call.getFloat("endX") ?: 0f, call.getFloat("endY") ?: 0f, call.getLong("durationMs", 100L) ?: 100L, call.getInt("displayId", 0) ?: 0); call.resolve() } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun injectTouchUp(call: PluginCall) {
        val s = userService ?: run { call.reject("Service not bound"); return }
        try { s.injectTouchUp(call.getInt("pointerId", 0) ?: 0, call.getInt("displayId", 0) ?: 0); call.resolve() } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun getConnectedGamepads(call: PluginCall) {
        val data = JSObject(); val arr = JSArray()
        try {
            val im = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
            for (id in im.inputDeviceIds) { val dev = android.view.InputDevice.getDevice(id); if (dev != null && ((dev.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD || (dev.sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK)) { arr.put(JSObject().put("deviceId", dev.id).put("name", dev.name).put("vendor", dev.vendorId.toString()).put("sources", dev.sources).put("isConnected", true)) } }
        } catch (e: Exception) { Log.e(TAG, "Failed to get gamepads", e) }
        data.put("devices", arr); call.resolve(data)
    }

    @PluginMethod fun setActiveProfile(call: PluginCall) {
        val json = call.getString("profileJson") ?: run { call.reject("profileJson required"); return }
        val s = userService ?: run { call.reject("Service not bound"); return }
        try { val profile = JSONObject(json); s.setProfile(json); val data = JSObject().put("success", true).put("packageName", profile.optString("packageName", "")); call.resolve(data); emitToJS("onProfileChanged", JSObject().put("packageName", profile.optString("packageName", ""))) } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun updateSwipeTrigger(call: PluginCall) {
        val key = call.getString("hardwareKey") ?: run { call.reject("hardwareKey required"); return }
        val dir = call.getString("direction") ?: run { call.reject("direction required"); return }
        val x = call.getFloat("touchX", 0.5f) ?: 0.5f; val y = call.getFloat("touchY", 0.5f) ?: 0.5f
        val s = userService; if (s != null) { try { s.updateSwipeTrigger(key, dir, x, y) } catch (e: Exception) { Log.e(TAG, "updateSwipeTrigger failed", e) } }
        call.resolve(JSObject().put("success", true).put("hardwareKey", key).put("direction", dir))
    }

    @PluginMethod fun setAntiBanConfig(call: PluginCall) {
        val s = userService ?: run { call.reject("Service not bound"); return }
        try { s.setAntiBanConfig(call.getBoolean("enabled", false) ?: false, call.getFloat("coordinateJitter", 4f) ?: 4f, call.getInt("timingJitterMs", 3) ?: 3, call.getFloat("pressureVariance", 0.15f) ?: 0.15f, call.getFloat("sizeVariance", 0.10f) ?: 0.10f); call.resolve() } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun startOverlay(call: PluginCall) {
        try { val intent = Intent(context, com.nanomindexplorer.gamemappermind.FloatingOverlayService::class.java); if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent); call.resolve() } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    @PluginMethod fun stopOverlay(call: PluginCall) {
        try { context.stopService(Intent(context, com.nanomindexplorer.gamemappermind.FloatingOverlayService::class.java)); call.resolve() } catch (e: Exception) { call.reject("Failed: ${e.message}") }
    }

    private fun emitToJS(eventName: String, data: JSObject) { try { notifyListeners(eventName, data) } catch (e: Exception) { Log.e(TAG, "Emit failed: $eventName", e) } }

    @JvmStatic fun emitGamepadButton(buttonName: String, value: Int, pressure: Float) { val d = JSObject(); d.put("buttonName", buttonName); d.put("value", value); d.put("pressure", pressure); instance?.notifyListeners("onGamepadButton", d) }
    @JvmStatic fun emitGamepadAxis(axes: FloatArray) { val d = JSObject(); val a = JSArray(); for (v in axes) a.put(v.toDouble()); d.put("axes", a); instance?.notifyListeners("onGamepadAxis", d) }
    @JvmStatic fun emitForegroundAppChanged(packageName: String) { val d = JSObject(); d.put("packageName", packageName); d.put("timestamp", System.currentTimeMillis()); instance?.notifyListeners("onForegroundAppChanged", d) }
}
