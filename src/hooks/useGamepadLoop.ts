import { useEffect, useRef, useCallback } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

interface PointerState {
  id: number;
  isActive: boolean;
  type: 'analog' | 'button';
  virtualKey?: string;
}

interface SmoothingState {
  lx: number;
  ly: number;
  rx: number;
  ry: number;
}

interface TriggerState {
  l2Pressed: boolean;
  r2Pressed: boolean;
}

// Callback type untuk report active state ke parent
export interface GamepadActiveState {
  keys: string[];
  axes: { lx: number; ly: number; rx: number; ry: number };
}

interface UseGamepadLoopOptions {
  onActiveStateChange?: (state: GamepadActiveState) => void;
}

const TRIGGER_PRESS_THRESHOLD = 0.3;
const TRIGGER_RELEASE_THRESHOLD = 0.15;
const DEFAULT_DEADZONE_INNER = 0.15;
const DEFAULT_DEADZONE_OUTER = 0.95;
const DEFAULT_MAX_RADIUS_PX = 150;

// FIX BUG-C04: Tambah pointer pool menjadi 20 slot (2 analog + 18 button)
const MAX_BUTTON_POINTERS = 18;

function applyRadialDeadzone(
  x: number,
  y: number,
  innerDeadzone: number = DEFAULT_DEADZONE_INNER,
  outerSaturation: number = DEFAULT_DEADZONE_OUTER
): [number, number] {
  if (x === 0 && y === 0) return [0, 0];
  const magnitude = Math.sqrt(x * x + y * y);
  if (magnitude < innerDeadzone) return [0, 0];
  if (magnitude >= outerSaturation) {
    return [x / magnitude, y / magnitude];
  }
  const remappedMag = (magnitude - innerDeadzone) / (outerSaturation - innerDeadzone);
  const scale = remappedMag / magnitude;
  return [x * scale, y * scale];
}

function applyExponentialSmoothing(
  current: number,
  previous: number,
  smoothing: number
): number {
  const alpha = 1 - smoothing;
  return alpha * current + (1 - alpha) * previous;
}

function applyAntiBanRandomization(
  x: number,
  y: number,
  antiBanEnabled: boolean
): [number, number] {
  if (!antiBanEnabled) return [Math.round(x), Math.round(y)];
  const u1 = Math.max(1e-10, Math.random());
  const u2 = Math.random();
  const z1 = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  const z2 = Math.sqrt(-2 * Math.log(u1)) * Math.sin(2 * Math.PI * u2);
  const sigma = 1.5;
  const offsetX = Math.max(-3, Math.min(3, z1 * sigma));
  const offsetY = Math.max(-3, Math.min(3, z2 * sigma));
  return [Math.round(x + offsetX), Math.round(y + offsetY)];
}

function getMaxRadiusFromMapping(mapping: any): number {
  if (mapping.width && mapping.width > 0) return mapping.width / 2;
  if (mapping.height && mapping.height > 0) return mapping.height / 2;
  return DEFAULT_MAX_RADIUS_PX;
}

export function useGamepadLoop(
  mapProfile: GamepadProfile | null, 
  active: boolean,
  options?: UseGamepadLoopOptions
) {
  const lastState = useRef<Record<string, boolean>>({});
  
  // FIX BUG-C04: Dynamic pointer pool dengan 20 slot
  const pointers = useRef<PointerState[]>([
    { id: 0, isActive: false, type: 'analog' },
    { id: 1, isActive: false, type: 'analog' },
    ...Array.from({ length: MAX_BUTTON_POINTERS }, (_, i) => ({ 
      id: i + 2, 
      isActive: false, 
      type: 'button' as const 
    }))
  ]);

  const mapProfileRef = useRef(mapProfile);
  useEffect(() => {
    mapProfileRef.current = mapProfile;
  }, [mapProfile]);

  const smoothingState = useRef<SmoothingState>({ lx: 0, ly: 0, rx: 0, ry: 0 });
  const triggerState = useRef<TriggerState>({ l2Pressed: false, r2Pressed: false });
  const latestAxesRef = useRef<number[] | null>(null);
  
  // FIX BUG-C03: Processing lock untuk mencegah race condition
  const isProcessingRef = useRef(false);
  const pendingAxesRef = useRef<number[] | null>(null);

  // FIX BUG-C01: Callback ref untuk report active state
  const onActiveStateChangeRef = useRef(options?.onActiveStateChange);
  useEffect(() => {
    onActiveStateChangeRef.current = options?.onActiveStateChange;
  }, [options?.onActiveStateChange]);

  // FIX BUG-C07: Gunakan ref untuk mapProfile di dependency array
  const activeRef = useRef(active);
  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  // FIX BUG-C01: Helper untuk report active state ke parent
  const reportActiveState = useCallback(() => {
    if (!onActiveStateChangeRef.current) return;
    
    const activeKeys: string[] = [];
    for (const pointer of pointers.current) {
      if (pointer.isActive && pointer.virtualKey) {
        activeKeys.push(pointer.virtualKey);
      }
    }
    
    onActiveStateChangeRef.current({
      keys: activeKeys,
      axes: {
        lx: smoothingState.current.lx,
        ly: smoothingState.current.ly,
        rx: smoothingState.current.rx,
        ry: smoothingState.current.ry
      }
    });
  }, []);

  useEffect(() => {
    if (!active) return;

    let buttonListener: any;
    let axisListener: any;
    let isCleanedUp = false;
    let rafId: number | null = null;

    // FIX BUG-C03: Process axes dengan lock mechanism
    const processAxesBatch = async () => {
      // Prevent concurrent processing
      if (isProcessingRef.current) {
        // Queue untuk diproses setelah selesai
        pendingAxesRef.current = latestAxesRef.current;
        return;
      }

      isProcessingRef.current = true;
      rafId = null;

      try {
        const axes = latestAxesRef.current;
        if (axes === null) {
          isProcessingRef.current = false;
          return;
        }

        latestAxesRef.current = null;
        const currentProfile = mapProfileRef.current;
        if (!currentProfile) {
          isProcessingRef.current = false;
          return;
        }

        const lx = axes[0] || 0;
        const ly = axes[1] || 0;
        const rx = axes[2] || 0;
        const ry = axes[3] || 0;
        const l2Analog = axes[4] ?? -1;
        const r2Analog = axes[5] ?? -1;

        const innerDeadzone = currentProfile.deadzone ?? DEFAULT_DEADZONE_INNER;
        const outerSaturation = DEFAULT_DEADZONE_OUTER;
        const smoothingFactor = currentProfile.smoothing ?? 0.5;

        smoothingState.current.lx = applyExponentialSmoothing(lx, smoothingState.current.lx, smoothingFactor);
        smoothingState.current.ly = applyExponentialSmoothing(ly, smoothingState.current.ly, smoothingFactor);
        smoothingState.current.rx = applyExponentialSmoothing(rx, smoothingState.current.rx, smoothingFactor);
        smoothingState.current.ry = applyExponentialSmoothing(ry, smoothingState.current.ry, smoothingFactor);

        const [adjLx, adjLy] = applyRadialDeadzone(
          smoothingState.current.lx, smoothingState.current.ly,
          innerDeadzone, outerSaturation
        );

        const [adjRx, adjRy] = applyRadialDeadzone(
          smoothingState.current.rx, smoothingState.current.ry,
          innerDeadzone, outerSaturation
        );

        // Left Stick
        const lMag = Math.sqrt(adjLx * adjLx + adjLy * adjLy);
        const lMapping = currentProfile.buttons?.find(b => b.mappedKey === 'L_STICK');
        const leftPointer = pointers.current.find(p => p.id === 0)!;

        if (lMapping) {
          const { x: lCenterX, y: lCenterY } = getScreenCoords(lMapping.x, lMapping.y);
          const maxRadius = getMaxRadiusFromMapping(lMapping);

          if (lMag > 0) {
            const targetX = lCenterX + (adjLx * maxRadius);
            const targetY = lCenterY + (adjLy * maxRadius);
            const antiBan = currentProfile.antiBanEnabled === true;
            const [finalX, finalY] = applyAntiBanRandomization(targetX, targetY, antiBan);

            if (!leftPointer.isActive) {
              leftPointer.isActive = true;
              const [downX, downY] = applyAntiBanRandomization(lCenterX, lCenterY, antiBan);
              await TouchInjection.touchDown({ pointerId: 0, x: downX, y: downY });
            }
            await TouchInjection.touchMove({ pointerId: 0, x: finalX, y: finalY });
          } else if (leftPointer.isActive) {
            leftPointer.isActive = false;
            await TouchInjection.touchUp({ pointerId: 0 });
          }
        }

        // Right Stick
        const rMag = Math.sqrt(adjRx * adjRx + adjRy * adjRy);
        const rMapping = currentProfile.buttons?.find(b => b.mappedKey === 'R_STICK');
        const rightPointer = pointers.current.find(p => p.id === 1)!;

        if (rMapping) {
          const { x: rCenterX, y: rCenterY } = getScreenCoords(rMapping.x, rMapping.y);
          const maxRadius = getMaxRadiusFromMapping(rMapping);

          if (rMag > 0) {
            const targetX = rCenterX + (adjRx * maxRadius);
            const targetY = rCenterY + (adjRy * maxRadius);
            const antiBan = currentProfile.antiBanEnabled === true;
            const [finalX, finalY] = applyAntiBanRandomization(targetX, targetY, antiBan);

            if (!rightPointer.isActive) {
              rightPointer.isActive = true;
              const [downX, downY] = applyAntiBanRandomization(rCenterX, rCenterY, antiBan);
              await TouchInjection.touchDown({ pointerId: 1, x: downX, y: downY });
            }
            await TouchInjection.touchMove({ pointerId: 1, x: finalX, y: finalY });
          } else if (rightPointer.isActive) {
            rightPointer.isActive = false;
            await TouchInjection.touchUp({ pointerId: 1 });
          }
        }

        // L2
        const mapL2 = currentProfile.buttons?.find((m: any) => m.mappedKey === 'LT');
        if (mapL2 && mapL2.x !== undefined && mapL2.y !== undefined) {
          if (!triggerState.current.l2Pressed && l2Analog >= TRIGGER_PRESS_THRESHOLD) {
            triggerState.current.l2Pressed = true;
            const p = pointers.current.find(c => !c.isActive && c.type === 'button');
            if (p) {
              p.isActive = true; p.virtualKey = 'LT_ANALOG';
              const { x: rawX, y: rawY } = getScreenCoords(mapL2.x, mapL2.y);
              const antiBan = currentProfile.antiBanEnabled === true;
              const [x, y] = applyAntiBanRandomization(rawX, rawY, antiBan);
              await TouchInjection.touchDown({ pointerId: p.id, x, y });
            }
          } else if (triggerState.current.l2Pressed && l2Analog < TRIGGER_RELEASE_THRESHOLD) {
            triggerState.current.l2Pressed = false;
            const p = pointers.current.find(c => c.isActive && c.virtualKey === 'LT_ANALOG');
            if (p) {
              p.isActive = false; p.virtualKey = undefined;
              await TouchInjection.touchUp({ pointerId: p.id });
            }
          }
          lastState.current['LT_ANALOG'] = triggerState.current.l2Pressed;
        }

        // R2
        const mapR2 = currentProfile.buttons?.find((m: any) => m.mappedKey === 'RT');
        if (mapR2 && mapR2.x !== undefined && mapR2.y !== undefined) {
          if (!triggerState.current.r2Pressed && r2Analog >= TRIGGER_PRESS_THRESHOLD) {
            triggerState.current.r2Pressed = true;
            const p = pointers.current.find(c => !c.isActive && c.type === 'button');
            if (p) {
              p.isActive = true; p.virtualKey = 'RT_ANALOG';
              const { x: rawX, y: rawY } = getScreenCoords(mapR2.x, mapR2.y);
              const antiBan = currentProfile.antiBanEnabled === true;
              const [x, y] = applyAntiBanRandomization(rawX, rawY, antiBan);
              await TouchInjection.touchDown({ pointerId: p.id, x, y });
            }
          } else if (triggerState.current.r2Pressed && r2Analog < TRIGGER_RELEASE_THRESHOLD) {
            triggerState.current.r2Pressed = false;
            const p = pointers.current.find(c => c.isActive && c.virtualKey === 'RT_ANALOG');
            if (p) {
              p.isActive = false; p.virtualKey = undefined;
              await TouchInjection.touchUp({ pointerId: p.id });
            }
          }
          lastState.current['RT_ANALOG'] = triggerState.current.r2Pressed;
        }

        // FIX BUG-C01: Report active state setelah processing
        reportActiveState();

      } catch (err) {
        console.error('[useGamepadLoop] processAxesBatch error:', err);
      } finally {
        isProcessingRef.current = false;
        
        // Process pending axes jika ada
        if (pendingAxesRef.current !== null) {
          latestAxesRef.current = pendingAxesRef.current;
          pendingAxesRef.current = null;
          scheduleAxisBatch();
        }
      }
    };

    const scheduleAxisBatch = () => {
      if (rafId === null) {
        rafId = window.requestAnimationFrame(() => {
          processAxesBatch().catch((e) => {
            console.error('[useGamepadLoop] rAF axis batch failed', e);
          });
        });
      }
    };

    // FIX BUG-C06: Gunakan window.innerWidth/Height untuk multi-window support
    const getScreenCoords = (pctX: number, pctY: number) => {
      const w = window.innerWidth;
      const h = window.innerHeight;
      return {
        x: Math.round((pctX / 100) * w),
        y: Math.round((pctY / 100) * h)
      };
    };

    const resetAllPointers = async () => {
      for (const pointer of pointers.current) {
        if (pointer.isActive) {
          try {
            await TouchInjection.touchUp({ pointerId: pointer.id });
          } catch (e) {
            console.error('[useGamepadLoop] Failed to release pointer', pointer.id, e);
          }
          pointer.isActive = false;
          pointer.virtualKey = undefined;
        }
      }
      triggerState.current.l2Pressed = false;
      triggerState.current.r2Pressed = false;
      lastState.current = {};
      
      // FIX BUG-C01: Reset active state
      reportActiveState();
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
            reportActiveState();
            return;
          }

          const wasPressed = lastState.current[buttonName];
          const antiBan = currentProfile.antiBanEnabled === true;
          const { x: rawX, y: rawY } = getScreenCoords(mapping.x, mapping.y);
          const [x, y] = applyAntiBanRandomization(rawX, rawY, antiBan);

          const buttonType = mapping.type || 'button';

          if (buttonType === 'swipe' && isPressed && !wasPressed) {
            const direction = mapping.swipeDirection || 'UP';
            const duration = mapping.swipeDuration || 100;
            const swipeDistance = 100;

            let targetX = x;
            let targetY = y;
            switch (direction) {
              case 'UP': targetY = y - swipeDistance; break;
              case 'DOWN': targetY = y + swipeDistance; break;
              case 'LEFT': targetX = x - swipeDistance; break;
              case 'RIGHT': targetX = x + swipeDistance; break;
            }

            const pointer = pointers.current.find(p => !p.isActive && p.type === 'button');
            if (pointer) {
              pointer.isActive = true;
              pointer.virtualKey = buttonName;
              reportActiveState();
              
              try {
                await TouchInjection.touchDown({ pointerId: pointer.id, x, y });
                const steps = 5;
                for (let i = 1; i <= steps; i++) {
                  const interpX = x + (targetX - x) * (i / steps);
                  const interpY = y + (targetY - y) * (i / steps);
                  await TouchInjection.touchMove({ pointerId: pointer.id, x: Math.round(interpX), y: Math.round(interpY) });
                  await new Promise(r => setTimeout(r, duration / steps));
                }
                await TouchInjection.touchUp({ pointerId: pointer.id });
                pointer.isActive = false;
                pointer.virtualKey = undefined;
              } catch (e) {
                console.error('[useGamepadLoop] Swipe failed', e);
                pointer.isActive = false;
                pointer.virtualKey = undefined;
              }
              reportActiveState();
            }
          } else if (buttonType === 'dpad') {
            if (isPressed && !wasPressed) {
              const pointer = pointers.current.find(p => !p.isActive && p.type === 'button');
              if (pointer) {
                pointer.isActive = true;
                pointer.virtualKey = buttonName;
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
          } else {
            if (isPressed && !wasPressed) {
              const pointer = pointers.current.find(p => !p.isActive && p.type === 'button');
              if (pointer) {
                pointer.isActive = true;
                pointer.virtualKey = buttonName;
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
          }

          lastState.current[buttonName] = isPressed;
          reportActiveState();
        });

        if (isCleanedUp) {
          buttonListener?.remove();
          return;
        }

        axisListener = await TouchInjection.addListener('onGamepadAxis', ({ axes }) => {
          latestAxesRef.current = axes;
          scheduleAxisBatch();
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

      if (rafId !== null) {
        window.cancelAnimationFrame(rafId);
        rafId = null;
      }

      resetAllPointers().catch((e) => {
        console.error('[useGamepadLoop] Failed to reset pointers on cleanup', e);
      });

      TouchInjection.stopGamepadListener().catch(() => {});
      TouchInjection.unbindService().catch(() => {});
    };
  // FIX BUG-C07: Hanya gunakan activeRef.current di dependency
  }, [active, reportActiveState]);
}
