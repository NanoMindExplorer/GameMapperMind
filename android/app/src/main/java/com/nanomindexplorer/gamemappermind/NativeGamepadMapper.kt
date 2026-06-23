package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.os.Handler
import android.os.Looper
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
            }
        }
    }

    class PointerState(val id: Int, var isActive: Boolean, val type: String, var virtualKey: String? = null)
    
    val pointers = mutableListOf(
        PointerState(0, false, "analog"),
        PointerState(1, false, "analog")
    ).apply {
        for (i in 2..15) add(PointerState(i, false, "button"))
    }

    val lastState = mutableMapOf<String, Boolean>()
    
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        instance = this
    }

    private fun getScreenCoords(pctX: Double, pctY: Double): Pair<Float, Float> {
        val dm = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val rotation = windowManager.defaultDisplay.rotation
        
        val (sw, sh) = when (rotation) {
            android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_180 -> {
                Pair(dm.widthPixels, dm.heightPixels)
            }
            android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 -> {
                Pair(dm.heightPixels, dm.widthPixels)
            }
            else -> Pair(dm.widthPixels, dm.heightPixels)
        }
        
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

    private fun getAntiBanOffset(antiBanEnabled: Boolean): Pair<Float, Float> {
        if (!antiBanEnabled) return Pair(0f, 0f)
        val radius = Random.nextFloat() * 8f
        val angle = Random.nextFloat() * 2 * Math.PI
        return Pair((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
    }

    fun handleButton(buttonName: String, isDown: Boolean) {
        synchronized(syncLock) {
            val mapping = findButtonMapping(buttonName)
            if (mapping == null || !mapping.has("x") || !mapping.has("y")) {
                val wasDown = lastState[buttonName] ?: false
                // Complete pending touchUp if it was mapped previously
                if (!isDown && wasDown) {
                    val p = pointers.find { it.isActive && it.virtualKey == buttonName }
                    if (p != null) {
                        p.isActive = false
                        p.virtualKey = null
                        try { TouchInjectionPlugin.touchService?.touchUp(p.id) } catch(e:Exception){}
                    }
                }
                lastState[buttonName] = isDown
                return
            }
            
            val antiBanEnabled = mapping.optBoolean("antiBanEnabled", false)
            val type = mapping.optString("type", "button")
            
            val wasDown = lastState[buttonName] ?: false
            if (isDown && !wasDown) {
                val p = pointers.find { !it.isActive && it.type == "button" }
                if (p != null) {
                    p.isActive = true
                    p.virtualKey = buttonName
                    var (x, y) = getScreenCoords(mapping.getDouble("x"), mapping.getDouble("y"))
                    val (ox, oy) = getAntiBanOffset(antiBanEnabled)
                    x += ox
                    y += oy
                    TouchInjectionPlugin.touchService?.touchDown(p.id, x, y)
                    
                    if (type == "swipe" && mapping.has("swipeEndX") && mapping.has("swipeEndY")) {
                        val (ex, ey) = getScreenCoords(mapping.getDouble("swipeEndX"), mapping.getDouble("swipeEndY"))
                        mainHandler.postDelayed({
                            synchronized(syncLock) {
                                if (p.isActive) {
                                    TouchInjectionPlugin.touchService?.touchMove(p.id, ex + ox, ey + oy)
                                }
                            }
                        }, 50)
                    }
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
    }

    fun handleAxes(lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        synchronized(syncLock) {
            val sLx = lx
            val sLy = ly
            val sRx = rx
            val sRy = ry
        
            val lMag = sqrt(sLx*sLx + sLy*sLy)
            val lMap = findButtonMapping("L_STICK")
            val lp = pointers[0]
            if (lMap != null && lMap.has("x") && lMap.has("y")) {
                val deadzone = lMap.optDouble("deadzone", 0.15).toFloat()
                val maxRadius = lMap.optDouble("radius", 100.0).toFloat()
                
                val (cX, cY) = getScreenCoords(lMap.getDouble("x"), lMap.getDouble("y"))
                if (lMag > deadzone) {
                    val tX = cX + (sLx * maxRadius)
                    val tY = cY + (sLy * maxRadius)
                    if (!lp.isActive) {
                        lp.isActive = true
                        TouchInjectionPlugin.touchService?.touchDown(lp.id, cX, cY)
                    }
                    TouchInjectionPlugin.touchService?.touchMove(lp.id, tX, tY)
                } else if (lp.isActive) {
                    lp.isActive = false
                    TouchInjectionPlugin.touchService?.touchUp(lp.id)
                }
            } else if (lp.isActive) {
                lp.isActive = false
                TouchInjectionPlugin.touchService?.touchUp(lp.id)
            }
            
            val rMag = sqrt(sRx*sRx + sRy*sRy)
            val rMap = findButtonMapping("R_STICK")
            val rp = pointers[1]
            if (rMap != null && rMap.has("x") && rMap.has("y")) {
                val deadzone = rMap.optDouble("deadzone", 0.15).toFloat()
                val maxRadius = rMap.optDouble("radius", 150.0).toFloat()
                
                val (cX, cY) = getScreenCoords(rMap.getDouble("x"), rMap.getDouble("y"))
                if (rMag > deadzone) {
                    val tX = cX + (sRx * maxRadius)
                    val tY = cY + (sRy * maxRadius)
                    if (!rp.isActive) {
                        rp.isActive = true
                        TouchInjectionPlugin.touchService?.touchDown(rp.id, cX, cY)
                    }
                    TouchInjectionPlugin.touchService?.touchMove(rp.id, tX, tY)
                } else if (rp.isActive) {
                    rp.isActive = false
                    TouchInjectionPlugin.touchService?.touchUp(rp.id)
                }
            } else if (rp.isActive) {
                rp.isActive = false
                TouchInjectionPlugin.touchService?.touchUp(rp.id)
            }
            
            if (l2 > 0.05f) {
                handleButton("LT", true)
            } else {
                handleButton("LT", false)
            }
            
            if (r2 > 0.05f) {
                handleButton("RT", true)
            } else {
                handleButton("RT", false)
            }
        }
    }
}
