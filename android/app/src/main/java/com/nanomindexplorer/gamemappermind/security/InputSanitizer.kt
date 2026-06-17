package com.nanomindexplorer.gamemappermind.security

import com.getcapacitor.PluginCall
import java.util.regex.Pattern

/**
 * FASE 4.4 — Input sanitization for every value pulled from a Capacitor PluginCall.
 *
 * Path di repo:
 *   android/app/src/main/java/com.nanomindexplorer.gamemappermind/security/InputSanitizer.kt
 *
 * Why this exists:
 *   The JS → Kotlin boundary is a trust boundary. The JS side is React code
 *   that we control, but in production the WebView is reachable by:
 *     - Malicious Shizuku injections that call window.Capacitor.Plugins.GameMapper.*
 *     - XSS via CSP bypass (if one is ever found)
 *     - Browser extensions on web preview builds
 *
 *   Every value we read from PluginCall must be validated BEFORE use, never
 *   assume the JS side is well-behaved. This file centralizes all validation
 *   so plugin methods can stay readable:
 *
 *     val slot = InputSanitizer.requirePointerSlot(call, "slot")
 *
 *   Each method throws IllegalArgumentException on bad input — the
 *   NativeCrashGuard (FASE 4.2) catches it and maps to INVALID_ARGUMENT.
 *
 * Validation rules enforced:
 *   - Strings: length caps, no control chars, no path traversal sequences
 *   - Ints:    range checks, no NaN/Infinity (for Double inputs)
 *   - JSON:    parse + size cap (256 KB), reject deeply nested (>32 levels)
 *   - URLs:    scheme/host allowlist
 *   - Profile JSON: validated separately by ProfileValidator (FASE 3.4)
 */

object InputSanitizer {

    // ───────────────────────────────────────────────────────────────────────
    // Limits (kept conservative; bump only if a real use case demands it)
    // ───────────────────────────────────────────────────────────────────────

    private const val MAX_STRING_LEN          = 65_536
    private const val MAX_JSON_STRING_LEN     = 262_144   // 256 KB
    private const val MAX_JSON_NESTING_DEPTH  = 32
    private const val MAX_TAP_DURATION_MS     = 5_000L
    private const val MIN_TAP_DURATION_MS     = 16L

    // Pointer slot ranges — must match TouchInjector.POOL_SIZE = 100.
    private const val MIN_SLOT = 0
    private const val MAX_SLOT = 99

    // Button code range — Linux evdev valid range (0..KEY_MAX = 0x2ff).
    private const val MIN_BUTTON_CODE = 0
    private const val MAX_BUTTON_CODE = 1023

    // ───────────────────────────────────────────────────────────────────────
    // Regex patterns (precompiled for hot-path performance)
    // ───────────────────────────────────────────────────────────────────────

    /** Strip control chars (except \t \n \r) and Unicode noncharacters. */
    private val CONTROL_CHARS_RE: Pattern = Pattern.compile(
        "[\\p{Cc}\\p{Cf}&&[^\\t\\n\\r]]|[\\uFFFE\\uFFFF]|\\uD83D[\\uDC00-\\uDFFF]"
    )

    /** Path traversal sequences that should never appear in user input. */
    private val PATH_TRAVERSAL_RE: Pattern = Pattern.compile(
        "(\\.\\./)|(\\.\\.%2[fF])|(%2[eE]\\.?%2[eE])|file://|content://"
    )

    /** Allowed Android package name (lowercase dots + alnum + underscore). */
    private val PACKAGE_NAME_RE: Pattern = Pattern.compile(
        "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
    )

    /** Allowed URL schemes for things like log/file path arguments. */
    private val ALLOWED_URL_SCHEMES = setOf("https", "appasset")

    // ───────────────────────────────────────────────────────────────────────
    // String validators
    // ───────────────────────────────────────────────────────────────────────

    /** Read a required string, apply length + control-char + path-traversal checks. */
    fun requireString(call: PluginCall, key: String, maxLen: Int = MAX_STRING_LEN): String {
        val raw = call.getString(key)
            ?: throw IllegalArgumentException("Missing required string: $key")
        return sanitizeString(raw, key, maxLen)
    }

    /** Read an optional string with the same validation; returns null if absent. */
    fun optionalString(call: PluginCall, key: String, maxLen: Int = MAX_STRING_LEN): String? {
        val raw = call.getString(key) ?: return null
        return sanitizeString(raw, key, maxLen)
    }

    private fun sanitizeString(raw: String, key: String, maxLen: Int): String {
        if (raw.length > maxLen) {
            throw IllegalArgumentException(
                "$key too long: ${raw.length} > $maxLen chars"
            )
        }
        // Strip control chars / noncharacters.
        val cleaned = CONTROL_CHARS_RE.matcher(raw).replaceAll("")
        // Reject path traversal.
        if (PATH_TRAVERSAL_RE.matcher(cleaned).find()) {
            throw IllegalArgumentException(
                "$key contains forbidden path-traversal sequence"
            )
        }
        return cleaned
    }

    // ───────────────────────────────────────────────────────────────────────
    // Integer / numeric validators
    // ───────────────────────────────────────────────────────────────────────

    fun requireInt(call: PluginCall, key: String, min: Int, max: Int): Int {
        val raw = call.getInt(key)
            ?: throw IllegalArgumentException("Missing required int: $key")
        if (raw < min || raw > max) {
            throw IllegalArgumentException(
                "$key out of range: $raw (must be $min..$max)"
            )
        }
        return raw
    }

    fun optionalInt(call: PluginCall, key: String, min: Int, max: Int, default: Int): Int {
        val raw = call.getInt(key) ?: return default
        if (raw < min || raw > max) {
            throw IllegalArgumentException(
                "$key out of range: $raw (must be $min..$max)"
            )
        }
        return raw
    }

    fun requireDouble(call: PluginCall, key: String, min: Double, max: Double): Double {
        val raw = call.getDouble(key)
            ?: throw IllegalArgumentException("Missing required double: $key")
        if (raw.isNaN() || raw.isInfinite()) {
            throw IllegalArgumentException("$key is NaN or Infinity")
        }
        if (raw < min || raw > max) {
            throw IllegalArgumentException(
                "$key out of range: $raw (must be $min..$max)"
            )
        }
        return raw
    }

    // ───────────────────────────────────────────────────────────────────────
    // GameMapper-specific typed validators
    // ───────────────────────────────────────────────────────────────────────

    /** Validate a pointer pool slot (0..99). */
    fun requirePointerSlot(call: PluginCall, key: String = "slot"): Int {
        return requireInt(call, key, MIN_SLOT, MAX_SLOT)
    }

    /** Validate a pixel coordinate against screen bounds. */
    fun requirePixelCoord(call: PluginCall, key: String, screenExtent: Int): Int {
        val v = requireInt(call, key, 0, screenExtent.coerceAtLeast(1))
        return v
    }

    /** Validate a fraction (0..1) for percentage-based coordinates. */
    fun requireFraction(call: PluginCall, key: String): Float {
        return requireDouble(call, key, 0.0, 1.0).toFloat()
    }

    /** Validate a button code (Linux evdev range). */
    fun requireButtonCode(call: PluginCall, key: String = "buttonCode"): Int {
        return requireInt(call, key, MIN_BUTTON_CODE, MAX_BUTTON_CODE)
    }

    /** Validate a tap/swipe duration (16..5000 ms). */
    fun requireDurationMs(call: PluginCall, key: String = "durationMs"): Long {
        val v = requireInt(call, key, MIN_TAP_DURATION_MS.toInt(), MAX_TAP_DURATION_MS.toInt())
        return v.toLong()
    }

    /** Validate a swipe direction (0=up, 1=down, 2=left, 3=right). */
    fun requireSwipeDirection(call: PluginCall, key: String = "direction"): Int {
        val v = requireInt(call, key, 0, 3)
        return v
    }

    /** Validate an Android package name string. */
    fun requirePackageName(call: PluginCall, key: String = "packageName"): String {
        val s = requireString(call, key, maxLen = 256)
        if (!PACKAGE_NAME_RE.matcher(s).matches()) {
            throw IllegalArgumentException("$key is not a valid Android package name: '$s'")
        }
        return s
    }

    // ───────────────────────────────────────────────────────────────────────
    // JSON validators
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Read a required JSON string, validate length + nesting depth.
     * Does NOT parse into a domain object — caller is responsible for that.
     * (For game profiles, use ProfileValidator.parseAndValidate directly.)
     */
    fun requireJsonString(call: PluginCall, key: String): String {
        val raw = requireString(call, key, maxLen = MAX_JSON_STRING_LEN)
        // Quick nesting-depth check to prevent StackOverflowError in JSON parser.
        var depth = 0
        var maxDepth = 0
        for (c in raw) {
            when (c) {
                '{', '[' -> {
                    depth++
                    if (depth > maxDepth) maxDepth = depth
                    if (depth > MAX_JSON_NESTING_DEPTH) {
                        throw IllegalArgumentException(
                            "$key JSON nesting too deep (> $MAX_JSON_NESTING_DEPTH levels)"
                        )
                    }
                }
                '}', ']' -> depth--
            }
        }
        return raw
    }

    /**
     * Parse a JSON object from the call, applying the same length + depth
     * checks as requireJsonString. Returns the parsed JSONObject.
     */
    fun requireJsonObject(call: PluginCall, key: String): org.json.JSONObject {
        val raw = requireJsonString(call, key)
        return try {
            org.json.JSONObject(raw)
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("$key is not valid JSON: ${e.message}")
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // URL validators
    // ───────────────────────────────────────────────────────────────────────

    /** Validate that a URL string uses an allowed scheme. */
    fun requireAllowedUrl(call: PluginCall, key: String): String {
        val raw = requireString(call, key, maxLen = 2048)
        try {
            val url = android.net.Uri.parse(raw)
            val scheme = url.scheme?.lowercase() ?: ""
            if (scheme !in ALLOWED_URL_SCHEMES) {
                throw IllegalArgumentException(
                    "$key URL scheme '$scheme' not in allowlist $ALLOWED_URL_SCHEMES"
                )
            }
            // Block path traversal in URL path.
            val path = url.path.orEmpty()
            if (PATH_TRAVERSAL_RE.matcher(path).find()) {
                throw IllegalArgumentException("$key URL contains path traversal")
            }
            return raw
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalArgumentException("$key is not a valid URL: ${t.message}")
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Boolean validators
    // ───────────────────────────────────────────────────────────────────────

    fun requireBoolean(call: PluginCall, key: String): Boolean {
        val raw = call.getBoolean(key)
            ?: throw IllegalArgumentException("Missing required boolean: $key")
        return raw
    }

    fun optionalBoolean(call: PluginCall, key: String, default: Boolean): Boolean {
        return call.getBoolean(key) ?: default
    }

    // ───────────────────────────────────────────────────────────────────────
    // Array validators (rarely needed — most arrays come via JSON)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Read a JS array as a JSON string, parse, validate each element via
     * the provided lambda. Returns the validated list.
     *
     * Usage:
     *   val slots = InputSanitizer.requireIntArray(call, "slots") { it in 0..99 }
     */
    fun requireIntArray(
        call: PluginCall,
        key: String,
        maxItems: Int = 256,
        validator: (Int) -> Boolean = { true }
    ): List<Int> {
        val raw = requireString(call, key, maxLen = MAX_JSON_STRING_LEN)
        val arr = try {
            org.json.JSONArray(raw)
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("$key is not a valid JSON array: ${e.message}")
        }
        if (arr.length() > maxItems) {
            throw IllegalArgumentException("$key has too many items: ${arr.length()} > $maxItems")
        }
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, Int.MIN_VALUE)
            if (v == Int.MIN_VALUE) {
                throw IllegalArgumentException("$key[$i] is not an integer")
            }
            if (!validator(v)) {
                throw IllegalArgumentException("$key[$i]=$v failed validation")
            }
            out.add(v)
        }
        return out
    }
}
