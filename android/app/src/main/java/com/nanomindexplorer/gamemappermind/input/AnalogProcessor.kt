package com.nanomindexplorer.gamemappermind.input

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AnalogProcessor — Process analog stick input dengan deadzone + smoothing.
 *
 * GMM-AEC-002 §11.3: Multi-step interpolation helper
 *   Menyediakan method calculateInterpolationSteps() yang menghitung
 *   jumlah step interpolasi optimal dari posisi current ke target.
 *   TouchInjector.analogMove() menggunakan ini untuk multi-step ACTION_MOVE
 *   yang smooth (bukan teleport).
 */
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
            if (state.isDown) {
                state.isDown = false
                state.smoothedX = 0f
                state.smoothedY = 0f
                return Triple(config.centerX, config.centerY, false)
            }
            return Triple(config.centerX, config.centerY, false)
        }
        val normalizedMag = ((magnitude - config.deadzone) / (1.0f - config.deadzone)).coerceIn(0.0f, 1.0f)
        val dirX = axisX / magnitude
        val dirY = axisY / magnitude
        val rawX = dirX * normalizedMag
        val rawY = dirY * normalizedMag
        val alpha = config.smoothing
        state.smoothedX = state.smoothedX * alpha + rawX * (1.0f - alpha)
        state.smoothedY = state.smoothedY * alpha + rawY * (1.0f - alpha)
        val targetX = config.centerX + (state.smoothedX * config.radius)
        val targetY = config.centerY + (state.smoothedY * config.radius)
        val dx = abs(targetX - state.lastX)
        val dy = abs(targetY - state.lastY)
        val hasChanged = dx > 0.5f || dy > 0.5f
        if (hasChanged) {
            state.lastX = targetX
            state.lastY = targetY
        }
        if (!state.isDown) {
            state.isDown = true
            state.lastX = targetX
            state.lastY = targetY
            return Triple(targetX, targetY, true)
        }
        return Triple(targetX, targetY, hasChanged)
    }

    /**
     * GMM-AEC-002 §11.3: Calculate number of interpolation steps.
     *
     * Algorithm:
     *   - distance = sqrt((targetX - currentX)^2 + (targetY - currentY)^2)
     *   - steps = clamp(distance / 20, 3, 8)
     *   - Untuk movement kecil (<60px) -> 3 step (minimum, sesuai kontrak)
     *   - Untuk movement besar (>160px) -> 8 step (maximum)
     */
    fun calculateInterpolationSteps(
        currentX: Float, currentY: Float,
        targetX: Float, targetY: Float
    ): Int {
        val dx = targetX - currentX
        val dy = targetY - currentY
        val distance = sqrt(dx * dx + dy * dy)
        return (distance.toInt() / 20).coerceIn(3, 8)
    }

    /**
     * GMM-AEC-002 §11.3: Generate interpolated points untuk multi-step MOVE.
     *
     * Algorithm:
     *   - Linear interpolation: progress = step / totalSteps
     *   - point.x = startX + (endX - startX) * progress
     *   - point.y = startY + (endY - startY) * progress
     */
    fun generateInterpolationPoints(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        steps: Int
    ): List<Pair<Float, Float>> {
        val result = mutableListOf<Pair<Float, Float>>()
        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            val x = startX + (endX - startX) * progress
            val y = startY + (endY - startY) * progress
            result.add(Pair(x, y))
        }
        return result
    }

    fun reset(state: AnalogState) {
        state.lastX = 0f
        state.lastY = 0f
        state.isDown = false
        state.smoothedX = 0f
        state.smoothedY = 0f
    }

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
