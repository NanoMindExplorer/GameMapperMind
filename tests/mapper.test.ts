import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NativeGamepadMapper } from '../src/utils/mapper';
import TouchInjection from '../src/plugins/TouchInjection';

vi.mock('../src/plugins/TouchInjection', () => {
    return {
        default: {
            touchDown: vi.fn(),
            touchMove: vi.fn(),
            touchUp: vi.fn(),
        }
    };
});

describe('Gamepad Mapper Tests', () => {
    let mapper: NativeGamepadMapper;

    beforeEach(() => {
        mapper = new NativeGamepadMapper();
        vi.clearAllMocks();
    });

    it('Test 1: handleButton dengan mapping valid', async () => {
        const mapping = { x: 100, y: 200 };
        await mapper.handleButton('A', true, mapping, false);
        expect(TouchInjection.touchDown).toHaveBeenCalledWith({ pointerId: 10, x: 100, y: 200 });
    });

    it('Test 2: handleButton dengan mapping null', async () => {
        await mapper.handleButton('UNKNOWN', true, null, false);
        expect(TouchInjection.touchDown).not.toHaveBeenCalled();
    });

    it('Test 3: handleButton dengan antiBanEnabled=true', async () => {
        const mapping = { x: 100, y: 200 };
        await mapper.handleButton('B', true, mapping, true);
        expect(TouchInjection.touchDown).toHaveBeenCalled();
        const callArgs = vi.mocked(TouchInjection.touchDown).mock.calls[0][0];
        // Coordinate should not be exactly 100, 200
        expect(callArgs.x !== 100 || callArgs.y !== 200).toBe(true);
    });

    it('Test 4: handleAxes dengan stick di deadzone (magnitude < 0.15)', async () => {
        await mapper.handleAxes(0.1, 500, 500);
        expect(TouchInjection.touchDown).not.toHaveBeenCalled();
        expect(TouchInjection.touchMove).not.toHaveBeenCalled();
    });

    it('Test 5: handleAxes dengan stick di luar deadzone (magnitude = 0.5)', async () => {
        await mapper.handleAxes(0.5, 500, 500);
        expect(TouchInjection.touchMove).toHaveBeenCalledWith({ pointerId: 11, x: 505, y: 505 });
    });
});
