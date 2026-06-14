package com.nanomindexplorer.gamemappermind;

import android.content.pm.PackageManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import rikka.shizuku.Shizuku;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

@CapacitorPlugin(name = "Shizuku")
public class ShizukuPlugin extends Plugin {

    private Process persistentProcess = null;
    private DataOutputStream processOutputStream = null;

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
            Log.e("GameMapper", "checkStatus error", e);
            e.printStackTrace();
        }

        Log.d("GameMapper", "Shizuku status: running=" + isRunning + ", permission=" + hasPermission);
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

    @PluginMethod
    public void startDaemon(PluginCall call) {
        Log.d("GameMapper", "Shizuku startDaemon called");
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (persistentProcess == null) {
                    java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    persistentProcess = (Process) method.invoke(null, new String[]{"sh"}, null, null);
                    processOutputStream = new DataOutputStream(persistentProcess.getOutputStream());
                    Log.d("GameMapper", "Shizuku daemon shell started from JS");
                }
                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            } else {
                call.reject("Shizuku permission not granted");
            }
        } catch (Exception e) {
            call.reject("Failed to start daemon", e);
        }
    }

    @PluginMethod
    public void stopDaemon(PluginCall call) {
        if (processOutputStream != null) {
            try {
                processOutputStream.writeBytes("exit\n");
                processOutputStream.flush();
                processOutputStream.close();
            } catch (Exception e) { e.printStackTrace(); }
            processOutputStream = null;
        }
        if (persistentProcess != null) {
            persistentProcess.destroy();
            persistentProcess = null;
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void injectInput(PluginCall call) {
        String command = call.getString("command"); // e.g. "input tap x y"
        if (command == null || processOutputStream == null) {
            call.reject("No command or daemon not running");
            return;
        }

        try {
            processOutputStream.writeBytes(command + "\n");
            processOutputStream.flush();
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to inject", e);
        }
    }

    @PluginMethod
    public void executeCommand(PluginCall call) {
        String command = call.getString("command");
        if (command == null) {
            call.reject("Command is required");
            return;
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Process process = null;
                try {
                    java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    process = (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
                } catch (Exception e) {
                    call.reject("Failed to invoke newProcess via reflection", e);
                    return;
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder errorOutput = new StringBuilder();
                String errorLine;
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }

                int exitCode = process.waitFor();
                
                JSObject result = new JSObject();
                result.put("output", output.toString());
                result.put("error", errorOutput.toString());
                result.put("exitCode", exitCode);
                call.resolve(result);
            } else {
                call.reject("Shizuku permission not granted");
            }
        } catch (Exception e) {
            call.reject("Error executing command", e);
        }
    }
}

