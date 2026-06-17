package com.nanomindexplorer.gamemappermind

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import rikka.shizuku.Shizuku

@CapacitorPlugin(name = "TouchInjection")
class TouchInjectionPlugin : Plugin() {

    private var touchService: ITouchService? = null
    private val REQUEST_CODE_SHIZUKU = 1001

    private val USER_SERVICE_ARGS = Shizuku.UserServiceArgs(
        ComponentName("com.nanomindexplorer.gamemappermind", "com.nanomindexplorer.gamemappermind.TouchDaemonService")
    ).daemon(false).processNameSuffix("touch_daemon").version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            Log.d("GameMapper", "Shizuku UserService connected: ${componentName.className}")
            if (binder != null && binder.pingBinder()) {
                touchService = ITouchService.Stub.asInterface(binder)
                try {
                    val ok = touchService?.startEvdevCapture() ?: false
                    Log.d("GameMapper", "Evdev capture started: $ok")
                } catch (e: Exception) {
                    Log.e("GameMapper", "Failed to start evdev capture", e)
                }
                // Notify JS that service is connected
                val data = JSObject()
                data.put("connected", true)
                notifyListeners("onShizukuServiceConnected", data)
            } else {
                Log.e("GameMapper", "Invalid binder received for UserService")
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Log.d("GameMapper", "Shizuku UserService disconnected")
            touchService = null
            val data = JSObject()
            data.put("connected", false)
            notifyListeners("onShizukuServiceDisconnected", data)
        }
    }

    // ============================================================
    // Shizuku binder lifecycle listeners
    // ============================================================
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("GameMapper", "Shizuku binder received")
        val data = JSObject()
        data.put("binderAlive", true)
        notifyListeners("onShizukuBinderReceived", data)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d("GameMapper", "Shizuku binder dead")
        val data = JSObject()
        data.put("binderAlive", false)
        notifyListeners("onShizukuBinderDead", data)
    }

    // ============================================================
    // Permission result listener
    // ============================================================
    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.d("GameMapper", "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult")
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        val data = JSObject()
        data.put("granted", granted)
        data.put("requestCode", requestCode)
        notifyListeners("onShizukuPermissionResult", data)

        // Auto-bind UserService immediately after permission is granted
        if (granted) {
            Log.d("GameMapper", "Permission granted — auto-binding UserService...")
            try {
                Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                Log.d("GameMapper", "UserService bind initiated after permission grant")
            } catch (e: Exception) {
                Log.e("GameMapper", "Auto-bind after permission failed", e)
            }
        }
    }

    override fun load() {
        super.load()
        instance = this

        // Register Shizuku listeners
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            Log.d("GameMapper", "Shizuku listeners registered")
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to register Shizuku listeners", e)
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to remove Shizuku listeners", e)
        }
    }

    // ============================================================
    // checkPermission — checks if Shizuku is running AND permission is granted
    // ============================================================
    @PluginMethod
    fun checkPermission(call: PluginCall) {
        val data = JSObject()
        try {
            val binderAlive = Shizuku.pingBinder()
            data.put("binderAlive", binderAlive)

            if (!binderAlive) {
                data.put("granted", false)
                data.put("reason", "Shizuku binder not alive — Shizuku app may not be running")
                call.resolve(data)
                return
            }

            if (Shizuku.isPreV11()) {
                data.put("granted", false)
                data.put("reason", "Shizuku version too old (pre-v11). Please update Shizuku.")
                call.resolve(data)
                return
            }

            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            data.put("granted", granted)
            if (!granted) {
                data.put("reason", "Permission not granted. Call requestPermission().")
            }
            call.resolve(data)
        } catch (e: Exception) {
            Log.e("GameMapper", "checkPermission error", e)
            data.put("granted", false)
            data.put("reason", "Error: ${e.message}")
            call.resolve(data)
        }
    }

    // ============================================================
    // requestPermission — shows Shizuku permission dialog to user
    // ============================================================
    @PluginMethod
    fun requestPermission(call: PluginCall) {
        try {
            // Check if binder is alive first
            if (!Shizuku.pingBinder()) {
                call.reject("Shizuku is not running. Please start Shizuku app first.")
                return
            }

            // Check if already granted
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                // Auto-bind service if permission already granted
                try {
                    Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                } catch (e: Exception) {
                    Log.e("GameMapper", "Auto-bind failed", e)
                }
                val data = JSObject()
                data.put("granted", true)
                data.put("message", "Permission already granted, service binding")
                call.resolve(data)
                return
            }

            // Request permission — MUST run on UI thread for dialog to show
            activity.runOnUiThread {
                try {
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                    Log.d("GameMapper", "Shizuku.requestPermission() called on UI thread")
                } catch (e: Exception) {
                    Log.e("GameMapper", "requestPermission on UI thread failed", e)
                }
            }

            val data = JSObject()
            data.put("granted", false)
            data.put("message", "Permission request sent. Check Shizuku dialog.")
            call.resolve(data)
        } catch (e: Exception) {
            Log.e("GameMapper", "requestPermission error", e)
            call.reject("Failed to request permission: ${e.message}")
        }
    }

    @PluginMethod
    fun bindService(call: PluginCall) {
        try {
            if (!Shizuku.pingBinder()) {
                call.reject("Shizuku binder not alive")
                return
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                call.reject("Shizuku permission not granted. Call requestPermission first.")
                return
            }

            Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
            call.resolve()
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to bind Shizuku UserService", e)
            call.reject(e.localizedMessage)
        }
    }

    @PluginMethod
    fun unbindService(call: PluginCall) {
        try {
            touchService?.stopEvdevCapture()
            Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
            touchService = null
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.localizedMessage)
        }
    }

    @PluginMethod
    fun startGamepadListener(call: PluginCall) {
        val intent = Intent(context, GamepadListenerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun stopGamepadListener(call: PluginCall) {
        val intent = Intent(context, GamepadListenerService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun startOverlay(call: PluginCall) {
        val intent = Intent(context, FloatingOverlayService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun stopOverlay(call: PluginCall) {
        val intent = Intent(context, FloatingOverlayService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun touchDown(call: PluginCall) {
        val id = call.getInt("pointerId") ?: 0
        val x = call.getFloat("x") ?: 0f
        val y = call.getFloat("y") ?: 0f
        try {
            touchService?.touchDown(id, x, y) ?: run {
                call.reject("Touch service not bound")
                return
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun touchMove(call: PluginCall) {
        val id = call.getInt("pointerId") ?: 0
        val x = call.getFloat("x") ?: 0f
        val y = call.getFloat("y") ?: 0f
        try {
            touchService?.touchMove(id, x, y) ?: run {
                call.reject("Touch service not bound")
                return
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun touchUp(call: PluginCall) {
        val id = call.getInt("pointerId") ?: 0
        try {
            touchService?.touchUp(id) ?: run {
                call.reject("Touch service not bound")
                return
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun injectTap(call: PluginCall) {
        val x = call.getFloat("x") ?: 0f
        val y = call.getFloat("y") ?: 0f
        try {
            touchService?.injectTap(x, y) ?: run {
                call.reject("Touch service not bound")
                return
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun setAntiBanConfig(call: PluginCall) {
        try {
            val enabled = call.getBoolean("enabled", false) ?: false
            val coordinateJitter = (call.getFloat("coordinateJitter", 4f) ?: 4f)
            val timingJitter = call.getInt("timingJitter", 3) ?: 3
            val pressureVariance = (call.getFloat("pressureVariance", 0.15f) ?: 0.15f)
            val sizeVariance = (call.getFloat("sizeVariance", 0.10f) ?: 0.10f)
            val strokeDurationJitter = call.getInt("strokeDurationJitter", 12) ?: 12
            val microPauseProbability = (call.getFloat("microPauseProbability", 0.02f) ?: 0.02f)
            val microPauseMaxMs = call.getInt("microPauseMaxMs", 45) ?: 45
            touchService?.setAntiBanConfig(
                enabled, coordinateJitter, timingJitter, pressureVariance,
                sizeVariance, strokeDurationJitter, microPauseProbability, microPauseMaxMs
            )
            call.resolve()
        } catch (e: Exception) {
            call.reject("setAntiBanConfig failed: ${e.message}")
        }
    }

    @PluginMethod
    fun startMacroCapture(call: PluginCall) {
        try {
            TouchAccessibilityService.setMacroCaptureEnabled(true, System.currentTimeMillis())
            call.resolve()
        } catch (e: Exception) {
            call.reject("startMacroCapture failed: ${e.message}")
        }
    }

    @PluginMethod
    fun stopMacroCapture(call: PluginCall) {
        try {
            TouchAccessibilityService.setMacroCaptureEnabled(false, 0L)
            call.resolve()
        } catch (e: Exception) {
            call.reject("stopMacroCapture failed: ${e.message}")
        }
    }

    companion object {
        @JvmField
        var instance: TouchInjectionPlugin? = null

        @JvmStatic
        fun emitGamepadButton(buttonName: String, value: Int, pressure: Float) {
            val data = JSObject()
            data.put("buttonName", buttonName)
            data.put("value", value)
            data.put("pressure", pressure)
            instance?.notifyListeners("onGamepadButton", data)
        }

        @JvmStatic
        fun emitGamepadAxis(axes: FloatArray) {
            val data = JSObject()
            val jsArray = com.getcapacitor.JSArray()
            axes.forEach { jsArray.put(it.toDouble()) }
            data.put("axes", jsArray)
            instance?.notifyListeners("onGamepadAxis", data)
        }

        @JvmStatic
        fun emitGyroData(x: Float, y: Float, z: Float, timestamp: Long) {
            val data = JSObject()
            data.put("x", x.toDouble())
            data.put("y", y.toDouble())
            data.put("z", z.toDouble())
            data.put("timestamp", timestamp)
            instance?.notifyListeners("onGyroData", data)
        }

        @JvmStatic
        fun emitForegroundAppChanged(packageName: String) {
            val data = JSObject()
            data.put("packageName", packageName)
            data.put("timestamp", System.currentTimeMillis())
            instance?.notifyListeners("onForegroundAppChanged", data)
        }

        @JvmStatic
        fun emitMacroCapture(
            action: String, pointerId: Int, x: Float, y: Float,
            pressure: Float, size: Float, timestamp: Long
        ) {
            val data = JSObject()
            data.put("action", action)
            data.put("pointerId", pointerId)
            data.put("x", x.toDouble())
            data.put("y", y.toDouble())
            data.put("pressure", pressure.toDouble())
            data.put("size", size.toDouble())
            data.put("timestamp", timestamp)
            instance?.notifyListeners("onMacroCapture", data)
        }

        @JvmStatic
        fun injectTouchDown(pointerId: Int, x: Float, y: Float) {
            try { instance?.touchService?.touchDown(pointerId, x, y) }
            catch (e: Exception) { Log.e("GameMapper", "overlay touchDown failed", e) }
        }

        @JvmStatic
        fun injectTouchMove(pointerId: Int, x: Float, y: Float) {
            try { instance?.touchService?.touchMove(pointerId, x, y) }
            catch (e: Exception) { Log.e("GameMapper", "overlay touchMove failed", e) }
        }

        @JvmStatic
        fun injectTouchUp(pointerId: Int) {
            try { instance?.touchService?.touchUp(pointerId) }
            catch (e: Exception) { Log.e("GameMapper", "overlay touchUp failed", e) }
        }

        @JvmStatic
        fun injectTapFromOverlay(x: Float, y: Float) {
            try { instance?.touchService?.injectTap(x, y) }
            catch (e: Exception) { Log.e("GameMapper", "overlay tap failed", e) }
        }
    }
}
