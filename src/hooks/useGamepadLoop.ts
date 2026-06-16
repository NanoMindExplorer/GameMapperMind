import { useEffect, useRef } from 'react';
import TouchInjection from '../plugins/TouchInjection';

interface PointerState {
  id: number;
  isActive: boolean;
  type: 'analog' | 'button';
  virtualKey?: string;
}

export function useGamepadLoop(mapProfile: any, active: boolean) {
  const lastState = useRef<Record<string, boolean>>({});
  const pointers = useRef<PointerState[]>([
    { id: 0, isActive: false, type: 'analog' }, // Reserved for left stick
    { id: 1, isActive: false, type: 'analog' }, // Reserved for right stick
    // IDs 2-9 for buttons
    ...Array.from({ length: 8 }, (_, i) => ({ id: i + 2, isActive: false, type: 'button' as const }))
  ]);

  useEffect(() => {
    if (!active) return;

    let buttonListener: any;
    let axisListener: any;

    const setupListeners = async () => {
      // Ensure Shizuku is bound
      await TouchInjection.bindService().catch(() => {});
      await TouchInjection.startGamepadListener().catch(() => {});

      buttonListener = await TouchInjection.addListener('onGamepadButton', async ({ buttonName, value }) => {
        const isPressed = value === 1;
        const mapping = mapProfile?.mappings?.find((m: any) => m.hardwareKey === buttonName);
        
        if (!mapping || !mapping.x || !mapping.y) {
           lastState.current[buttonName] = isPressed;
           return;
        }

        const wasPressed = lastState.current[buttonName];

        if (isPressed && !wasPressed) {
          // Find free pointer
          const pointer = pointers.current.find(p => !p.isActive && p.type === 'button');
          if (pointer) {
            pointer.isActive = true;
            pointer.virtualKey = buttonName;
            await TouchInjection.touchDown({ pointerId: pointer.id, x: mapping.x, y: mapping.y });
          }
        } else if (!isPressed && wasPressed) {
          // Find matching pointer
          const pointer = pointers.current.find(p => p.isActive && p.type === 'button' && p.virtualKey === buttonName);
          if (pointer) {
            pointer.isActive = false;
            pointer.virtualKey = undefined;
            await TouchInjection.touchUp({ pointerId: pointer.id });
          }
        }

        lastState.current[buttonName] = isPressed;
      });

      axisListener = await TouchInjection.addListener('onGamepadAxis', async ({ axes }) => {
        const lx = axes[0] || 0;
        const ly = axes[1] || 0;
        const deadzone = 0.15;
        const magnitude = Math.sqrt(lx * lx + ly * ly);

        // Map config for joystick
        const centerX = mapProfile?.joystick?.centerX ?? 250;
        const centerY = mapProfile?.joystick?.centerY ?? 500;
        const radius = mapProfile?.joystick?.radius ?? 150;

        const stickPointer = pointers.current.find(p => p.id === 0)!;

        if (magnitude > deadzone) {
          const targetX = centerX + (lx * radius);
          const targetY = centerY + (ly * radius);

          if (!stickPointer.isActive) {
            stickPointer.isActive = true;
            await TouchInjection.touchDown({ pointerId: 0, x: centerX, y: centerY });
          }
          await TouchInjection.touchMove({ pointerId: 0, x: targetX, y: targetY });
        } else {
          if (stickPointer.isActive) {
            stickPointer.isActive = false;
            await TouchInjection.touchUp({ pointerId: 0 });
          }
        }
      });
    };

    setupListeners();

    return () => {
      buttonListener?.remove();
      axisListener?.remove();
    };
  }, [mapProfile, active]);
}
