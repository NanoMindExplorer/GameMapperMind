import { describe, it, expect } from 'vitest';

export function getScreenCoords(orientation: number, stickX: number, stickY: number, screenW: number, screenH: number) {
    if (orientation === 0 || orientation === 180) {
        return { x: screenW / 2 + stickX * 100, y: screenH / 2 + stickY * 100 };
    } else {
        return { x: screenH / 2 + stickX * 100, y: screenW / 2 + stickY * 100 };
    }
}

describe('Coordinate Transform Tests', () => {
    it('handles ROTATION_0', () => {
        const coords = getScreenCoords(0, 0.5, 0.5, 1080, 1920);
        expect(coords.x).toBe(1080/2 + 50);
        expect(coords.y).toBe(1920/2 + 50);
    });

    it('handles ROTATION_90', () => {
        const coords = getScreenCoords(90, 0.5, 0.5, 1920, 1080);
        expect(coords.x).toBe(1080/2 + 50);
        expect(coords.y).toBe(1920/2 + 50);
    });

    it('handles ROTATION_180', () => {
        const coords = getScreenCoords(180, -0.5, -0.5, 1080, 1920);
        expect(coords.x).toBe(1080/2 - 50);
        expect(coords.y).toBe(1920/2 - 50);
    });

    it('handles ROTATION_270', () => {
        const coords = getScreenCoords(270, -0.5, -0.5, 1920, 1080);
        expect(coords.x).toBe(1080/2 - 50);
        expect(coords.y).toBe(1920/2 - 50);
    });
});
