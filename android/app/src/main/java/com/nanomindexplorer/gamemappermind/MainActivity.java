package com.nanomindexplorer.gamemappermind;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.net.Uri;
import android.provider.Settings;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private androidx.activity.result.ActivityResultLauncher<Intent> overlayPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(ShizukuPlugin.class);
        registerPlugin(OverlayPlugin.class);

        overlayPermissionLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                        android.util.Log.d("GameMapper", "Overlay permission granted! Auto-starting service.");
                        Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }
                    }
                }
        );

        // Check Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayPermissionLauncher.launch(intent);
        }
        
        // Aktifkan Gamepad API & properti WebView
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().getSettings().setJavaScriptEnabled(true);
            bridge.getWebView().getSettings().setDomStorageEnabled(true);
            bridge.getWebView().setFocusable(true);
            bridge.getWebView().setFocusableInTouchMode(true);
            bridge.getWebView().requestFocus();
            
            // Nonaktifkan scroll (overscroll dan scroll bars)
            bridge.getWebView().setOverScrollMode(View.OVER_SCROLL_NEVER);
            bridge.getWebView().setVerticalScrollBarEnabled(false);
            bridge.getWebView().setHorizontalScrollBarEnabled(false);
        }
    }

    // Teruskan event gamepad ke WebView
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().dispatchKeyEvent(event);
                return true; // Consume event to prevent default behavior
            }
        }
        return super.dispatchKeyEvent(event);
    }
    
    // Teruskan event analog (joystick motion)
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().dispatchGenericMotionEvent(event);
                return true; // Consume event to prevent default scrolling
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }
}
