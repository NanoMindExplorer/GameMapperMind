import { useEffect, useRef } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

interface PointerState {
  id: number;
  isActive: boolean;
  type: 'analog' | 'button';
  virtualKey?: string;
}

export function useGamepadLoop(mapProfile: GamepadProfile | null, active: boolean) {
  const lastState = useRef<Record<string, boolean>>({});
  const pointers = useRef<PointerState[]>([
    { id: 0, isActive: false, type: 'analog' }, // Reserved for left stick
    { id: 1, isActive: false, type: 'analog' }, // Reserved for right stick
    // IDs 2-9 for buttons
    ...Array.from({ length: 8 }, (_, i) => ({ id: i + 2, isActive: false, type: 'button' as const }))
  ]);

  const mapProfileRef = useRef(mapProfile);
  useEffect(() => {
    mapProfileRef.current = mapProfile;
  }, [mapProfile]);

  useEffect(() => {
    if (!active) return;
    let buttonListener: any;
    let axisListener: any;
    let isCleanedUp = false;

    const getScreenCoords = (pctX: number, pctY: number) => {
        const sw = Math.max(window.screen.width, window.screen.height);
        const sh = Math.min(window.screen.width, window.screen.height);
        return {
            x: Math.round((pctX / 100) * sw),
            y: Math.round((pctY / 100) * sh)
        };
    };

    const setupListeners = async () => {
      try {
        await TouchInjection.bindService().catch(() => {});
        await TouchInjection.startGamepadListener().catch(() => {});

        if (isCleanedUp) return;

        buttonListener = await TouchInjection.addListener('onGamepadButton', async ({ buttonName, value }) => {
          const currentProfile = mapProfileRef.current;
          if (!currentProfile) return;
          const isPressed = value === 1;
          const mapping = currentProfile.buttons?.find((m: any) => m.mappedKey === buttonName);
          
          if (!mapping || mapping.x === undefined || mapping.y === undefined) {
             lastState.current[buttonName] = isPressed;
             return;
          }

          const wasPressed = lastState.current[buttonName];

          if (isPressed && !wasPressed) {
            const pointer = pointers.current.find(p => !p.isActive && p.type === 'button');
            if (pointer) {
              pointer.isActive = true;
              pointer.virtualKey = buttonName;
              const { x, y } = getScreenCoords(mapping.x, mapping.y);
              await TouchInjection.touchDown({ pointerId: pointer.id, x, y });
            }
          } else if (!isPressed && wasPressed) {
            const pointer = pointers.current.find(p => p.isActive && p.type === 'button' && p.virtualKey === buttonName);
            if (pointer) {
              pointer.isActive = false;
              pointer.virtualKey = undefined;
              await TouchInjection.touchUp({ pointerId: pointer.id });
            }
          }

          lastState.current[buttonName] = isPressed;
        });

        if (isCleanedUp) {
          buttonListener?.remove();
          return;
        }

        axisListener = await TouchInjection.addListener('onGamepadAxis', async ({ axes }) => {
          const currentProfile = mapProfileRef.current;
          if (!currentProfile) return;

          // Left stick
          const lx = axes[0] || 0;
          const ly = axes[1] || 0;
          // Right stick
          const rx = axes[2] || 0;
          const ry = axes[3] || 0;
          // L2 / R2 (analog triggers)
          const l2Analog = axes[4] ?? -1;
          const r2Analog = axes[5] ?? -1;
          
          const deadzone = 0.15;
          const maxRadius = 150; // pixels for analog stroke
          
          // 1. Left Stick (Pointer 0)
          const lMag = Math.sqrt(lx * lx + ly * ly);
          const lMapping = currentProfile.buttons?.find(b => b.mappedKey === 'L_STICK');
          const leftPointer = pointers.current.find(p => p.id === 0)!;

          if (lMapping) {
              const { x: lCenterX, y: lCenterY } = getScreenCoords(lMapping.x, lMapping.y);
              if (lMag > deadzone) {
                const targetX = lCenterX + (lx * maxRadius);
                const targetY = lCenterY + (ly * maxRadius);
                if (!leftPointer.isActive) {
                  leftPointer.isActive = true;
                  await TouchInjection.touchDown({ pointerId: 0, x: lCenterX, y: lCenterY });
                }
                await TouchInjection.touchMove({ pointerId: 0, x: targetX, y: targetY });
              } else if (leftPointer.isActive) {
                leftPointer.isActive = false;
                await TouchInjection.touchUp({ pointerId: 0 });
              }
          }

          // 2. Right Stick (Pointer 1)
          const rMag = Math.sqrt(rx * rx + ry * ry);
          const rMapping = currentProfile.buttons?.find(b => b.mappedKey === 'R_STICK');
          const rightPointer = pointers.current.find(p => p.id === 1)!;

          if (rMapping) {
              const { x: rCenterX, y: rCenterY } = getScreenCoords(rMapping.x, rMapping.y);
              if (rMag > deadzone) {
                const targetX = rCenterX + (rx * maxRadius);
                const targetY = rCenterY + (ry * maxRadius);
                if (!rightPointer.isActive) {
                  rightPointer.isActive = true;
                  await TouchInjection.touchDown({ pointerId: 1, x: rCenterX, y: rCenterY });
                }
                await TouchInjection.touchMove({ pointerId: 1, x: targetX, y: targetY });
              } else if (rightPointer.isActive) {
                rightPointer.isActive = false;
                await TouchInjection.touchUp({ pointerId: 1 });
              }
          }
          
          // 3. L2 (Analog to Button emulation)
          const mapL2 = currentProfile.buttons?.find((m: any) => m.mappedKey === 'LT');
          if (mapL2 && mapL2.x !== undefined && mapL2.y !== undefined) {
             const isL2Pressed = l2Analog > 0.0;
             const wasL2Pressed = lastState.current['LT_ANALOG'];
             if (isL2Pressed && !wasL2Pressed) {
                 const p = pointers.current.find(c => !c.isActive && c.type === 'button');
                 if (p) {
                     p.isActive = true; p.virtualKey = 'LT_ANALOG';
                     const { x, y } = getScreenCoords(mapL2.x, mapL2.y);
                     await TouchInjection.touchDown({ pointerId: p.id, x, y });
                 }
             } else if (!isL2Pressed && wasL2Pressed) {
                 const p = pointers.current.find(c => c.isActive && c.virtualKey === 'LT_ANALOG');
                 if (p) {
                     p.isActive = false; p.virtualKey = undefined;
                     await TouchInjection.touchUp({ pointerId: p.id });
                 }
             }
             lastState.current['LT_ANALOG'] = isL2Pressed;
          }

          // 4. R2 (Analog to Button emulation)
          const mapR2 = currentProfile.buttons?.find((m: any) => m.mappedKey === 'RT');
          if (mapR2 && mapR2.x !== undefined && mapR2.y !== undefined) {
             const isR2Pressed = r2Analog > 0.0;
             const wasR2Pressed = lastState.current['RT_ANALOG'];
             if (isR2Pressed && !wasR2Pressed) {
                 const p = pointers.current.find(c => !c.isActive && c.type === 'button');
                 if (p) {
                     p.isActive = true; p.virtualKey = 'RT_ANALOG';
                     const { x, y } = getScreenCoords(mapR2.x, mapR2.y);
                     await TouchInjection.touchDown({ pointerId: p.id, x, y });
                 }
             } else if (!isR2Pressed && wasR2Pressed) {
                 const p = pointers.current.find(c => c.isActive && c.virtualKey === 'RT_ANALOG');
                 if (p) {
                     p.isActive = false; p.virtualKey = undefined;
                     await TouchInjection.touchUp({ pointerId: p.id });
                 }
             }
             lastState.current['RT_ANALOG'] = isR2Pressed;
          }
        });
      } catch (err) {
        console.error('[useGamepadLoop] Setup listeners failed:', err);
      }
    };

    setupListeners();

    return () => {
      isCleanedUp = true;
      buttonListener?.remove();
      axisListener?.remove();
    };
  }, [mapProfile, active]);
}
