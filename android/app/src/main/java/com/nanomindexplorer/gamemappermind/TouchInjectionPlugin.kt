package com.nanomindexplorer.gamemappermind

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager

@CapacitorPlugin(name = "TouchInjection")
class TouchInjectionPlugin : Plugin() {

    companion object {
        var instance: java.lang.ref.WeakReference<TouchInjectionPlugin>? = null
        var touchService: ITouchService? = null

        fun emitGamepadButton(buttonName: String, value: Int, pressure: Float) {
            val data = JSObject()
            data.put("buttonName", buttonName)
            data.put("value", value)
            data.put("pressure", pressure)
            instance?.get()?.notifyListeners("onGamepadButton", data)
        }

        fun emitGamepadAxis(axes: FloatArray) {
            val data = JSObject()
            val jsArray = com.getcapacitor.JSArray()
            axes.forEach { jsArray.put(it.toDouble()) }
            data.put("axes", jsArray)
            instance?.get()?.notifyListeners("onGamepadAxis", data)
        }

        fun emitGamepadFeedback(type: String, intensity: Float, duration: Long) {
            val data = JSObject()
            data.put("type", type)
            data.put("intensity", intensity)
            data.put("duration", duration)
            instance?.get()?.notifyListeners("onGamepadFeedback", data)
        }
    }

    private var isBound = false
    // BUG FIX: Add tag for ProGuard/R8 stability (per Shizuku API docs)
    private val USER_SERVICE_ARGS = Shizuku.UserServiceArgs(
        ComponentName("com.nanomindexplorer.gamemappermind", TouchDaemonService::class.java.name)
    ).tag("touch_daemon_v1").daemon(false).processNameSuffix("touch_daemon").version(1)

    private var pendingBindCalls = mutableListOf<PluginCall>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            touchService = ITouchService.Stub.asInterface(binder)
            Log.d("GameMapper", "Shizuku Touch Service connected")
            pendingBindCalls.forEach { it.resolve() }
            pendingBindCalls.clear()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            touchService = null
            isBound = false
            Log.d("GameMapper", "Shizuku Touch Service disconnected")
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
            val data = JSObject()
            data.put("granted", grantResult == PackageManager.PERMISSION_GRANTED)
            notifyListeners("onShizukuPermissionResult", data)
        }
    }

    override fun load() {
        super.load()
        instance = java.lang.ref.WeakReference(this)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun handleOnDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        // Do NOT destroy or unbind service on handleOnDestroy.
        // handleOnDestroy is called when the Activity is destroyed (e.g., app backgrounded,
        // orientation change, or swipe-away). If we destroy/unbind here, the Shizuku
        // user service process dies and the app disappears from Shizuku management.
        // The service should persist across Activity lifecycle changes.
        // Service will be properly cleaned up when:
        // 1. User explicitly clicks "Stop Daemon" (calls unbindService)
        // 2. App is fully terminated (process death)
        // 3. Shizuku itself is stopped
        super.handleOnDestroy()
    }

    @PluginMethod
    fun bindService(call: PluginCall) {
        try {
            // BUG FIX: Check isPreV11 per Shizuku API docs
            if (Shizuku.isPreV11()) {
                call.reject("Shizuku is not running or version is too old (pre-v11)")
                return
            }
            if (!Shizuku.pingBinder()) {
                call.reject("Shizuku binder is not active. Please start Shizuku first.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val serviceAlive = touchService != null && touchService!!.asBinder().isBinderAlive
                if (!isBound || !serviceAlive) {
                    pendingBindCalls.add(call)
                    try {
                        if (isBound) {
                            // BUG FIX: Use false (don't remove) instead of true (remove).
                            // unbindUserService with true KILLS the service process,
                            // causing app to disappear from Shizuku management.
                            Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, false)
                        }
                    } catch (e: Exception) {}
                    Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                    isBound = true
                } else {
                    call.resolve()
                }
            } else {
                call.reject("Shizuku permission not granted")
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to bind Shizuku user service", e)
            call.reject("Failed to bind Shizuku service: ${e.message}")
        }
    }

    @PluginMethod
    fun unbindService(call: PluginCall) {
        try {
            if (isBound) {
                try {
                    touchService?.destroy()
                } catch (e: Exception) {
                    // ignore
                }
                Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
                touchService = null
                isBound = false
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.localizedMessage)
        }
    }

    @PluginMethod
    fun startGamepadListener(call: PluginCall) {
        val intent = Intent(context, GamepadListenerService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            call.resolve()
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to start gamepad listener service", e)
            call.reject("Failed to start gamepad listener service: ${e.message}")
        }
    }

    @PluginMethod
    fun startOverlay(call: PluginCall) {
        val profileObj = call.getObject("profile")
        val profileJson = profileObj?.toString() ?: "{}"
        val intent = Intent(context, FloatingOverlayService::class.java)
        intent.putExtra("config", profileJson)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            call.resolve()
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to start overlay service", e)
            call.reject("Failed to start overlay service: ${e.message}")
        }
    }

    @PluginMethod
    fun stopOverlay(call: PluginCall) {
        val intent = Intent(context, FloatingOverlayService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun stopGamepadListener(call: PluginCall) {
        val intent = Intent(context, GamepadListenerService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        try {
            // BUG FIX: Check isPreV11 per Shizuku API docs
            if (Shizuku.isPreV11()) {
                call.reject("Shizuku is not running or version is too old (pre-v11)")
                return
            }
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (granted) {
                val data = JSObject()
                data.put("granted", true)
                call.resolve(data)
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                call.reject("Permission denied previously.")
            } else {
                Shizuku.requestPermission(1234)
                val data = JSObject()
                data.put("granted", false)
                data.put("requested", true)
                call.resolve(data)
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "Shizuku request permission error", e)
            call.reject("Shizuku request permission error: ${e.message}")
        }
    }

    @PluginMethod
    fun executeShizukuCommand(call: PluginCall) {
        val command = call.getString("command") ?: ""
        
        // Anti-Regression: Fix BUG-N01 via whitelist
        val ALLOWED_PREFIXES = listOf("getevent -lp", "getevent -l", "dumpsys input", "pm list packages", "input tap", "input swipe", "input keyevent")
        
        val isAllowed = ALLOWED_PREFIXES.any { command.startsWith(it) }
        if (!isAllowed) {
            call.reject("Command not allowed")
            return
        }

        if (touchService == null) {
            call.reject("Shizuku user service not bound. Please bind service first.")
            return
        }

        try {
            val jsonResult = touchService?.executeShellCommand(command) ?: "{}"
            val obj = org.json.JSONObject(jsonResult)
            val data = JSObject()
            data.put("output", obj.optString("output", ""))
            data.put("error", obj.optString("error", ""))
            data.put("exitCode", obj.optInt("exitCode", -1))
            call.resolve(data)
        } catch (e: Exception) {
            call.reject(e.localizedMessage)
        }
    }

    @PluginMethod
    fun updateActiveProfile(call: PluginCall) {
        val configJson = call.getString("profileJson")
        GamepadListenerService.activeProfileJson = configJson
        touchService?.releaseAllPointers()
        if (configJson != null) {
            try { touchService?.updateConfig(configJson) } catch (e: Exception) {}
        }
        NativeGamepadMapper.resetAll()
        call.resolve()
    }

    @PluginMethod
    fun checkDaemonRunning(call: PluginCall) {
        val data = JSObject()
        data.put("daemonRunning", GamepadListenerService.isRunning)
        call.resolve(data)
    }

    @PluginMethod
    fun checkBattery(call: PluginCall) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val data = JSObject()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            data.put("isIgnoring", powerManager.isIgnoringBatteryOptimizations(context.packageName))
        } else {
            data.put("isIgnoring", true)
        }
        call.resolve(data)
    }

    @PluginMethod
    fun requestBatteryIgnore(call: PluginCall) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        try {
            // BUG FIX: Comprehensive check per Shizuku API
            if (Shizuku.isPreV11()) {
                val data = JSObject()
                data.put("granted", false)
                data.put("isBound", false)
                data.put("touchServiceAlive", false)
                call.resolve(data)
                return
            }
            val binderAlive = Shizuku.pingBinder()
            val granted = binderAlive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val touchServiceAlive = touchService != null && touchService!!.asBinder().isBinderAlive
            val data = JSObject()
            data.put("granted", granted)
            data.put("isBound", isBound)
            data.put("touchServiceAlive", touchServiceAlive)
            Log.d("GameMapper", "checkPermission: binder=$binderAlive granted=$granted isBound=$isBound serviceAlive=$touchServiceAlive")
            call.resolve(data)
        } catch (e: Exception) {
            Log.e("GameMapper", "checkPermission error", e)
            val data = JSObject()
            data.put("granted", false)
            data.put("isBound", false)
            data.put("touchServiceAlive", false)
            call.resolve(data)
        }
    }

    @PluginMethod
    fun touchDown(call: PluginCall) {
        val id = call.getInt("pointerId")
        val x = call.getFloat("x")
        val y = call.getFloat("y")
        if (id == null || x == null || y == null) {
            call.reject("pointerId, x, and y must be provided")
            return
        }
        try {
            val success = touchService?.touchDown(id, x, y) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false (perhaps injectInputEvent is null or failed)")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun touchMove(call: PluginCall) {
        val id = call.getInt("pointerId")
        val x = call.getFloat("x")
        val y = call.getFloat("y")
        if (id == null || x == null || y == null) {
             call.reject("pointerId, x, and y must be provided")
             return
        }
        try {
            val success = touchService?.touchMove(id, x, y) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun touchUp(call: PluginCall) {
        val id = call.getInt("pointerId")
        if (id == null) {
            call.reject("pointerId must be provided")
            return
        }
        try {
            val success = touchService?.touchUp(id) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun injectTap(call: PluginCall) {
        val x = call.getFloat("x")
        val y = call.getFloat("y")
        val duration = call.getLong("duration") ?: 60L
        if (x == null || y == null) {
            call.reject("x and y must be provided")
            return
        }
        try {
            val success = touchService?.injectTap(x, y, duration) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }
}
