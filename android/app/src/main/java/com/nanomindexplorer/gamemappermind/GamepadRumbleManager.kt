package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * REC-07: Rumble dan haptic feedback via VibratorManager.
 *
 * Beberapa game mengirim event rumble ke gamepad via evdev FF code.
 * GameMapperMind intercept dan trigger vibration di ponsel.
 * Untuk button press, berikan haptic feedback via impact (light/medium/heavy).
 *
 * Math-Logic (Pasal 5.1):
 * - Vibrate: O(1) system call
 * - Kompleksitas: O(1) per feedback
 *
 * Invariant:
 * - Hanya vibrate jika user enable (configurable)
 * - Intensity 0-255 (0 = off, 255 = max)
 * - Thread-safe: Vibrator system service thread-safe
 */
object GamepadRumbleManager {

    private var vibrator: Vibrator? = null
    private var isEnabled = false
    private var intensity = 128 // 0-255, default medium

    fun init(context: Context) {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            Log.d("GameMapper", "REC-07: Rumble manager initialized")
        } catch (e: Exception) {
            Log.e("GameMapper", "REC-07: Failed to init vibrator", e)
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun setIntensity(value: Int) {
        intensity = value.coerceIn(0, 255)
    }

    /**
     * Haptic feedback untuk button press.
     * @param strength "light", "medium", atau "heavy"
     */
    fun hapticFeedback(strength: String = "medium") {
        if (!isEnabled || vibrator == null) return

        val duration = when (strength) {
            "light" -> 10L
            "medium" -> 20L
            "heavy" -> 40L
            else -> 20L
        }

        val amplitude = when (strength) {
            "light" -> (intensity * 0.3).toInt().coerceIn(1, 255)
            "medium" -> (intensity * 0.6).toInt().coerceIn(1, 255)
            "heavy" -> intensity
            else -> (intensity * 0.6).toInt().coerceIn(1, 255)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration)
            }
        } catch (e: Exception) {
            Log.w("GameMapper", "REC-07: Vibrate failed", e)
        }
    }

    /**
     * Rumble effect untuk game events (explosion, collision, dll).
     * @param durationMs durasi dalam milidetik
     * @param intensity 0-255
     */
    fun rumble(durationMs: Long = 200, intensity: Int = 128) {
        if (!isEnabled || vibrator == null) return

        try {
            val amp = intensity.coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amp))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w("GameMapper", "REC-07: Rumble failed", e)
        }
    }

    fun hasVibrator(): Boolean {
        return vibrator?.hasVibrator() == true
    }
}
