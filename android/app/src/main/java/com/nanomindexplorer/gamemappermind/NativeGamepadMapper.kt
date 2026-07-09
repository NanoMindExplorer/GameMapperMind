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

private const val TAG = "GameMapper"

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
                if (key.isNotEmpty() && key != "null") {
                    buttonMapCache[key] = b
                }

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
            Log.d(TAG, "buildMapCache: Loaded ${buttonMapCache.size} button mappings")
        } catch (e: Exception) {
            Log.e(TAG, "buildMapCache failed", e)
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
            try { TouchInjectionPlugin.touchService?.touchDown(pointer.id, cX + ox, cY + oy) } catch (e: Exception) {
                pointer.isActive = false
                Log.w(TAG, "touchDown failed in processStick: ${e.message}")
            }
        }
        if (pointer.isActive) {
            try { TouchInjectionPlugin.touchService?.touchMove(pointer.id, tX + ox, tY + oy) } catch (e: Exception) {
                Log.w(TAG, "touchMove failed in processStick: ${e.message}")
            }
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
            Log.i(TAG, "Gamepad $gamepadIndex reset (hotplug)")
        }
    }

    // ==================== HANDLE BUTTON ====================

    fun handleButton(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        synchronized(syncLock) {
            if (gamepadIndex !in 0..3) return
            val offset = gamepadIndex * 16
            val ts = TouchInjectionPlugin.touchService ?: return

            val wasDown = lastState[buttonName + gamepadIndex] ?: false
            lastState[buttonName + gamepadIndex] = isDown

            if (triggerMapCache.containsKey(buttonName)) {
                evaluateTriggerMappings(buttonName, gamepadIndex, isDown, offset)
                val legacy = findButtonMapping(buttonName)
                if (legacy?.optJSONObject("trigger") == null) {
                    lastState[buttonName + gamepadIndex] = wasDown
                } else return
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
                    try { ts.injectTap(x + ox, y + oy, tapDuration) } catch (e: Exception) {
                        Log.w(TAG, "injectTap failed for $buttonName: ${e.message}")
                    }
                    return
                }

                val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                    .find { !it.isActive && it.type == "button" }

                if (p != null) {
                    p.isActive = true
                    p.virtualKey = buttonName
                    try { ts.touchDown(p.id, x + ox, y + oy) } catch (e: Exception) {
                        p.isActive = false
                        p.virtualKey = null
                        Log.w(TAG, "touchDown failed for $buttonName: ${e.message}")
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

    // ==================== DIAGNOSTIC ====================

    fun runDiagnosticTestTap(): String {
        val svc = TouchInjectionPlugin.touchService
        if (svc == null) {
            return JSONObject()
                .put("error", "Touch daemon not connected (touchService is null)")
                .toString()
        }

        val firstButton = buttonMapCache.values.firstOrNull()
        val (pctX, pctY) = if (firstButton != null) {
            Pair(firstButton.optDouble("x", 50.0), firstButton.optDouble("y", 50.0))
        } else {
            Pair(50.0, 50.0)
        }

        val (x, y) = getScreenCoords(pctX, pctY)
        return try {
            svc.testInjection(x, y)
        } catch (e: Exception) {
            JSONObject().put("error", "testInjection failed: ${e.message}").toString()
        }
    }

    // ==================== INTERACTION HANDLERS ====================

    private fun evaluateTriggerMappings(buttonName: String, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        val mappings = triggerMapCache[buttonName] ?: return
        for (mapping in mappings) {
            val nodeId = mapping.optString("id", "")
            if (nodeId.isEmpty()) continue

            val trigger = mapping.optJSONObject("trigger")
            val isChord = trigger != null && trigger.optString("type") == "chord"

            if (isDown) {
                if (isChord && !isChordActive(mapping, gamepadIndex)) continue
                dispatchInteraction(mapping, gamepadIndex, true, offset)
            } else {
                dispatchInteraction(mapping, gamepadIndex, false, offset)
            }
        }
    }

    private fun isChordActive(mapping: JSONObject, gamepadIndex: Int): Boolean {
        val trigger = mapping.optJSONObject("trigger") ?: return true
        val inputs = trigger.optJSONArray("inputs") ?: return true
        for (i in 0 until inputs.length()) {
            if (!(lastState[inputs.optString(i) + gamepadIndex] ?: false)) return false
        }
        return true
    }

    private fun dispatchInteraction(mapping: JSONObject, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        when (mapping.optString("interactionType", "hold")) {
            "tap" -> if (isDown) handleTap(mapping, offset)
            "turbo" -> handleTurbo(mapping, mapping.optString("id"), isDown, gamepadIndex)
            "toggle" -> handleToggle(mapping, mapping.optString("id"), isDown, offset)
            "charge" -> handleCharge(mapping, mapping.optString("id"), isDown, offset)
            else -> handleHoldInteraction(mapping, isDown, offset)
        }
    }

    private fun handleTap(mapping: JSONObject, offset: Int) {
        val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        val tapDuration = mapping.optLong("tapDuration", 60L)
        try {
            TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
        } catch (e: Exception) {
            Log.w(TAG, "handleTap failed: ${e.message}")
        }
    }

    private fun handleTurbo(mapping: JSONObject, nodeId: String, isDown: Boolean, gamepadIndex: Int) {
        if (isDown) {
            turboRunnables[nodeId]?.let { mainHandler.removeCallbacks(it) }
            val intervalMs = mapping.optLong("repeatIntervalMs", 50L)
            val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
            val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
            val tapDuration = mapping.optLong("tapDuration", 30L)

            val runnable = object : Runnable {
                override fun run() {
                    try {
                        TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                    } catch (e: Exception) {
                        Log.w(TAG, "turbo tap failed: ${e.message}")
                    }
                    mainHandler.postDelayed(this, intervalMs)
                }
            }
            turboRunnables[nodeId] = runnable
            runnable.run()
        } else {
            turboRunnables[nodeId]?.let { mainHandler.removeCallbacks(it) }
            turboRunnables.remove(nodeId)
        }
    }

    private fun handleToggle(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        if (!isDown) return
        val isToggled = toggleState[nodeId] ?: false
        val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))

        if (!isToggled) {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { !it.isActive && it.type == "button" }
            if (p != null) {
                p.isActive = true
                p.virtualKey = "toggle_$nodeId"
                try { TouchInjectionPlugin.touchService?.touchDown(p.id, x + ox, y + oy) } catch (_: Exception) {}
            }
            toggleState[nodeId] = true
        } else {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { it.isActive && it.virtualKey == "toggle_$nodeId" }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch (_: Exception) {}
            }
            toggleState[nodeId] = false
        }
    }

    private fun handleCharge(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        if (isDown) {
            chargeTimestamps[nodeId] = System.currentTimeMillis()
        } else {
            val pressTime = chargeTimestamps[nodeId] ?: return
            val heldMs = System.currentTimeMillis() - pressTime
            val threshold = mapping.optLong("chargeThresholdMs", 500L)
            chargeTimestamps.remove(nodeId)

            if (heldMs >= threshold) {
                val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
                val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
                val tapDuration = mapping.optLong("tapDuration", 60L)
                try {
                    TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                } catch (e: Exception) {
                    Log.w(TAG, "charge tap failed: ${e.message}")
                }
            }
        }
    }

    private fun handleHoldInteraction(mapping: JSONObject, isDown: Boolean, offset: Int) {
        val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        val nodeId = "hold_${mapping.optString("id", "")}"

        if (isDown) {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { !it.isActive && it.type == "button" }
            if (p != null) {
                p.isActive = true
                p.virtualKey = nodeId
                try { TouchInjectionPlugin.touchService?.touchDown(p.id, x + ox, y + oy) } catch (e: Exception) {
                    p.isActive = false
                }
            }
        } else {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { it.isActive && it.virtualKey == nodeId }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch (_: Exception) {}
            }
        }
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
            else -> absX
        }
    }
}
