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
        @Volatile var instance: java.lang.ref.WeakReference<TouchInjectionPlugin>? = null
        @Volatile var touchService: ITouchService? = null

        fun emitGamepadButton(buttonName: String, value: Int, pressure: Float) {
            try {
                val data = JSObject()
                data.put("buttonName", buttonName)
                data.put("value", value)
                data.put("pressure", pressure)
                instance?.get()?.notifyListeners("onGamepadButton", data)
                Log.d("GameMapper", "emitGamepadButton: $buttonName value=$value")
            } catch (e: Exception) {
                Log.w("GameMapper", "emitGamepadButton failed: ${e.message}")
            }
        }

        fun emitGamepadAxis(axes: FloatArray) {
            try {
                val data = JSObject()
                val jsArray = com.getcapacitor.JSArray()
                axes.forEach { jsArray.put(it.toDouble()) }
                data.put("axes", jsArray)
                instance?.get()?.notifyListeners("onGamepadAxis", data)
            } catch (e: Exception) {
                Log.w("GameMapper", "emitGamepadAxis failed: ${e.message}")
            }
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
    private val USER_SERVICE_ARGS = Shizuku.UserServiceArgs(
        ComponentName("com.nanomindexplorer.gamemappermind", TouchDaemonService::class.java.name)
    ).tag("touch_daemon_v1").daemon(true).processNameSuffix("touch_daemon").version(1)

    private val pendingBindCalls = mutableListOf<PluginCall>()
    private val pendingLock = Any()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            touchService = ITouchService.Stub.asInterface(binder)
            Log.d("GameMapper", "✅ Shizuku Touch Service CONNECTED")
            
            // CRITICAL FIX: Immediately deliver active profile to native after service connects
            val profile = GamepadListenerService.activeProfileJson
            if (!profile.isNullOrEmpty() && profile != "{}") {
                Log.i("GameMapper", "Service connected: re-sending active profile to native")
                try {
                    NativeGamepadMapper.instance?.buildMapCache()
                } catch(e: Exception) {
                    Log.w("GameMapper", "Failed to rebuild map cache on service connect", e)
                }
            }
            
            synchronized(pendingLock) {
                pendingBindCalls.forEach { it.resolve() }
                pendingBindCalls.clear()
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            touchService = null
            isBound = false
            Log.d("GameMapper", "❌ Shizuku Touch Service DISCONNECTED")
            synchronized(pendingLock) {
                pendingBindCalls.forEach { it.reject("Service disconnected") }
                pendingBindCalls.clear()
            }
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
        
        // CRITICAL FIX: Create NativeGamepadMapper IMMEDIATELY in plugin load
        try {
            if (NativeGamepadMapper.instance == null) {
                Log.i("GameMapper", "Creating NativeGamepadMapper in TouchInjectionPlugin.load()")
                NativeGamepadMapper(context)
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to create NativeGamepadMapper in load()", e)
        }
    }

    override fun handleOnDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.handleOnDestroy()
    }

    @PluginMethod
    fun bindService(call: PluginCall) {
        try {
            if (Shizuku.isPreV11()) {
                call.reject("Shizuku is not running or version is too old (pre-v11)")
                return
            }
            if (!Shizuku.pingBinder()) {
                call.reject("Shizuku binder is not active. Please start Shizuku first.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val serviceAlive = touchService != null && try { touchService!!.asBinder().isBinderAlive } catch(e: Exception) { false }
                if (isBound && serviceAlive) {
                    call.resolve()
                    return
                }
                synchronized(pendingLock) { pendingBindCalls.add(call) }
                try {
                    Log.i("GameMapper", "Binding Shizuku service...")
                    Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                    isBound = true
                } catch (e: Exception) {
                    synchronized(pendingLock) { pendingBindCalls.remove(call) }
                    call.reject("Failed to bind Shizuku service: ${e.message}")
                }
            } else {
                call.reject("Shizuku permission not granted")
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to bind Shizuku user service", e)
            synchronized(pendingLock) { pendingBindCalls.remove(call) }
            call.reject("Failed to bind Shizuku service: ${e.message}")
        }
    }

    @PluginMethod
    fun unbindService(call: PluginCall) {
        try {
            try {
                val intent = Intent(context, GamepadListenerService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w("GameMapper", "Failed to stop GamepadListenerService", e)
            }
            
            try { Thread.sleep(100) } catch (e: InterruptedException) {}
            
            if (isBound) {
                Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
                touchService = null
                isBound = false
                Log.i("GameMapper", "Service unbound")
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.localizedMessage)
        }
    }

    @PluginMethod
    fun startGamepadListener(call: PluginCall) {
        if (GamepadListenerService.isRunning) {
            call.resolve()
            return
        }
        val intent = Intent(context, GamepadListenerService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Log.i("GameMapper", "Starting GamepadListenerService (foreground)")
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(context)) {
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + context.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                call.reject("Overlay permission (SYSTEM_ALERT_WINDOW) belum di-grant. Buka Settings untuk mengizinkan.")
                return
            }
        }
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
        
        val ALLOWED_PREFIXES = listOf("getevent -lp", "getevent -l", "dumpsys input", "pm list packages")
        
        val isAllowed = ALLOWED_PREFIXES.any { command.startsWith(it) }
        if (!isAllowed) {
            call.reject("Command not allowed")
            return
        }
        if (Regex("[;|&\n<>`$]").containsMatchIn(command)) {
            call.reject("Command contains forbidden shell metacharacters")
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
        Log.i("GameMapper", "updateActiveProfile: json length=${configJson?.length ?: 0}")
        GamepadListenerService.activeProfileJson = configJson
        
        // Send to Shizuku daemon
        try {
            touchService?.releaseAllPointers()
            if (configJson != null && configJson != "{}") {
                touchService?.updateConfig(configJson)
                Log.i("GameMapper", "Profile sent to Shizuku daemon")
            }
        } catch (e: Exception) {
            Log.w("GameMapper", "Failed to update config in daemon: ${e.message}")
        }
        
        // CRITICAL FIX: ALWAYS rebuild native mapper cache when profile updates
        try {
            val mapper = NativeGamepadMapper.instance
            if (mapper != null) {
                mapper.buildMapCache()
                Log.i("GameMapper", "✅ Cache rebuilt: ${mapper.buttonMapCache.size} mappings loaded")
                if (mapper.buttonMapCache.isEmpty()) {
                    Log.w("GameMapper", "⚠️ WARNING: buttonMapCache is EMPTY - profile parsing failed!")
                }
            } else {
                Log.w("GameMapper", "⚠️ NativeGamepadMapper instance is NULL!")
            }
        } catch(e: Exception) {
            Log.e("GameMapper", "Failed to rebuild native mapper cache: ${e.message}", e)
        }
        
        call.resolve()
    }

    @PluginMethod
    fun runDiagnostics(call: PluginCall) {
        val data = JSObject()
        val sb = StringBuilder()

        sb.append("=== DIAGNOSTICS ===\n")
        try {
            sb.append("1. Shizuku isPreV11: ${Shizuku.isPreV11()}\n")
            sb.append("2. Shizuku pingBinder: ${Shizuku.pingBinder()}\n")
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            sb.append("3. Shizuku permission granted: $granted\n")
        } catch (e: Exception) {
            sb.append("1-3. ERROR: ${e.message}\n")
        }

        val ts = touchService
        sb.append("4. touchService: ${if (ts != null) "✅ CONNECTED" else "❌ NULL"}\n")
        if (ts != null) {
            try {
                sb.append("5. touchService.isAlive: ${ts.isAlive()}\n")
                sb.append("6. touchService.binderAlive: ${ts.asBinder().isBinderAlive}\n")
            } catch (e: Exception) {
                sb.append("5-6. ERROR: ${e.message}\n")
            }
        }

        sb.append("7. GamepadListenerService.isRunning: ${GamepadListenerService.isRunning}\n")

        val mapper = NativeGamepadMapper.instance
        sb.append("8. NativeGamepadMapper.instance: ${if (mapper != null) "✅ EXISTS" else "❌ NULL"}\n")
        if (mapper != null) {
            sb.append("9. buttonMapCache size: ${mapper.buttonMapCache.size}\n")
            if (mapper.buttonMapCache.isEmpty()) {
                sb.append("⚠️ CRITICAL: buttonMapCache is EMPTY - profile not loaded!\n")
            } else {
                sb.append("✅ Profile loaded with mappings: ${mapper.buttonMapCache.keys.joinToString(", ")}\n")
            }
            sb.append("10. activeProfileJson length: ${GamepadListenerService.activeProfileJson?.length ?: -1}\n")
        }

        sb.append("11. isBound: $isBound\n")

        if (ts != null) {
            try {
                val result = ts.executeShellCommand("getevent -lp")
                val obj = org.json.JSONObject(result)
                val output = obj.optString("output", "")
                val hasGamepad = output.contains("BTN_A") || output.contains("BTN_GAMEPAD") || output.contains("BTN_SOUTH")
                sb.append("12. getevent -lp has gamepad: ${if (hasGamepad) "✅ YES" else "❌ NO"}\n")
                val devicePaths = Regex("/dev/input/event\\d+").findAll(output).map { it.value }.toSet()
                sb.append("13. Input devices: $devicePaths\n")
            } catch (e: Exception) {
                sb.append("12-13. getevent ERROR: ${e.message}\n")
            }
        }

        val report = sb.toString()
        Log.i("GameMapper", report)
        data.put("report", report)
        call.resolve(data)
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
            val ts = touchService
            val touchServiceAlive = ts != null && ts.asBinder().isBinderAlive
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
            Log.d("GameMapper", "touchDown: id=$id ($x, $y)")
            val success = touchService?.touchDown(id, x, y) ?: false
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
            Log.d("GameMapper", "touchUp: id=$id")
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
            Log.i("GameMapper", "injectTap: ($x, $y) duration=$duration")
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

    @PluginMethod
    fun testInjection(call: PluginCall) {
        val x = call.getFloat("x") ?: 240f
        val y = call.getFloat("y") ?: 240f
        try {
            Log.i("GameMapper", "testInjection: ($x, $y)")
            if (touchService == null) {
                call.reject("Shizuku user service not bound. Please start daemon first.")
                return
            }
            val jsonResult = touchService?.testInjection(x, y) ?: "{}"
            val obj = org.json.JSONObject(jsonResult)
            val data = JSObject()
            for (key in obj.keys()) {
                data.put(key, obj.get(key))
            }
            call.resolve(data)
        } catch (e: Exception) {
            call.reject("testInjection failed: ${e.message}")
        }
    }
}
