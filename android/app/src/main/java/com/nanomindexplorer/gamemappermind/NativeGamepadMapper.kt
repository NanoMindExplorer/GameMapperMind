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

        // FIX (root cause of "RB tekan → analog berhenti", general delay, "analog nyangkut"):
        // every touchDown/touchMove/touchUp/injectTap call is a SYNCHRONOUS cross-process
        // Binder call into the Shizuku daemon. Previously these ran INLINE on the single
        // thread that also decides button/axis state (GamepadJniPlugin's injectionThread) —
        // so one slow injection (very commonly a button, since those go through more
        // interaction-type branching) blocked ALL subsequent processing on that thread,
        // including fresh stick-movement updates, until the Binder call returned. A held
        // button could stall stick input for its entire hold duration.
        // Fix: dispatch the actual AIDL call onto its own dedicated background thread,
        // decoupled from the decision-making thread, which now only does fast in-memory
        // work (pointer allocation, deadzone/smoothing math, lastState edge-detection) and
        // is never blocked waiting on IPC. A single background thread (not a pool)
        // preserves strict FIFO delivery order for touch events, matching prior behavior —
        // just off the critical path that also has to keep up with a moving stick.
        private val aidlThread = android.os.HandlerThread("TouchAidlDispatch").also { it.start() }
        private val aidlHandler = Handler(aidlThread.looper)

        fun dispatchTouchCall(block: () -> Unit) {
            aidlHandler.post(block)
        }

        fun resetAll() {
            synchronized(syncLock) {
                instance?.pointers?.forEach {
                    if (it.isActive) {
                        it.isActive = false
                        dispatchTouchCall {
                            try { TouchInjectionPlugin.touchService?.touchUp(it.id) } catch (e: Exception) { instance?.logInjectFailure("touchUp", it.id, e) }
                        }
                    }
                }
                instance?.lastState?.clear()
                instance?.smoothedAxes?.forEach { it.fill(0f) }
                instance?.buildMapCache()
            }
        }
    }

    // FIX: isActive/virtualKey are now written from BOTH the decision thread (allocation) and
    // the background aidlThread (failure rollback after a dispatched call fails) — @Volatile
    // for correct cross-thread visibility. No compound check-then-act race is introduced since
    // allocation decisions (the only place these are read-then-written together) stay
    // exclusively on the single decision thread; the background thread only ever does a plain
    // rollback write (isActive = false) on failure.
    class PointerState(val id: Int, @Volatile var isActive: Boolean, val type: String, @Volatile var virtualKey: String? = null)

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

    // Tracks whether the last touchMove for a pointer failed, so the hot path
    // (processStick) logs a failure once per streak instead of every frame.
    private val moveFailWarned = mutableMapOf<Int, Boolean>()

    private fun logInjectFailure(action: String, pointerId: Int, e: Exception) {
        Log.w(TAG, "Touch injection failed: $action pointer=$pointerId (${e.javaClass.simpleName}: ${e.message})")
    }

    var buttonMapCache = mutableMapOf<String, JSONObject>()
    private var triggerMapCache = mutableMapOf<String, MutableList<JSONObject>>()

    private val turboRunnables = mutableMapOf<String, Runnable>()
    private val toggleState = mutableMapOf<String, Boolean>()
    private val chargeTimestamps = mutableMapOf<String, Long>()
    private val activeMacros = mutableMapOf<String, Runnable>()

    // Macro Recording
    private var isRecordingMacro = false
    private var currentRecordingId: String? = null
    private val recordedMacros = mutableMapOf<String, MutableList<JSONObject>>()

    // Profile & Scene
    var currentProfileId: String = "default"
    var currentScene: String = "default"

    // Screen calibration cache — read once per buildMapCache() call, applied in
    // getScreenCoords(). Defaults to 0 (legacy full-screen behavior, no change for anyone
    // who hasn't calibrated).
    @Volatile private var screenInsetTop = 0.0
    @Volatile private var screenInsetBottom = 0.0
    @Volatile private var screenInsetLeft = 0.0
    @Volatile private var screenInsetRight = 0.0

    fun buildMapCache() {
        buttonMapCache.clear()
        triggerMapCache.clear()
        val jsonStr = GamepadListenerService.activeProfileJson ?: return
        if (jsonStr.isEmpty() || jsonStr == "{}") return

        try {
            val root = JSONObject(jsonStr)
            screenInsetTop = root.optDouble("screenInsetTop", 0.0).coerceIn(0.0, 45.0)
            screenInsetBottom = root.optDouble("screenInsetBottom", 0.0).coerceIn(0.0, 45.0)
            screenInsetLeft = root.optDouble("screenInsetLeft", 0.0).coerceIn(0.0, 45.0)
            screenInsetRight = root.optDouble("screenInsetRight", 0.0).coerceIn(0.0, 45.0)
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

    // FIX: previously mapped percentages directly onto the full physical screen bounds with
    // no way to compensate for a game that doesn't render truly edge-to-edge (visible status
    // bar, letterboxing, aspect-ratio mismatch) — a button placed at "90% from top" in the
    // editor could land at a visibly different relative position inside the actual game.
    // screenInsetTop/Bottom/Left/Right (calibrated per-profile, see GameSelector.tsx) now
    // remap the 0-100% editor space onto the actual game play-area rectangle within the
    // screen. All insets default to 0, so uncalibrated profiles behave exactly as before.
    private fun getScreenCoords(pctX: Double, pctY: Double): Pair<Float, Float> {
        return try {
            val bounds = windowManager.currentWindowMetrics.bounds
            val usableWidthFrac = (100.0 - screenInsetLeft - screenInsetRight) / 100.0
            val usableHeightFrac = (100.0 - screenInsetTop - screenInsetBottom) / 100.0
            val effectivePctX = screenInsetLeft + (pctX / 100.0) * usableWidthFrac * 100.0
            val effectivePctY = screenInsetTop + (pctY / 100.0) * usableHeightFrac * 100.0
            Pair(
                ((effectivePctX / 100.0) * bounds.width()).toFloat(),
                ((effectivePctY / 100.0) * bounds.height()).toFloat()
            )
        } catch (e: Exception) {
            Pair(1080f, 1920f)
        }
    }

    // FIX: previously referenced by GamepadListenerService's notification "Test Tap" action
    // and by TouchDaemonService.testInjection(), but never actually defined anywhere — a
    // straight-up compile error once a real Kotlin build was run (this sandbox can only
    // typecheck the TS side, not compile Kotlin, so it slipped through prior patches).
    // Runs a real diagnostic tap at screen center via the AIDL binder and returns the
    // daemon's structured JSON report (success / active injection path / error).
    fun runDiagnosticTestTap(): String {
        return try {
            val ts = TouchInjectionPlugin.touchService
                ?: return "{\"error\":\"Shizuku/TouchService not connected\"}"
            val (x, y) = getScreenCoords(50.0, 50.0)
            ts.testInjection(x, y)
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
    }

    private fun findButtonMapping(mappedKey: String): JSONObject? = buttonMapCache[mappedKey]

    private fun getAntiBanOffset(enabled: Boolean): Pair<Float, Float> {
        if (!enabled) return Pair(0f, 0f)
        val radius = Random.nextFloat() * 8f
        val angle = Random.nextFloat() * (2 * Math.PI).toFloat()
        return Pair((radius * cos(angle.toDouble())).toFloat(), (radius * sin(angle.toDouble())).toFloat())
    }

    // ==================== RADIAL DEADZONE ====================

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
                val pid = pointer.id
                dispatchTouchCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
            }
            return
        }

        val deadzone = mapping.optDouble("deadzone", 0.12).toFloat()

        // FIX (root cause of "analog nyangkut ke bawah"): deadzone check previously ran on
        // the SMOOTHED magnitude. When the stick was released (raw → 0), the smoothed value
        // decayed exponentially over several frames before dropping below deadzone — during
        // that decay the touch position kept drifting toward center (visually "stuck moving
        // downward" if the stick had been pushed up). Checking deadzone on the RAW input
        // makes release immediate: the moment the physical stick returns inside the deadzone
        // circle, touchUp fires and the smoothing buffer is reset to zero. Smoothing is now
        // only applied to non-deadzone input, so it never creates release lag.
        val rawInputMag = sqrt(rawX * rawX + rawY * rawY)
        if (rawInputMag <= deadzone) {
            if (pointer.isActive) {
                val pid = pointer.id
                dispatchTouchCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
                pointer.isActive = false
            }
            smoothBuffer[smoothOffset] = 0f
            smoothBuffer[smoothOffset + 1] = 0f
            return
        }

        // Apply smoothing only for non-deadzone input — keeps movement smooth without
        // delaying release.
        smoothBuffer[smoothOffset] = alpha * rawX + (1 - alpha) * smoothBuffer[smoothOffset]
        smoothBuffer[smoothOffset + 1] = alpha * rawY + (1 - alpha) * smoothBuffer[smoothOffset + 1]

        val sx = smoothBuffer[smoothOffset]
        val sy = smoothBuffer[smoothOffset + 1]
        val rawMag = sqrt(sx * sx + sy * sy)

        val (dzX, dzY) = applyRadialDeadzone(sx, sy, deadzone)
        val rescaledMag = sqrt(dzX * dzX + dzY * dzY).coerceIn(0f, 1f)

        val curve = mapping.optString("sensitivityCurve", "linear")
        val curvedMag = applyCurve(rescaledMag, curve, mapping.optJSONArray("curvePoints"))

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
            val pid = pointer.id
            val downX = cX + ox; val downY = cY + oy
            dispatchTouchCall {
                try {
                    TouchInjectionPlugin.touchService?.touchDown(pid, downX, downY)
                } catch (e: Exception) {
                    pointer.isActive = false
                    logInjectFailure("touchDown", pid, e)
                }
            }
        }
        if (pointer.isActive) {
            val pid = pointer.id
            val moveX = tX + ox; val moveY = tY + oy
            dispatchTouchCall {
                try {
                    // touchMove returns false (no exception) when TouchDaemonService is degraded
                    // to shell-fallback Path C, which only supports single-pointer tap and silently
                    // rejects MOVE. A false return is just as much a dropped frame as a thrown
                    // exception, so it must hit the same failure-tracking path below.
                    val moved = TouchInjectionPlugin.touchService?.touchMove(pid, moveX, moveY) ?: false
                    if (moved) {
                        if (moveFailWarned[pid] == true) moveFailWarned[pid] = false
                    } else if (moveFailWarned[pid] != true) {
                        moveFailWarned[pid] = true
                        Log.w(TAG, "Touch injection returned false: touchMove pointer=$pid (stick drag likely stuck on shell-fallback Path C)")
                    }
                } catch (e: Exception) {
                    // Hot path (fires every axis update while stick deflected) - log once per
                    // failure streak instead of every frame, or logcat gets flooded.
                    if (moveFailWarned[pid] != true) {
                        moveFailWarned[pid] = true
                        logInjectFailure("touchMove", pid, e)
                    }
                }
            }
        }
    }

    // ==================== IMPROVED TRIGGER ====================

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
            if (TouchInjectionPlugin.touchService == null) return

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

    // ==================== HOTPLUG ====================

    fun resetGamepad(gamepadIndex: Int) {
        if (gamepadIndex !in 0..3) return
        synchronized(syncLock) {
            val offset = gamepadIndex * 16
            for (i in 0 until 16) {
                val p = pointersById[offset + i]
                if (p != null && p.isActive) {
                    val pid = p.id
                    dispatchTouchCall {
                        try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                    }
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
            // Guard: bail out early if the Shizuku touch service isn't bound. All actual
            // touch calls below go through dispatchTouchCall, which reads
            // TouchInjectionPlugin.touchService directly (not this local), so we only need
            // the null check here — no need to keep a reference.
            if (TouchInjectionPlugin.touchService == null) return

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
                        val pid = p.id
                        // FIX: async dispatch — previously synchronous ts.touchUp blocked
                        // the decision thread (same thread that processes axis events),
                        // causing "analog berhenti saat dikombo dengan tombol lain".
                        dispatchTouchCall {
                            try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                        }
                    }
                }
                return
            }

            // FIX (root cause of "tombol A, LT, RT tidak menginjeksi sentuhan sama sekali"):
            // The old code only honored `interactionType` (tap/turbo/toggle/charge/macro)
            // for buttons that had a `trigger` object — buttons without a trigger always
            // fell through to a hardcoded "hold" path (or, if tapDuration>0, a synchronous
            // `ts.injectTap()` that internally used pointer ID 0 — the SAME pointer used by
            // L_STICK — hijacking the analog stick's pointer and breaking both the tap AND
            // the stick in one shot).
            //
            // The UI lets users set interactionType on ANY button without requiring a
            // trigger, so the native side must honor it universally. Now every button
            // dispatches through `dispatchInteraction`, which routes to handleTap /
            // handleTurbo / handleToggle / handleCharge / handleMacro / handleHoldInteraction
            // based on `interactionType`. All of those handlers already use `dispatchTouchCall`
            // for async injection, so button presses no longer block the decision thread
            // (fixing "analog berhenti saat dikombo dengan tombol lain").
            dispatchInteraction(mapping, gamepadIndex, isDown, offset)
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
            "macro" -> handleMacro(mapping, offset)
            else -> handleHoldInteraction(mapping, isDown, offset)
        }
    }

    private fun handleTap(mapping: JSONObject, offset: Int) {
        val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
        val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))
        val tapDuration = mapping.optLong("tapDuration", 60L)
        dispatchTouchCall {
            try {
                TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
            } catch (e: Exception) {
                Log.w(TAG, "handleTap failed: ${e.message}")
            }
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
                    // FIX: previously called injectTap() directly on mainHandler (the UI
                    // thread) — since injectTap blocks for its full duration on the daemon
                    // side per AIDL call, this meant every turbo repeat briefly blocked the
                    // UI thread too, on top of the same "blocks other input" problem shared
                    // with every other direct touchService call in this file.
                    dispatchTouchCall {
                        try {
                            TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                        } catch (e: Exception) {
                            Log.w(TAG, "turbo failed: ${e.message}")
                        }
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
                val pid = p.id
                dispatchTouchCall {
                    try { TouchInjectionPlugin.touchService?.touchDown(pid, x + ox, y + oy) } catch (e: Exception) { logInjectFailure("touchDown", pid, e) }
                }
            }
            toggleState[nodeId] = true
        } else {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { it.isActive && it.virtualKey == "toggle_$nodeId" }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                val pid = p.id
                dispatchTouchCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
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
                dispatchTouchCall {
                    try {
                        TouchInjectionPlugin.touchService?.injectTap(x + ox, y + oy, tapDuration)
                    } catch (e: Exception) {
                        Log.w(TAG, "charge tap failed: ${e.message}")
                    }
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
                val pid = p.id
                // FIX: this is the single most impactful site for "RB tekan → analog kiri/
                // kanan berhenti" — RB (and most ordinary buttons) use the default "hold"
                // interaction type, so THIS touchDown was almost certainly the exact call
                // blocking the shared decision thread while a stick was actively moving.
                dispatchTouchCall {
                    try { TouchInjectionPlugin.touchService?.touchDown(pid, x + ox, y + oy) } catch (e: Exception) {
                        p.isActive = false
                        logInjectFailure("touchDown", pid, e)
                    }
                }
            }
        } else {
            val p = (offset..offset + 15).mapNotNull { pointersById[it] }
                .find { it.isActive && it.virtualKey == nodeId }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                val pid = p.id
                dispatchTouchCall {
                    try { TouchInjectionPlugin.touchService?.touchUp(pid) } catch (e: Exception) { logInjectFailure("touchUp", pid, e) }
                }
            }
        }
    }

    // ==================== MACRO SYSTEM (Phase 4) ====================

    private fun handleMacro(mapping: JSONObject, offset: Int) {
        val macroId = mapping.optString("id", "")
        if (macroId.isEmpty()) return

        activeMacros[macroId]?.let {
            mainHandler.removeCallbacks(it)
            activeMacros.remove(macroId)
            return
        }

        val steps = mapping.optJSONArray("macroSteps") ?: return
        if (steps.length() == 0) return

        var currentStep = 0

        val macroRunnable = object : Runnable {
            override fun run() {
                if (currentStep >= steps.length()) {
                    activeMacros.remove(macroId)
                    return
                }

                val step = steps.optJSONObject(currentStep) ?: return
                val action = step.optString("action", "tap")
                val x = step.optDouble("x", 50.0)
                val y = step.optDouble("y", 50.0)
                val delay = step.optLong("delayMs", 100L)
                val duration = step.optLong("durationMs", 60L)

                val (screenX, screenY) = getScreenCoords(x, y)
                val (ox, oy) = getAntiBanOffset(mapping.optBoolean("antiBanEnabled", false))

                when (action.lowercase()) {
                    "tap" -> {
                        // FIX: same as turbo — this runnable is scheduled on mainHandler (the
                        // UI thread), so calling injectTap() directly here blocked the UI
                        // thread for the duration of each macro step's AIDL round-trip.
                        dispatchTouchCall {
                            try {
                                TouchInjectionPlugin.touchService?.injectTap(screenX + ox, screenY + oy, duration)
                            } catch (e: Exception) {
                                Log.w(TAG, "Macro tap failed: ${e.message}")
                            }
                        }
                    }
                }

                currentStep++
                mainHandler.postDelayed(this, delay)
            }
        }

        activeMacros[macroId] = macroRunnable
        macroRunnable.run()
    }

    // ==================== MACRO RECORDING (for UI) ====================

    fun startMacroRecording(macroId: String) {
        isRecordingMacro = true
        currentRecordingId = macroId
        recordedMacros[macroId] = mutableListOf()
        Log.i(TAG, "Started recording macro: $macroId")
    }

    fun stopMacroRecording() {
        isRecordingMacro = false
        currentRecordingId = null
        Log.i(TAG, "Stopped macro recording")
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
