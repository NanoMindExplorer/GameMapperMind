package com.nanomindexplorer.gamemappermind.util

import android.os.Build
import android.util.Log

/**
 * HarmonyOSHelper — Deteksi dan utilitas khusus HarmonyOS 4.2 (Huawei).
 *
 * Implementasi klausul §9.1 GMM-AEC-002:
 *   AI wajib mengimplementasikan fungsi isHarmonyOS(): Boolean yang akurat —
 *   deteksi via Build.MANUFACTURER, System.getProperty("ro.build.version.emui"),
 *   dan keberadaan class HwDeviceManager.
 *
 * Algoritma deteksi (3-path validation, ALL must match for true positive):
 *
 *   Path 1 — Build.MANUFACTURER:
 *     Build.MANUFACTURER.equalsIgnoreCase("HUAWEI")
 *     atau Build.BRAND.equalsIgnoreCase("HUAWEI")
 *     atau Build.BRAND.equalsIgnoreCase("HONOR")
 *
 *   Path 2 — System property EMUI version:
 *    反射 SystemProperties.get("ro.build.version.emui") returns non-empty
 *     Contoh: "EmotionUI_14.0.0", "HarmonyOS 4.2.0"
 *
 *   Path 3 — Huawei hidden class existence:
 *     Class.forName("android.app.HwDeviceManager") tidak throw ClassNotFoundException
 *     atau Class.forName("com.huawei.android.util.HwNotchSizeUtil")
 *
 * Decision matrix:
 *   - 3/3 match → isHarmonyOS() = true (high confidence)
 *   - 2/3 match → isHarmonyOS() = true (medium confidence, log warning)
 *   - 1/3 match → isHarmonyOS() = false (one signal alone is not enough)
 *   - 0/3 match → isHarmonyOS() = false (vanilla Android)
 *
 * Cache: hasil deteksi di-cache di @Volatile var setelah first call
 * untuk avoid reflection overhead di hot path.
 *
 * Complexity: O(1) cached, O(reflection-cost) on first call
 * Thread safety: @Volatile + synchronized init block
 */
object HarmonyOSHelper {
    private const val TAG = "GameMapper/HarmonyOSHelper"

    // ============================================================
    // Cached detection result — computed once, reused forever
    // ============================================================
    @Volatile
    private var detected: Boolean = false

    @Volatile
    private var detectionDone: Boolean = false

    @Volatile
    private var emuiVersion: String = ""

    @Volatile
    private var harmonyMajorVersion: Int = 0

    @Volatile
    private var detectionConfidence: Int = 0  // 0..3

    /**
     * Deteksi apakah perangkat ini menjalankan HarmonyOS.
     *
     * @return true jika HarmonyOS (Huawei) terdeteksi dengan confidence >= 2/3
     */
    fun isHarmonyOS(): Boolean {
        if (detectionDone) return detected
        synchronized(this) {
            if (detectionDone) return detected
            performDetection()
            detectionDone = true
            return detected
        }
    }

    /**
     * Dapatkan versi EMUI/HarmonyOS (mis. "EmotionUI_14.0.0" atau "HarmonyOS 4.2.0").
     * @return String versi, atau "" jika bukan HarmonyOS
     */
    fun getEmuiVersion(): String {
        if (!detectionDone) isHarmonyOS()
        return emuiVersion
    }

    /**
     * Dapatkan major version HarmonyOS (mis. 4 untuk HarmonyOS 4.2).
     * @return Int major version, atau 0 jika bukan HarmonyOS
     */
    fun getHarmonyMajorVersion(): Int {
        if (!detectionDone) isHarmonyOS()
        return harmonyMajorVersion
    }

    /**
     * Confidence level deteksi (0..3).
     * 3 = high confidence (semua 3 path match)
     * 2 = medium confidence
     * 1/0 = low/no confidence (isHarmonyOS returns false)
     */
    fun getDetectionConfidence(): Int {
        if (!detectionDone) isHarmonyOS()
        return detectionConfidence
    }

    /**
     * Lakukan deteksi aktual — dipanggil sekali, hasil di-cache.
     */
    private fun performDetection() {
        var score = 0

        // ============================================================
        // Path 1: Build.MANUFACTURER / BRAND
        // ============================================================
        val manufacturer = Build.MANUFACTURER?.lowercase()?.trim() ?: ""
        val brand = Build.BRAND?.lowercase()?.trim() ?: ""
        val isHuaweiByBuild = manufacturer == "huawei" || brand == "huawei" || brand == "honor"
        if (isHuaweiByBuild) score++

        // ============================================================
        // Path 2: System property ro.build.version.emui (via reflection)
        // ============================================================
        var emuiVer = ""
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            emuiVer = getMethod.invoke(null, "ro.build.version.emui") as? String ?: ""
            if (emuiVer.isNotEmpty()) {
                score++
                // Parse major version: "EmotionUI_14.0.0" -> 14, "HarmonyOS 4.2.0" -> 4
                harmonyMajorVersion = parseHarmonyMajorVersion(emuiVer)
            }
        } catch (e: Exception) {
            // SystemProperties not accessible — try alternative property
            try {
                val spClass = Class.forName("android.os.SystemProperties")
                val getMethod = spClass.getMethod("get", String::class.java)
                emuiVer = getMethod.invoke(null, "ro.build.version.magic") as? String ?: ""
                if (emuiVer.isNotEmpty()) {
                    score++
                    harmonyMajorVersion = parseHarmonyMajorVersion(emuiVer)
                }
            } catch (_: Exception) {
                // Both properties unavailable — score stays as-is
            }
        }
        emuiVersion = emuiVer

        // ============================================================
        // Path 3: Hidden Huawei class existence
        // ============================================================
        val hwClassExists = try {
            Class.forName("android.app.HwDeviceManager")
            true
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName("com.huawei.android.util.HwNotchSizeUtil")
                true
            } catch (_: ClassNotFoundException) {
                try {
                    Class.forName("com.huawei.android.security.IHwBehaviorCollectManager")
                    true
                } catch (_: ClassNotFoundException) {
                    false
                }
            }
        }
        if (hwClassExists) score++

        detectionConfidence = score

        // Decision: require >= 2/3 match
        detected = score >= 2

        Log.i(TAG, "HarmonyOS detection: detected=$detected, score=$score/3, " +
                   "manufacturer=$manufacturer, brand=$brand, " +
                   "emuiVersion='$emuiVer', harmonyMajor=$harmonyMajorVersion, " +
                   "hwClassExists=$hwClassExists")
    }

    /**
     * Parse major version dari string EMUI/HarmonyOS.
     * Contoh input:
     *   "EmotionUI_14.0.0" → 14
     *   "HarmonyOS 4.2.0" → 4
     *   "HarmonyOS 12.0.0" → 12 (HarmonyOS 4.x HuaweiMatePad reports as 12 internally)
     *   "Magic UI 6.0" → 6
     *   "" → 0
     */
    private fun parseHarmonyMajorVersion(version: String): Int {
        if (version.isEmpty()) return 0
        // Cari digit pertama dalam string
        val match = Regex("(\\d+)").find(version)
        return match?.value?.toIntOrNull() ?: 0
    }

    /**
     * Apakah perangkat mendukung gesture navigation Huawei (bukan 3-button nav)?
     * Di HarmonyOS 4+, gesture nav adalah default.
     *
     * @return true jika gesture navigation kemungkinan aktif
     */
    fun isGestureNavigationLikely(): Boolean {
        if (!isHarmonyOS()) return false
        // HarmonyOS 4+ selalu gesture nav by default
        return harmonyMajorVersion >= 4 || harmonyMajorVersion == 14
    }

    /**
     * Rekomendasi polling rate (Hz) berdasarkan platform.
     * Kontrak §12.4: max 120Hz HarmonyOS / 200Hz Android murni.
     *
     * @return Polling rate dalam Hz
     */
    fun getRecommendedPollingHz(): Int {
        return if (isHarmonyOS()) 120 else 200
    }

    /**
     * Rekomendasi polling interval dalam ms.
     * @return Interval polling (1000ms / Hz)
     */
    fun getRecommendedPollingIntervalMs(): Long {
        return 1000L / getRecommendedPollingHz()
    }

    /**
     * Reset cache — untuk testing.
     */
    fun resetCache() {
        synchronized(this) {
            detected = false
            detectionDone = false
            emuiVersion = ""
            harmonyMajorVersion = 0
            detectionConfidence = 0
        }
    }
}
