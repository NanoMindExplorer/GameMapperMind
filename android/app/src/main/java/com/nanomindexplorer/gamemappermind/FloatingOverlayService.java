package com.nanomindexplorer.gamemappermind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.webkit.WebViewAssetLoader;

import com.getcapacitor.Bridge;
import com.getcapacitor.Plugin;

public class FloatingOverlayService extends Service {
    private WindowManager windowManager;
    private WebView webView;
    private WindowManager.LayoutParams webViewParams;
    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private String currentConfigJson = "{}";

    public FloatingOverlayService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Floating Overlay for GameMapperMind");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("GameMapper", "FloatingOverlayService onStartCommand");
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GameMapperMind Overlay")
                .setContentText("Overlay is running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, notification);
            }
        } catch (Exception e) {
            Log.e("GameMapper", "startForeground failed", e);
        }

        if (intent != null && intent.hasExtra("config")) {
            currentConfigJson = intent.getStringExtra("config");
            if (webView != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    webView.evaluateJavascript(
                        "if(window.injectConfig) window.injectConfig('" + currentConfigJson.replace("'", "\\'") + "');",
                        null
                    );
                });
            }
        }

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("GameMapper", "FloatingOverlayService onCreate() called");
        createNotificationChannel();

        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            new Handler(Looper.getMainLooper()).post(() -> {
                webView = new WebView(FloatingOverlayService.this);
                webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
                webView.setBackgroundColor(0x00000000);

                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setMediaPlaybackRequiresUserGesture(false);

                webView.setFocusable(true);
                webView.setFocusableInTouchMode(true);

                final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                        .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(FloatingOverlayService.this))
                        .build();

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        return assetLoader.shouldInterceptRequest(request.getUrl());
                    }
                });

                webView.addJavascriptInterface(new WebAppInterface(), "AndroidOverlay");

                webViewParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                webViewParams.gravity = Gravity.FILL;

                windowManager.addView(webView, webViewParams);
                webView.requestFocus();

                Log.d("GameMapper", "Loading index.html into Overlay WebView");
                webView.loadUrl("https://appassets.androidplatform.net/public/index.html?overlay=true");
            });
        } catch (Exception e) {
            Log.e("GameMapper", "Error in onCreate of FloatingOverlayService", e);
        }
    }

    /**
     * Bridge exposed to the overlay WebView as window.AndroidOverlay.
     * React-side OverlayWysiwyg calls these methods when the user taps virtual
     * buttons in play mode (Issue #27 fix: was previously an empty stub).
     */
    private class WebAppInterface {
        @JavascriptInterface
        public void onReactReady() {
            Log.d("GameMapper", "React is ready in Overlay");
            new Handler(Looper.getMainLooper()).post(() -> {
                if (currentConfigJson != null && !currentConfigJson.isEmpty()) {
                    webView.evaluateJavascript(
                        "if (window.injectConfig) { window.injectConfig('" + currentConfigJson.replace("'", "\\'") + "'); }",
                        null
                    );
                }
            });
        }

        /**
         * Handle commands from the React overlay.
         * Supported formats (consistent with useShizuku.injectInput parser):
         *   "down <x> <y>"   → touchDown
         *   "move <x> <y>"   → touchMove
         *   "up <x> <y>"     → touchUp
         *   "tap <x> <y>"    → injectTap
         * Coordinates are absolute pixels (NOT pre-multiplied by devicePixelRatio).
         */
        @JavascriptInterface
        public void onCommand(String command) {
            if (command == null || command.isEmpty()) return;
            String[] parts = command.trim().split("\\s+");
            if (parts.length < 1) return;

            String action = parts[0].toLowerCase();
            try {
                // x / y are optional for "up" actions
                int x = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                int y = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

                switch (action) {
                    case "down":
                        TouchInjectionPlugin.emitGamepadButton("OVERLAY_DOWN", 1, 1.0f);
                        // Forward to the active Shizuku user service (if bound)
                        forwardToTouchService("touchDown", 99, x, y);
                        break;
                    case "move":
                        forwardToTouchService("touchMove", 99, x, y);
                        break;
                    case "up":
                        forwardToTouchService("touchUp", 99, 0, 0);
                        break;
                    case "tap":
                        forwardToTouchService("injectTap", 0, x, y);
                        break;
                    default:
                        Log.w("GameMapper", "Unknown overlay command: " + command);
                }
            } catch (NumberFormatException e) {
                Log.e("GameMapper", "Bad command format: " + command, e);
            }
        }

        private void forwardToTouchService(String method, int pointerId, float x, float y) {
            // The Overlay service runs in the app process. We delegate to the
            // TouchInjectionPlugin singleton (bound to the Shizuku UserService).
            try {
                TouchInjectionPlugin plugin = TouchInjectionPlugin.instance;
                if (plugin == null) {
                    Log.w("GameMapper", "TouchInjectionPlugin not loaded; cannot " + method);
                    return;
                }
                // Use reflection-free public API on TouchInjectionPlugin:
                // we re-export a static helper there.
                switch (method) {
                    case "touchDown": plugin.injectTouchDown(pointerId, x, y); break;
                    case "touchMove": plugin.injectTouchMove(pointerId, x, y); break;
                    case "touchUp":   plugin.injectTouchUp(pointerId);          break;
                    case "injectTap": plugin.injectTapFromOverlay(x, y);        break;
                }
            } catch (Exception e) {
                Log.e("GameMapper", "forwardToTouchService failed: " + method, e);
            }
        }

        @JavascriptInterface
        public void closeOverlay() {
            new Handler(Looper.getMainLooper()).post(() -> stopSelf());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            windowManager.removeView(webView);
            webView.destroy();
            webView = null;
        }
    }
}
