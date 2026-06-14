package com.nanomindexplorer.gamemappermind;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.net.Uri;
import android.provider.Settings;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        registerPlugin(ShizukuPlugin.class);
        registerPlugin(OverlayPlugin.class);

        // Check Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        
        // Aktifkan Gamepad API di WebView
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().getSettings().setJavaScriptEnabled(true);
            bridge.getWebView().setFocusable(true);
            bridge.getWebView().setFocusableInTouchMode(true);
            bridge.getWebView().requestFocus();
        }
    }

    // Teruskan event gamepad ke WebView
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            if (bridge != null && bridge.getWebView() != null) {
                return bridge.getWebView().dispatchKeyEvent(event);
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
