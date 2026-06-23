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
});
