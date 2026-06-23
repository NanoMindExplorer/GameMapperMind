package com.nanomindexplorer.gamemappermind;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import rikka.shizuku.Shizuku;

@CapacitorPlugin(name = "ShizukuPlugin")
public class ShizukuPlugin extends Plugin {
    static final int REQUEST_CODE = 1001;

    @PluginMethod
    public void checkStatus(PluginCall call) {
        try {
            boolean available = Shizuku.pingBinder();
            JSObject result = new JSObject();
            result.put("available", available);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Shizuku is not running", e);
        }
    }
}
