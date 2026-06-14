package com.nanomindexplorer.gamemappermind;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class TouchDaemon {
    public static void main(String[] args) {
        Log.i("GameMapper", "TouchDaemon native binder started in app_process!");
        
        try {
            Method getRuntime = Class.forName("dalvik.system.VMRuntime").getDeclaredMethod("getRuntime");
            Object vmRuntime = getRuntime.invoke(null);
            Method setHiddenApiExemptions = vmRuntime.getClass().getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            Log.i("GameMapper", "Hidden API exemptions applied");
        } catch (Throwable t) {
            Log.w("GameMapper", "Could not bypass hidden API restrictions", t);
        }

        try {
            InputManager im = (InputManager) InputManager.class.getDeclaredMethod("getInstance").invoke(null);
            Method injectMethod = InputManager.class.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            injectMethod.setAccessible(true);

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String[] parts = line.trim().split(" ");
                    if (parts.length >= 3) {
                        String action = parts[0];
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        long now = SystemClock.uptimeMillis();
                        int actionCode = -1;

                        if (action.equals("down")) {
                            actionCode = MotionEvent.ACTION_DOWN;
                        } else if (action.equals("up")) {
                            actionCode = MotionEvent.ACTION_UP;
                        } else if (action.equals("move")) {
                            actionCode = MotionEvent.ACTION_MOVE;
                        }

                        if (actionCode != -1) {
                            MotionEvent ev = MotionEvent.obtain(now, now, actionCode, x, y, 0);
                            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                            injectMethod.invoke(im, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
                            ev.recycle();
                        }
                    }
                } catch (Exception parseEx) {
                    Log.e("GameMapper", "Parse command error", parseEx);
                }
            }
        } catch (Exception e) {
            Log.e("GameMapper", "TouchDaemon error", e);
        }
    }
}
