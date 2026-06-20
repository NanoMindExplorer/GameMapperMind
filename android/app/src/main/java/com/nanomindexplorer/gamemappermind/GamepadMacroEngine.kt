package com.nanomindexplorer.gamemappermind

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * REC-20: Macro triggering dari gamepad button.
 *
 * Saat gamepad button ditekan dan button type adalah 'macro' atau mappedKey
 * match triggerKey, jalankan macro sequence.
 *
 * Macro sequence dijalankan dengan timer presisi (Handler.postAtTime)
 * bukan setTimeout JS.
 *
 * Math-Logic (Pasal 5.1):
 * - Macro lookup: O(n) di mana n = jumlah macro (biasanya 1-5)
 * - Playback: O(m) di mana m = jumlah action dalam macro
 * - Timing: Handler.postAtTime presisi ~1ms
 * - Kompleksitas total: O(n + m) per trigger
 *
 * Invariant:
 * - Hanya satu macro yang aktif per waktu (no concurrent macro)
 * - Jika macro sedang playing dan trigger lagi, skip (dedup)
 * - Setelah macro selesai, emit event MACRO_COMPLETED
 */
class GamepadMacroEngine {

    data class MacroAction(
        val type: String, // touch_down, touch_move, touch_up, delay
        val x: Float = 0f,
        val y: Float = 0f,
        val delayMs: Long = 33L,
        val pointerId: Int = 0
    )

    data class Macro(
        val id: String,
        val name: String,
        val triggerKey: String,
        val actions: List<MacroAction>,
        val playbackSpeed: Float = 1.0f
    )

    private val handler = Handler(Looper.getMainLooper())
    private var macros: List<Macro> = emptyList()
    private var isPlaying = false

    /**
     * Load macros dari JSON string (dari profile).
     */
    fun loadMacros(macrosJson: String) {
        try {
            val arr = JSONArray(macrosJson)
            val list = mutableListOf<Macro>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val actions = mutableListOf<MacroAction>()
                val actionsArr = m.optJSONArray("actions") ?: JSONArray()
                for (j in 0 until actionsArr.length()) {
                    val a = actionsArr.getJSONObject(j)
                    actions.add(MacroAction(
                        type = a.optString("type", "delay"),
                        x = a.optDouble("x", 0.0).toFloat(),
                        y = a.optDouble("y", 0.0).toFloat(),
                        delayMs = a.optLong("delayMs", 33),
                        pointerId = a.optInt("pointerId", 0)
                    ))
                }
                list.add(Macro(
                    id = m.optString("id", ""),
                    name = m.optString("name", ""),
                    triggerKey = m.optString("triggerKey", ""),
                    actions = actions,
                    playbackSpeed = m.optDouble("playbackSpeed", 1.0).toFloat()
                ))
            }
            macros = list
            Log.d("GameMapper", "REC-20: ${macros.size} macros loaded")
        } catch (e: Exception) {
            Log.e("GameMapper", "REC-20: Failed to load macros", e)
        }
    }

    /**
     * Trigger macro berdasarkan button name.
     * @param buttonName nama tombol yang ditekan
     * @return true jika macro triggered, false jika tidak ada match atau sedang playing
     */
    fun triggerMacro(buttonName: String): Boolean {
        if (isPlaying) return false

        val macro = macros.find { it.triggerKey == buttonName } ?: return false
        if (macro.actions.isEmpty()) return false

        isPlaying = true
        GamepadLogger.log(GamepadLogger.Level.INFO, "GamepadMacroEngine",
            "Triggering macro: ${macro.name}, ${macro.actions.size} actions")

        playMacroSequence(macro, 0)
        return true
    }

    /**
     * Play macro sequence recursively via Handler.
     *
     * Math-Logic (Pasal 5.1):
     * - Setiap action dijadwalkan dengan delay / playbackSpeed
     * - touch_down: injectButtonDown
     * - touch_move: injectAxisMove
     * - touch_up: injectButtonUp
     * - delay: hanya wait
     * - Kompleksitas: O(m) di mana m = jumlah action
     */
    private fun playMacroSequence(macro: Macro, index: Int) {
        if (index >= macro.actions.size) {
            isPlaying = false
            GamepadLogger.log(GamepadLogger.Level.INFO, "GamepadMacroEngine",
                "Macro completed: ${macro.name}")
            TouchInjectionPlugin.emitGamepadButton("MACRO_COMPLETED", 1, 1.0f)
            return
        }

        val action = macro.actions[index]
        val delayMs = (action.delayMs / macro.playbackSpeed).toLong()

        handler.postDelayed({
            when (action.type) {
                "touch_down" -> {
                    TouchInjectionPlugin.injectButtonDown(action.pointerId, action.x, action.y)
                }
                "touch_move" -> {
                    TouchInjectionPlugin.injectAxisMove(action.pointerId, action.x, action.y)
                }
                "touch_up" -> {
                    TouchInjectionPlugin.injectButtonUp(action.pointerId)
                }
                "delay" -> { /* just wait */ }
            }

            playMacroSequence(macro, index + 1)
        }, delayMs)
    }

    /**
     * Stop macro yang sedang playing.
     */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        isPlaying = false
        Log.d("GameMapper", "REC-20: Macro stopped")
    }

    fun isPlaying(): Boolean = isPlaying
    fun getMacros(): List<Macro> = macros
}
