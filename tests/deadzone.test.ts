import { describe, it, expect } from 'vitest';
import { radialDeadzone, DEFAULT_DEADZONE } from '../src/constants/gamepad';

describe('Deadzone Tests', () => {
    it('should return 0 inside deadzone', () => {
        expect(radialDeadzone(0.1, 0.1, 0.5)).toEqual({ x: 0, y: 0 });
    });
    
    it('should scale outside deadzone', () => {
        const val = radialDeadzone(0.8, 0, 0.5);
        expect(val.x).toBeGreaterThan(0);
        expect(val.y).toBe(0);
    });
    
    it('should handle negative values', () => {
        const val = radialDeadzone(-0.8, 0, 0.5);
        expect(val.x).toBeLessThan(0);
        expect(val.y).toBe(0);
    });

    it('should retain active output inside hysteresis band when wasActive is true', () => {
        // With deadzone = 0.5 and default hysteresisFactor = 0.88, effective threshold is 0.44.
        // At magnitude = 0.46:
        // - wasActive = false -> 0.46 <= 0.5 -> neutral (0, 0)
        // - wasActive = true -> 0.46 > 0.44 -> still active (> 0)
        const neutral = radialDeadzone(0.46, 0, 0.5, { wasActive: false });
        expect(neutral).toEqual({ x: 0, y: 0 });

        const active = radialDeadzone(0.46, 0, 0.5, { wasActive: true });
        expect(active.x).toBeGreaterThan(0);
        expect(active.y).toBe(0);
    });

    it('should release to 0 when falling below hysteresis threshold even when wasActive is true', () => {
        // Below 0.44, it must return (0, 0) even with wasActive = true
        const released = radialDeadzone(0.40, 0, 0.5, { wasActive: true });
        expect(released).toEqual({ x: 0, y: 0 });
    });
});

