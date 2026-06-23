package com.nanomindexplorer.gamemappermind

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import rikka.shizuku.Shizuku

@CapacitorPlugin(name = "ShizukuPlugin")
class ShizukuPlugin : Plugin() {

    private var iService: IGamepadDaemonService? = null
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            iService = IGamepadDaemonService.Stub.asInterface(service)
            val ret = JSObject()
            ret.put("status", "CONNECTED_SHIZUKU")
            notifyListeners("shizukuStatusChange", ret)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            iService = null
            val ret = JSObject()
            ret.put("status", "DISCONNECTED")
            notifyListeners("shizukuStatusChange", ret)
        }
    }

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        val granted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        val ret = JSObject()
        ret.put("granted", granted)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        if (Shizuku.isPreV11()) {
            call.reject("Shizuku is not running or version is too old")
            return
        }
        
        Shizuku.requestPermission(100)
        call.resolve()
    }
    
    @PluginMethod
    fun startDaemon(call: PluginCall) {
        val intent = android.content.Intent("com.nanomindexplorer.gamemappermind.TouchDaemonService")
        intent.setPackage(context.packageName)
        try {
            Shizuku.bindUserService(Shizuku.UserServiceArgs(ComponentName(context.packageName, TouchDaemonService::class.java.name)).daemon(false), connection)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Failed to bind Shizuku user service", e)
        }
    }

    @PluginMethod
    fun stopDaemon(call: PluginCall) {
        Shizuku.unbindUserService(Shizuku.UserServiceArgs(ComponentName(context.packageName, TouchDaemonService::class.java.name)), connection, true)
        call.resolve()
    }
}
