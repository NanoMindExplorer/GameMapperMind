export const DEFAULT_DEADZONE = 0.15;

/**
 * BUG-M3/M12 FIX: Radial deadzone with proper clamping.
 *
 * Mathematical contract:
 *   Input: (x, y) in [-1, 1]² (axis values from gamepad API)
 *   Output: (outX, outY) in [-1, 1]²
 *
 * Algorithm:
 *   1. Compute magnitude = sqrt(x² + y²)
 *   2. If magnitude <= deadzone: return (0, 0) — stick is "neutral"
 *   3. Otherwise, rescale so that:
 *        - At magnitude = deadzone: output magnitude = 0 (smooth transition)
 *        - At magnitude = 1: output magnitude = 1 (full deflection)
 *      scale = (magnitude - deadzone) / (1 - deadzone)
 *      This maps [deadzone, 1] → [0, 1] linearly.
 *   4. Direction is preserved: (x/magnitude, y/magnitude) * scale
 *   5. BUG-M12 FIX: Clamp final output to [-1, 1] in case input magnitude > 1
 *      (some gamepads report values slightly above 1.0 due to calibration drift).
 */
export function radialDeadzone(x: number, y: number, deadzone = DEFAULT_DEADZONE) {
    const magnitude = Math.sqrt(x * x + y * y);
    if (magnitude <= deadzone) {
        return { x: 0, y: 0 };
    }
    // BUG-M3 FIX: Guard against division by zero when deadzone = 1 (edge case).
    const denom = 1 - deadzone;
    if (denom <= 0) {
        return { x: 0, y: 0 };
    }
    let scale = (magnitude - deadzone) / denom;
    // BUG-M12 FIX: Clamp scale to [0, 1] — magnitude can exceed 1 on some gamepads.
    if (scale > 1) scale = 1;
    if (scale < 0) scale = 0;
    return {
        x: (x / magnitude) * scale,
        y: (y / magnitude) * scale
    };
}
