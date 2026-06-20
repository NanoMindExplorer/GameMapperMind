package com.nanomindexplorer.gamemappermind

import android.view.InputDevice
import android.util.Log

/**
 * REC-18: Non-standard gamepad layout support (Switch Pro, DualShock, Xbox).
 *
 * Setiap platform punya layout berbeda:
 * - Switch Pro: A=B, B=A (Nintendo layout, swap A/B dan X/Y)
 * - DualShock: X=Square, O=Circle (Sony layout)
 * - Xbox: A=A (Microsoft layout, standard)
 *
 * Math-Logic (Pasal 5.1):
 * - Layout detection: O(1) vendor ID lookup
 * - Button swap: O(1) map lookup
 * - Kompleksitas: O(1) per button event
 *
 * Invariant:
 * - Layout di-deteksi otomatis berdasarkan vendor ID
 * - User dapat override manual
 * - Swap hanya terjadi untuk Nintendo layout (A<->B, X<->Y)
 */
object GamepadLayoutManager {

    enum class GamepadLayout(val displayName: String) {
        MICROSOFT("Xbox / Standard"),
        SONY("DualShock / PlayStation"),
        NINTENDO("Switch Pro / Nintendo"),
        GENERIC("Generic / Unknown")
    }

    // Vendor ID mapping (dari USB IDs)
    // Nintendo: 0x057E, Sony: 0x054C, Microsoft: 0x045E
    private val vendorLayoutMap = mapOf(
        0x057E to GamepadLayout.NINTENDO,
        0x054C to GamepadLayout.SONY,
        0x045E to GamepadLayout.MICROSOFT
    )

    private var currentLayout = GamepadLayout.GENERIC
    private var userOverride: GamepadLayout? = null

    /**
     * Detect layout berdasarkan InputDevice vendor ID.
     *
     * @param device InputDevice dari Android API
     */
    fun detectLayout(device: InputDevice) {
        val vendorId = device.vendorId
        val detected = vendorLayoutMap[vendorId] ?: GamepadLayout.GENERIC

        // Hanya update jika user tidak override.
        if (userOverride == null) {
            currentLayout = detected
            Log.d("GameMapper", "REC-18: Layout detected: ${detected.displayName} (vendor=0x${vendorId.toString(16)})")
        }
    }

    /**
     * User manual override layout.
     */
    fun setLayout(layout: GamepadLayout) {
        userOverride = layout
        currentLayout = layout
        Log.d("GameMapper", "REC-18: Layout overridden to ${layout.displayName}")
    }

    /**
     * Reset override (kembali ke auto-detect).
     */
    fun resetOverride() {
        userOverride = null
        Log.d("GameMapper", "REC-18: Layout override reset, back to auto-detect")
    }

    /**
     * Apply layout swap ke button name.
     *
     * Untuk Nintendo layout:
     * - A -> B (Nintendo A adalah Xbox B)
     * - B -> A (Nintendo B adalah Xbox A)
     * - X -> Y
     * - Y -> X
     *
     * Untuk Sony dan Microsoft: no swap (sudah standard)
     *
     * @param buttonName nama tombol dari evdev (setelah mapEvdevToButton)
     * @return buttonName setelah layout swap
     */
    fun applyLayoutSwap(buttonName: String): String {
        if (currentLayout != GamepadLayout.NINTENDO) return buttonName

        return when (buttonName) {
            "A" -> "B"
            "B" -> "A"
            "X" -> "Y"
            "Y" -> "X"
            else -> buttonName
        }
    }

    fun getCurrentLayout(): GamepadLayout = currentLayout
    fun isAutoDetect(): Boolean = userOverride == null

    /**
     * Get all available layouts untuk UI dropdown.
     */
    fun getAllLayouts(): List<GamepadLayout> = GamepadLayout.values().toList()
}
