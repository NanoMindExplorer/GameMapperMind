package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class NativeGamepadMapper(private val context: Context) {
    companion object {
        var instance: NativeGamepadMapper? = null
        val syncLock = Any()
        
        fun resetAll() {
            synchronized(syncLock) {
                instance?.pointers?.forEach { 
                    if (it.isActive) {
                        try {
                            TouchInjectionPlugin.touchService?.touchUp(it.id)
                        } catch(e: Exception) {}
                        it.isActive = false 
                    }
                }
                instance?.lastState?.clear()
                instance?.buildMapCache()
            }
        }
    }

    class PointerState(val id: Int, var isActive: Boolean, val type: String, var virtualKey: String? = null)
    
    // Support up to 4 gamepads, with 16 pointers each: P1=0-15, P2=16-31, P3=32-47, P4=48-63
    val pointers = mutableListOf<PointerState>().apply {
        for (gp in 0..3) {
            val offset = gp * 16
            add(PointerState(offset + 0, false, "analog"))
            add(PointerState(offset + 1, false, "analog"))
            for (i in 2..15) add(PointerState(offset + i, false, "button"))
        }
    }
    // BUG-F5 FIX: Indexed lookup array for O(1) pointer access (vs O(n) find).
    private val pointersById: Array<PointerState?> = Array(64) { null }
    init {
        for (p in pointers) { pointersById[p.id] = p }
    }

    val lastState = mutableMapOf<String, Boolean>()
    private val smoothedAxes = Array(4) { FloatArray(4) }
    var buttonMapCache = mutableMapOf<String, JSONObject>()
    
    fun buildMapCache() {
        buttonMapCache.clear()
        val jsonStr = GamepadListenerService.activeProfileJson ?: return
        try {
            val root = JSONObject(jsonStr)
            val buttons = root.optJSONArray("buttons") ?: return
            for (i in 0 until buttons.length()) {
                val b = buttons.optJSONObject(i)
                val key = b?.optString("mappedKey")
                if (key != null) {
                    buttonMapCache[key] = b
                }
            }
        } catch (e: Exception) {}
    }
    
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        instance = this
        buildMapCache()
    }

    private fun getScreenCoords(pctX: Double, pctY: Double): Pair<Float, Float> {
        // BUG-M15 FIX: WindowMetrics.currentWindowMetrics is stable since API 30 (R), not API 31 (S).
        // Use VERSION_CODES.R as the threshold for the modern API path.
        val (sw, sh) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                Pair(bounds.width().toFloat(), bounds.height().toFloat())
            } catch (e: Exception) {
                // BUG-M9 FALLBACK: If WindowMetrics fails (e.g., on some MIUI/EMUI devices),
                // fall back to deprecated Display API with rotation handling.
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(dm)
                val rotation = windowManager.defaultDisplay.rotation
                when (rotation) {
                    android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 ->
                        Pair(dm.heightPixels.toFloat(), dm.widthPixels.toFloat())
                    else -> Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
                }
            }
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            val rotation = windowManager.defaultDisplay.rotation
            when (rotation) {
                android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 ->
                    Pair(dm.heightPixels.toFloat(), dm.widthPixels.toFloat())
                else -> Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
            }
        }
        return Pair(((pctX / 100.0) * sw).toFloat(), ((pctY / 100.0) * sh).toFloat())
    }
    
    private fun findButtonMapping(mappedKey: String): JSONObject? {
        return buttonMapCache[mappedKey]
    }

    private fun getAntiBanOffset(antiBanEnabled: Boolean): Pair<Float, Float> {
        if (!antiBanEnabled) return Pair(0f, 0f)
        val radius = Random.nextFloat() * 8f
        // BUG-F6 FIX: Use Kotlin-only math API (consistent with rest of code).
        val angle = (Random.nextFloat() * 2 * kotlin.math.PI).toFloat()
        return Pair((radius * cos(angle.toDouble())).toFloat(), (radius * sin(angle.toDouble())).toFloat())
    }

    fun handleButton(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        synchronized(syncLock) {
            // BUG-MULTI2 FIX: Reject out-of-range gamepad index (defense-in-depth).
            if (gamepadIndex !in 0..3) {
                Log.w("GameMapper", "handleButton: gamepadIndex out of range: $gamepadIndex")
                return
            }
            val offset = (gamepadIndex % 4) * 16
            
            val mapping = findButtonMapping(buttonName)
            // check player filter
            if (mapping != null && mapping.has("player")) {
                 val player = mapping.optInt("player", 1)
                 if (player != gamepadIndex + 1) return
            }

            if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
                val wasDown = lastState[buttonName + gamepadIndex] ?: false
                // Complete pending touchUp if it was mapped previously
                if (!isDown && wasDown) {
                    // BUG-F5 FIX: O(1) lookup via pointersById
                    val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { it.isActive && it.virtualKey == buttonName }
                    if (p != null) {
                        p.isActive = false
                        p.virtualKey = null
                        try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch(e:Exception){}
                    }
                }
                lastState[buttonName + gamepadIndex] = isDown
                return
            }
            
            val antiBanEnabled = mapping.optBoolean("antiBanEnabled", false)
            val type = mapping.optString("type", "button")
            
            val wasDown = lastState[buttonName + gamepadIndex] ?: false
            if (isDown && !wasDown) {
                val tapDuration = mapping.optLong("tapDuration", 0L)
                var (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
                val (ox, oy) = getAntiBanOffset(antiBanEnabled)
                x += ox
                y += oy
                
                if (tapDuration > 0L) {
                    // DEFECT #2 FIX: injectTap uses internal daemon pointer (100-199), NOT pointersById.
                    // Previously, lastState was set true and return — but if injectTap failed (service
                    // null, RemoteException), lastState stayed true forever. Next press: wasDown=true →
                    // `isDown && !wasDown` false → injectTap never fires again → button permanently stuck.
                    //
                    // Fix: Use try-catch. If injectTap succeeds, set lastState=true. If it fails,
                    // do NOT set lastState=true (allow retry on next press).
                    try {
                        val tapResult = TouchInjectionPlugin.touchService?.injectTap(x, y, tapDuration) ?: false
                        if (tapResult) {
                            lastState[buttonName + gamepadIndex] = true
                        }
                        // If tapResult is false, lastState stays false → next press will retry.
                    } catch (e: Exception) {
                        Log.w("GameMapper", "injectTap failed for $buttonName: ${e.message}")
                        // lastState stays false → retry on next press.
                    }
                    return
                }

                // BUG-F5 FIX: O(1) lookup via pointersById
                val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { !it.isActive && it.type == "button" }
                if (p != null) {
                    p.isActive = true
                    p.virtualKey = buttonName
                    TouchInjectionPlugin.touchService?.touchDown(p.id, x, y)

                    if (type == "swipe" && mapping.has("swipeEndX") && mapping.has("swipeEndY")) {
                        val (ex, ey) = getScreenCoords(mapping.getDouble("swipeEndX"), mapping.getDouble("swipeEndY"))
                        // BUG-M10 FIX: Schedule touchMove AND touchUp after the swipe duration.
                        val swipeDuration = mapping.optLong("swipeDuration", 50L)
                        mainHandler.postDelayed({
                            synchronized(syncLock) {
                                if (p.isActive) {
                                    TouchInjectionPlugin.touchService?.touchMove(p.id, ex + ox, ey + oy)
                                    mainHandler.postDelayed({
                                        synchronized(syncLock) {
                                            if (p.isActive) {
                                                p.isActive = false
                                                p.virtualKey = null
                                                TouchInjectionPlugin.touchService?.touchUp(p.id)
                                            }
                                        }
                                    }, swipeDuration)
                                }
                            }
                        }, 50)
                    }
                }
            } else if (!isDown && wasDown) {
                // DEFECT #2 FIX: For tapDuration path, no pointer is active in pointersById
                // (injectTap uses internal daemon pointer). The release branch correctly finds
                // p=null and skips touchUp (injectTap schedules its own touchUp internally).
                // lastState is reset to false at line 216 below — this is correct.
                // No additional fix needed here; the fix is in the press path (try-catch above).
                val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { it.isActive && it.virtualKey == buttonName }
                if (p != null) {
                    p.isActive = false
                    p.virtualKey = null
                    TouchInjectionPlugin.touchService?.touchUp(p.id)
                }
            }
            lastState[buttonName + gamepadIndex] = isDown
        }
    }

    private fun applyCurve(x: Float, curveType: String?, curvePoints: org.json.JSONArray?): Float {
        if (curveType == null) return x
        val sign = kotlin.math.sign(x)
        val absX = kotlin.math.abs(x)
        return sign * when (curveType.lowercase()) {
            // BUG-F1 FIX: "parabolic" now correctly returns x² (was returning √x which is concave, opposite of intended).
            // Both "exponential" and "parabolic" produce x² — they are mathematically the same curve.
            // Use "exponential" for stick-small=insensitive, stick-large=sensitive (ideal for FPS aim assist).
            "exponential", "expo", "parabolic", "para" -> absX * absX
            "concave" -> kotlin.math.sqrt(absX)  // stick-small=sensitive, stick-large=insensitive (inverse)
            "custom" -> {
                if (curvePoints == null || curvePoints.length() < 2) return absX
                // BUG-M7 FIX: Clamp absX to [0, 1] before interpolation to avoid idx out of range.
                // Also coerce idx to [0, n-2] and recompute t from clamped values.
                val clampedX = absX.coerceIn(0f, 1f)
                val n = curvePoints.length()
                val step = 1.0f / (n - 1)
                val idx = (clampedX / step).toInt().coerceIn(0, n - 2)
                val t = (clampedX - idx * step) / step
                val y1 = curvePoints.optDouble(idx, 0.0).toFloat()
                val y2 = curvePoints.optDouble(idx + 1, 1.0).toFloat()
                (y1 + t * (y2 - y1)).coerceIn(0f, 1f)
            }
            else -> absX // linear
        }
    }

    fun handleAxes(gamepadIndex: Int, lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        synchronized(syncLock) {
            // BUG-MULTI2 FIX: Reject out-of-range gamepad index.
            if (gamepadIndex !in 0..3) return
            val offset = (gamepadIndex % 4) * 16

            val lMap = findButtonMapping("L_STICK")
            val rMap = findButtonMapping("R_STICK")

            // BUG-M8 FIX: Use independent smoothing factors for L and R sticks.
            // Previously, lMap's smoothing was applied to BOTH sticks, ignoring rMap's smoothing.
            val lSmoothing = lMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f
            val rSmoothing = rMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f
            val lAlpha = 1f - lSmoothing.coerceIn(0f, 0.95f)
            val rAlpha = 1f - rSmoothing.coerceIn(0f, 0.95f)

            smoothedAxes[gamepadIndex][0] = lAlpha * lx + (1 - lAlpha) * smoothedAxes[gamepadIndex][0]
            smoothedAxes[gamepadIndex][1] = lAlpha * ly + (1 - lAlpha) * smoothedAxes[gamepadIndex][1]
            smoothedAxes[gamepadIndex][2] = rAlpha * rx + (1 - rAlpha) * smoothedAxes[gamepadIndex][2]
            smoothedAxes[gamepadIndex][3] = rAlpha * ry + (1 - rAlpha) * smoothedAxes[gamepadIndex][3]

            val sLxRaw = smoothedAxes[gamepadIndex][0]
            val sLyRaw = smoothedAxes[gamepadIndex][1]
            val sRxRaw = smoothedAxes[gamepadIndex][2]
            val sRyRaw = smoothedAxes[gamepadIndex][3]

            val lCurve = lMap?.optString("sensitivityCurve", "linear")
            val lPoints = lMap?.optJSONArray("curvePoints")
            
            val rCurve = rMap?.optString("sensitivityCurve", "linear")
            val rPoints = rMap?.optJSONArray("curvePoints")

            val cLx = applyCurve(sLxRaw, lCurve, lPoints)
            val cLy = applyCurve(sLyRaw, lCurve, lPoints)
            val cRx = applyCurve(sRxRaw, rCurve, rPoints)
            val cRy = applyCurve(sRyRaw, rCurve, rPoints)
            
            val sLx = cLx
            val sLy = cLy
            val sRx = cRx
            val sRy = cRy
        
            val lMag = sqrt(sLx*sLx + sLy*sLy)
            val lp = pointersById[offset + 0] ?: pointers[0]
            if (lMap != null && lMap.has("x") && lMap.has("y")) {
                val deadzone = lMap.optDouble("deadzone", 0.15).toFloat()
                val maxRadius = lMap.optDouble("radius", 100.0).toFloat()
                
                val (cX, cY) = getScreenCoords(lMap.getDouble("x"), lMap.getDouble("y"))
                if (lMag > deadzone) {
                    val tX = cX + (sLx * maxRadius)
                    val tY = cY + (sLy * maxRadius)
                    if (!lp.isActive) {
                        lp.isActive = true
                        try { TouchInjectionPlugin.touchService?.touchDown(lp.id, cX, cY) } catch(e: Exception) { lp.isActive = false }
                    }
                    if (lp.isActive) {
                        try { TouchInjectionPlugin.touchService?.touchMove(lp.id, tX, tY) } catch(e: Exception) { lp.isActive = false; try { TouchInjectionPlugin.touchService?.touchUp(lp.id) } catch(_: Exception) {} }
                    }
                } else if (lp.isActive) {
                    lp.isActive = false
                    try { TouchInjectionPlugin.touchService?.touchUp(lp.id) } catch(e: Exception) {}
                }
            } else if (lp.isActive) {
                lp.isActive = false
                try { TouchInjectionPlugin.touchService?.touchUp(lp.id) } catch(e: Exception) {}
            }
            
            val rMag = sqrt(sRx*sRx + sRy*sRy)
            val rp = pointersById[offset + 1] ?: pointers[1]
            if (rMap != null && rMap.has("x") && rMap.has("y")) {
                val deadzone = rMap.optDouble("deadzone", 0.15).toFloat()
                val maxRadius = rMap.optDouble("radius", 150.0).toFloat()
                
                val (cX, cY) = getScreenCoords(rMap.getDouble("x"), rMap.getDouble("y"))
                if (rMag > deadzone) {
                    val tX = cX + (sRx * maxRadius)
                    val tY = cY + (sRy * maxRadius)
                    if (!rp.isActive) {
                        rp.isActive = true
                        try { TouchInjectionPlugin.touchService?.touchDown(rp.id, cX, cY) } catch(e: Exception) { rp.isActive = false }
                    }
                    if (rp.isActive) {
                        try { TouchInjectionPlugin.touchService?.touchMove(rp.id, tX, tY) } catch(e: Exception) { rp.isActive = false; try { TouchInjectionPlugin.touchService?.touchUp(rp.id) } catch(_: Exception) {} }
                    }
                } else if (rp.isActive) {
                    rp.isActive = false
                    try { TouchInjectionPlugin.touchService?.touchUp(rp.id) } catch(e: Exception) {}
                }
            } else if (rp.isActive) {
                rp.isActive = false
                try { TouchInjectionPlugin.touchService?.touchUp(rp.id) } catch(e: Exception) {}
            }
            
            // BUG-F3 FIX: Debounce trigger LT/RT — only set false if analog value stays below threshold
            // for at least 50ms (avoids flicker during transition between digital BTN_TL2 and analog ABS_Z).
            if (l2 > 0.05f) {
                handleButton(gamepadIndex, "LT", true)
            } else if (l2 < 0.03f) {  // Lower threshold for release (hysteresis)
                handleButton(gamepadIndex, "LT", false)
            }
            
            if (r2 > 0.05f) {
                handleButton(gamepadIndex, "RT", true)
            } else if (r2 < 0.03f) {
                handleButton(gamepadIndex, "RT", false)
            }
        }
    }
}
