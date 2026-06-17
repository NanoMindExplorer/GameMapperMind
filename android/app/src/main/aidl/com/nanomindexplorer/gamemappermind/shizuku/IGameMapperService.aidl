// ============================================================
// IGameMapperService.aidl — AIDL interface for Shizuku UserService
// ============================================================
// This interface defines the contract between the app process
// and the Shizuku UserService (shell-privilege process).
//
// All touch injection + gamepad reading happens inside the
// UserService process (UID 2000 / shell).
// ============================================================

package com.nanomindexplorer.gamemappermind.shizuku;

interface IGameMapperService {

    // ============================================================
    // Shizuku lifecycle — transaction ID 16777114 is reserved
    // by Shizuku for the destroy() callback.
    // ============================================================
    void destroy() = 16777114;

    // ============================================================
    // Touch Injection — runs inside shell-privilege process
    // Uses IInputManager.injectInputEvent() via ShizukuBinderWrapper
    // ============================================================

    // Single tap at (x, y) on specified display
    void injectTap(float x, float y, int displayId) = 1;

    // Swipe from (startX, startY) to (endX, endY) over durationMs
    void injectSwipe(float startX, float startY, float endX, float endY, long durationMs, int displayId) = 2;

    // Multi-touch down with multiple pointers
    // pointerIds: comma-separated IDs (e.g., "0,1,2")
    // coords: comma-separated "x:y" pairs (e.g., "100:200,300:400")
    void injectMultiTouchDown(String pointerIds, String coords, int displayId) = 3;

    // Multi-touch move (update positions of active pointers)
    void injectMultiTouchMove(String pointerIds, String coords, int displayId) = 4;

    // Multi-touch up for specific pointer
    void injectTouchUp(int pointerId, int displayId) = 5;

    // Analog stick simulation: touch down at center, move to (centerX+deltaX, centerY+deltaY)
    void injectAnalogStick(float centerX, float centerY, float deltaX, float deltaY, int pointerId, int displayId) = 6;

    // Release analog stick (touch up for that pointer)
    void releaseAnalogStick(int pointerId, int displayId) = 7;

    // ============================================================
    // Gamepad input reading — runs inside shell-privilege process
    // Uses evdev /dev/input/event* access (requires shell UID)
    // ============================================================

    // Start reading raw gamepad events from /dev/input/event*
    // Events are emitted back to app via callback (handled by Service)
    boolean startGamepadRead() = 8;

    // Stop reading gamepad events
    boolean stopGamepadRead() = 9;

    // ============================================================
    // Anti-ban configuration
    // ============================================================
    void setAntiBanConfig(
        boolean enabled,
        float coordinateJitter,
        int timingJitterMs,
        float pressureVariance,
        float sizeVariance
    ) = 10;

    // ============================================================
    // Status
    // ============================================================
    boolean isAlive() = 11;
}
