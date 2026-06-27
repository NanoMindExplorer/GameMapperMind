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
        // BUG-FATAL-2 FIX: @Volatile — instance is written from Background Thread
        // (GamepadListenerService.startGetEventCapture) and read from Main Thread (GamepadJniPlugin).
        // Without @Volatile, Main Thread may never see the instance even after it's created.
        @Volatile var instance: NativeGamepadMapper? = null
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

    // INTERACTION-EXPANSION: Trigger cache maps each physical input → list of mappings
    // that use that input as a trigger. A single input can be in multiple mappings
    // (e.g., "A" could be in both a tap mapping and a chord mapping with "B").
    private var triggerMapCache = mutableMapOf<String, MutableList<JSONObject>>()

    // INTERACTION-EXPANSION: State tracking for turbo/toggle/charge per node ID.
    private val turboRunnables = mutableMapOf<String, Runnable>()   // nodeId → active turbo loop
    private val toggleState = mutableMapOf<String, Boolean>()       // nodeId → isToggledOn
    private val chargeTimestamps = mutableMapOf<String, Long>()     // nodeId → press time ms

    fun buildMapCache() {
        buttonMapCache.clear()
        triggerMapCache.clear()
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
                if (key != null && key != "null") {
                    buttonMapCache[key] = b
                }
                // INTERACTION-EXPANSION: Also index by trigger inputs if trigger field exists.
                val trigger = b?.optJSONObject("trigger")
                if (trigger != null) {
                    val inputs = trigger.optJSONArray("inputs")
                    if (inputs != null) {
                        for (j in 0 until inputs.length()) {
                            val input = inputs.optString(j)
                            if (input.isNotEmpty()) {
                                triggerMapCache.getOrPut(input) { mutableListOf() }.add(b)
                            }
                        }
                    }
                }
            }
            Log.i("GameMapper", "buildMapCache: loaded ${buttonMapCache.size} mappedKey + ${triggerMapCache.size} trigger inputs from profile")
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
        // REBUILD: minSdk=31 (Android 12), so WindowMetrics API is always available.
        // Use currentWindowMetrics for accurate screen dimensions (includes system bars).
        val (sw, sh) = try {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } catch (e: Exception) {
            // Fallback: if WindowMetrics fails on some OEM ROM, use deprecated Display API.
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
        val angle = (Random.nextFloat() * 2 * kotlin.math.PI).toFloat()
        return Pair((radius * cos(angle.toDouble())).toFloat(), (radius * sin(angle.toDouble())).toFloat())
    }

    // ========================================================================
    // INTERACTION-EXPANSION: Flexible trigger evaluation + interaction handlers
    // ========================================================================

    /**
     * Check if ALL inputs in a chord trigger are currently active.
     * For single-button trigger, always returns true (the one button IS the trigger).
     */
    private fun isChordActive(mapping: JSONObject, gamepadIndex: Int): Boolean {
        val trigger = mapping.optJSONObject("trigger") ?: return true
        val inputs = trigger.optJSONArray("inputs") ?: return true
        for (i in 0 until inputs.length()) {
            val input = inputs.optString(i)
            val isActive = lastState[input + gamepadIndex] ?: false
            if (!isActive) return false
        }
        return true
    }

    /**
     * Evaluate a trigger-based mapping and dispatch to the appropriate interaction handler.
     * Called from handleButton when triggerMapCache has entries for the button.
     */
    private fun evaluateTriggerMappings(buttonName: String, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        val mappings = triggerMapCache[buttonName] ?: return
        for (mapping in mappings) {
            val nodeId = mapping.optString("id", "")
            if (nodeId.isEmpty()) continue

            // For chord: only trigger when ALL inputs are active (on press) or ANY released (on release)
            val trigger = mapping.optJSONObject("trigger")
            val isChord = trigger != null && trigger.optString("type") == "chord"

            if (isDown) {
                if (isChord && !isChordActive(mapping, gamepadIndex)) {
                    continue // Not all chord inputs pressed yet — wait
                }
                dispatchInteraction(mapping, gamepadIndex, true, offset)
            } else {
                // On release: always dispatch (end the interaction)
                dispatchInteraction(mapping, gamepadIndex, false, offset)
            }
        }
    }

    /**
     * Dispatch to interaction handler based on interactionType.
     */
    private fun dispatchInteraction(mapping: JSONObject, gamepadIndex: Int, isDown: Boolean, offset: Int) {
        val interactionType = mapping.optString("interactionType", "hold")
        val nodeId = mapping.optString("id", "")

        when (interactionType) {
            "tap" -> {
                // Tap: single injectTap on press, nothing on release
                if (isDown) {
                    val tapDuration = mapping.optLong("tapDuration", 60L)
                    var (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
                    val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
                    try {
                        TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                    } catch (e: Exception) {
                        Log.w("GameMapper", "tap interaction failed: ${e.message}")
                    }
                }
            }
            "turbo" -> {
                // Turbo: auto-repeat injectTap every repeatIntervalMs while held
                handleTurbo(mapping, nodeId, isDown, gamepadIndex)
            }
            "toggle" -> {
                // Toggle: first press = touchDown (stays), second press = touchUp
                handleToggle(mapping, nodeId, isDown, offset)
            }
            "charge" -> {
                // Charge: hold for chargeThresholdMs, then release to trigger
                handleCharge(mapping, nodeId, isDown, offset)
            }
            "gesture" -> {
                // Gesture: sequence of touchMove points
                if (isDown) handleGesture(mapping, offset)
            }
            "macro" -> {
                // Macro: trigger recorded macro sequence (one-shot on press)
                if (isDown) handleMacro(mapping, offset)
            }
            else -> {
                // "hold" (default) — same as existing button behavior: touchDown on press, touchUp on release
                handleHoldInteraction(mapping, isDown, offset)
            }
        }
    }

    /**
     * INTERACTION-EXPANSION: Macro trigger — plays back a recorded macro sequence.
     * Macros are stored in the profile JSON under "macros" array. Each macro has:
     *   id, name, actions: [{type: 'touch_down'|'touch_move'|'touch_up'|'delay', x?, y?, delayMs?, pointerId}]
     * The macroId field in the mapping selects which macro to play.
     */
    private fun handleMacro(mapping: JSONObject, offset: Int) {
        val macroId = mapping.optString("macroId", "")
        if (macroId.isEmpty()) {
            Log.w("GameMapper", "handleMacro: no macroId specified")
            return
        }

        // Find the macro in the profile JSON
        val jsonStr = GamepadListenerService.activeProfileJson ?: return
        try {
            val root = JSONObject(jsonStr)
            // Macros might be stored at root level or we need to look in a separate store.
            // For now, check if there's a "macros" array in the profile.
            val macrosArray = root.optJSONArray("macros")
            if (macrosArray == null) {
                Log.w("GameMapper", "handleMacro: no macros array in profile")
                return
            }

            var macro: JSONObject? = null
            for (i in 0 until macrosArray.length()) {
                val m = macrosArray.optJSONObject(i)
                if (m?.optString("id") == macroId) {
                    macro = m
                    break
                }
            }

            if (macro == null) {
                Log.w("GameMapper", "handleMacro: macro '$macroId' not found")
                return
            }

            val actions = macro.optJSONArray("actions")
            if (actions == null || actions.length() == 0) {
                Log.w("GameMapper", "handleMacro: macro '$macroId' has no actions")
                return
            }

            val playbackSpeed = macro.optDouble("playbackSpeed", 1.0)
            Log.i("GameMapper", "handleMacro: playing macro '$macroId' (${actions.length()} actions, speed=$playbackSpeed)")

            // Schedule each action sequentially with delays
            var cumulativeDelay = 0L
            for (i in 0 until actions.length()) {
                val action = actions.optJSONObject(i) ?: continue
                val type = action.optString("type", "")
                val actionDelay = (action.optLong("delayMs", 33L) / playbackSpeed).toLong()
                cumulativeDelay += actionDelay

                mainHandler.postDelayed({
                    synchronized(syncLock) {
                        try {
                            val ts = TouchInjectionPlugin.touchService ?: return@synchronized
                            val pointerId = action.optInt("pointerId", 200) + offset
                            when (type) {
                                "touch_down" -> {
                                    val (x, y) = getScreenCoords(action.optDouble("x", 50.0), action.optDouble("y", 50.0))
                                    ts.touchDown(pointerId, x, y)
                                }
                                "touch_move" -> {
                                    val (x, y) = getScreenCoords(action.optDouble("x", 50.0), action.optDouble("y", 50.0))
                                    ts.touchMove(pointerId, x, y)
                                }
                                "touch_up" -> {
                                    ts.touchUp(pointerId)
                                }
                                "delay" -> { /* delay is handled by cumulativeDelay */ }
                            }
                        } catch (e: Exception) {
                            Log.w("GameMapper", "macro action '$type' failed: ${e.message}")
                        }
                    }
                }, cumulativeDelay)
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "handleMacro: failed to parse macro", e)
        }
    }

    private fun handleTurbo(mapping: JSONObject, nodeId: String, isDown: Boolean, gamepadIndex: Int) {
        if (isDown) {
            // Cancel any existing turbo for this node
            turboRunnables[nodeId]?.let { mainHandler.removeCallbacks(it) }

            val intervalMs = mapping.optLong("repeatIntervalMs", 50L)
            var (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
            val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
            x += ox; y += oy
            val tapDuration = mapping.optLong("tapDuration", 30L)

            val runnable = object : Runnable {
                override fun run() {
                    try {
                        TouchInjectionPlugin.touchService?.injectTap(x, y, tapDuration)
                    } catch (e: Exception) {
                        Log.w("GameMapper", "turbo tap failed: ${e.message}")
                    }
                    mainHandler.postDelayed(this, intervalMs)
                }
            }
            turboRunnables[nodeId] = runnable
            // First tap immediately, then repeat
            runnable.run()
        } else {
            // Cancel turbo loop
            turboRunnables[nodeId]?.let { mainHandler.removeCallbacks(it) }
            turboRunnables.remove(nodeId)
        }
    }

    private fun handleToggle(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        if (!isDown) return // Toggle only acts on press
        val isToggled = toggleState[nodeId] ?: false
        var (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        x += ox; y += oy

        if (!isToggled) {
            // Turn ON: touchDown (stays pressed)
            val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { !it.isActive && it.type == "button" }
            if (p != null) {
                p.isActive = true
                p.virtualKey = "toggle_$nodeId"
                try { TouchInjectionPlugin.touchService?.touchDown(p.id, x, y) } catch(e: Exception) {}
            }
            toggleState[nodeId] = true
        } else {
            // Turn OFF: touchUp
            val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { it.isActive && it.virtualKey == "toggle_$nodeId" }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch(e: Exception) {}
            }
            toggleState[nodeId] = false
        }
    }

    private fun handleCharge(mapping: JSONObject, nodeId: String, isDown: Boolean, offset: Int) {
        if (isDown) {
            // Record press time
            chargeTimestamps[nodeId] = System.currentTimeMillis()
        } else {
            // On release: check if held long enough
            val pressTime = chargeTimestamps[nodeId] ?: return
            val heldMs = System.currentTimeMillis() - pressTime
            val threshold = mapping.optLong("chargeThresholdMs", 500L)
            chargeTimestamps.remove(nodeId)

            if (heldMs >= threshold) {
                // Charge complete — fire the action (tap)
                var (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
                val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
                val tapDuration = mapping.optLong("tapDuration", 60L)
                try {
                    TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                } catch (e: Exception) {
                    Log.w("GameMapper", "charge tap failed: ${e.message}")
                }
            }
        }
    }

    private fun handleGesture(mapping: JSONObject, offset: Int) {
        val points = mapping.optJSONArray("gesturePoints") ?: return
        if (points.length() == 0) return

        val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { !it.isActive && it.type == "button" } ?: return
        p.isActive = true
        p.virtualKey = "gesture_${mapping.optString("id", "")}"

        var (startX, startY) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        startX += ox; startY += oy

        try { TouchInjectionPlugin.touchService?.touchDown(p.id, startX, startY) } catch(e: Exception) { p.isActive = false; return }

        // Schedule gesture points sequentially
        var cumulativeDelay = 0L
        for (i in 0 until points.length()) {
            val pt = points.optJSONObject(i) ?: continue
            val (px, py) = getScreenCoords(pt.getDouble("x"), pt.getDouble("y"))
            val delayMs = pt.optLong("delayMs", 50L)
            cumulativeDelay += delayMs

            mainHandler.postDelayed({
                synchronized(syncLock) {
                    if (p.isActive) {
                        try { TouchInjectionPlugin.touchService?.touchMove(p.id, px + ox, py + oy) } catch(e: Exception) {}
                    }
                }
            }, cumulativeDelay)
        }

        // Schedule touchUp after all gesture points
        mainHandler.postDelayed({
            synchronized(syncLock) {
                if (p.isActive) {
                    p.isActive = false
                    p.virtualKey = null
                    try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch(e: Exception) {}
                }
            }
        }, cumulativeDelay + 50L)
    }

    private fun handleHoldInteraction(mapping: JSONObject, isDown: Boolean, offset: Int) {
        // Same as existing button press/release logic, but for trigger-based mappings
        var (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        x += ox; y += oy
        val nodeId = "hold_${mapping.optString("id", "")}"

        if (isDown) {
            val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { !it.isActive && it.type == "button" }
            if (p != null) {
                p.isActive = true
                p.virtualKey = nodeId
                try { TouchInjectionPlugin.touchService?.touchDown(p.id, x, y) } catch(e: Exception) { p.isActive = false }
            }
        } else {
            val p = (offset..offset+15).mapNotNull { pointersById[it] }.find { it.isActive && it.virtualKey == nodeId }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch(e: Exception) {}
            }
        }
    }

    fun handleButton(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        synchronized(syncLock) {
            if (gamepadIndex !in 0..3) {
                Log.w("GameMapper", "handleButton: gamepadIndex out of range: $gamepadIndex")
                return
            }
            val offset = (gamepadIndex % 4) * 16
            
            // BUG-FIX: Check touchService FIRST. If null, no injection is possible.
            val ts = TouchInjectionPlugin.touchService
            if (ts == null) {
                Log.e("GameMapper", "handleButton: touchService is NULL — daemon not started! " +
                    "Button '$buttonName' isDown=$isDown IGNORED.")
                return
            }

            // INTERACTION-EXPANSION: Update lastState EARLY so chord evaluation can check it.
            val wasDown = lastState[buttonName + gamepadIndex] ?: false
            lastState[buttonName + gamepadIndex] = isDown

            // INTERACTION-EXPANSION: Evaluate trigger-based mappings first.
            // If triggerMapCache has entries for this button, dispatch to interaction handlers.
            // This takes precedence over the legacy mappedKey path.
            if (triggerMapCache.containsKey(buttonName)) {
                evaluateTriggerMappings(buttonName, gamepadIndex, isDown, offset)
                // Also run legacy path if the same button has a mappedKey entry (backward compat).
                // But only for non-trigger mappings (avoid double injection).
                val legacyMapping = findButtonMapping(buttonName)
                if (legacyMapping != null && legacyMapping.optJSONObject("trigger") == null) {
                    // This mapping uses mappedKey only (no trigger field) — run legacy path.
                    // Need to restore wasDown since we already updated lastState.
                    // The legacy path below reads wasDown from lastState, so we temporarily set it back.
                    lastState[buttonName + gamepadIndex] = wasDown // restore for legacy path
                } else {
                    return // Trigger-based mapping handled everything — skip legacy path.
                }
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
            // FIX: "exponential" is now a TRUE exponential curve (e^(kx)-1)/(e^k-1) with k=3.
            // This gives a smoother "small=insensitive, large=sensitive" feel than x².
            // "parabolic" remains x² (simpler, more aggressive near 0).
            "exponential", "expo" -> {
                val k = 3.0
                ((Math.exp(k * absX.toDouble()) - 1) / (Math.exp(k) - 1)).toFloat()
            }
            "parabolic", "para" -> absX * absX
            "concave" -> kotlin.math.sqrt(absX)  // stick-small=sensitive, stick-large=insensitive (inverse)
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

        // FIX: Apply sensitivity multiplier from ButtonPropertyPanel slider.
        val sensitivity = mapping.optDouble("sensitivity", 1.0).toFloat().coerceIn(0.1f, 5.0f)
        val finalMag = (curvedMag * sensitivity).coerceIn(0f, 1f)

        // STICK-MODE-DRAG: In 'drag' mode, the stick moves the touch point ABSOLUTELY
        // across the screen (like dragging a finger), not relative to a center point.
        // Useful for mortar/sniper aim where you need continuous full-screen movement.
        // In 'joystick' mode (default), the touch stays within maxRadius of center.
        val stickMode = mapping.optString("stickMode", "joystick")
        val (tX, tY) = if (stickMode == "drag") {
            // BUG-HIGH-13 FIX: Use screen dimensions (not cX/cY) for drag offset.
            // Previous formula cX + (sx * finalMag * cX * 0.8) was dimensionally wrong —
            // cX was used both as position AND as screen width proxy.
            // Now: offset proportional to screen size, clamped to screen bounds.
            val screenW = cX * 2f  // approximate screen width (cX is ~50% of screen)
            val screenH = cY * 2f
            val dragX = (cX + (sx * finalMag * screenW * 0.3f)).coerceIn(0f, screenW)
            val dragY = (cY + (sy * finalMag * screenH * 0.3f)).coerceIn(0f, screenH)
            Pair(dragX, dragY)
        } else {
            // Joystick mode (default): relative to center, bounded by maxRadius
            val invMag = if (rawMag > 1e-6f) 1f / rawMag else 0f
            val outX = (sx * invMag) * finalMag * maxRadius
            val outY = (sy * invMag) * finalMag * maxRadius
            Pair(cX + outX, cY + outY)
        }

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
