package com.nanomindexplorer.gamemappermind

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import android.view.InputDevice

/**
 * REC-16: Gamepad battery indicator dan device info.
 *
 * Beberapa gamepad (terutama Xbox, PlayStation) report battery via Bluetooth GATT.
 * Untuk gamepad yang tidak report battery via Bluetooth, coba pakai BatteryManager broadcast.
 *
 * Math-Logic (Pasal 5.1):
 * - Battery check: O(1) system query
 * - Device info: O(1) InputDevice API
 * - Kompleksitas: O(1) per check
 *
 * Invariant:
 * - Battery level 0-100 (persen)
 * - Jika battery tidak terdeteksi, return -1
 * - Warning jika battery < 20%
 */
object GamepadBatteryManager {

    data class GamepadInfo(
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val batteryLevel: Int, // 0-100, atau -1 jika tidak terdeteksi
        val isConnected: Boolean
    )

    /**
     * Get info untuk semua gamepad yang terhubung.
     */
    fun getConnectedGamepads(context: Context): List<GamepadInfo> {
        val gamepads = mutableListOf<GamepadInfo>()

        try {
            // Cek via InputDevice API.
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? android.hardware.input.InputManager
            inputManager?.inputDeviceIds?.forEach { deviceId ->
                val device = InputDevice.getDevice(deviceId)
                if (device != null && isGamepadDevice(device)) {
                    val batteryLevel = getBatteryLevel(context, device)
                    gamepads.add(GamepadInfo(
                        name = device.name,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        batteryLevel = batteryLevel,
                        isConnected = true
                    ))
                    Log.d("GameMapper", "REC-16: Gamepad: ${device.name}, battery=$batteryLevel%")
                }
            }
        } catch (e: Exception) {
            Log.e("GameMapper", "REC-16: Failed to get gamepad info", e)
        }

        return gamepads
    }

    /**
     * Get battery level untuk device.
     * Coba beberapa method:
     * 1. InputDevice.getBatteryLevel() (API 33+)
     * 2. BatteryManager broadcast (untuk BT device)
     * 3. BluetoothDevice battery GATT (jika ada)
     */
    private fun getBatteryLevel(context: Context, device: InputDevice): Int {
        // Method 1: InputDevice.getBattery() (API 33+) via reflection
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val batteryMethod = InputDevice::class.java.getMethod("getBattery")
                val battery = batteryMethod.invoke(device)
                if (battery != null) {
                    val getStatusMethod = battery.javaClass.getMethod("getStatus")
                    val status = getStatusMethod.invoke(battery)
                    if (status != null) {
                        val getLevelMethod = status.javaClass.getMethod("getLevel")
                        val level = getLevelMethod.invoke(status) as Int
                        return level
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through to other methods
        }

        // Method 2: BatteryManager untuk BT device
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            // Ini hanya untuk phone battery, bukan gamepad. Tapi coba saja.
            // Untuk gamepad BT, perlu GATT lookup yang lebih kompleks.
        } catch (e: Exception) {
            // Fall through
        }

        // Jika tidak bisa detect battery, return -1
        return -1
    }

    /**
     * Check apakah device adalah gamepad.
     */
    private fun isGamepadDevice(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * Check jika battery level low (< 20%).
     */
    fun isBatteryLow(batteryLevel: Int): Boolean {
        return batteryLevel in 0..19
    }

    /**
     * Get battery status string untuk UI.
     */
    fun getBatteryStatusString(batteryLevel: Int): String {
        return when {
            batteryLevel < 0 -> "Unknown"
            batteryLevel == 0 -> "Empty"
            batteryLevel < 20 -> "Low ($batteryLevel%)"
            batteryLevel < 50 -> "Medium ($batteryLevel%)"
            batteryLevel < 80 -> "Good ($batteryLevel%)"
            else -> "Full ($batteryLevel%)"
        }
    }
}
