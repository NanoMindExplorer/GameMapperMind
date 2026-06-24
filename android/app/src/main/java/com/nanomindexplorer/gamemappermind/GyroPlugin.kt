package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "Gyroscope")
class GyroPlugin : Plugin(), SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    @Volatile private var isCalibrating = false
    // BUG-P3 FIX: Cap calibration samples to prevent memory leak if sensor fires faster than expected.
    // 100 samples is enough for bias calculation; cap at 200 as safety margin.
    private val calibrationSamples = mutableListOf<FloatArray>()
    private val MAX_CALIBRATION_SAMPLES = 200

    // BUG-P8 FIX: Mark bias fields as @Volatile — they're written from sensor thread,
    // read from notifyListeners (also sensor thread, but defensive against future changes).
    @Volatile private var biasX = 0f
    @Volatile private var biasY = 0f
    @Volatile private var biasZ = 0f

    override fun load() {
        super.load()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    @PluginMethod
    fun startListening(call: PluginCall) {
        if (gyroSensor != null) {
            sensorManager?.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
            call.resolve()
        } else {
            call.reject("Gyroscope not available")
        }
    }

    @PluginMethod
    fun stopListening(call: PluginCall) {
        sensorManager?.unregisterListener(this)
        // BUG-P2 FIX: Cancel any in-progress calibration when stopping listener.
        // Otherwise, calibrationSamples accumulates indefinitely if user stops listening
        // mid-calibration, then starts again — old samples contaminate new calibration.
        synchronized(calibrationSamples) {
            isCalibrating = false
            calibrationSamples.clear()
        }
        call.resolve()
    }

    @PluginMethod
    fun calibrate(call: PluginCall) {
        synchronized(calibrationSamples) {
            isCalibrating = true
            calibrationSamples.clear()
        }
        val ret = JSObject()
        ret.put("message", "Calibration started")
        call.resolve(ret)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            if (isCalibrating) {
                synchronized(calibrationSamples) {
                    // BUG-P3 FIX: Cap sample count to prevent unbounded memory growth.
                    if (calibrationSamples.size < MAX_CALIBRATION_SAMPLES) {
                        calibrationSamples.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
                    }
                    if (calibrationSamples.size >= 100) {
                        isCalibrating = false
                        biasX = calibrationSamples.map { it[0] }.average().toFloat()
                        biasY = calibrationSamples.map { it[1] }.average().toFloat()
                        biasZ = calibrationSamples.map { it[2] }.average().toFloat()
                        calibrationSamples.clear()

                        val ret = JSObject()
                        ret.put("biasX", biasX)
                        ret.put("biasY", biasY)
                        ret.put("biasZ", biasZ)
                        notifyListeners("calibrationComplete", ret)
                    }
                }
            } else {
                val ret = JSObject()
                ret.put("x", event.values[0] - biasX)
                ret.put("y", event.values[1] - biasY)
                ret.put("z", event.values[2] - biasZ)
                ret.put("timestamp", event.timestamp)
                notifyListeners("gyroEvent", ret)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do nothing
    }
}
