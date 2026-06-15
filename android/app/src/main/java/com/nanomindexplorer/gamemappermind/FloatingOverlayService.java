package com.nanomindexplorer.gamemappermind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.DataOutputStream;

public class FloatingOverlayService extends Service {

    private WindowManager windowManager;
    private View handleContainer;
    private WebView overlayWebView;

    private boolean isEditMode = false;
    private String currentConfigJson = "{}";

    private static final String CHANNEL_ID = "OverlayServiceChannel";

    // Shizuku
    private Process shizukuProcess;
    private DataOutputStream shizukuOut;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "GameMapperMind Overlay", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Floating Overlay Service");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        initShizukuDaemon();

        // Notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GameMapperMind")
                .setContentText("Overlay Active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        if (intent != null && intent.hasExtra("config")) {
            currentConfigJson = intent.getStringExtra("config");
        }

        new Handler(Looper.getMainLooper()).post(this::setupOverlay);

        return START_STICKY;
    }

    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createFloatingHandle();
        createReactOverlay();
    }

    // ==================== FLOATING HANDLE (Gamepad Icon) ====================
    private void createFloatingHandle() {
        if (handleContainer != null) try { windowManager.removeView(handleContainer); } catch (Exception ignored) {}

        handleContainer = new FrameLayout(this);
        TextView icon = new TextView(this);
        icon.setText("🎮");
        icon.setTextSize(26f);
        icon.setGravity(Gravity.CENTER);

        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#1e293b"));
        gd.setCornerRadius(999f);
        gd.setStroke(4, Color.parseColor("#10b981"));
        icon.setBackground(gd);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(130, 130);
        ((FrameLayout) handleContainer).addView(icon, lp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 80;
        params.y = 80;

        windowManager.addView(handleContainer, params);

        icon.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick = true;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int)(event.getRawX() - initialTouchX);
                        params.y = initialY + (int)(event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(handleContainer, params);
                        isClick = false;
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            isEditMode = !isEditMode;
                            gd.setStroke(4, isEditMode ? Color.parseColor("#ef4444") : Color.parseColor("#10b981"));
                            icon.setBackground(gd);
                            sendEditModeToReact(isEditMode);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ==================== REACT WEBVIEW OVERLAY ====================
    private void createReactOverlay() {
        if (overlayWebView != null) try { windowManager.removeView(overlayWebView); } catch (Exception ignored) {}

        overlayWebView = new WebView(this);
        overlayWebView.setBackgroundColor(Color.TRANSPARENT);

        WebSettings settings = overlayWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        overlayWebView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);

        overlayWebView.addJavascriptInterface(new WebAppInterface(), "Android");

        overlayWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                sendConfigToReact(currentConfigJson);
                sendEditModeToReact(isEditMode);
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 0;

        windowManager.addView(overlayWebView, params);

        // Load React App
        overlayWebView.loadUrl("file:///android_asset/public/index.html");
    }

    // ==================== BRIDGE NATIVE ↔ REACT ====================
    private class WebAppInterface {
        @JavascriptInterface
        public void updateButtonPosition(String buttonId, float x, float y) {
            // TODO: Simpan perubahan posisi ke profile jika diperlukan
            Log.d("GameMapper", "Button moved: " + buttonId + " -> (" + x + ", " + y + ")");
        }

        @JavascriptInterface
        public void onButtonPress(String buttonId, String action) {
            Log.d("GameMapper", "Button pressed: " + buttonId + " | " + action);
            // injectCommand di sini sesuai kebutuhan
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d("ReactOverlay", message);
        }
    }

    private void sendConfigToReact(String configJson) {
        if (overlayWebView != null) {
            overlayWebView.evaluateJavascript(
                "if (window.updateOverlayConfig) window.updateOverlayConfig(" + configJson + ");", 
                null
            );
        }
    }

    private void sendEditModeToReact(boolean editMode) {
        if (overlayWebView != null) {
            overlayWebView.evaluateJavascript(
                "if (window.setOverlayEditMode) window.setOverlayEditMode(" + editMode + ");", 
                null
            );
        }
    }

    private void initShizukuDaemon() {
        // Isi dengan kode Shizuku kamu yang lama
    }

    private void injectCommand(String cmd) {
        // Isi dengan kode injectCommand kamu yang lama
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayWebView != null) try { windowManager.removeView(overlayWebView); } catch (Exception ignored) {}
        if (handleContainer != null) try { windowManager.removeView(handleContainer); } catch (Exception ignored) {}
        // Cleanup Shizuku...
    }
}
