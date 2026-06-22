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
    }

    private var isBound = false
    private val USER_SERVICE_ARGS = Shizuku.UserServiceArgs(
        ComponentName("com.nanomindexplorer.gamemappermind", TouchDaemonService::class.java.name)
    ).daemon(false).processNameSuffix("touch_daemon").version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            touchService = ITouchService.Stub.asInterface(binder)
            Log.d("GameMapper", "Shizuku Touch Service connected")
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
        if (isBound) {
            touchService?.releaseAllPointers()
            Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
            touchService = null
            isBound = false
        }
        super.handleOnDestroy()
    }

    @PluginMethod
    fun bindService(call: PluginCall) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (!isBound) {
                    Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                    isBound = true
                }
                call.resolve()
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
                touchService?.releaseAllPointers()
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
        val ALLOWED_COMMANDS = listOf("getevent -lp", "getevent -l", "dumpsys input", "pm list packages")
        
        if (!ALLOWED_COMMANDS.contains(command)) {
            call.reject("Command not allowed")
            return
        }

        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }
            
            val exitCode = process.waitFor()
            val data = JSObject()
            data.put("output", output.toString())
            data.put("error", errorOutput.toString())
            data.put("exitCode", exitCode)
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
            val granted = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val data = JSObject()
            data.put("granted", granted)
            call.resolve(data)
        } catch (e: Exception) {
            val data = JSObject()
            data.put("granted", false)
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
        if (x == null || y == null) {
            call.reject("x and y must be provided")
            return
        }
        try {
            val success = touchService?.injectTap(x, y) ?: false
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
