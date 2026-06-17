package com.nanomindexplorer.gamemappermind.input

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class AnalogProcessor {
    companion object { private const val TAG = "GameMapper/AnalogProcessor" }

    data class AnalogConfig(
        var centerX: Float, var centerY: Float, var radius: Float,
        var deadzone: Float = 0.15f, var smoothing: Float = 0.3f
    )

    data class AnalogState(
        var lastX: Float = 0f, var lastY: Float = 0f,
        var isDown: Boolean = false, var smoothedX: Float = 0f, var smoothedY: Float = 0f
    )

    fun process(axisX: Float, axisY: Float, config: AnalogConfig, state: AnalogState): Triple<Float, Float, Boolean> {
        val magnitude = sqrt(axisX * axisX + axisY * axisY)
        if (magnitude < config.deadzone) {
            if (state.isDown) { state.isDown = false; state.smoothedX = 0f; state.smoothedY = 0f; return Triple(config.centerX, config.centerY, false) }
            return Triple(config.centerX, config.centerY, false)
        }
        val normalizedMag = ((magnitude - config.deadzone) / (1.0f - config.deadzone)).coerceIn(0.0f, 1.0f)
        val dirX = axisX / magnitude; val dirY = axisY / magnitude
        val rawX = dirX * normalizedMag; val rawY = dirY * normalizedMag
        val alpha = config.smoothing
        state.smoothedX = state.smoothedX * alpha + rawX * (1.0f - alpha)
        state.smoothedY = state.smoothedY * alpha + rawY * (1.0f - alpha)
        val targetX = config.centerX + (state.smoothedX * config.radius)
        val targetY = config.centerY + (state.smoothedY * config.radius)
        val dx = abs(targetX - state.lastX); val dy = abs(targetY - state.lastY)
        val hasChanged = dx > 0.5f || dy > 0.5f
        if (hasChanged) { state.lastX = targetX; state.lastY = targetY }
        if (!state.isDown) { state.isDown = true; state.lastX = targetX; state.lastY = targetY; return Triple(targetX, targetY, true) }
        return Triple(targetX, targetY, hasChanged)
    }

    fun reset(state: AnalogState) { state.lastX = 0f; state.lastY = 0f; state.isDown = false; state.smoothedX = 0f; state.smoothedY = 0f }
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float { val dx = x2 - x1; val dy = y2 - y1; return sqrt(dx * dx + dy * dy) }
}
