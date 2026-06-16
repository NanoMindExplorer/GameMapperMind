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
        // @JvmField makes `instance` accessible as a plain field from Java code
        // (FloatingOverlayService.java needs this for forwarding overlay touch events).
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
    }

    private var touchService: ITouchService? = null
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
            Log.d("GameMapper", "Shizuku Touch Service disconnected")
        }
    }

    override fun load() {
        super.load()
        instance = this
    }

    @PluginMethod
    fun bindService(call: PluginCall) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                call.resolve()
            } catch (e: Exception) {
                Log.e("GameMapper", "Failed to bind Shizuku user service", e)
                call.reject(e.localizedMessage)
            }
        } else {
            call.reject("Shizuku permission not granted")
        }
    }

    @PluginMethod
    fun unbindService(call: PluginCall) {
        try {
            Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
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
    fun stopGamepadListener(call: PluginCall) {
        val intent = Intent(context, GamepadListenerService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        val granted = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val data = JSObject()
        data.put("granted", granted)
        call.resolve(data)
    }

    @PluginMethod
    fun touchDown(call: PluginCall) {
        val id = call.getInt("pointerId") ?: 0
        val x = call.getFloat("x") ?: 0f
        val y = call.getFloat("y") ?: 0f
        try {
            touchService?.touchDown(id, x, y)
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
            touchService?.touchMove(id, x, y)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun touchUp(call: PluginCall) {
        val id = call.getInt("pointerId") ?: 0
        try {
            touchService?.touchUp(id)
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
            touchService?.injectTap(x, y)
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

    // ============================================================
    // Public overlay-facing helpers (used by FloatingOverlayService)
    // — made @JvmStatic for cleaner Java interop
    // ============================================================
    @JvmStatic
    fun injectTouchDown(pointerId: Int, x: Float, y: Float) {
        try { touchService?.touchDown(pointerId, x, y) }
        catch (e: Exception) { Log.e("GameMapper", "overlay touchDown failed", e) }
    }

    @JvmStatic
    fun injectTouchMove(pointerId: Int, x: Float, y: Float) {
        try { touchService?.touchMove(pointerId, x, y) }
        catch (e: Exception) { Log.e("GameMapper", "overlay touchMove failed", e) }
    }

    @JvmStatic
    fun injectTouchUp(pointerId: Int) {
        try { touchService?.touchUp(pointerId) }
        catch (e: Exception) { Log.e("GameMapper", "overlay touchUp failed", e) }
    }

    @JvmStatic
    fun injectTapFromOverlay(x: Float, y: Float) {
        try { touchService?.injectTap(x, y) }
        catch (e: Exception) { Log.e("GameMapper", "overlay tap failed", e) }
    }
}
