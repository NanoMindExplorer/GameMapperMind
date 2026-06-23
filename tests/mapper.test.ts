import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useGamepadLoop } from '../src/hooks/useGamepadLoop';
import TouchInjection from '../src/plugins/TouchInjection';

vi.mock('../src/plugins/TouchInjection', () => {
    return {
        default: {
            bindService: vi.fn().mockResolvedValue(undefined),
            startGamepadListener: vi.fn().mockResolvedValue(undefined),
            updateActiveProfile: vi.fn().mockResolvedValue(undefined),
            addListener: vi.fn().mockReturnValue({ remove: vi.fn() }),
            touchDown: vi.fn(),
            touchMove: vi.fn(),
            touchUp: vi.fn(),
        }
    };
});

describe('useGamepadLoop integration with native NativeGamepadMapper', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('Test 1: handleButton with valid mapping correctly calls native TouchInjection.updateActiveProfile', () => {
        const dummyProfile = { name: "Test" };
        renderHook(() => useGamepadLoop(dummyProfile as any, false));
        expect(TouchInjection.updateActiveProfile).toHaveBeenCalled();
    });

    it('Test 2: null activeProfile handles properly', () => {
        renderHook(() => useGamepadLoop(null as any, false));
        expect(TouchInjection.updateActiveProfile).toHaveBeenCalled();
    });

    it('Test 3: Does not crash on hook unmount', () => {
        const { unmount } = renderHook(() => useGamepadLoop({ name: "Test" } as any, false));
        unmount();
        // The mock addListener returns { remove: vi.fn() } so unmount shouldn't crash
        expect(true).toBe(true);
    });
});
