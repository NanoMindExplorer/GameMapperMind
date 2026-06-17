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
import org.json.JSONObject;
import com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin;
import com.nanomindexplorer.gamemappermind.shizuku.IGameMapperService;
import com.nanomindexplorer.gamemappermind.shizuku.ShizukuHelper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * FloatingOverlayService — Displays a transparent WebView overlay on top
 * of the active game. The overlay shows virtual gamepad buttons that the
 * user can tap to trigger touch injection.
 *
 * SECURITY HARDENING (FASE 1.3):
 *
 *   1. WebView hardening:
 *      - setAllowFileAccess(false) — blocks file:// URIs
 *      - setAllowContentAccess(false) — blocks content:// URIs
 *      - setAllowFileAccessFromFileURLs(false) — explicit deny
 *      - setAllowUniversalAccessFromFileURLs(false) — explicit deny
 *      - setGeolocationEnabled(false) — no location leak
 *      - setMediaPlaybackRequiresUserGesture(true) — no autoplay
 *      - setMixedContentMode(MIXED_CONTENT_NEVER_ALLOW) — no HTTPS→HTTP mix
 *
 *   2. JavascriptInterface restriction:
 *      - Only three methods are exposed via @JavascriptInterface:
 *          onReactReady() — called when React app signals readiness
 *          onCommand()    — receives a single command string from overlay
 *          closeOverlay() — requests overlay teardown
 *      - No method exposes file paths, ContentResolver, or Context APIs.
 *      - All methods validate their inputs strictly.
 *
 *   3. onCommand() input validation:
 *      - Command string capped at 256 characters (DoS prevention)
 *      - Action must match a strict whitelist: {down, move, up, tap, close}
 *      - X and Y coordinates must be integers in range [0, MAX_COORD]
 *        where MAX_COORD = 7680 (8K display maximum)
 *      - Virtual key (optional) must match a strict pattern: alphanumeric
 *        plus underscore, max 32 chars
 *      - Any validation failure is logged and the command is dropped
 *
 *   4. Safe JS evaluation:
 *      - Uses JSONObject.quote() to escape JSON strings before embedding
 *        in JavaScript — prevents injection via crafted config payloads
 *      - Fallback path uses manual escaping only if JSONObject.quote()
 *        throws (extremely unlikely)
 *
 *   5. URL allowlist:
 *      - WebViewClient.shouldOverrideUrlLoading blocks any URL that is
 *        not the Capacitor asset loader origin
 *      - Blocks navigation to external sites from the overlay
 *
 * The overlay loads from https://appassets.androidplatform.net (Capacitor's
 * built-in asset loader) which serves files from the app's assets directory
 * over HTTPS — no file:// access needed.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG = "GameMapper";
    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private static final String OVERLAY_ORIGIN = "https://appassets.androidplatform.net";

    // Input validation constants — strict bounds prevent DoS and injection.
    private static final int MAX_COMMAND_LENGTH = 256;
    private static final int MAX_COORD = 7680;          // 8K display maximum
    private static final int MAX_VIRTUAL_KEY_LENGTH = 32;

    // Whitelist of allowed actions in onCommand(). Any other action is rejected.
    private static final Set<String> ALLOWED_ACTIONS = new HashSet<>(Arrays.asList(
            "down", "move", "up", "tap", "close"
    ));

    // Virtual key pattern: alphanumeric + underscore only.
    private static final Pattern VIRTUAL_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{1,32}$");

    private WindowManager windowManager;
    private WebView webView;
    private WindowManager.LayoutParams webViewParams;
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
        Log.d(TAG, "FloatingOverlayService onStartCommand");
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
            Log.e(TAG, "startForeground failed", e);
        }

        if (intent != null && intent.hasExtra("config")) {
            String config = intent.getStringExtra("config");
            if (config != null) {
                currentConfigJson = config;
                updateOverlayConfig(currentConfigJson);
            }
        }

        return START_STICKY;
    }

    /**
     * Safely evaluate JavaScript to update the overlay configuration.
     * Uses JSONObject.quote() to properly escape the JSON string for
     * embedding inside a JavaScript string literal. This prevents
     * injection attacks via crafted config payloads.
     *
     * @param configJson Raw JSON string to pass to window.injectConfig()
     */
    private void updateOverlayConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            Log.w(TAG, "updateOverlayConfig: configJson is null or empty");
            return;
        }

        // Cap config size to prevent memory exhaustion attacks.
        if (configJson.length() > 65536) {
            Log.e(TAG, "updateOverlayConfig: configJson too large (" + configJson.length() + " bytes)");
            return;
        }

        final String safeJson = configJson;
        final WebView view = webView;

        new Handler(Looper.getMainLooper()).post(() -> {
            if (view == null) {
                Log.w(TAG, "WebView not ready yet — config will be sent on ReactReady");
                return;
            }

            try {
                // JSONObject.quote() wraps the string in double quotes and escapes
                // all special characters (newlines, quotes, backslashes) per JSON spec.
                // This prevents syntax errors and JS injection when embedding in JS.
                String escapedJson = JSONObject.quote(safeJson);
                String js = "if(window.injectConfig){window.injectConfig(" + escapedJson + ");}";
                view.evaluateJavascript(js, null);
                Log.d(TAG, "Overlay config updated via safe JS evaluation");
            } catch (Exception e) {
                Log.e(TAG, "Failed to evaluate JS for config update", e);
                // Fallback: try with manual escaping (less safe but better than nothing).
                // This path is only taken if JSONObject.quote() throws, which is
                // extremely unlikely for valid string inputs.
                try {
                    String manualEscaped = safeJson
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r");
                    view.evaluateJavascript(
                            "if(window.injectConfig){window.injectConfig('" + manualEscaped + "');}",
                            null);
                } catch (Exception e2) {
                    Log.e(TAG, "Fallback JS evaluation also failed", e2);
                }
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "FloatingOverlayService onCreate() called");
        createNotificationChannel();

        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    webView = new WebView(FloatingOverlayService.this);
                    webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
                    webView.setBackgroundColor(0x00000000); // completely transparent

                    WebSettings settings = webView.getSettings();
                    // JavaScript is required for the React overlay app to run.
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true);
                    settings.setDatabaseEnabled(true);
                    settings.setMediaPlaybackRequiresUserGesture(true);

                    // SECURITY: Disable all file and content access.
                    settings.setAllowFileAccess(false);
                    settings.setAllowContentAccess(false);
                    // Explicitly deny file:// universal access (legacy setting).
                    @SuppressWarnings("deprecation")
                    WebSettings sRef = settings;
                    sRef.setAllowFileAccessFromFileURLs(false);
                    sRef.setAllowUniversalAccessFromFileURLs(false);

                    // Disable geolocation — no location leak from overlay.
                    settings.setGeolocationEnabled(false);

                    // Disable mixed content — never load HTTP resources from HTTPS page.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                    }

                    // Disable caching — overlay always loads fresh from app assets.
                    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

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
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                            // SECURITY: Only allow the Capacitor asset loader origin.
                            // Block navigation to any external URL — prevents phishing
                            // and resource loading from untrusted sources.
                            String url = request.getUrl() != null ? request.getUrl().toString() : "";
                            if (url.startsWith(OVERLAY_ORIGIN)) {
                                return false; // allow
                            }
                            Log.w(TAG, "Blocked navigation to non-allowlisted URL: " + url);
                            return true; // block
                        }
                    });

                    // Restricted JavascriptInterface — only three methods exposed.
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

                    Log.d(TAG, "Loading index.html into Overlay WebView");
                    webView.loadUrl(OVERLAY_ORIGIN + "/public/index.html?overlay=true");
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing WebView in onCreate", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate of FloatingOverlayService", e);
        }
    }

    /**
     * Bridge exposed to the overlay WebView as window.AndroidOverlay.
     * React-side OverlayWysiwyg calls these methods when the user taps
     * virtual buttons in play mode.
     *
     * SECURITY: Only three methods are annotated with @JavascriptInterface.
     * None of them access the file system, ContentResolver, or sensitive
     * Context APIs. All inputs are strictly validated.
     */
    private class WebAppInterface {

        /**
         * Called by the React overlay app when it has finished bootstrapping
         * and is ready to receive configuration via window.injectConfig().
         */
        @JavascriptInterface
        public void onReactReady() {
            Log.d(TAG, "React is ready in Overlay");
            // Use the safe config update method instead of inline JS.
            if (currentConfigJson != null && !currentConfigJson.isEmpty()) {
                updateOverlayConfig(currentConfigJson);
            }
        }

        /**
         * Handle commands from the React overlay. Strictly validated.
         *
         * Supported formats:
         *   "down <x> <y> [virtualKey]"   → touchDown
         *   "move <x> <y> [virtualKey]"   → touchMove
         *   "up <x> <y> [virtualKey]"     → touchUp
         *   "tap <x> <y> [virtualKey]"    → injectTap
         *   "close"                        → closeOverlay (no coords needed)
         *
         * Validation rules:
         *   - Command string must be non-null, non-empty, ≤ 256 chars
         *   - Action must be in {down, move, up, tap, close}
         *   - For down/move/up/tap: X and Y must be integers in [0, 7680]
         *   - Optional virtualKey must match ^[A-Za-z0-9_]{1,32}$
         *   - Any validation failure → log warning + drop command
         *
         * @param command The command string from the overlay
         */
        @JavascriptInterface
        public void onCommand(String command) {
            // Step 1: Null/empty check.
            if (command == null || command.isEmpty()) {
                Log.w(TAG, "onCommand: null or empty command");
                return;
            }

            // Step 2: Length cap (DoS prevention).
            String trimmed = command.trim();
            if (trimmed.length() > MAX_COMMAND_LENGTH) {
                Log.w(TAG, "onCommand: command too long (" + trimmed.length() + " chars)");
                return;
            }

            // Step 3: Tokenize.
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 1) {
                Log.w(TAG, "onCommand: empty tokenized command");
                return;
            }

            String action = parts[0].toLowerCase();

            // Step 4: Action whitelist check.
            if (!ALLOWED_ACTIONS.contains(action)) {
                Log.w(TAG, "onCommand: action not in allowlist: " + action);
                return;
            }

            // Step 5: Handle "close" (no coordinates required).
            if (action.equals("close")) {
                closeOverlay();
                return;
            }

            // Step 6: Parse X and Y coordinates for down/move/up/tap.
            if (parts.length < 3) {
                Log.w(TAG, "onCommand: " + action + " requires <x> <y>, got " + (parts.length - 1) + " args");
                return;
            }

            int x;
            int y;
            try {
                x = Integer.parseInt(parts[1]);
                y = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                Log.w(TAG, "onCommand: non-integer coordinate: " + parts[1] + ", " + parts[2]);
                return;
            }

            // Step 7: Coordinate range validation.
            if (x < 0 || x > MAX_COORD) {
                Log.w(TAG, "onCommand: X out of range [0," + MAX_COORD + "]: " + x);
                return;
            }
            if (y < 0 || y > MAX_COORD) {
                Log.w(TAG, "onCommand: Y out of range [0," + MAX_COORD + "]: " + y);
                return;
            }

            // Step 8: Optional virtual key validation.
            String virtualKey = "";
            if (parts.length >= 4) {
                virtualKey = parts[3];
                if (!VIRTUAL_KEY_PATTERN.matcher(virtualKey).matches()) {
                    Log.w(TAG, "onCommand: invalid virtual key format: " + virtualKey);
                    return;
                }
            }

            // Step 9: Dispatch to plugin.
            // FIX #13: Previously emitted "OVERLAY_DOWN" etc. which no JS listener
            // handles. Now routes directly to UserService injection via AIDL,
            // matching the pattern used by useShizuku.injectInput().
            try {
                IGameMapperService service = GameMapperPlugin.instance != null
                    ? getGameMapperService() : null;
                if (service != null) {
                    switch (action) {
                        case "down":
                            service.injectTap((float) x, (float) y, 0);
                            break;
                        case "move":
                            service.injectSwipe((float) x, (float) y, (float) x, (float) y, 1L, 0);
                            break;
                        case "up":
                            service.injectTouchUp(99, 0);
                            break;
                        case "tap":
                            service.injectTap((float) x, (float) y, 0);
                            break;
                        default:
                            Log.w(TAG, "onCommand: unreachable action: " + action);
                    }
                } else {
                    // Fallback: emit button event for JS-side handling
                    // (used when Shizuku is not connected — dev/browser mode)
                    switch (action) {
                        case "down":
                            GameMapperPlugin.emitGamepadButton("OVERLAY_DOWN", 1, 1.0f);
                            break;
                        case "move":
                            GameMapperPlugin.emitGamepadButton("OVERLAY_MOVE", 1, 1.0f);
                            break;
                        case "up":
                            GameMapperPlugin.emitGamepadButton("OVERLAY_UP", 0, 1.0f);
                            break;
                        case "tap":
                            GameMapperPlugin.emitGamepadButton("OVERLAY_TAP", 1, 1.0f);
                            break;
                        default:
                            Log.w(TAG, "onCommand: unreachable action: " + action);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "onCommand: dispatch failed for action=" + action, e);
            }
        }

        /**
         * Request overlay teardown. Called when the user closes the overlay
         * from the React UI.
         */
        @JavascriptInterface
        public void closeOverlay() {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    stopSelf();
                } catch (Exception e) {
                    Log.e(TAG, "closeOverlay: stopSelf failed", e);
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            try {
                // Remove JavascriptInterface before destroying to prevent
                // any in-flight JS callbacks from referencing a stale bridge.
                webView.removeJavascriptInterface("AndroidOverlay");
                if (windowManager != null) {
                    windowManager.removeView(webView);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove WebView from WindowManager", e);
            }
            try {
                webView.destroy();
            } catch (Exception e) {
                Log.e(TAG, "webView.destroy() failed", e);
            }
            webView = null;
        }
    }

    /**
     * Get the IGameMapperService binder from ShizukuHelper.
     * FIX #13: Used by onCommand() to route overlay button presses
     * directly to UserService injection (bypassing JS layer).
     *
     * @return IGameMapperService or null if not available
     */
    private IGameMapperService getGameMapperService() {
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            return helper.getService();
        } catch (Exception e) {
            Log.w(TAG, "getGameMapperService: " + e.getMessage());
            return null;
        }
    }
}
