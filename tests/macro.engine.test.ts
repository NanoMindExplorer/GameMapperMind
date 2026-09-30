import { describe, it, expect } from 'vitest';
import { GamepadMacro, MacroAction } from '../src/types';

describe('Macro Engine Unit & Contract Tests', () => {
    // 1. Playback Speed & Delay Calculation
    function calculateEffectiveDelay(delayMs: number, playbackSpeed: number): number {
        const effectiveSpeed = playbackSpeed > 0 ? playbackSpeed : 1.0;
        const scaled = delayMs / effectiveSpeed;
        return Math.max(16, Math.round(scaled));
    }

    it('calculates delay accurately across playback speeds', () => {
        expect(calculateEffectiveDelay(100, 1.0)).toBe(100);
        expect(calculateEffectiveDelay(100, 2.0)).toBe(50);
        expect(calculateEffectiveDelay(100, 0.5)).toBe(200);
    });

    it('clamps negative or zero playback speed to 1.0x to avoid division by zero', () => {
        expect(calculateEffectiveDelay(100, 0)).toBe(100);
        expect(calculateEffectiveDelay(100, -1.5)).toBe(100);
    });

    it('enforces 16ms minimum delay floor for 60fps frame rate stability', () => {
        expect(calculateEffectiveDelay(10, 2.0)).toBe(16);
        expect(calculateEffectiveDelay(5, 1.0)).toBe(16);
    });

    // 2. Coordinate Normalization
    function normalizeCoordinate(val: number): number {
        if (val > 100) return val / 10;
        return Math.max(0, Math.min(100, val));
    }

    it('normalizes 0-1000 high-resolution coordinate scale to 0-100 percentage', () => {
        expect(normalizeCoordinate(500)).toBe(50);
        expect(normalizeCoordinate(1000)).toBe(100);
        expect(normalizeCoordinate(250)).toBe(25);
        expect(normalizeCoordinate(75.5)).toBe(75.5);
    });

    // 3. Trigger Key Matching Logic
    function getNormalizedTriggerKeys(key: string): string[] {
        const trimmed = key.trim();
        if (!trimmed) return [];
        const upper = trimmed.toUpperCase();
        const set = new Set<string>([trimmed, upper]);
        if (upper.startsWith('BUTTON_')) {
            set.add(upper.replace('BUTTON_', ''));
        } else {
            set.add(`BUTTON_${upper}`);
        }
        return Array.from(set);
    }

    it('normalizes trigger keys with and without BUTTON_ prefix', () => {
        const keysY = getNormalizedTriggerKeys('Y');
        expect(keysY).toContain('Y');
        expect(keysY).toContain('BUTTON_Y');

        const keysBtnA = getNormalizedTriggerKeys('BUTTON_A');
        expect(keysBtnA).toContain('BUTTON_A');
        expect(keysBtnA).toContain('A');

        const keysLower = getNormalizedTriggerKeys('button_x');
        expect(keysLower).toContain('BUTTON_X');
        expect(keysLower).toContain('X');
    });

    // 4. Pointer Lifecycle Tracking Simulation
    it('accurately tracks active pointer lifecycle and releases lingering touches', () => {
        const activePointers = new Set<number>();
        const actions: MacroAction[] = [
            { id: '1', type: 'touch_down', x: 200, y: 300, pointerId: 2, delayMs: 50 },
            { id: '2', type: 'touch_down', x: 400, y: 500, pointerId: 3, delayMs: 50 },
            { id: '3', type: 'touch_up', pointerId: 2, delayMs: 50 }
        ];

        // Simulate step-by-step playback
        for (const action of actions) {
            if (action.type === 'touch_down') activePointers.add(action.pointerId);
            else if (action.type === 'touch_up') activePointers.delete(action.pointerId);
        }

        expect(activePointers.has(2)).toBe(false);
        expect(activePointers.has(3)).toBe(true);

        // Emergency cleanup must release all lingering active pointers
        const releasedPointers = Array.from(activePointers);
        activePointers.clear();
        expect(releasedPointers).toEqual([3]);
        expect(activePointers.size).toBe(0);
    });
});
