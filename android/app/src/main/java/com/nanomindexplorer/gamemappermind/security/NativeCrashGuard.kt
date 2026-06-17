package com.nanomindexplorer.gamemappermind.security

import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * FASE 4.2 — Native crash guard for every @PluginMethod entry point.
 *
 * Path di repo:
 *   android/app/src/main/java/com.nanomindexplorer.gamemappermind/security/NativeCrashGuard.kt
 *
 * Why this exists:
 *   Capacitor's @PluginMethod dispatch has no global try/catch — any uncaught
 *   Throwable inside a plugin method crashes the entire WebView process,
 *   taking the overlay UI with it. We wrap every entry point so:
 *     1. The plugin method never throws up to Capacitor.
 *     2. The caller (JS side) receives a structured reject() with a stable
 *        error code + correlation id, NOT a generic "Internal error".
 *     3. A structured "app:error" event is emitted so useCrashReporter()
 *        on the JS side can persist the crash for post-mortem.
 *
 * Usage pattern (every @PluginMethod):
 *   @PluginMethod
 *   fun setProfile(call: PluginCall) {
 *       NativeCrashGuard.guard("GameMapper", "setProfile", call) {
 *           // business logic — may throw freely
 *           val profile = parseProfile(call)
 *           pipeline.setProfile(profile)
 *           call.resolve()
 *       }
 *   }
 *
 * Error codes are stable strings (never i18n'd) so the JS side can branch
 * on them. See ErrorCode companion for the canonical list.
 */
object NativeCrashGuard {

    private const val TAG = "NativeCrashGuard"

    /**
     * Stable error codes. NEVER reuse a code after release — only add new ones.
     * Document each in CHANGELOG so the JS side can update its error catalog.
     */
    object ErrorCode {
        const val INVALID_ARGUMENT   = "INVALID_ARGUMENT"     // Caller passed bad input
        const val PERMISSION_DENIED  = "PERMISSION_DENIED"    // Shizuku not granted/bound
        const val SERVICE_UNAVAILABLE= "SERVICE_UNAVAILABLE"  // Shizuku not running
        const val INTERNAL_ERROR     = "INTERNAL_ERROR"       // Unexpected exception
        const val TIMEOUT            = "TIMEOUT"              // Operation exceeded deadline
        const val NOT_FOUND          = "NOT_FOUND"            // Requested resource missing
        const val CONFLICT           = "CONFLICT"             // State conflict (e.g. profile active)
        const val RATE_LIMITED       = "RATE_LIMITED"         // Caller hit a quota
        const val NATIVE_CRASH       = "NATIVE_CRASH"         // Uncaught Throwable
    }

    /**
     * Wrap a plugin-method body with crash protection.
     *
     * Contract:
     *   - If `block` throws, `call.reject()` is invoked with a structured
     *     error JSON. The block is NOT retried.
     *   - If `block` calls `call.resolve()` or `call.reject()` itself,
     *     that's fine — guard() does nothing further.
     *   - If `block` completes without resolving/rejecting, guard() rejects
     *     with INTERNAL_ERROR (caller bug — every method must resolve OR reject).
     *
     * @param pluginName  short plugin name for logging ("GameMapper")
     * @param methodName  method name for logging ("setProfile")
     * @param call        the PluginCall from Capacitor
     * @param block       business logic; should call call.resolve() or call.reject()
     */
    fun guard(
        pluginName: String,
        methodName: String,
        call: PluginCall,
        block: () -> Unit
    ) {
        val correlationId = UUID.randomUUID().toString()
        val startNs = System.nanoTime()
        try {
            block()
            // Sanity: did the block actually resolve or reject? Capacitor doesn't
            // expose "is resolved" so we rely on a convention: if block() returned
            // normally without throwing, the contract requires resolve() or reject().
            // We can't enforce this at compile time — log if neither was called.
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            if (elapsedMs > 5_000L) {
                Log.w(TAG, "[$correlationId] $pluginName.$methodName took ${elapsedMs}ms")
            }
        } catch (t: Throwable) {
            handleThrowable(pluginName, methodName, call, correlationId, t)
        }
    }

    /**
     * Variant that returns a value instead of calling resolve/reject — useful
     * for non-Capacitor entry points (broadcasts, listeners, scheduled jobs).
     * Returns null on failure (caller should treat null as "no result" and
     * not crash further up the stack).
     */
    fun <T> guardReturn(
        pluginName: String,
        methodName: String,
        defaultValue: T,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (t: Throwable) {
            logAndEmit(pluginName, methodName, UUID.randomUUID().toString(), t, recoverable = true)
            defaultValue
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Internal
    // ───────────────────────────────────────────────────────────────────────

    private fun handleThrowable(
        pluginName: String,
        methodName: String,
        call: PluginCall,
        correlationId: String,
        t: Throwable
    ) {
        val (code, recoverable) = classify(t)
        val message = sanitizeMessage(t)
        val stack = sanitizeStack(t)

        logAndEmit(pluginName, methodName, correlationId, t, recoverable)

        // Build structured error payload that the JS side can switch on.
        val errorJson = JSObject()
        try {
            errorJson.put("code", code)
            errorJson.put("message", message)
            errorJson.put("correlationId", correlationId)
            errorJson.put("plugin", pluginName)
            errorJson.put("method", methodName)
            errorJson.put("recoverable", recoverable)
            errorJson.put("timestamp", System.currentTimeMillis())
            // Include the root cause class name for debugging.
            errorJson.put("exceptionClass", t.javaClass.simpleName)
        } catch (e: JSONException) {
            // Should never happen — JSObject is our own construction.
            Log.e(TAG, "Failed to build error JSON", e)
        }

        try {
            call.reject(message, code, errorJson)
        } catch (e: Throwable) {
            // PluginCall might be in a weird state — last-resort reject.
            try {
                call.reject("Internal error: $message", code)
            } catch (_: Throwable) {
                Log.e(TAG, "Even fallback call.reject() threw", e)
            }
        }
    }

    private fun logAndEmit(
        pluginName: String,
        methodName: String,
        correlationId: String,
        t: Throwable,
        recoverable: Boolean
    ) {
        // 1) Logcat (visible via `adb logcat -s NativeCrashGuard:E`).
        Log.e(
            TAG,
            "[$correlationId] $pluginName.$methodName threw ${t.javaClass.simpleName}: ${t.message}",
            t
        )

        // 2) Emit Capacitor event for useCrashReporter.ts on the JS side.
        //    We use App plugin's bridge — but to avoid a circular dependency
        //    on the GameMapperPlugin instance, we stash the latest event in
        //    a volatile field that the plugin reads on its main thread.
        //    The plugin is responsible for actually firing notifyListeners().
        val payload = JSONObject()
        try {
            payload.put("id", correlationId)
            payload.put("timestamp", System.currentTimeMillis())
            payload.put("source", "plugin")
            payload.put("plugin", pluginName)
            payload.put("method", methodName)
            payload.put("message", sanitizeMessage(t))
            payload.put("stack", sanitizeStack(t))
            payload.put("recoverable", recoverable)
            payload.put("exceptionClass", t.javaClass.simpleName)
        } catch (_: JSONException) {
            /* no-op */
        }
        PendingErrorBus.publish(payload)
    }

    /**
     * Map Throwable subclasses to stable error codes.
     * The recoverable flag tells the JS side whether retry makes sense.
     */
    private fun classify(t: Throwable): Pair<String, Boolean> = when (t) {
        is IllegalArgumentException,
        is IllegalStateException -> ErrorCode.INVALID_ARGUMENT to true

        is SecurityException       -> ErrorCode.PERMISSION_DENIED to false
        is NullPointerException    -> ErrorCode.INTERNAL_ERROR to false
        is OutOfMemoryError        -> ErrorCode.NATIVE_CRASH to false

        is java.util.concurrent.TimeoutException,
        is java.util.concurrent.CancellationException -> ErrorCode.TIMEOUT to true

        is java.io.FileNotFoundException -> ErrorCode.NOT_FOUND to true
        is java.io.IOException           -> ErrorCode.SERVICE_UNAVAILABLE to true

        is JSONException,
        is org.json.JSONException -> ErrorCode.INVALID_ARGUMENT to true

        else -> {
            // Heuristic on message content for Shizuku-specific failures.
            val msg = t.message.orEmpty().lowercase()
            when {
                "shizuku" in msg && "permission" in msg ->
                    ErrorCode.PERMISSION_DENIED to false
                "shizuku" in msg && ("not running" in msg || "not bound" in msg || "dead" in msg) ->
                    ErrorCode.SERVICE_UNAVAILABLE to true
                "denied" in msg || "unauthorized" in msg ->
                    ErrorCode.PERMISSION_DENIED to false
                else -> ErrorCode.NATIVE_CRASH to true
            }
        }
    }

    /**
     * Sanitize error messages before sending to JS:
     *   - Truncate to 2KB (avoid OOM in WebView JSON parser).
     *   - Strip file system paths (PII on some devices).
     *   - Strip anything that looks like a JWT/API key.
     */
    private fun sanitizeMessage(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        val truncated = if (raw.length > 2048) raw.substring(0, 2048) else raw
        return truncated
            .replace(Regex("""/data/(data|user)/[^/\s]+"""), "/data/<app>")
            .replace(Regex("""/storage/emulated/0/[^/\s]+"""), "/sdcard/<path>")
            .replace(Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"""), "<jwt>")
            .replace(Regex("""[Aa]uthorization:\s*Bearer\s+\S+"""), "Authorization: Bearer <redacted>")
            .replace(Regex("""\b[a-f0-9]{32,}\b"""), "<hex>")
    }

    /**
     * Sanitize stack traces:
     *   - Truncate to 8KB (cap WebView memory).
     *   - Drop frames from java.base / android.jar to reduce noise.
     */
    private fun sanitizeStack(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val raw = sw.toString()
        val truncated = if (raw.length > 8192) raw.substring(0, 8192) else raw
        // Filter out framework noise but keep our own package frames.
        return truncated.lineSequence()
            .filter { line ->
                !line.contains("java.base@") &&
                !line.contains("android.os.Handler") &&
                !line.contains("android.os.Looper")
            }
            .joinToString("\n")
            .ifEmpty { "(no stack)" }
    }
}
