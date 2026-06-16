package com.nanomindexplorer.gamemappermind

import android.app.Service
import android.content.Intent
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// TouchDaemonService runs as a Shizuku UserService (shell-privilege process).
// In Shizuku API v13.1.5, UserService is NOT a class you extend — you write
// a regular Android Service and bind to it via Shizuku.bindUserService().
class TouchDaemonService : Service() {

    data class AntiBanConfig(
        var enabled: Boolean = false,
        var coordinateJitter: Float = 4f,
        var timingJitter: Int = 3,
        var pressureVariance: Float = 0.15f,
        var sizeVariance: Float = 0.10f,
        var strokeDurationJitter: Int = 12,
        var microPauseProbability: Float = 0.02f,
        var microPauseMaxMs: Int = 45
    )

    @Volatile private var antiBan = AntiBanConfig()

    private val touchStub = object : ITouchService.Stub() {
        override fun touchDown(pointerId: Int, x: Float, y: Float) {
            this@TouchDaemonService.touchDown(pointerId, x, y)
        }
        override fun touchMove(pointerId: Int, x: Float, y: Float) {
            this@TouchDaemonService.touchMove(pointerId, x, y)
        }
        override fun touchUp(pointerId: Int) {
            this@TouchDaemonService.touchUp(pointerId)
        }
        override fun injectTap(x: Float, y: Float) {
            this@TouchDaemonService.injectTap(x, y)
        }
        override fun isAlive(): Boolean = true

        override fun setAntiBanConfig(
            enabled: Boolean,
            coordinateJitter: Float,
            timingJitter: Int,
            pressureVariance: Float,
            sizeVariance: Float,
            strokeDurationJitter: Int,
            microPauseProbability: Float,
            microPauseMaxMs: Int
        ) {
            antiBan = AntiBanConfig(
                enabled, coordinateJitter, timingJitter, pressureVariance,
                sizeVariance, strokeDurationJitter, microPauseProbability, microPauseMaxMs
            )
            Log.d("GameMapper", "Anti-ban config updated: enabled=$enabled jitter=${coordinateJitter}px")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = touchStub

    private val inputManager: InputManager? by lazy {
        try {
            InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get InputManager", e); null
        }
    }

    private val injectInputEventMethod by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get injectInputEvent method", e); null
        }
    }

    class PointerState {
        var x: Float = 0f
        var y: Float = 0f
        var isDown: Boolean = false
        var pressure: Float = 1.0f
        var size: Float = 1.0f
    }

    private val pointers = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L
    private val rng = Random(System.currentTimeMillis())

    private fun applyCoordinateJitter(x: Float, y: Float): Pair<Float, Float> {
        if (!antiBan.enabled || antiBan.coordinateJitter <= 0f) return Pair(x, y)
        val angle = rng.nextFloat() * 2f * Math.PI.toFloat()
        val mag = rng.nextFloat() * antiBan.coordinateJitter
        return Pair(x + cos(angle) * mag, y + sin(angle) * mag)
    }

    private fun applyPressureVariance(): Float {
        if (!antiBan.enabled || antiBan.pressureVariance <= 0f) return 1.0f
        return 1.0f - (rng.nextFloat() * antiBan.pressureVariance)
    }

    private fun applySizeVariance(): Float {
        if (!antiBan.enabled || antiBan.sizeVariance <= 0f) return 1.0f
        return 1.0f - (rng.nextFloat() * antiBan.sizeVariance)
    }

    private fun applyTimingJitter() {
        if (!antiBan.enabled || antiBan.timingJitter <= 0) return
        val delay = rng.nextInt(0, antiBan.timingJitter * 2) - antiBan.timingJitter
        if (delay > 0) {
            try { Thread.sleep(delay.toLong()) } catch (_: InterruptedException) {}
        }
    }

    private fun maybeMicroPause() {
        if (!antiBan.enabled || antiBan.microPauseProbability <= 0f) return
        if (rng.nextFloat() < antiBan.microPauseProbability) {
            val pause = rng.nextInt(10, antiBan.microPauseMaxMs)
            try { Thread.sleep(pause.toLong()) } catch (_: InterruptedException) {}
        }
    }

    private fun injectMotionEvent(action: Int, actionIndex: Int) {
        val downTime = baseDownTime
        val eventTime = SystemClock.uptimeMillis()

        var pointerCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) pointerCount++
        }

        if (pointerCount == 0 && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            pointerCount = 1
        }
        if (pointerCount == 0) return

        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        var activeIndex = 0
        for (i in 0 until pointers.size()) {
            val pointerId = pointers.keyAt(i)
            val state = pointers.valueAt(i)

            val isActive = state.isDown ||
                ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP && pointerId == actionIndex) ||
                ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP)

            if (isActive) {
                pointerProperties[activeIndex].id = pointerId
                pointerProperties[activeIndex].toolType = MotionEvent.TOOL_TYPE_FINGER

                val (jx, jy) = applyCoordinateJitter(state.x, state.y)
                pointerCoords[activeIndex].x = jx
                pointerCoords[activeIndex].y = jy
                pointerCoords[activeIndex].pressure = if (state.pressure > 0) state.pressure else applyPressureVariance()
                pointerCoords[activeIndex].size = if (state.size > 0) state.size else applySizeVariance()
                activeIndex++
            }
        }

        var compactedActionIndex = 0
        if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN ||
            (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
            for (i in 0 until activeIndex) {
                if (pointerProperties[i].id == actionIndex) {
                    compactedActionIndex = i; break
                }
            }
        }

        val finalAction = if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN ||
                              (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
            (action and MotionEvent.ACTION_MASK) or (compactedActionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action

        val event = MotionEvent.obtain(
            downTime, eventTime, finalAction, activeIndex,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        try {
            injectInputEventMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            Log.e("GameMapper", "Injection failed", e)
        }
        event.recycle()
    }

    fun touchDown(pointerId: Int, x: Float, y: Float) {
        maybeMicroPause()
        applyTimingJitter()

        var state = pointers.get(pointerId)
        if (state == null) {
            state = PointerState()
            pointers.put(pointerId, state)
        }
        state.x = x
        state.y = y
        state.isDown = true
        state.pressure = applyPressureVariance()
        state.size = applySizeVariance()

        var activePointersCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activePointersCount++
        }

        if (activePointersCount == 1) {
            baseDownTime = SystemClock.uptimeMillis()
            injectMotionEvent(MotionEvent.ACTION_DOWN, pointerId)
        } else {
            injectMotionEvent(MotionEvent.ACTION_POINTER_DOWN, pointerId)
        }
    }

    fun touchMove(pointerId: Int, x: Float, y: Float) {
        applyTimingJitter()
        val state = pointers.get(pointerId) ?: return
        state.x = x
        state.y = y
        if (antiBan.enabled) {
            state.pressure = (state.pressure + (rng.nextFloat() - 0.5f) * antiBan.pressureVariance * 0.3f)
                .coerceIn(0.7f, 1.0f)
        }
        if (state.isDown) {
            injectMotionEvent(MotionEvent.ACTION_MOVE, 0)
        }
    }

    fun touchUp(pointerId: Int) {
        applyTimingJitter()
        val state = pointers.get(pointerId) ?: return

        var activePointersCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activePointersCount++
        }

        if (activePointersCount <= 1) {
            injectMotionEvent(MotionEvent.ACTION_UP, pointerId)
            state.isDown = false
            pointers.clear()
        } else {
            injectMotionEvent(MotionEvent.ACTION_POINTER_UP, pointerId)
            state.isDown = false
            pointers.remove(pointerId)
        }
    }

    fun injectTap(x: Float, y: Float) {
        val duration = if (antiBan.enabled) {
            (20 + rng.nextInt(0, antiBan.strokeDurationJitter * 2) - antiBan.strokeDurationJitter)
                .coerceAtLeast(8).toLong()
        } else 20L
        val id = 99
        touchDown(id, x, y)
        try { Thread.sleep(duration) } catch (_: InterruptedException) {}
        touchUp(id)
    }

    fun isAlive(): Boolean = true
}
