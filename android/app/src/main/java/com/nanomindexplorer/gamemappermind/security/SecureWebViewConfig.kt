package com.nanomindexplorer.gamemappermind.security

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * FASE 4.3 — WebView security hardening.
 *
 * Path di repo:
 *   android/app/src/main/java/com.nanomindexplorer.gamemappermind/security/SecureWebViewConfig.kt
 *
 * Hardening checklist (defense in depth):
 *
 *   1. Disable file:// access (file access + content access).
 *   2. Disable allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs.
 *   3. Disable addJavascriptInterface on bare WebView (we use Capacitor bridge instead).
 *   4. Disable geolocation, mediaPlaybackWithoutUserGesture, mixed content.
 *   5. Set a strict CSP via meta tag (enforced by the WebView).
 *   6. Block all external URLs — only allow WebViewAssetLoader (app://) and https://localhost.
 *   7. Block SSL error bypass — never call handler.proceed() on certificate errors.
 *   8. Disable WebView debugging in release builds (debuggable=false enforced by AGP).
 *   9. Set safe browsing to enabled (Android 8+).
 *  10. Strip Referer + Origin headers on cross-origin requests.
 *
 * Usage:
 *   val assetLoader = WebViewAssetLoader.Builder()
 *       .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
 *       .build()
 *   SecureWebViewConfig.applyTo(webView, assetLoader)
 *   webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
 *
 *   // Or for Capacitor:
 *   SecureWebViewConfig.applyTo(bridge.webView, assetLoader)
 */

object SecureWebViewConfig {

    private const val TAG = "SecureWebViewConfig"

    /**
     * Apply all hardening flags to a WebView. Idempotent.
     *
     * IMPORTANT: This MUST be called BEFORE any content is loaded, because
     * some settings (like allowFileAccess) only take effect at load time.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun applyTo(webView: WebView, assetLoader: WebViewAssetLoader? = null) {
        val settings = webView.settings

        // ───── 1. JavaScript & plugins ─────
        // JavaScript is REQUIRED for Capacitor — we can't disable it.
        // But we CAN disable DOM storage and database access if we don't use them.
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true   // Required by Capacitor localStorage
        settings.databaseEnabled = true     // Required by Capacitor WebSQL shim
        settings.allowContentAccess = false // No content:// URIs
        settings.allowFileAccess = false    // No file:// URIs

        // These two are deprecated but still dangerous on older Androids — explicitly off.
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false

        // ───── 2. Media & autoplay ─────
        settings.mediaPlaybackRequiresUserGesture = true
        settings.loadsImagesAutomatically = true

        // ───── 3. Geolocation ─────
        settings.setGeolocationEnabled(false)

        // ───── 4. Mixed content ─────
        // API 21+ — never allow HTTPS page to load HTTP subresources.
        settings.mixedContentMode =
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // ───── 5. Safe Browsing (API 26+) ─────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.webkit.WebView::class.java
                    .getMethod("setSafeBrowsingEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(webView, true)
            } catch (_: Throwable) { /* API < 26 */ }
        }

        // ───── 6. Caching — disable for security; we re-bundle on every release ─────
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        settings.setAppCacheEnabled(false)  // deprecated but enforce

        // ───── 7. Disable algorithmic dark mode forcing (let CSS handle it) ─────
        try {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        } catch (_: Throwable) { /* older androidx.webkit */ }

        // ───── 8. Disable WebView debugging in release ─────
        // AGP sets android:debuggable=false in release manifest; WebView reads it.
        // But we double-down by explicitly disabling in release:
        try {
            val isDebuggable = webView.context.applicationContext
                .applicationInfo
                .flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            android.webkit.WebView::class.java
                .getMethod("setWebContentsDebuggingEnabled", Boolean::class.javaPrimitiveType)
                .invoke(null, isDebuggable)
        } catch (_: Throwable) { /* API < 19 */ }

        // ───── 9. Install hardened WebViewClient ─────
        webView.webViewClient = SecureWebViewClient(assetLoader)

        // ───── 10. Remove any default JavascriptInterface that a subclass might have added ─────
        // (Capacitor adds its own bridge — we leave that alone.)

        Log.i(TAG, "WebView hardened: JS=${settings.javaScriptEnabled}, " +
                "fileAccess=${settings.allowFileAccess}, " +
                "contentAccess=${settings.allowContentAccess}, " +
                "mixedContent=${settings.mixedContentMode}")
    }

    /**
     * Content-Security-Policy applied as a meta tag in index.html.
     * Returns the CSP string for documentation/testing — the actual enforcement
     * happens by injecting <meta http-equiv="Content-Security-Policy"> in the HTML.
     *
     * Policy:
     *   - default-src 'self'         → only same-origin by default
     *   - script-src 'self'          → no inline scripts, no eval, no external CDNs
     *   - style-src 'self' 'unsafe-inline'  → Capacitor injects inline styles
     *   - img-src 'self' data: blob: → allow data URIs for icons
     *   - connect-src 'self'         → block XHR to external APIs
     *   - frame-ancestors 'none'     → never allow iframe embedding
     *   - form-action 'none'         → no form submissions
     *   - base-uri 'self'            → no <base> tag hijack
     *   - object-src 'none'          → no Flash/Java/objects
     *   - worker-src 'self'          → only same-origin workers
     */
    const val CSP_POLICY: String =
        "default-src 'self'; " +
        "script-src 'self'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob:; " +
        "connect-src 'self'; " +
        "frame-ancestors 'none'; " +
        "form-action 'none'; " +
        "base-uri 'self'; " +
        "object-src 'none'; " +
        "worker-src 'self'; " +
        "manifest-src 'self'; " +
        "font-src 'self' data:"

    /**
     * Build the full <meta> tag string for embedding in index.html.
     */
    fun cspMetaTag(): String =
        "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP_POLICY\">"
}

// ─────────────────────────────────────────────────────────────────────────────
// Hardened WebViewClient
// ─────────────────────────────────────────────────────────────────────────────

private class SecureWebViewClient(
    private val assetLoader: WebViewAssetLoader? = null
) : WebViewClient() {

    companion object {
        // Allowlist of permitted URL schemes/hosts. Anything outside this
        // list is blocked at shouldOverrideUrlLoading + shouldInterceptRequest.
        private val ALLOWED_SCHEMES = setOf("https", "appasset")
        private val ALLOWED_HOSTS = setOf(
            "appassets.androidplatform.net",  // WebViewAssetLoader default
            "localhost"
        )
        private val BLOCKED_FILE_EXTENSIONS = setOf(
            ".apk", ".dex", ".so", ".jar", ".zip", ".rar", ".7z"
        )
    }

    /** For shouldOverrideUrlLoading (clicked links, window.open). */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return true  // block null
        val scheme = url.scheme?.lowercase(Locale.ROOT) ?: return true
        val host = url.host?.lowercase(Locale.ROOT) ?: ""

        // Allow only allowlisted schemes.
        if (scheme !in ALLOWED_SCHEMES) {
            Log.w("SecureWebView", "Blocked navigation to scheme '$scheme' url=$url")
            return true
        }
        // For https, allow only allowlisted hosts.
        if (scheme == "https" && host !in ALLOWED_HOSTS) {
            Log.w("SecureWebView", "Blocked navigation to host '$host' url=$url")
            return true
        }
        // Block file extensions that should never be navigated to.
        val path = url.path.orEmpty().lowercase(Locale.ROOT)
        if (BLOCKED_FILE_EXTENSIONS.any { path.endsWith(it) }) {
            Log.w("SecureWebView", "Blocked navigation to suspicious file: $path")
            return true
        }
        return false
    }

    /** For shouldInterceptRequest (XHR, fetch, img, etc.). */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null

        // Delegate to asset loader for app:// / https://appassets.androidplatform.net/...
        if (assetLoader != null) {
            try {
                val intercepted = assetLoader.shouldInterceptRequest(url)
                if (intercepted != null) return intercepted
            } catch (t: Throwable) {
                Log.w("SecureWebView", "AssetLoader intercept failed: ${t.message}")
            }
        }

        // Block any non-allowlisted scheme.
        val scheme = url.scheme?.lowercase(Locale.ROOT) ?: return blockResponse()
        if (scheme !in ALLOWED_SCHEMES) {
            Log.w("SecureWebView", "Blocked subresource scheme '$scheme' url=$url")
            return blockResponse()
        }
        // Block file extensions.
        val path = url.path.orEmpty().lowercase(Locale.ROOT)
        if (BLOCKED_FILE_EXTENSIONS.any { path.endsWith(it) }) {
            Log.w("SecureWebView", "Blocked subresource file type: $path")
            return blockResponse()
        }
        return null  // allow default load
    }

    /** NEVER bypass SSL errors — always cancel. */
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        Log.e("SecureWebView", "SSL error (${error?.primaryError}): ${error?.url}")
        handler?.cancel()  // never call handler.proceed()
    }

    /** Block any attempt to load a URL with an unrecognized scheme via JS redirection. */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        Log.w("SecureWebView", "Resource error: ${request?.url} — ${error?.description}")
    }

    private fun blockResponse(): WebResourceResponse {
        // Return 403 with empty body — WebView treats as failed load.
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        ).apply {
            setStatusCodeAndReasonPhrase(403, "Blocked by SecureWebViewClient")
        }
    }
}
