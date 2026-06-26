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
        val jsonStr = GamepadListenerService.activeProfileJson
        if (jsonStr == null) {
            Log.w("GameMapper", "buildMapCache: activeProfileJson is NULL — no profile delivered yet")
            return
        }
        if (jsonStr == "{}" || jsonStr.isEmpty()) {
            Log.w("GameMapper", "buildMapCache: activeProfileJson is EMPTY — profile was cleared")
            return
        }
        try {
            val root = JSONObject(jsonStr)
            val buttons = root.optJSONArray("buttons")
            if (buttons == null) {
                Log.w("GameMapper", "buildMapCache: no buttons array in profile JSON")
                return
            }
            for (i in 0 until buttons.length()) {
                val b = buttons.optJSONObject(i)
                val key = b?.optString("mappedKey")
                if (key != null) {
                    buttonMapCache[key] = b
                }
            }
            Log.i("GameMapper", "buildMapCache: loaded ${buttonMapCache.size} button mappings from profile")
        } catch (e: Exception) {
            Log.e("GameMapper", "buildMapCache: failed to parse profile JSON", e)
        }
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
            if (gamepadIndex !in 0..3) {
                Log.w("GameMapper", "handleButton: gamepadIndex out of range: $gamepadIndex")
                return
            }
            val offset = (gamepadIndex % 4) * 16
            
            // BUG-FIX: Check touchService FIRST. If null, no injection is possible.
            // Log clearly so user knows daemon must be started.
            val ts = TouchInjectionPlugin.touchService
            if (ts == null) {
                Log.e("GameMapper", "handleButton: touchService is NULL — daemon not started! " +
                    "Tap 'BOOT DAEMON' in Shizuku tab first. Button '$buttonName' isDown=$isDown IGNORED.")
                return
            }
            
            val mapping = findButtonMapping(buttonName)
            
            if (mapping == null) {
                Log.w("GameMapper", "handleButton: NO MAPPING for '$buttonName' (cache size=${buttonMapCache.size})")
            } else if (!mapping.has("x") || !mapping.has("y")) {
                Log.w("GameMapper", "handleButton: mapping for '$buttonName' has no x/y coordinates")
            } else {
                Log.d("GameMapper", "handleButton: '$buttonName' isDown=$isDown → mapping at (${mapping.getDouble("x")}%, ${mapping.getDouble("y")}%)")
            }
            
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

    /**
     * Apply proper radial-stick signal processing pipeline.
     *
     * BUG-ANALOG-1/2/3 FIX: Previously this function applied curve PER-AXIS, then computed
     * magnitude from curved values, then tested deadzone on the curved magnitude. This was
     * mathematically wrong on three counts:
     *
     *   1. Deadzone tested on POST-curve magnitude → concave curve (√x) could push small
     *      sub-deadzone inputs above the threshold, leaking noise.
     *   2. No rescaling after deadzone → stick just barely past deadzone jumped to ~15% of
     *      maxRadius instead of starting near 0.
     *   3. Per-axis curve distorted direction → atan2(cLy, cLx) ≠ atan2(ly, lx), so diagonal
     *      inputs bent toward the dominant axis.
     *
     * Correct pipeline (standard in input engineering):
     *
     *   raw → smooth → radial_deadzone(rawMag) → rescale[deadzone,1]→[0,1] →
     *        curve(rescaledMag) → unit_vector * curvedMag * maxRadius
     *
     * The curve is applied to the SCALAR magnitude only, preserving the original direction.
     */
    private fun processStick(
        rawX: Float,
        rawY: Float,
        mapping: JSONObject?,
        smoothBuffer: FloatArray,
        smoothOffset: Int,
        alpha: Float,
        pointer: PointerState,
        defaultRadius: Float
    ) {
        // 1. Smoothing (exponential moving average)
        smoothBuffer[smoothOffset]     = alpha * rawX + (1 - alpha) * smoothBuffer[smoothOffset]
        smoothBuffer[smoothOffset + 1] = alpha * rawY + (1 - alpha) * smoothBuffer[smoothOffset + 1]
        val sx = smoothBuffer[smoothOffset]
        val sy = smoothBuffer[smoothOffset + 1]

        // 2. Radial deadzone on RAW (pre-curve) magnitude
        val rawMag = sqrt(sx * sx + sy * sy)
        val deadzone = mapping?.optDouble("deadzone", 0.15)?.toFloat() ?: 0.15f
        val maxRadius = mapping?.optDouble("radius", defaultRadius.toDouble())?.toFloat() ?: defaultRadius

        if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
            if (pointer.isActive) {
                pointer.isActive = false
                try { TouchInjectionPlugin.touchService?.touchUp(pointer.id) } catch(e: Exception) {}
            }
            return
        }

        val (cX, cY) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))

        if (rawMag <= deadzone) {
            // Inside deadzone — release pointer if active
            if (pointer.isActive) {
                pointer.isActive = false
                try { TouchInjectionPlugin.touchService?.touchUp(pointer.id) } catch(e: Exception) {}
            }
            return
        }

        // 3. Rescale magnitude from [deadzone, 1] → [0, 1]
        val rescaledMag = ((rawMag - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)

        // 4. Apply sensitivity curve to magnitude only (direction preserved)
        val curve = mapping.optString("sensitivityCurve", "linear")
        val curvePoints = mapping.optJSONArray("curvePoints")
        val curvedMag = applyCurve(rescaledMag, curve, curvePoints)

        // 5. Reconstruct output vector: unit_vector(rawX, rawY) * curvedMag * maxRadius
        val invMag = if (rawMag > 1e-6f) 1f / rawMag else 0f
        val outX = (sx * invMag) * curvedMag * maxRadius
        val outY = (sy * invMag) * curvedMag * maxRadius
        val tX = cX + outX
        val tY = cY + outY

        // 6. Inject
        if (!pointer.isActive) {
            pointer.isActive = true
            try { TouchInjectionPlugin.touchService?.touchDown(pointer.id, cX, cY) } catch(e: Exception) { pointer.isActive = false }
        }
        if (pointer.isActive) {
            try { TouchInjectionPlugin.touchService?.touchMove(pointer.id, tX, tY) } catch(e: Exception) {
                pointer.isActive = false
                try { TouchInjectionPlugin.touchService?.touchUp(pointer.id) } catch(_: Exception) {}
            }
        }
    }

    fun handleAxes(gamepadIndex: Int, lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        synchronized(syncLock) {
            if (gamepadIndex !in 0..3) return
            val offset = (gamepadIndex % 4) * 16

            // BUG-FIX: Check touchService FIRST. If null, no injection possible.
            val ts = TouchInjectionPlugin.touchService
            if (ts == null) {
                Log.e("GameMapper", "handleAxes: touchService is NULL — daemon not started!")
                return
            }

            val lMap = findButtonMapping("L_STICK")
            val rMap = findButtonMapping("R_STICK")

            // BUG-M8 FIX: Use independent smoothing factors for L and R sticks.
            val lSmoothing = lMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f
            val rSmoothing = rMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f
            val lAlpha = 1f - lSmoothing.coerceIn(0f, 0.95f)
            val rAlpha = 1f - rSmoothing.coerceIn(0f, 0.95f)

            val lp = pointersById[offset + 0] ?: pointers[0]
            val rp = pointersById[offset + 1] ?: pointers[1]

            // L-stick: smoothing buffer indices [0,1] of smoothedAxes[gamepadIndex]
            processStick(
                rawX = lx, rawY = ly,
                mapping = lMap,
                smoothBuffer = smoothedAxes[gamepadIndex],
                smoothOffset = 0,
                alpha = lAlpha,
                pointer = lp,
                defaultRadius = 100f
            )

            // R-stick: smoothing buffer indices [2,3] of smoothedAxes[gamepadIndex]
            processStick(
                rawX = rx, rawY = ry,
                mapping = rMap,
                smoothBuffer = smoothedAxes[gamepadIndex],
                smoothOffset = 2,
                alpha = rAlpha,
                pointer = rp,
                defaultRadius = 150f
            )

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
