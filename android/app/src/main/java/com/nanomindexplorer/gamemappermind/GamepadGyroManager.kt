package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface

/**
 * REC-19: Gyro dan accelerometer via SensorManager untuk tilt steering.
 *
 * Beberapa game (misal racing) cocok dengan gyro steering.
 * Konversi rate-of-rotation ke delta touch move.
 *
 * Math-Logic (Pasal 5.1):
 * - Sensor update rate: 60Hz (GAME rotation vector)
 * - dx = gyroY * sensitivity * dt
 * - dy = -gyroX * sensitivity * dt
 * - Calibration: record offset selama 2 detik, subtract dari raw
 * - Kompleksitas: O(1) per sensor event
 *
 * Invariant:
 * - Sensor hanya active jika profile punya gyro_area button type
 * - Calibration offset disimpan dan subtract dari raw
 * - Sensitivity configurable per-profile
 */
class GamepadGyroManager(private val context: Context) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null
    private var isActive = false

    // Calibration offsets.
    private var offsetX = 0f
    private var offsetY = 0f
    private var isCalibrated = false

    // Sensitivity (configurable per-profile).
    private var sensitivity = 1.0f

    // Callback untuk send gyro data ke GamepadMappingService.
    var onGyroUpdate: ((Float, Float) -> Unit)? = null

    // Last event timestamp for dt calculation.
    private var lastTimestamp: Long = 0L

    fun init() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        Log.d("GameMapper", "REC-19: Gyro manager initialized, gyro=${gyroscope != null}, rotVec=${rotationVector != null}")
    }

    /**
     * Start listening gyro events.
     * @param sensitivity multiplier for gyro to touch conversion
     */
    fun start(sensitivity: Float = 1.0f) {
        if (isActive) return
        this.sensitivity = sensitivity
        isActive = true
        sensorManager?.registerListener(this, rotationVector ?: gyroscope, SensorManager.SENSOR_DELAY_GAME)
        Log.d("GameMapper", "REC-19: Gyro listener started, sensitivity=$sensitivity")
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        sensorManager?.unregisterListener(this)
        Log.d("GameMapper", "REC-19: Gyro listener stopped")
    }

    /**
     * Calibrate gyro: record offset selama 2 detik.
     * User harus hold device steady saat calibration.
     */
    fun calibrate(durationMs: Long = 2000) {
        Log.d("GameMapper", "REC-19: Calibrating gyro for ${durationMs}ms")
        val samples = mutableListOf<Pair<Float, Float>>()
        val startTime = System.currentTimeMillis()

        // Temporary listener untuk calibration.
        val calListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val x = if (event.values.isNotEmpty()) event.values[0] else 0f
                val y = if (event.values.size > 1) event.values[1] else 0f
                samples.add(Pair(x, y))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(calListener, rotationVector ?: gyroscope, SensorManager.SENSOR_DELAY_GAME)

        // Wait for duration (blocking, should be called from background thread).
        Thread.sleep(durationMs)

        sensorManager?.unregisterListener(calListener)

        if (samples.isNotEmpty()) {
            val avgX = samples.map { it.first }.average().toFloat()
            val avgY = samples.map { it.second }.average().toFloat()
            offsetX = avgX
            offsetY = avgY
            isCalibrated = true
            Log.d("GameMapper", "REC-19: Calibration done, offset=($offsetX, $offsetY), samples=${samples.size}")
        } else {
            Log.w("GameMapper", "REC-19: Calibration failed, no samples")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isActive) return

        val rawX = if (event.values.isNotEmpty()) event.values[0] else 0f
        val rawY = if (event.values.size > 1) event.values[1] else 0f

        // Subtract calibration offset.
        val adjX = rawX - offsetX
        val adjY = rawY - offsetY

        // Calculate dt (seconds).
        val now = event.timestamp
        val dt = if (lastTimestamp > 0) (now - lastTimestamp) / 1e9f else 0.016f
        lastTimestamp = now

        // Convert gyro rate to delta touch move.
        // dx = gyroY * sensitivity * dt
        // dy = -gyroX * sensitivity * dt (negate karena Y axis terbalik)
        val dx = adjY * sensitivity * dt
        val dy = -adjX * sensitivity * dt

        onGyroUpdate?.invoke(dx, dy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun isCalibrated(): Boolean = isCalibrated
    fun isActive(): Boolean = isActive
}
