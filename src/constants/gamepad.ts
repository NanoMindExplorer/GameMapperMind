export const DEFAULT_DEADZONE = 0.15;

export function radialDeadzone(x: number, y: number, deadzone = DEFAULT_DEADZONE) {
    const magnitude = Math.sqrt(x * x + y * y);
    if (magnitude <= deadzone) {
        return { x: 0, y: 0 };
    }
    const scale = (magnitude - deadzone) / (1 - deadzone);
    return {
        x: (x / magnitude) * scale,
        y: (y / magnitude) * scale
    };
}
