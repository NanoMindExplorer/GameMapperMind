package com.nanomindexplorer.gamemappermind;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Overlay")
public class OverlayPlugin extends Plugin {

    @PluginMethod
    public void startOverlay(PluginCall call) {
        Log.d("GameMapper", "startOverlay requested");
        if (!Settings.canDrawOverlays(getContext())) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.reject("Please grant overlay permission");
            return;
        }

        String config = call.getString("config", "{}");

        Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
        serviceIntent.putExtra("config", config);
        
        try {
            Log.d("GameMapper", "Attempting to start context foreground service");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(serviceIntent);
            } else {
                getContext().startService(serviceIntent);
            }
            Log.d("GameMapper", "startOverlay service launched successfully");
            call.resolve();
        } catch (Exception e) {
            Log.e("GameMapper", "Failed to start overlay service", e);
            call.reject("Failed to start overlay service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopOverlay(PluginCall call) {
        Log.d("GameMapper", "stopOverlay requested");
        Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
        getContext().stopService(serviceIntent);
        call.resolve();
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        boolean hasPermission = Settings.canDrawOverlays(getContext());
        JSObject ret = new JSObject();
        ret.put("hasPermission", hasPermission);
        call.resolve(ret);
    }
}
