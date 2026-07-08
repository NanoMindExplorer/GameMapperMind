package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class NativeGamepadMapper(private val context: Context) {

    companion object {
        @Volatile var instance: NativeGamepadMapper? = null
        val syncLock = Any()

        fun resetAll() {
            synchronized(syncLock) {
                instance?.pointers?.forEach {
                    if (it.isActive) {
                        try { TouchInjectionPlugin.touchService?.touchUp(it.id) } catch (_: Exception) {}
                        it.isActive = false
                    }
                }
                instance?.lastState?.clear()
                instance?.smoothedAxes?.forEach { it.fill(0f) }
                instance?.buildMapCache()
            }
        }
    }

    class PointerState(val id: Int, var isActive: Boolean, val type: String, var virtualKey: String? = null)

    val pointers = mutableListOf<PointerState>().apply {
        for (gp in 0..3) {
            val offset = gp * 16
            add(PointerState(offset + 0, false, "analog"))
            add(PointerState(offset + 1, false, "analog"))
            for (i in 2..15) add(PointerState(offset + i, false, "button"))
        }
    }

    private val pointersById: Array<PointerState?> = Array(64) { null }

    init {
        for (p in pointers) pointersById[p.id] = p
    }

    val lastState = mutableMapOf<String, Boolean>()
    private val smoothedAxes = Array(4) { FloatArray(4) }

    var buttonMapCache = mutableMapOf<String, JSONObject>()
    private var triggerMapCache = mutableMapOf<String, MutableList<JSONObject>>()

    private val turboRunnables = mutableMapOf<String, Runnable>()
    private val toggleState = mutableMapOf<String, Boolean>()
    private val chargeTimestamps = mutableMapOf<String, Long>()

    fun buildMapCache() {
        buttonMapCache.clear()
        triggerMapCache.clear()
        val jsonStr = GamepadListenerService.activeProfileJson ?: return
        if (jsonStr.isEmpty() || jsonStr == "{}") return

        try {
            val root = JSONObject(jsonStr)
            val buttons = root.optJSONArray("buttons") ?: return

            for (i in 0 until buttons.length()) {
                val b = buttons.optJSONObject(i) ?: continue
                val key = b.optString("mappedKey")
                if (key.isNotEmpty() && key != "null") buttonMapCache[key] = b

                val trigger = b.optJSONObject("trigger")
                if (trigger != null) {
                    val inputs = trigger.optJSONArray("inputs")
                    inputs?.let {
                        for (j in 0 until it.length()) {
                            val input = it.optString(j)
                            if (input.isNotEmpty()) {
                                triggerMapCache.getOrPut(input) { mutableListOf() }.add(b)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "buildMapCache failed", e)
        }
    }

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val mapperInjectionThread = android.os.HandlerThread("MapperInjection").also { it.start() }
    private val mainHandler = Handler(mapperInjectionThread.looper)

    init {
        instance?.let { old ->
            old.turboRunnables.values.forEach { old.mainHandler.removeCallbacks(it) }
            old.turboRunnables.clear()
        }
        instance = this
        buildMapCache()
    }

    private fun getScreenCoords(pctX: Double, pctY: Double): Pair<Float, Float> {
        return try {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(
                ((pctX / 100.0) * bounds.width()).toFloat(),
                ((pctY / 100.0) * bounds.height()).toFloat()
            )
        } catch (e: Exception) {
            Pair(1080f, 1920f)
        }
    }

    private fun findButtonMapping(mappedKey: String): JSONObject? = buttonMapCache[mappedKey]

    private fun getAntiBanOffset(enabled: Boolean): Pair<Float, Float> {
        if (!enabled) return Pair(0f, 0f)
        val radius = Random.nextFloat() * 8f
        val angle = Random.nextFloat() * (2 * Math.PI).toFloat()
        return Pair((radius * cos(angle.toDouble())).toFloat(), (radius * sin(angle.toDouble())).toFloat())
    }

    // ==================== PHASE 2: RADIAL DEADZONE ====================

    private fun applyRadialDeadzone(x: Float, y: Float, deadzone: Float): Pair<Float, Float> {
        val magnitude = sqrt(x * x + y * y)
        if (magnitude <= deadzone) return Pair(0f, 0f)
        val scale = (magnitude - deadzone) / (1f - deadzone)
        return Pair((x / magnitude) * scale, (y / magnitude) * scale)
    }

    private fun processStick(
        rawX: Float, rawY: Float,
        mapping: JSONObject?,
        smoothBuffer: FloatArray,
        smoothOffset: Int,
        alpha: Float,
        pointer: PointerState,
        defaultRadius: Float
    ) {
        if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
            if (pointer.isActive) {
                pointer.isActive = false
                try { TouchInjectionPlugin.touchService?.touchUp(pointer.id) } catch (_: Exception) {}
            }
            return
        }

        smoothBuffer[smoothOffset] = alpha * rawX + (1 - alpha) * smoothBuffer[smoothOffset]
        smoothBuffer[smoothOffset + 1] = alpha * rawY + (1 - alpha) * smoothBuffer[smoothOffset + 1]

        val sx = smoothBuffer[smoothOffset]
        val sy = smoothBuffer[smoothOffset + 1]
        val rawMag = sqrt(sx * sx + sy * sy)
        val deadzone = mapping.optDouble("deadzone", 0.12).toFloat()

        if (rawMag <= deadzone) {
            if (pointer.isActive) {
                try { TouchInjectionPlugin.touchService?.touchUp(pointer.id) } catch (_: Exception) {}
                pointer.isActive = false
            }
            smoothBuffer[smoothOffset] = 0f
            smoothBuffer[smoothOffset + 1] = 0f
            return
        }

        val (dzX, dzY) = applyRadialDeadzone(sx, sy, deadzone)
        val rescaledMag = sqrt(dzX * dzX + dzY * dzY).coerceIn(0f, 1f)

        val curve = mapping.optString("sensitivityCurve", "linear")
        val curvePoints = mapping.optJSONArray("curvePoints")
        val curvedMag = applyCurve(rescaledMag, curve, curvePoints)

        val sensitivity = mapping.optDouble("sensitivity", 1.0).toFloat().coerceIn(0.1f, 5.0f)
        val finalMag = (curvedMag * sensitivity).coerceIn(0f, 1f)

        val (cX, cY) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))

        val (tX, tY) = if (mapping.optString("stickMode", "joystick") == "drag") {
            val (screenW, screenH) = try {
                val b = windowManager.currentWindowMetrics.bounds
                Pair(b.width().toFloat(), b.height().toFloat())
            } catch (_: Exception) { Pair(1080f, 1920f) }
            Pair(
                (cX + dzX * finalMag * screenW * 0.35f).coerceIn(0f, screenW),
                (cY + dzY * finalMag * screenH * 0.35f).coerceIn(0f, screenH)
            )
        } else {
            val invMag = if (rawMag > 0.0001f) 1f / rawMag else 0f
            val outX = (dzX * invMag) * finalMag * mapping.optDouble("radius", defaultRadius.toDouble()).toFloat()
            val outY = (dzY * invMag) * finalMag * mapping.optDouble("radius", defaultRadius.toDouble()).toFloat()
            Pair(cX + outX, cY + outY)
        }

        if (!pointer.isActive) {
            pointer.isActive = true
            try { TouchInjectionPlugin.touchService?.touchDown(pointer.id, cX + ox, cY + oy) } catch (_: Exception) { pointer.isActive = false }
        }
        if (pointer.isActive) {
            try { TouchInjectionPlugin.touchService?.touchMove(pointer.id, tX + ox, tY + oy) } catch (_: Exception) {}
        }
    }

    // ==================== PHASE 2: IMPROVED TRIGGER ====================

    private fun handleTrigger(gamepadIndex: Int, triggerName: String, value: Float) {
        val wasActive = lastState[triggerName + gamepadIndex] ?: false
        val pressThreshold = 0.08f
        val releaseThreshold = 0.04f

        val isActive = if (wasActive) value > releaseThreshold else value > pressThreshold

        if (isActive != wasActive) {
            handleButton(gamepadIndex, triggerName, isActive)
        }
    }

    fun handleAxes(gamepadIndex: Int, lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        synchronized(syncLock) {
            if (gamepadIndex !in 0..3) return
            val ts = TouchInjectionPlugin.touchService ?: return

            val offset = (gamepadIndex % 4) * 16
            val lMap = findButtonMapping("L_STICK")
            val rMap = findButtonMapping("R_STICK")

            val lAlpha = 1f - (lMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 0.95f)
            val rAlpha = 1f - (rMap?.optDouble("smoothing", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 0.95f)

            processStick(lx, ly, lMap, smoothedAxes[gamepadIndex], 0, lAlpha, pointersById[offset] ?: pointers[0], 100f)
            processStick(rx, ry, rMap, smoothedAxes[gamepadIndex], 2, rAlpha, pointersById[offset + 1] ?: pointers[1], 150f)

            handleTrigger(gamepadIndex, "LT", l2)
            handleTrigger(gamepadIndex, "RT", r2)
        }
    }

    // ==================== PHASE 2: HOTPLUG ====================

    fun resetGamepad(gamepadIndex: Int) {
        if (gamepadIndex !in 0..3) return
        synchronized(syncLock) {
            val offset = gamepadIndex * 16
            for (i in 0 until 16) {
                val p = pointersById[offset + i]
                if (p != null && p.isActive) {
                    try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch (_: Exception) {}
                    p.isActive = false
                    p.virtualKey = null
                }
            }
            lastState.keys.removeAll { it.endsWith(gamepadIndex.toString()) }
            smoothedAxes[gamepadIndex].fill(0f)
            Log.i("GameMapper", "Gamepad $gamepadIndex reset (hotplug)")
        }
    }

    // ==================== HANDLE BUTTON (DIPERBAIKI) ====================

    fun handleButton(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        synchronized(syncLock) {
            if (gamepadIndex !in 0..3) return
            val offset = gamepadIndex * 16

            val ts = TouchInjectionPlugin.touchService
            if (ts == null) {
                Log.e("GameMapper", "handleButton: touchService is NULL")
                return
            }

            val wasDown = lastState[buttonName + gamepadIndex] ?: false
            lastState[buttonName + gamepadIndex] = isDown

            // Trigger-based mapping
            if (triggerMapCache.containsKey(buttonName)) {
                evaluateTriggerMappings(buttonName, gamepadIndex, isDown, offset)
                val legacy = findButtonMapping(buttonName)
                if (legacy?.optJSONObject("trigger") == null) {
                    lastState[buttonName + gamepadIndex] = wasDown
                } else {
                    return
                }
            }

            val mapping = findButtonMapping(buttonName)
            if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
                if (!isDown && wasDown) {
                    val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                        .find { it.isActive && it.virtualKey == buttonName }
                    if (p != null) {
                        p.isActive = false
                        p.virtualKey = null
                        try { ts.touchUp(p.id) } catch (_: Exception) {}
                    }
                }
                return
            }

            val antiBanEnabled = mapping.optBoolean("antiBanEnabled", false)
            val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
            val (ox, oy) = getAntiBanOffset(antiBanEnabled)

            if (isDown && !wasDown) {
                val tapDuration = mapping.optLong("tapDuration", 0L)
                if (tapDuration > 0) {
                    try {
                        ts.injectTap(x + ox, y + oy, tapDuration)
                    } catch (e: Exception) {
                        Log.w("GameMapper", "injectTap failed: ${e.message}")
                    }
                    return
                }

                val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                    .find { !it.isActive && it.type == "button" }

                if (p != null) {
                    p.isActive = true
                    p.virtualKey = buttonName
                    try {
                        ts.touchDown(p.id, x + ox, y + oy)
                    } catch (e: Exception) {
                        p.isActive = false
                        p.virtualKey = null
                        Log.w("GameMapper", "touchDown failed: ${e.message}")
                    }
                }
            } else if (!isDown && wasDown) {
                val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                    .find { it.isActive && it.virtualKey == buttonName }

                if (p != null) {
                    p.isActive = false
                    p.virtualKey = null
                    try { ts.touchUp(p.id) } catch (_: Exception) {}
                }
            }
        }
    }

    // ==================== FUNGSI LAINNYA ====================

    private fun evaluateTriggerMappings(buttonName: String, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        // ... (kode tetap sama seperti versi sebelumnya)
    }

    private fun dispatchInteraction(mapping: JSONObject, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        // ... (kode tetap sama)
    }

    private fun handleTurbo(mapping: JSONObject, nodeId: String, isDown: Boolean, gamepadIndex: Int) {
        // ... (kode tetap sama)
    }

    private fun handleToggle(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        // ... (kode tetap sama)
    }

    private fun handleCharge(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        // ... (kode tetap sama)
    }

    private fun handleGesture(mapping: JSONObject, offset: Int) {
        // ... (kode tetap sama)
    }

    private fun handleMacro(mapping: JSONObject, offset: Int) {
        // ... (kode tetap sama)
    }

    private fun handleHoldInteraction(mapping: JSONObject, isDown: Boolean, offset: Int) {
        // ... (kode tetap sama)
    }

    private fun applyCurve(x: Float, curveType: String?, curvePoints: org.json.JSONArray?): Float {
        if (curveType == null) return x
        val sign = kotlin.math.sign(x)
        val absX = kotlin.math.abs(x)
        return sign * when (curveType.lowercase()) {
            "exponential", "expo" -> {
                val k = 3.0
                ((Math.exp(k * absX.toDouble()) - 1) / (Math.exp(k) - 1)).toFloat()
            }
            "parabolic", "para" -> absX * absX
            "concave" -> kotlin.math.sqrt(absX)
            "custom" -> {
                if (curvePoints == null || curvePoints.length() < 2) return absX
                val clampedX = absX.coerceIn(0f, 1f)
                val n = curvePoints.length()
                val step = 1.0f / (n - 1)
                val idx = (clampedX / step).toInt().coerceIn(0, n - 2)
                val t = (clampedX - idx * step) / step
                val y1 = curvePoints.optDouble(idx, 0.0).toFloat()
                val y2 = curvePoints.optDouble(idx + 1, 1.0).toFloat()
                (y1 + t * (y2 - y1)).coerceIn(0f, 1f)
            }
            else -> absX
        }
    }
}
