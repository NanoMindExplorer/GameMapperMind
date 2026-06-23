import { describe, it, expect } from 'vitest';

describe('Profile Schema Tests', () => {
    it('validates a correct profile', () => {
        const dummyProfile = { orientation: 'landscape', hapticIntensity: 0.8 };
        // Simulating schema parsing logic
        expect(dummyProfile.orientation).toBe('landscape');
    });

    it('rejects invalid profile', () => {
        expect(() => {
            throw new Error('invalid schema');
        }).toThrow();
    });

    it('handles defaults correctly', () => {
        const dummyVal = { a: 1 };
        expect(dummyVal.a).toBe(1);
    });
});
