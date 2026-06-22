package com.nanomindexplorer.gamemappermind

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.sqrt

class NativeGamepadMapper(private val context: Context) {
    class PointerState(val id: Int, var isActive: Boolean, val type: String, var virtualKey: String? = null)
    
    private val pointers = mutableListOf(
        PointerState(0, false, "analog"),
        PointerState(1, false, "analog")
    ).apply {
        for (i in 2..9) add(PointerState(i, false, "button"))
    }

    private val lastState = mutableMapOf<String, Boolean>()
    
    // Config values
    private val deadzone = 0.15f
    private val maxRadius = 150f

    private fun getScreenCoords(pctX: Double, pctY: Double): Pair<Float, Float> {
        val dm = context.resources.displayMetrics
        val sw = Math.max(dm.widthPixels, dm.heightPixels)
        val sh = Math.min(dm.widthPixels, dm.heightPixels)
        return Pair(((pctX / 100.0) * sw).toFloat(), ((pctY / 100.0) * sh).toFloat())
    }
    
    private fun findButtonMapping(mappedKey: String): JSONObject? {
        val jsonStr = GamepadListenerService.activeProfileJson ?: return null
        try {
            val root = JSONObject(jsonStr)
            val buttons = root.optJSONArray("buttons") ?: return null
            for (i in 0 until buttons.length()) {
                val b = buttons.optJSONObject(i)
                if (b?.optString("mappedKey") == mappedKey) {
                    return b
                }
            }
        } catch (e: Exception) {}
        return null
    }

    // Call from Service thread
    fun handleButton(buttonName: String, isDown: Boolean) {
        val mapping = findButtonMapping(buttonName)
        if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
            lastState[buttonName] = isDown
            return
        }
        val wasDown = lastState[buttonName] ?: false
        if (isDown && !wasDown) {
            val p = pointers.find { !it.isActive && it.type == "button" }
            if (p != null) {
                p.isActive = true
                p.virtualKey = buttonName
                val (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
                TouchInjectionPlugin.touchService?.touchDown(p.id, x, y)
            }
        } else if (!isDown && wasDown) {
            val p = pointers.find { it.isActive && it.virtualKey == buttonName }
            if (p != null) {
                p.isActive = false
                p.virtualKey = null
                TouchInjectionPlugin.touchService?.touchUp(p.id)
            }
        }
        lastState[buttonName] = isDown
    }

    fun handleAxes(lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        // 1. Left stick
        val lMag = sqrt(lx*lx + ly*ly)
        val lMap = findButtonMapping("L_STICK")
        val lp = pointers[0]
        if (lMap != null && lMap.has("x") && lMap.has("y")) {
            val (cX, cY) = getScreenCoords(lMap.getDouble("x"), lMap.getDouble("y"))
            if (lMag > deadzone) {
                val tX = cX + (lx * maxRadius)
                val tY = cY + (ly * maxRadius)
                if (!lp.isActive) {
                    lp.isActive = true
                    TouchInjectionPlugin.touchService?.touchDown(lp.id, cX, cY)
                }
                TouchInjectionPlugin.touchService?.touchMove(lp.id, tX, tY)
            } else if (lp.isActive) {
                lp.isActive = false
                TouchInjectionPlugin.touchService?.touchUp(lp.id)
            }
        }
        
        // 2. Right stick
        val rMag = sqrt(rx*rx + ry*ry)
        val rMap = findButtonMapping("R_STICK")
        val rp = pointers[1]
        if (rMap != null && rMap.has("x") && rMap.has("y")) {
            val (cX, cY) = getScreenCoords(rMap.getDouble("x"), rMap.getDouble("y"))
            if (rMag > deadzone) {
                val tX = cX + (rx * maxRadius)
                val tY = cY + (ry * maxRadius)
                if (!rp.isActive) {
                    rp.isActive = true
                    TouchInjectionPlugin.touchService?.touchDown(rp.id, cX, cY)
                }
                TouchInjectionPlugin.touchService?.touchMove(rp.id, tX, tY)
            } else if (rp.isActive) {
                rp.isActive = false
                TouchInjectionPlugin.touchService?.touchUp(rp.id)
            }
        }
        
        // 3. L2 Analog to Button
        if (l2 > 0.0f) {
            handleButton("LT", true)
        } else {
            handleButton("LT", false)
        }
        
        // 4. R2 Analog to Button
        if (r2 > 0.0f) {
            handleButton("RT", true)
        } else {
            handleButton("RT", false)
        }
    }
}
