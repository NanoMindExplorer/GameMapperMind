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

    private PluginCall permissionCall = null;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode == 1001 && permissionCall != null) {
                JSObject result = new JSObject();
                result.put("granted", grantResult == PackageManager.PERMISSION_GRANTED);
                permissionCall.resolve(result);
                permissionCall = null;
                Shizuku.removeRequestPermissionResultListener(this);
            }
        }
    };

    @PluginMethod
    public void requestPermission(PluginCall call) {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    JSObject result = new JSObject();
                    result.put("granted", true);
                    call.resolve(result);
                    return;
                }
                permissionCall = call;
                Shizuku.addRequestPermissionResultListener(permissionListener);
                Shizuku.requestPermission(1001);
            } else {
                call.reject("Shizuku is not running");
            }
        } catch (Exception e) {
            e.printStackTrace();
            call.reject("Error requesting permission", e);
        }
    }

    @PluginMethod
    public void startDaemon(PluginCall call) {
        Log.d("GameMapper", "Shizuku startDaemon called");
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (persistentProcess == null) {
                    java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    persistentProcess = (Process) method.invoke(null, new Object[]{new String[]{"sh"}, null, null});
                    processOutputStream = new DataOutputStream(persistentProcess.getOutputStream());
                    
                    String apkPath = getContext().getApplicationInfo().sourceDir;
                    processOutputStream.writeBytes("export CLASSPATH=" + apkPath + "\n");
                    processOutputStream.writeBytes("exec app_process /system/bin com.nanomindexplorer.gamemappermind.TouchDaemon\n");
                    processOutputStream.flush();

                    // Drain stdout to prevent blocking
                    new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(persistentProcess.getInputStream()))) {
                            while (reader.readLine() != null) {}
                        } catch (Exception e) {}
                    }).start();

                    // Drain stderr to prevent blocking
                    new Thread(() -> {
                        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(persistentProcess.getErrorStream()))) {
                            while (errorReader.readLine() != null) {}
                        } catch (Exception e) {}
                    }).start();

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
            if (command.startsWith("down ") || command.startsWith("up ") || command.startsWith("move ")) {
                processOutputStream.writeBytes(command + "\n");
            } else if (command.startsWith("input tap ")) {
                String[] parts = command.split(" ");
                if (parts.length >= 4) {
                    String x = parts[2];
                    String y = parts[3];
                    processOutputStream.writeBytes("down " + x + " " + y + "\n");
                    processOutputStream.writeBytes("up " + x + " " + y + "\n");
                }
            } else if (command.startsWith("input swipe ")) {
                 String[] parts = command.split(" ");
                 if (parts.length >= 6) {
                    String x1 = parts[2];
                    String y1 = parts[3];
                    String x2 = parts[4];
                    String y2 = parts[5];
                    processOutputStream.writeBytes("down " + x1 + " " + y1 + "\n");
                    processOutputStream.writeBytes("move " + x2 + " " + y2 + "\n");
                    processOutputStream.writeBytes("up " + x2 + " " + y2 + "\n");
                 }
            } else {
                processOutputStream.writeBytes(command + "\n");
            }
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
                    process = (Process) method.invoke(null, new Object[]{new String[]{"sh", "-c", command}, null, null});
                } catch (Exception e) {
                    call.reject("Failed to invoke newProcess via reflection", e);
                    return;
                }
                
                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();
                
                final Process p = process;
                Thread outThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                        }
                    } catch (Exception e) {}
                });
                
                Thread errThread = new Thread(() -> {
                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                        String errorLine;
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorOutput.append(errorLine).append("\n");
                        }
                    } catch (Exception e) {}
                });
                
                outThread.start();
                errThread.start();

                int exitCode = process.waitFor();
                outThread.join();
                errThread.join();
                
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

    @PluginMethod
    public void checkBatteryOptimization(PluginCall call) {
        boolean isIgnoring = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                isIgnoring = pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
            }
        } catch (Exception e) {
            Log.e("GameMapper", "checkBatteryOptimization error", e);
        }
        JSObject result = new JSObject();
        result.put("isIgnoring", isIgnoring);
        call.resolve(result);
    }

    @PluginMethod
    public void requestIgnoreBatteryOptimization(PluginCall call) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.content.Intent intent = new android.content.Intent();
                intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to request battery optimization override", e);
        }
    }
}

