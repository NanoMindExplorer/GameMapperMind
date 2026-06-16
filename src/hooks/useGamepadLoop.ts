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
        // Left stick
        const lx = axes[0] || 0;
        const ly = axes[1] || 0;
        // Right stick
        const rx = axes[2] || 0;
        const ry = axes[3] || 0;
        // L2 / R2 (analog triggers, often axes 4/5 or similar)
        const l2Analog = axes[4] ?? -1;
        const r2Analog = axes[5] ?? -1;
        
        const deadzone = 0.15;
        
        // 1. Left Stick (Pointer 0)
        const lMag = Math.sqrt(lx * lx + ly * ly);
        const lCenterX = mapProfile?.leftJoystick?.centerX ?? 250;
        const lCenterY = mapProfile?.leftJoystick?.centerY ?? 500;
        const lRadius = mapProfile?.leftJoystick?.radius ?? 150;
        const leftPointer = pointers.current.find(p => p.id === 0)!;

        if (lMag > deadzone) {
          const targetX = lCenterX + (lx * lRadius);
          const targetY = lCenterY + (ly * lRadius);
          if (!leftPointer.isActive) {
            leftPointer.isActive = true;
            await TouchInjection.touchDown({ pointerId: 0, x: lCenterX, y: lCenterY });
          }
          await TouchInjection.touchMove({ pointerId: 0, x: targetX, y: targetY });
        } else if (leftPointer.isActive) {
          leftPointer.isActive = false;
          await TouchInjection.touchUp({ pointerId: 0 });
        }

        // 2. Right Stick (Pointer 1)
        const rMag = Math.sqrt(rx * rx + ry * ry);
        const rCenterX = mapProfile?.rightJoystick?.centerX ?? 700;
        const rCenterY = mapProfile?.rightJoystick?.centerY ?? 500;
        const rRadius = mapProfile?.rightJoystick?.radius ?? 150;
        const rightPointer = pointers.current.find(p => p.id === 1)!;

        if (rMag > deadzone) {
          const targetX = rCenterX + (rx * rRadius);
          const targetY = rCenterY + (ry * rRadius);
          if (!rightPointer.isActive) {
            rightPointer.isActive = true;
            await TouchInjection.touchDown({ pointerId: 1, x: rCenterX, y: rCenterY });
          }
          await TouchInjection.touchMove({ pointerId: 1, x: targetX, y: targetY });
        } else if (rightPointer.isActive) {
          rightPointer.isActive = false;
          await TouchInjection.touchUp({ pointerId: 1 });
        }
        
        // 3. L2 (Analog to Button emulation, using remaining pointer if mapped)
        const mapL2 = mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'L2');
        if (mapL2 && mapL2.x && mapL2.y) {
           const isL2Pressed = l2Analog > 0.0; // Triggered if slightly pressed
           const wasL2Pressed = lastState.current['L2_ANALOG'];
           if (isL2Pressed && !wasL2Pressed) {
               const p = pointers.current.find(c => !c.isActive && c.type === 'button');
               if (p) {
                   p.isActive = true; p.virtualKey = 'L2_ANALOG';
                   await TouchInjection.touchDown({ pointerId: p.id, x: mapL2.x, y: mapL2.y });
               }
           } else if (!isL2Pressed && wasL2Pressed) {
               const p = pointers.current.find(c => c.isActive && c.virtualKey === 'L2_ANALOG');
               if (p) {
                   p.isActive = false; p.virtualKey = undefined;
                   await TouchInjection.touchUp({ pointerId: p.id });
               }
           }
           lastState.current['L2_ANALOG'] = isL2Pressed;
        }

        // 4. R2 (Analog to Button emulation)
        const mapR2 = mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'R2');
        if (mapR2 && mapR2.x && mapR2.y) {
           const isR2Pressed = r2Analog > 0.0;
           const wasR2Pressed = lastState.current['R2_ANALOG'];
           if (isR2Pressed && !wasR2Pressed) {
               const p = pointers.current.find(c => !c.isActive && c.type === 'button');
               if (p) {
                   p.isActive = true; p.virtualKey = 'R2_ANALOG';
                   await TouchInjection.touchDown({ pointerId: p.id, x: mapR2.x, y: mapR2.y });
               }
           } else if (!isR2Pressed && wasR2Pressed) {
               const p = pointers.current.find(c => c.isActive && c.virtualKey === 'R2_ANALOG');
               if (p) {
                   p.isActive = false; p.virtualKey = undefined;
                   await TouchInjection.touchUp({ pointerId: p.id });
               }
           }
           lastState.current['R2_ANALOG'] = isR2Pressed;
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
