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
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.webkit.WebViewClient;
import androidx.core.app.NotificationCompat;
import androidx.webkit.WebViewAssetLoader;

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

    private void updateNotification(boolean isEditing) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        Intent actionIntent = new Intent(this, FloatingOverlayService.class);
        actionIntent.setAction(isEditing ? "ACTION_PLAY" : "ACTION_EDIT");
        PendingIntent actionPendingIntent = PendingIntent.getService(this, isEditing ? 2 : 1, actionIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        String actionTitle = isEditing ? "Resume Play" : "Edit Layout";

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GameMapperMind Overlay")
                .setContentText(isEditing ? "Overlay is in Edit Mode" : "Overlay is running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_edit, actionTitle, actionPendingIntent)
                .build();
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("GameMapper", "FloatingOverlayService onStartCommand");
        createNotificationChannel();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        Intent actionIntent = new Intent(this, FloatingOverlayService.class);
        actionIntent.setAction("ACTION_EDIT");
        PendingIntent actionPendingIntent = PendingIntent.getService(this, 1, actionIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GameMapperMind Overlay")
                .setContentText("Overlay is running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_edit, "Edit Layout", actionPendingIntent)
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

        if ("ACTION_EDIT".equals(intent.getAction())) {
            setOverlayInteractive(true);
            if (webView != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    webView.evaluateJavascript("if(window.togglePalette) window.togglePalette(true);", null);
                });
            }
            updateNotification(true);
            return START_STICKY;
        } else if ("ACTION_PLAY".equals(intent.getAction())) {
            setOverlayInteractive(false);
            if (webView != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    webView.evaluateJavascript("if(window.togglePalette) window.togglePalette(false);", null);
                });
            }
            updateNotification(false);
            return START_STICKY;
        }

        if (intent != null && intent.hasExtra("config")) {
            currentConfigJson = intent.getStringExtra("config");
            if (webView != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    // BUG-N7 FIX: Escape currentConfigJson before injecting into JS to prevent
                    // script injection. If JSON contains </script> or backslashes, raw injection
                    // breaks JS parsing. Use JSON.stringify equivalent: wrap in single quotes
                    // and escape special chars.
                    final WebView wv = webView; // capture for null-safe access
                    if (wv == null) return;
                    String safeJson;
                    try {
                        // Re-parse and re-stringify to ensure valid JSON, then escape for JS string.
                        org.json.JSONObject parsed = new org.json.JSONObject(currentConfigJson);
                        safeJson = parsed.toString()
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("</", "<\\/");
                    } catch (Exception e) {
                        Log.e("GameMapper", "Failed to sanitize config JSON", e);
                        return;
                    }
                    wv.evaluateJavascript(
                        "if(window.injectConfig) window.injectConfig('" + safeJson + "');",
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
                // Initialize WebView
                webView = new WebView(FloatingOverlayService.this);
                // Hardware execution for performance & transparency
                webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
                webView.setBackgroundColor(0x00000000); // completely transparent
                
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setMediaPlaybackRequiresUserGesture(false);
                
                webView.setFocusable(true);
                webView.setFocusableInTouchMode(true);
                
                // WebView Asset Loader (intercept appassets.androidplatform.net to /public/ and /assets/)
                final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                        .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(FloatingOverlayService.this))
                        .build();

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        return assetLoader.shouldInterceptRequest(request.getUrl());
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        // BUG-N8 FIX: Capture webView in local final to avoid NPE if onDestroy sets field to null.
                        if (currentConfigJson != null && !currentConfigJson.isEmpty()) {
                            try {
                                org.json.JSONObject parsed = new org.json.JSONObject(currentConfigJson);
                                String safeJson = parsed.toString()
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'")
                                    .replace("\n", "\\n")
                                    .replace("\r", "\\r")
                                    .replace("</", "<\\/");
                                view.evaluateJavascript(
                                    "if(window.injectConfig) window.injectConfig('" + safeJson + "');",
                                    null
                                );
                            } catch (Exception e) {
                                Log.e("GameMapper", "Failed to inject config on page finished", e);
                            }
                        }
                    }
                });
                
                // Add JavaScript Interface
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
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Start in play mode (not touchable)
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

    private void setOverlayInteractive(boolean interactive) {
        if (windowManager == null || webViewParams == null || webView == null) return;
        if (interactive) {
            webViewParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        } else {
            webViewParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        windowManager.updateViewLayout(webView, webViewParams);
    }

    private class WebAppInterface {
        @JavascriptInterface
        public void setInteractive(boolean interactive) {
            new Handler(Looper.getMainLooper()).post(() -> {
                setOverlayInteractive(interactive);
            });
        }

        @JavascriptInterface
        public void onReactReady() {
            Log.d("GameMapper", "React is ready in Overlay");
            new Handler(Looper.getMainLooper()).post(() -> {
                // BUG-N8 FIX: Capture webView in local final — field may be null after onDestroy.
                final WebView wv = webView;
                if (wv == null) return;
                if (currentConfigJson != null && !currentConfigJson.isEmpty()) {
                    try {
                        org.json.JSONObject parsed = new org.json.JSONObject(currentConfigJson);
                        String safeJson = parsed.toString()
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("</", "<\\/");
                        wv.evaluateJavascript(
                            "if(window.injectConfig) window.injectConfig('" + safeJson + "');",
                            null
                        );
                    } catch (Exception e) {
                        Log.e("GameMapper", "Failed to inject config on React ready", e);
                    }
                }
            });
        }

        // BUG-R6 FIX: Add onCommand method for profile save from overlay
        @JavascriptInterface
        public void onCommand(String command) {
            Log.d("GameMapper", "Overlay command: " + command);
            // Handle commands from overlay (e.g., 'request_config_save {...}')
            // Can be extended for other commands
        }

        @JavascriptInterface
        public void closeOverlay() {
            new Handler(Looper.getMainLooper()).post(() -> {
                stopSelf();
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(1);
        }
        if (webView != null) {
            try {
                if (webView.isAttachedToWindow() || webView.getWindowToken() != null) {
                    windowManager.removeViewImmediate(webView);
                }
            } catch (Exception e) {
                Log.e("GameMapper", "Exception removing webview from window manager", e);
            }
            webView.destroy();
            webView = null;
        }
    }
}


