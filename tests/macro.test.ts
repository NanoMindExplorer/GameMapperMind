import { describe, it, expect } from 'vitest';
import { MacroService } from '../src/services/MacroService';
import { MacroProfile } from '../src/types/macro';

describe('Macro Service Tests', () => {
    it('executes a macro sequentially', async () => {
        let touchCount = 0;
        const profile: MacroProfile = {
            id: '1', name: 'Test', triggerKey: 'A', playbackSpeed: 1, actions: [
                { id: 'a', type: 'touch_down', pointerId: 1 },
                { id: 'b', type: 'delay', delayMs: 10, pointerId: 1 },
                { id: 'c', type: 'touch_up', pointerId: 1 }
            ]
        };
        await MacroService.executeMacro(profile, () => { touchCount++ });
        expect(touchCount).toBe(2);
    });

    it('cancels an active macro', async () => {
        let count = 0;
        const profile: MacroProfile = {
            id: '2', name: 'Test2', triggerKey: 'B', playbackSpeed: 1, actions: [
                { id: 'a', type: 'delay', delayMs: 50, pointerId: 1 },
                { id: 'b', type: 'touch_down', pointerId: 1 }
            ]
        };
        const p = MacroService.executeMacro(profile, () => { count++ });
        MacroService.cancelMacro('2');
        await p;
        expect(count).toBe(0);
    });

    it('prevents concurrent execution of same macro', async () => {
        let count = 0;
        const profile: MacroProfile = {
            id: '3', name: 'Test3', triggerKey: 'C', playbackSpeed: 1, actions: [
                { id: 'a', type: 'delay', delayMs: 30, pointerId: 1 },
                { id: 'b', type: 'touch_down', pointerId: 1 }
            ]
        };
        MacroService.executeMacro(profile, () => { count++ });
        MacroService.executeMacro(profile, () => { count++ });
        await new Promise(r => setTimeout(r, 60));
        expect(count).toBe(1);
    });
});
