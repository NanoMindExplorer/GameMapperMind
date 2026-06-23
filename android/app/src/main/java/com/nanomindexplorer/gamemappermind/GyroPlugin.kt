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
    private var isCalibrating = false
    private var calibrationSamples = mutableListOf<FloatArray>()
    
    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f

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
        call.resolve()
    }

    @PluginMethod
    fun calibrate(call: PluginCall) {
        isCalibrating = true
        calibrationSamples.clear()
        
        // Wait for 100 samples in onSensorChanged
        // Return immediately to let async process run
        val ret = JSObject()
        ret.put("message", "Calibration started")
        call.resolve(ret)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            if (isCalibrating) {
                calibrationSamples.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
                if (calibrationSamples.size >= 100) {
                    isCalibrating = false
                    biasX = calibrationSamples.map { it[0] }.average().toFloat()
                    biasY = calibrationSamples.map { it[1] }.average().toFloat()
                    biasZ = calibrationSamples.map { it[2] }.average().toFloat()
                    
                    val ret = JSObject()
                    ret.put("biasX", biasX)
                    ret.put("biasY", biasY)
                    ret.put("biasZ", biasZ)
                    notifyListeners("calibrationComplete", ret)
                }
            } else {
                val ret = JSObject()
                // Apply Madgwick-like sensor fusion here implicitly or at least subtract bias
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
