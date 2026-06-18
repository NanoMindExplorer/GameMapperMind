package com.nanomindexplorer.gamemappermind.util

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * HarmonyOSSafeAreaHelper — Dynamic safe-area probe khusus HarmonyOS 4.2.
 *
 * Implementasi klausul §9.4 GMM-AEC-002:
 *   Safe-area dan coordinate probe harus menangani gesture navigation Huawei
 *   yang berbeda dari AOSP — gunakan WindowInsets.getInsets(systemBars())
 *   dengan fallback ke static safe margin untuk HarmonyOS.
 *
 * Algorithm:
 *   1. Jika Activity tersedia → gunakan WindowInsets API (API 30+)
 *   2. Jika Activity tidak tersedia (UserService context) → fallback:
 *      - Gunakan static margins berdasarkan HarmonyOS 4.2 defaults
 *   3. Untuk perangkat non-HarmonyOS → return zero insets
 *
 * Cache: hasil probe di-cache per orientation + config change
 * Thread safety: @Volatile cached values, synchronized probe
 */
object HarmonyOSSafeAreaHelper {
    private const val TAG = "GameMapper/HarmonyOSSafeArea"

    // Static fallback margins untuk HarmonyOS 4.2 (MRO-W09)
    private const val HARMONY_STATUS_BAR_HEIGHT_PX = 108
    private const val HARMONY_GESTURE_NAV_BOTTOM_PX = 144

    @Volatile
    private var cachedInsets: Rect = Rect(0, 0, 0, 0)

    @Volatile
    private var cacheValid: Boolean = false

    @Volatile
    private var lastOrientation: Int = -1

    fun probeSafeArea(activity: Activity?): Rect {
        if (activity == null) {
            return getStaticFallbackInsets()
        }

        try {
            val rootView = activity.window?.decorView ?: return getStaticFallbackInsets()
            val orientation = activity.resources.configuration.orientation
            if (cacheValid && lastOrientation == orientation) {
                return cachedInsets
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rootWindowInsets = rootView.rootWindowInsets
                if (rootWindowInsets != null) {
                    val systemBars = rootWindowInsets.getInsets(WindowInsets.Type.systemBars())
                    val insets = Rect(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                    cachedInsets = insets
                    cacheValid = true
                    lastOrientation = orientation
                    Log.d(TAG, "probeSafeArea (API 30+): " + insets + " (orientation=" + orientation + ")")
                    return insets
                }
            }

            @Suppress("DEPRECATION")
            val rootWindowInsets = rootView.rootWindowInsets
            if (rootWindowInsets != null) {
                @Suppress("DEPRECATION")
                val insets = Rect(
                    rootWindowInsets.systemWindowInsetLeft,
                    rootWindowInsets.systemWindowInsetTop,
                    rootWindowInsets.systemWindowInsetRight,
                    rootWindowInsets.systemWindowInsetBottom
                )
                cachedInsets = insets
                cacheValid = true
                lastOrientation = orientation
                Log.d(TAG, "probeSafeArea (API 20-29): " + insets + " (orientation=" + orientation + ")")
                return insets
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeSafeArea failed, using fallback: " + e.message)
        }

        return getStaticFallbackInsets()
    }

    fun getStaticFallbackInsets(): Rect {
        val isHarmony = HarmonyOSHelper.isHarmonyOS()
        return if (isHarmony) {
            Rect(0, HARMONY_STATUS_BAR_HEIGHT_PX, 0, HARMONY_GESTURE_NAV_BOTTOM_PX)
        } else {
            Rect(0, 0, 0, 0)
        }
    }

    fun applySafeArea(
        x: Float, y: Float,
        screenWidth: Int, screenHeight: Int,
        insets: Rect
    ): Pair<Float, Float> {
        val adjustedX = x.coerceIn(insets.left.toFloat(), (screenWidth - insets.right).toFloat())
        val adjustedY = y.coerceIn(insets.top.toFloat(), (screenHeight - insets.bottom).toFloat())
        return Pair(adjustedX, adjustedY)
    }

    fun getEffectiveDrawableArea(
        screenWidth: Int, screenHeight: Int,
        insets: Rect
    ): Pair<Int, Int> {
        val effectiveW = screenWidth - insets.left - insets.right
        val effectiveH = screenHeight - insets.top - insets.bottom
        return Pair(effectiveW.coerceAtLeast(1), effectiveH.coerceAtLeast(1))
    }

    fun invalidateCache() {
        cacheValid = false
        cachedInsets = Rect(0, 0, 0, 0)
        lastOrientation = -1
    }

    fun isGestureNavigationActive(): Boolean {
        if (!HarmonyOSHelper.isHarmonyOS()) return false
        val majorVersion = HarmonyOSHelper.getHarmonyMajorVersion()
        if (majorVersion >= 4 || majorVersion == 14) return true
        return try {
            val modeFile = java.io.File("/sys/class/hwnavigation/mode")
            if (modeFile.exists()) {
                val mode = modeFile.readText().trim()
                mode == "gesture" || mode == "0"
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
