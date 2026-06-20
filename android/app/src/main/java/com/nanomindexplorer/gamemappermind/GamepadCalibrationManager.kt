package com.nanomindexplorer.gamemappermind

import android.util.Log
import android.view.InputDevice

/**
 * REC-08: Calibration mode untuk gamepad non-standard.
 *
 * Beberapa gamepad China murah punya button mapping tidak standar
 * (misal BTN_1 instead BTN_A). Calibration mode memungkinkan user
 * tekan setiap tombol sesuai urutan, aplikasi record evdev code.
 *
 * Math-Logic (Pasal 5.1):
 * - Calibration: O(n) di mana n = jumlah tombol (16)
 * - Lookup: O(1) HashMap
 * - Kompleksitas: O(n) untuk calibration, O(1) untuk runtime lookup
 *
 * Invariant:
 * - customMapping selalu konsisten dengan yang user set
 * - Jika calibration tidak dilakukan, pakai default mapping
 */
object GamepadCalibrationManager {

    data class CalibrationStep(
        val buttonName: String,
        val displayName: String,
        val evdevCode: String? = null
    )

    private val calibrationSteps = listOf(
        CalibrationStep("A", "A / Cross"),
        CalibrationStep("B", "B / Circle"),
        CalibrationStep("X", "X / Square"),
        CalibrationStep("Y", "Y / Triangle"),
        CalibrationStep("LB", "L1 / Left Bumper"),
        CalibrationStep("RB", "R1 / Right Bumper"),
        CalibrationStep("LT", "L2 / Left Trigger"),
        CalibrationStep("RT", "R2 / Right Trigger"),
        CalibrationStep("L3", "L3 / Left Stick Click"),
        CalibrationStep("R3", "R3 / Right Stick Click"),
        CalibrationStep("START", "Start"),
        CalibrationStep("SELECT", "Select / Back"),
        CalibrationStep("DPAD_UP", "D-Pad Up"),
        CalibrationStep("DPAD_DOWN", "D-Pad Down"),
        CalibrationStep("DPAD_LEFT", "D-Pad Left"),
        CalibrationStep("DPAD_RIGHT", "D-Pad Right")
    )

    // Custom mapping: evdevCode -> buttonName (hasil calibration)
    private val customMapping: MutableMap<String, String> = mutableMapOf()
    private var isCalibrating = false
    private var currentStepIndex = 0

    /**
     * Start calibration mode.
     * Frontend panggil method ini, lalu user tekan tombol sesuai urutan.
     */
    fun startCalibration() {
        isCalibrating = true
        currentStepIndex = 0
        customMapping.clear()
        Log.d("GameMapper", "REC-08: Calibration started, ${calibrationSteps.size} steps")
        TouchInjectionPlugin.emitGamepadButton("CALIBRATION_STARTED", 1, 1.0f)
    }

    /**
     * Record evdev code untuk step saat ini.
     * Dipanggil saat user menekan tombol selama calibration.
     *
     * @param evdevCode code dari getevent (misal BTN_A, BTN_SOUTH, ABS_HAT0X)
     * @return true jika step selesai, false jika calibration tidak aktif
     */
    fun recordCalibrationStep(evdevCode: String): Boolean {
        if (!isCalibrating || currentStepIndex >= calibrationSteps.size) return false

        val step = calibrationSteps[currentStepIndex]
        customMapping[evdevCode] = step.buttonName
        Log.d("GameMapper", "REC-08: Calibrated ${step.buttonName} -> $evdevCode (${currentStepIndex + 1}/${calibrationSteps.size})")

        // Emit progress ke frontend.
        TouchInjectionPlugin.emitGamepadButton("CALIBRATION_PROGRESS", currentStepIndex + 1, 1.0f)

        currentStepIndex++
        if (currentStepIndex >= calibrationSteps.size) {
            isCalibrating = false
            Log.d("GameMapper", "REC-08: Calibration completed, ${customMapping.size} buttons mapped")
            TouchInjectionPlugin.emitGamepadButton("CALIBRATION_COMPLETED", 1, 1.0f)
        }

        return true
    }

    /**
     * Cancel calibration.
     */
    fun cancelCalibration() {
        isCalibrating = false
        currentStepIndex = 0
        customMapping.clear()
        Log.d("GameMapper", "REC-08: Calibration cancelled")
        TouchInjectionPlugin.emitGamepadButton("CALIBRATION_CANCELLED", 0, 0.0f)
    }

    /**
     * Get calibrated button name untuk evdev code.
     * Jika tidak ada custom mapping, return null (pakai default mapEvdevToButton).
     *
     * @param evdevCode code dari getevent
     * @return buttonName jika ada di custom mapping, null jika tidak
     */
    fun getCalibratedButtonName(evdevCode: String): String? {
        return customMapping[evdevCode]
    }

    fun isCalibrating(): Boolean = isCalibrating
    fun getCurrentStep(): CalibrationStep? = calibrationSteps.getOrNull(currentStepIndex)
    fun getCalibrationSteps(): List<CalibrationStep> = calibrationSteps
    fun getProgress(): Pair<Int, Int> = Pair(currentStepIndex, calibrationSteps.size)
    fun hasCustomMapping(): Boolean = customMapping.isNotEmpty()
}
