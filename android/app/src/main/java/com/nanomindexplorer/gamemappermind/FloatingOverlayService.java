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

import java.io.DataOutputStream;

import android.widget.ImageView;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

public class FloatingOverlayService extends Service {
    private WindowManager windowManager;
    private WebView webView;
    private android.view.View floatingButton;
    private WindowManager.LayoutParams webViewParams;
    private WindowManager.LayoutParams floatButtonParams;
    private boolean isEditMode = false;
    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private String currentConfigJson = "{}";
    
    // Persistent Shizuku Shell for ultra-fast injection
    private Process shizukuProcess;
    private DataOutputStream shizukuOut;

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
    
    private void initShizukuDaemon() {
        try {
            if (rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (shizukuProcess == null) {
                    Log.d("GameMapper", "Starting persistent Shizuku shell process");
                    java.lang.reflect.Method method = rikka.shizuku.Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    shizukuProcess = (Process) method.invoke(null, new Object[]{new String[]{"sh"}, null, null});
                    shizukuOut = new DataOutputStream(shizukuProcess.getOutputStream());
                    
                    // Boot up the TouchDaemon inside the shell using app_process
                    String apkPath = getApplicationInfo().sourceDir;
                    shizukuOut.writeBytes("export CLASSPATH=" + apkPath + "\n");
                    shizukuOut.writeBytes("exec app_process /system/bin com.nanomindexplorer.gamemappermind.TouchDaemon\n");
                    shizukuOut.flush();
                    
                    // Create Background Threads to drain stdout and stderr
                    new Thread(() -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(shizukuProcess.getInputStream()))) {
                            while (reader.readLine() != null) {}
                        } catch (Exception e) {}
                    }).start();

                    new Thread(() -> {
                        try (java.io.BufferedReader errReader = new java.io.BufferedReader(new java.io.InputStreamReader(shizukuProcess.getErrorStream()))) {
                            while (errReader.readLine() != null) {}
                        } catch (Exception e) {}
                    }).start();
                    
                    Log.d("GameMapper", "Shizuku daemon shell started successfully");
                }
            } else {
                Log.w("GameMapper", "Shizuku not ready or permission denied");
            }
        } catch (Exception e) {
            Log.e("GameMapper", "Failed to init Shizuku daemon", e);
        }
    }

    private void injectCommand(String cmd) {
        if (shizukuOut != null) {
            try {
                Log.d("GameMapper", "Injecting fast command: " + cmd);
                shizukuOut.writeBytes(cmd + "\n");
                shizukuOut.flush();
            } catch (Exception e) {
                Log.e("GameMapper", "Fast injection failed", e);
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(FloatingOverlayService.this, "Shizuku injection failed!", Toast.LENGTH_SHORT).show());
            }
        } else {
            Log.w("GameMapper", "Cannot inject, Shizuku daemon out stream is null");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("GameMapper", "FloatingOverlayService onStartCommand");
        createNotificationChannel();
        initShizukuDaemon();
        
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
                    // Update running webview
                    webView.evaluateJavascript("if(window.injectConfig) window.injectConfig('" + currentConfigJson.replace("'", "\\'") + "');", null);
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
                
                // Create Floating Button for Toggling Mode
                android.widget.TextView floatingButtonTxt = new android.widget.TextView(FloatingOverlayService.this);
                floatingButtonTxt.setText("☰ NEX");
                floatingButtonTxt.setTextColor(Color.WHITE);
                floatingButtonTxt.setTextSize(16);
                floatingButtonTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                floatingButtonTxt.setBackgroundColor(Color.parseColor("#80000000"));
                floatingButtonTxt.setPadding(30, 30, 30, 30);
                
                floatingButton = floatingButtonTxt;
                
                floatButtonParams = new WindowManager.LayoutParams(
                        150,
                        150,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT);
                
                floatButtonParams.gravity = Gravity.TOP | Gravity.LEFT;
                floatButtonParams.x = 100;
                floatButtonParams.y = 100;
                
                floatingButton.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX;
                    private int initialY;
                    private float initialTouchX;
                    private float initialTouchY;
                    private boolean isMoved = false;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                initialX = floatButtonParams.x;
                                initialY = floatButtonParams.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                isMoved = false;
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                int dx = (int) (event.getRawX() - initialTouchX);
                                int dy = (int) (event.getRawY() - initialTouchY);
                                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isMoved = true;
                                floatButtonParams.x = initialX + dx;
                                floatButtonParams.y = initialY + dy;
                                windowManager.updateViewLayout(floatingButton, floatButtonParams);
                                return true;
                            case MotionEvent.ACTION_UP:
                                if (!isMoved) {
                                    isEditMode = !isEditMode;
                                    if (isEditMode) {
                                        webViewParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                        webViewParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                                        ((android.widget.TextView)floatingButton).setTextColor(Color.GREEN);
                                        webView.evaluateJavascript("if(window.togglePalette) window.togglePalette(true);", null);
                                    } else {
                                        webViewParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                        webViewParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                                        ((android.widget.TextView)floatingButton).setTextColor(Color.WHITE);
                                        webView.evaluateJavascript("if(window.togglePalette) window.togglePalette(false);", null);
                                    }
                                    windowManager.updateViewLayout(webView, webViewParams);
                                }
                                return true;
                        }
                        return false;
                    }
                });
                
                windowManager.addView(floatingButton, floatButtonParams);
                
                // Load URL (WebViewAssetLoader uses https://appassets.androidplatform.net/)
                Log.d("GameMapper", "Loading index.html into Overlay WebView");
                webView.loadUrl("https://appassets.androidplatform.net/public/index.html?overlay=true");
            });
        } catch (Exception e) {
            Log.e("GameMapper", "Error in onCreate of FloatingOverlayService", e);
        }
    }

    private class WebAppInterface {
        @JavascriptInterface
        public void onReactReady() {
            Log.d("GameMapper", "React is ready in Overlay");
            new Handler(Looper.getMainLooper()).post(() -> {
                if (currentConfigJson != null && !currentConfigJson.isEmpty()) {
                    webView.evaluateJavascript("if (window.injectConfig) { window.injectConfig('" + currentConfigJson.replace("'", "\\'") + "'); }", null);
                }
            });
        }

        @JavascriptInterface
        public void onCommand(String command) {
            injectCommand(command);
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
        if (webView != null) {
            windowManager.removeView(webView);
            webView.destroy();
            webView = null;
        }
        if (floatingButton != null) {
            windowManager.removeView(floatingButton);
            floatingButton = null;
        }
        if (shizukuOut != null) {
            try {
                shizukuOut.writeBytes("exit\n");
                shizukuOut.flush();
                shizukuOut.close();
            } catch (Exception e) {}
        }
        if (shizukuProcess != null) shizukuProcess.destroy();
    }
}


