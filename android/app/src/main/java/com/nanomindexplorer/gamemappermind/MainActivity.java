package com.nanomindexplorer.gamemappermind;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.InputDevice;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        registerPlugin(ShizukuPlugin.class);
        
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
