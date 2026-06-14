package com.nanomindexplorer.gamemappermind;

import android.content.pm.PackageManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import rikka.shizuku.Shizuku;

@CapacitorPlugin(name = "Shizuku")
public class ShizukuPlugin extends Plugin {

    @PluginMethod
    public void checkStatus(PluginCall call) {
        boolean isRunning = false;
        boolean hasPermission = false;
        try {
            isRunning = Shizuku.pingBinder();
            if (isRunning) {
                hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        JSObject result = new JSObject();
        result.put("isRunning", isRunning);
        result.put("hasPermission", hasPermission);
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(1001);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        call.resolve();
    }
}
