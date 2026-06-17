package com.nanomindexplorer.gamemappermind.security

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.model.GameProfile
import com.nanomindexplorer.gamemappermind.model.ProfileValidator
import org.json.JSONObject

/**
 * FASE 4.2 — Reference implementation of a hardened Capacitor plugin.
 *
 * Path di repo (replace the existing plugin):
 *   android/app/src/main/java/com.nanomindexplorer.gamemappermind/plugin/GameMapperPlugin.kt
 *
 * This file demonstrates the FASE 4.2 pattern: every @PluginMethod is
 * wrapped in NativeCrashGuard.guard() and routes through input sanitization
 * (FASE 4.4 — InputSanitizer).
 *
 * The actual GameMapperPlugin in your repo may have more methods; the
 * pattern is the same — wrap each in guard() and sanitize every input.
 *
 * Responsibilities of this file:
 *   1. Wrap every @PluginMethod in NativeCrashGuard.guard().
 *   2. Use InputSanitizer (FASE 4.4) for every value pulled from PluginCall.
 *   3. Periodically drain PendingErrorBus and forward to notifyListeners().
 *   4. Expose a "getRecentErrors" method for JS to poll if events were missed.
 */

class GameMapperPluginReference : Plugin() {

    companion object {
        private const val TAG = "GameMapperPlugin"
        private const val ERROR_POLL_INTERVAL_MS = 1000L
        private const val EVENT_APP_ERROR = "app:error"
    }

    // ───── Pipeline handles (set during load()) ─────
    private var touchInjector: TouchInjector? = null
    private var pipelineWorker: Any? = null  // InputPipelineWorker
    private var currentProfile: GameProfile? = null

    // ───── Error-bus poller ─────
    private val mainHandler = Handler(Looper.getMainLooper())
    private val errorPollRunnable = object : Runnable {
        override fun run() {
            drainErrorBus()
            mainHandler.postDelayed(this, ERROR_POLL_INTERVAL_MS)
        }
    }

    // ───── Lifecycle ─────

    override fun load() {
        super.load()
        // Start polling the error bus — emits any crashes that happened
        // on background threads since the last tick.
        mainHandler.postDelayed(errorPollRunnable, ERROR_POLL_INTERVAL_MS)
        Log.i(TAG, "GameMapperPlugin loaded; error-poller started")
    }

    override fun handleOnDestroy() {
        mainHandler.removeCallbacks(errorPollRunnable)
        super.handleOnDestroy()
    }

    // ───── Plugin methods (every one wrapped in guard) ─────

    @PluginMethod
    fun setProfile(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "setProfile", call) {
            val jsonStr = InputSanitizer.requireJsonString(call, "profile")
            when (val result = ProfileValidator.parseAndValidate(jsonStr)) {
                is ProfileValidator.ValidationResult.Ok -> {
                    currentProfile = result.profile
                    pushProfileToPipeline(result.profile)
                    val ret = JSObject()
                    ret.put("ok", true)
                    ret.put("profileId", result.profile.profileId)
                    call.resolve(ret)
                }
                is ProfileValidator.ValidationResult.Err -> {
                    val errJson = JSObject()
                    errJson.put("code", NativeCrashGuard.ErrorCode.INVALID_ARGUMENT)
                    errJson.put("errors", result.errors.joinToString("; "))
                    call.reject("Invalid profile", NativeCrashGuard.ErrorCode.INVALID_ARGUMENT, errJson)
                }
            }
        }
    }

    @PluginMethod
    fun clearProfile(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "clearProfile", call) {
            currentProfile = null
            pushProfileToPipeline(null)
            call.resolve()
        }
    }

    @PluginMethod
    fun tap(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "tap", call) {
            val injector = touchInjector ?: throw IllegalStateException("TouchInjector not initialized")
            val slot = InputSanitizer.requirePointerSlot(call, "slot")
            val x = InputSanitizer.requirePixelCoord(call, "x", injector.screenWidthPx)
            val y = InputSanitizer.requirePixelCoord(call, "y", injector.screenHeightPx)
            injector.tap(slot, x, y)
            call.resolve()
        }
    }

    @PluginMethod
    fun swipe(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "swipe", call) {
            val injector = touchInjector ?: throw IllegalStateException("TouchInjector not initialized")
            val slot = InputSanitizer.requirePointerSlot(call, "slot")
            val x1 = InputSanitizer.requirePixelCoord(call, "x1", injector.screenWidthPx)
            val y1 = InputSanitizer.requirePixelCoord(call, "y1", injector.screenHeightPx)
            val x2 = InputSanitizer.requirePixelCoord(call, "x2", injector.screenWidthPx)
            val y2 = InputSanitizer.requirePixelCoord(call, "y2", injector.screenHeightPx)
            val durationMs = InputSanitizer.requireDurationMs(call, "durationMs")
            injector.swipe(slot, x1, y1, x2, y2, durationMs)
            call.resolve()
        }
    }

    @PluginMethod
    fun releaseAllPointers(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "releaseAllPointers", call) {
            touchInjector?.releaseAll()
            call.resolve()
        }
    }

    @PluginMethod
    fun updateSwipeTrigger(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "updateSwipeTrigger", call) {
            val buttonCode = InputSanitizer.requireButtonCode(call, "buttonCode")
            val direction = InputSanitizer.requireSwipeDirection(call, "direction")
            pushSwipeTriggerToPipeline(buttonCode, direction)
            call.resolve()
        }
    }

    @PluginMethod
    fun getRecentErrors(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "getRecentErrors", call) {
            val errors = PendingErrorBus.drain()
            val arr = org.json.JSONArray()
            for (e in errors) arr.put(e)
            val ret = JSObject()
            ret.put("errors", arr)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun clearErrorHistory(call: PluginCall) {
        NativeCrashGuard.guard("GameMapper", "clearErrorHistory", call) {
            PendingErrorBus.clear()
            call.resolve()
        }
    }

    // ───── Pipeline glue (these methods adapt to whatever InputPipelineWorker
    //        API you actually have — replace the reflection-style calls with
    //        direct method calls once the wiring is finalized). ─────

    private fun pushProfileToPipeline(profile: GameProfile?) {
        val pw = pipelineWorker ?: return
        try {
            // Use reflection to call setProfile(GameProfile?) — adapts to any
            // pipeline class without forcing a hard compile-time dependency
            // in this reference file.
            val m = pw.javaClass.getMethod("setProfile", GameProfile::class.java)
            m.invoke(pw, profile)
        } catch (t: Throwable) {
            Log.w(TAG, "pushProfileToPipeline reflection failed: ${t.message}")
        }
    }

    private fun pushSwipeTriggerToPipeline(buttonCode: Int, direction: Int) {
        val pw = pipelineWorker ?: return
        try {
            val m = pw.javaClass.getMethod("updateSwipeTrigger", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            m.invoke(pw, buttonCode, direction)
        } catch (t: Throwable) {
            Log.w(TAG, "pushSwipeTriggerToPipeline reflection failed: ${t.message}")
        }
    }

    // ───── Error bus polling ─────

    private fun drainErrorBus() {
        val pending = PendingErrorBus.drain()
        if (pending.isEmpty()) return
        for (payload in pending) {
            try {
                val jsObj = JSObject(payload.toString())
                notifyListeners(EVENT_APP_ERROR, jsObj)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to emit app:error event", t)
            }
        }
    }

    // ───── Wiring setters (called by Application.onCreate or Activity) ─────

    fun setTouchInjector(injector: TouchInjector) {
        touchInjector = injector
    }

    fun setPipelineWorker(worker: Any) {
        pipelineWorker = worker
    }
}
