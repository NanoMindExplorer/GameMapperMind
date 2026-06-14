package com.nanomindexplorer.gamemappermind;

import android.content.pm.PackageManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import rikka.shizuku.Shizuku;

import java.io.BufferedReader;
import java.io.InputStreamReader;

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

    @PluginMethod
    public void executeCommand(PluginCall call) {
        String command = call.getString("command");
        if (command == null) {
            call.reject("Command is required");
            return;
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Process process = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
                
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

