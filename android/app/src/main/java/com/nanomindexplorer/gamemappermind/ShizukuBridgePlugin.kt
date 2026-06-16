package com.nanomindexplorer.gamemappermind

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.rikka.shizuku.Shizuku

@CapacitorPlugin(name = "ShizukuBridge")
class ShizukuBridgePlugin : Plugin() {

    private var touchService: ITouchService? = null
    private val USER_SERVICE_ARGS = Shizuku.UserServiceArgs(
        ComponentName("com.nanomindexplorer.gamemappermind", TouchDaemonService::class.java.name)
    ).daemon(false).processNameSuffix("touch_daemon").version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            touchService = ITouchService.Stub.asInterface(binder)
            Log.i("GameMapper", "Shizuku user service connected.")
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            touchService = null
            Log.i("GameMapper", "Shizuku user service disconnected.")
        }
    }

    // [VERIFIED] - Returns Shizuku permission status
    @PluginMethod
    fun checkStatus(call: PluginCall) {
        val ret = JSObject()
        try {
            if (Shizuku.pingBinder()) {
                val hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ret.put("running", true)
                ret.put("authorized", hasPermission)
            } else {
                ret.put("running", false)
                ret.put("authorized", false)
            }
        } catch (e: Exception) {
            ret.put("running", false)
            ret.put("authorized", false)
        }
        call.resolve(ret)
    }

    // [UNVERIFIED] - Binds Shizuku service, needs physical device to verify connection
    @PluginMethod
    fun startDaemon(call: PluginCall) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                call.resolve(JSObject().put("success", true))
            } else {
                call.reject("Shizuku permission not granted.")
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to start daemon", e)
            call.reject("Failed to start daemon", e)
        }
    }

    // [VERIFIED] - Unbinds service
    @PluginMethod
    fun stopDaemon(call: PluginCall) {
        try {
            Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
            touchService = null
            call.resolve(JSObject().put("success", true))
        } catch (e: Exception) {
            call.reject("Failed to stop daemon", e)
        }
    }

    // [UNVERIFIED] - Injects tap using TouchDaemonService via AIDL, requires device testing
    @PluginMethod
    fun injectTap(call: PluginCall) {
        val x = call.getInt("x") ?: 0
        val y = call.getInt("y") ?: 0
        
        try {
            if (touchService != null && touchService!!.isAlive()) {
                touchService!!.injectTap(x, y)
                call.resolve(JSObject().put("success", true))
            } else {
                call.reject("Touch service is not alive or not connected")
            }
        } catch (e: RemoteException) {
            Log.e("GameMapper", "RemoteException on injectTap", e)
            call.reject("Error injecting tap remotely", e)
        } catch (e: Exception) {
            Log.e("GameMapper", "Inject error", e)
            call.reject("Error injecting tap", e)
        }
    }

    // [UNVERIFIED] - Injects swipe using TouchDaemonService via AIDL, requires device testing
    @PluginMethod
    fun injectSwipe(call: PluginCall) {
        val x1 = call.getInt("x1") ?: 0
        val y1 = call.getInt("y1") ?: 0
        val x2 = call.getInt("x2") ?: 0
        val y2 = call.getInt("y2") ?: 0
        val duration = call.getInt("duration") ?: 16
        
        try {
            if (touchService != null && touchService!!.isAlive()) {
                touchService!!.injectSwipe(x1, y1, x2, y2, duration)
                call.resolve(JSObject().put("success", true))
            } else {
                call.reject("Touch service is not alive or not connected")
            }
        } catch (e: RemoteException) {
            Log.e("GameMapper", "RemoteException on injectSwipe", e)
            call.reject("Error injecting swipe remotely", e)
        } catch (e: Exception) {
            Log.e("GameMapper", "Inject error", e)
            call.reject("Error injecting swipe", e)
        }
    }

    @PluginMethod
    fun touchDown(call: PluginCall) {
        val x = call.getInt("x") ?: 0
        val y = call.getInt("y") ?: 0
        val pointerId = call.getInt("pointerId") ?: 0
        
        try {
            if (touchService != null && touchService!!.isAlive()) {
                touchService!!.touchDown(x, y, pointerId)
                call.resolve(JSObject().put("success", true))
            } else {
                call.reject("Touch service is not alive or not connected")
            }
        } catch (e: RemoteException) {
            Log.e("GameMapper", "RemoteException on touchDown", e)
            call.reject("Error injecting touchDown remotely", e)
        } catch (e: Exception) {
            Log.e("GameMapper", "Inject error", e)
            call.reject("Error injecting touchDown", e)
        }
    }

    @PluginMethod
    fun touchMove(call: PluginCall) {
        val x = call.getInt("x") ?: 0
        val y = call.getInt("y") ?: 0
        val pointerId = call.getInt("pointerId") ?: 0
        
        try {
            if (touchService != null && touchService!!.isAlive()) {
                touchService!!.touchMove(x, y, pointerId)
                call.resolve(JSObject().put("success", true))
            } else {
                call.reject("Touch service is not alive or not connected")
            }
        } catch (e: RemoteException) {
            Log.e("GameMapper", "RemoteException on touchMove", e)
            call.reject("Error injecting touchMove remotely", e)
        } catch (e: Exception) {
            Log.e("GameMapper", "Inject error", e)
            call.reject("Error injecting touchMove", e)
        }
    }

    @PluginMethod
    fun touchUp(call: PluginCall) {
        val pointerId = call.getInt("pointerId") ?: 0
        
        try {
            if (touchService != null && touchService!!.isAlive()) {
                touchService!!.touchUp(pointerId)
                call.resolve(JSObject().put("success", true))
            } else {
                call.reject("Touch service is not alive or not connected")
            }
        } catch (e: RemoteException) {
            Log.e("GameMapper", "RemoteException on touchUp", e)
            call.reject("Error injecting touchUp remotely", e)
        } catch (e: Exception) {
            Log.e("GameMapper", "Inject error", e)
            call.reject("Error injecting touchUp", e)
        }
    }
}
