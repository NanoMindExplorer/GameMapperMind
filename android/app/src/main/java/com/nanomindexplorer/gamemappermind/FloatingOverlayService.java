package com.nanomindexplorer.gamemappermind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
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
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebViewClient;
import androidx.core.app.NotificationCompat;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONArray;
import org.json.JSONObject;

public class FloatingOverlayService extends Service {
    private WindowManager windowManager;

    // Canvas mode (existing full-screen WYSIWYG WebView overlay — screenshot bg + buttons,
    // editable in place).
    private WebView webView;
    private WindowManager.LayoutParams webViewParams;

    // HYBRID-OVERLAY: Floating mode (new) — minimal floating native button indicators,
    // similar in spirit to k2er-style key-mapper overlays. No WebView, just small
    // translucent circular views positioned from the active profile's x/y percentages.
    private FrameLayout floatingContainer;
    private WindowManager.LayoutParams floatingParams;

    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private String currentConfigJson = "{}";

    // "canvas" (default, backward compatible) or "floating". Selected via the
    // "overlayMode" intent extra sent from TouchInjectionPlugin.startOverlay(). Only one
    // mode is ever active at a time for a given service instance — switching modes on the
    // JS side (handleOverlayModeChange in App.tsx) always calls stopOverlay() before
    // starting the other one, so there is never a conflict between the two.
    private String overlayMode = "canvas";
    private boolean overlayViewCreated = false;

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
                .setContentText(isEditing ? "Overlay is in Edit Mode" : "Overlay is running (" + overlayMode + ")")
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
    public void onCreate() {
        super.onCreate();
        Log.d("GameMapper", "FloatingOverlayService onCreate() called");
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("GameMapper", "FloatingOverlayService onStartCommand, action=" + (intent != null ? intent.getAction() : "null"));
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

        // BUG-FIX: original code called intent.getAction() without a null-check on intent.
        // A null intent can legitimately be delivered here (e.g. Android redelivering a
        // START_STICKY service after the process was killed and restarted) and would crash
        // the whole overlay service with a NullPointerException.
        String action = intent != null ? intent.getAction() : null;

        if ("ACTION_EDIT".equals(action)) {
            handleEditAction(true);
            return START_STICKY;
        } else if ("ACTION_PLAY".equals(action)) {
            handleEditAction(false);
            return START_STICKY;
        }

        if (intent != null && intent.hasExtra("overlayMode")) {
            String requestedMode = intent.getStringExtra("overlayMode");
            overlayMode = "floating".equals(requestedMode) ? "floating" : "canvas";
        }
        if (intent != null && intent.hasExtra("config")) {
            currentConfigJson = intent.getStringExtra("config");
        }

        if (!overlayViewCreated) {
            overlayViewCreated = true;
            final String modeToInit = overlayMode;
            new Handler(Looper.getMainLooper()).post(() -> {
                if ("floating".equals(modeToInit)) {
                    initFloatingOverlay();
                } else {
                    initCanvasOverlay();
                }
            });
        } else {
            pushConfigToActiveOverlay();
        }

        return START_STICKY;
    }

    private void handleEditAction(boolean editing) {
        if ("floating".equals(overlayMode)) {
            setFloatingInteractive(editing);
            if (editing) {
                // Floating mode has no in-place drag-to-reposition — button positions are
                // authored in the app's own WYSIWYG Overlay tab (Canvas mode editor).
                // Bring the app to front so the user can adjust the layout there, then
                // switch back to Floating for lightweight play.
                try {
                    Intent openApp = new Intent(this, MainActivity.class);
                    openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(openApp);
                    Toast.makeText(this, "Edit posisi tombol di tab Overlay pada aplikasi.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e("GameMapper", "Failed to open app for floating overlay edit", e);
                }
            }
        } else {
            setOverlayInteractive(editing);
            if (webView != null) {
                final WebView wv = webView;
                new Handler(Looper.getMainLooper()).post(() -> {
                    wv.evaluateJavascript("if(window.togglePalette) window.togglePalette(" + editing + ");", null);
                });
            }
        }
        updateNotification(editing);
    }

    private void pushConfigToActiveOverlay() {
        if ("floating".equals(overlayMode)) {
            new Handler(Looper.getMainLooper()).post(this::rebuildFloatingButtons);
            return;
        }
        final WebView wv = webView;
        if (wv == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            String safeJson = sanitizeConfigForJs(currentConfigJson);
            if (safeJson == null) return;
            wv.evaluateJavascript("if(window.injectConfig) window.injectConfig('" + safeJson + "');", null);
        });
    }

    /**
     * BUG-N7 FIX (kept from original): escape currentConfigJson before injecting into JS
     * to prevent script injection / parse breakage if the JSON contains </script> or
     * backslashes. Extracted into a single shared helper — previously this exact logic
     * was duplicated in three places, which risked the copies drifting out of sync.
     */
    private String sanitizeConfigForJs(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject parsed = new JSONObject(json);
            return parsed.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("</", "<\\/");
        } catch (Exception e) {
            Log.e("GameMapper", "Failed to sanitize config JSON", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Canvas mode (existing full WYSIWYG WebView overlay)
    // ------------------------------------------------------------------

    private void initCanvasOverlay() {
        try {
            webView = new WebView(FloatingOverlayService.this);
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
            webView.setBackgroundColor(0x00000000); // completely transparent

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

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    String safeJson = sanitizeConfigForJs(currentConfigJson);
                    if (safeJson != null) {
                        view.evaluateJavascript("if(window.injectConfig) window.injectConfig('" + safeJson + "');", null);
                    }
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Start in play mode (not touchable)
                    PixelFormat.TRANSLUCENT);
            // NEW-M5 FIX: Extend overlay into display cutout area for fullscreen games.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                webViewParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            webViewParams.gravity = Gravity.FILL;

            windowManager.addView(webView, webViewParams);
            // BUG-CRITICAL-4 FIX (kept from original): no webView.requestFocus() — it
            // contradicts FLAG_NOT_FOCUSABLE and can steal focus from eFootball on some
            // OEMs (MIUI, EMUI).

            Log.d("GameMapper", "Loading index.html into Overlay WebView (canvas mode)");
            webView.loadUrl("https://appassets.androidplatform.net/public/index.html?overlay=true");
        } catch (Exception e) {
            Log.e("GameMapper", "Error initializing canvas overlay", e);
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
                final WebView wv = webView;
                if (wv == null) return;
                String safeJson = sanitizeConfigForJs(currentConfigJson);
                if (safeJson != null) {
                    wv.evaluateJavascript("if(window.injectConfig) window.injectConfig('" + safeJson + "');", null);
                }
            });
        }

        // BUG-R6 FIX: onCommand method for profile save from overlay
        @JavascriptInterface
        public void onCommand(String command) {
            Log.d("GameMapper", "Overlay command: " + command);
        }

        @JavascriptInterface
        public void closeOverlay() {
            new Handler(Looper.getMainLooper()).post(() -> {
                stopSelf();
            });
        }
    }

    // ------------------------------------------------------------------
    // HYBRID-OVERLAY: Floating mode (new) — minimal native buttons, k2er-style
    // ------------------------------------------------------------------

    private void initFloatingOverlay() {
        try {
            floatingContainer = new FrameLayout(this);

            floatingParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // play mode: pure visual, pass-through
                    PixelFormat.TRANSLUCENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                floatingParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            floatingParams.gravity = Gravity.FILL;

            windowManager.addView(floatingContainer, floatingParams);
            Log.d("GameMapper", "Floating (k2er-style) overlay created");
            rebuildFloatingButtons();
        } catch (Exception e) {
            Log.e("GameMapper", "Error initializing floating overlay", e);
        }
    }

    /**
     * Rebuilds the minimal floating button indicators from currentConfigJson. Purely
     * visual in play mode (the container window is FLAG_NOT_TOUCHABLE) — real touches
     * for gameplay are injected by the Shizuku touch daemon based on physical gamepad
     * input, not generated by the user tapping these indicator views. Safe to call
     * repeatedly, e.g. when the active profile changes while the overlay is running.
     */
    private void rebuildFloatingButtons() {
        if (floatingContainer == null) return;
        floatingContainer.removeAllViews();
        try {
            JSONObject root = new JSONObject(currentConfigJson == null || currentConfigJson.isEmpty() ? "{}" : currentConfigJson);
            JSONArray buttons = root.optJSONArray("buttons");
            if (buttons == null) return;

            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            int sizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 52, dm);
            int strokePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, dm);

            // FIX: previously used raw pctX/pctY straight against the full screen, which drifted
            // out of sync with NativeGamepadMapper.getScreenCoords() once that started applying
            // Game Screen Calibration insets — the K2-style dots would visually sit somewhere
            // different from where the actual touch gets injected. Same remap formula here.
            double insetTop = root.optDouble("screenInsetTop", 0.0);
            double insetBottom = root.optDouble("screenInsetBottom", 0.0);
            double insetLeft = root.optDouble("screenInsetLeft", 0.0);
            double insetRight = root.optDouble("screenInsetRight", 0.0);
            double usableWidthFrac = (100.0 - insetLeft - insetRight) / 100.0;
            double usableHeightFrac = (100.0 - insetTop - insetBottom) / 100.0;

            for (int i = 0; i < buttons.length(); i++) {
                JSONObject b = buttons.optJSONObject(i);
                if (b == null) continue;
                double pctX = insetLeft + (b.optDouble("x", 50.0) / 100.0) * usableWidthFrac * 100.0;
                double pctY = insetTop + (b.optDouble("y", 50.0) / 100.0) * usableHeightFrac * 100.0;
                String label = b.optString("mappedKey", "?");

                TextView dot = new TextView(this);
                dot.setText(label);
                dot.setTextColor(Color.WHITE);
                dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                dot.setGravity(Gravity.CENTER);
                dot.setSingleLine(true);

                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(0x552196F3); // translucent blue — visible but unobtrusive
                bg.setStroke(strokePx, 0xAA2196F3);
                dot.setBackground(bg);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx);
                lp.leftMargin = (int) ((pctX / 100.0) * screenW - sizePx / 2.0);
                lp.topMargin = (int) ((pctY / 100.0) * screenH - sizePx / 2.0);
                dot.setLayoutParams(lp);

                floatingContainer.addView(dot);
            }
            Log.d("GameMapper", "Floating overlay: rendered " + buttons.length() + " button indicators");
        } catch (Exception e) {
            Log.e("GameMapper", "Failed to build floating overlay buttons", e);
        }
    }

    private void setFloatingInteractive(boolean interactive) {
        if (windowManager == null || floatingParams == null || floatingContainer == null) return;
        if (interactive) {
            floatingParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        } else {
            floatingParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        windowManager.updateViewLayout(floatingContainer, floatingParams);
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
        if (floatingContainer != null) {
            try {
                if (floatingContainer.isAttachedToWindow()) {
                    windowManager.removeViewImmediate(floatingContainer);
                }
            } catch (Exception e) {
                Log.e("GameMapper", "Exception removing floating overlay from window manager", e);
            }
            floatingContainer = null;
        }
        overlayViewCreated = false;
    }
}
