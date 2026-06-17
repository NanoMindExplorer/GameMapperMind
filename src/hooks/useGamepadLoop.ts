import { useEffect, useRef } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { getButtonMappingForMode, detectControllerMode } from '../defaults';
import type { ControllerMode } from '../types';

interface PointerState {
  id: number;
  isActive: boolean;
  type: 'analog' | 'button';
  virtualKey?: string;
}

// ============================================================
// useGamepadLoop — listens to native GamepadListenerService events
// (emitted via TouchInjectionPlugin) and translates them to
// touch injection calls. Activated when Shizuku is connected.
// ============================================================
export function useGamepadLoop(mapProfile: any, active: boolean) {
  const lastState = useRef<Record<string, boolean>>({});
  const pointers = useRef<PointerState[]>([
    { id: 0, isActive: false, type: 'analog' }, // Reserved for left stick
    { id: 1, isActive: false, type: 'analog' }, // Reserved for right stick
    ...Array.from({ length: 8 }, (_, i) => ({ id: i + 2, isActive: false, type: 'button' as const }))
  ]);

  // Controller mode — determines button mapping (Xbox vs Switch).
  // Detected from the synthetic "CONTROLLER_ID:<name>" event emitted by
  // GamepadListenerService when it sees an evdev "name:" line.
  const controllerModeRef = useRef<ControllerMode>('XBOX');
  const detectedButtonMappingRef = useRef<string[]>(getButtonMappingForMode('XBOX'));

  // Gyro state
  const gyroLastEmitRef = useRef<number>(0);
  const gyroSmoothedRef = useRef<{ x: number; y: number; z: number }>({ x: 0, y: 0, z: 0 });

  // ============================================================
  // Push anti-ban config to native daemon whenever profile changes
  // ============================================================
  useEffect(() => {
    if (!active) return;
    const cfg = mapProfile?.antiBanConfig;
    const enabled = !!mapProfile?.antiBanEnabled && !!cfg?.enabled;
    if (cfg) {
      TouchInjection.setAntiBanConfig({
        enabled,
        coordinateJitter: cfg.coordinateJitter ?? 4,
        timingJitter: cfg.timingJitter ?? 3,
        pressureVariance: cfg.pressureVariance ?? 0.15,
        sizeVariance: cfg.sizeVariance ?? 0.10,
        strokeDurationJitter: cfg.strokeDurationJitter ?? 12,
        microPauseProbability: cfg.microPauseProbability ?? 0.02,
        microPauseMaxMs: cfg.microPauseMaxMs ?? 45,
      }).catch((e: any) => console.warn('setAntiBanConfig failed', e));
    }
  }, [mapProfile, active]);

  useEffect(() => {
    if (!active) return;

    let buttonListener: any;
    let axisListener: any;
    let gyroListener: any;

    const setupListeners = async () => {
      await TouchInjection.bindService().catch(() => {});
      await TouchInjection.startGamepadListener().catch(() => {});

      buttonListener = await TouchInjection.addListener('onGamepadButton', async ({ buttonName, value }) => {
        // ============================================================
        // Handle controller-id detection event — emitted when the
        // GamepadListenerService sees a new "name:" line in getevent.
        // ============================================================
        if (buttonName.startsWith('CONTROLLER_ID:')) {
          const id = buttonName.substring('CONTROLLER_ID:'.length);
          const { mode } = detectControllerMode(id);
          controllerModeRef.current = mode;
          detectedButtonMappingRef.current = getButtonMappingForMode(mode);
          console.log('[useGamepadLoop] Controller detected:', id, '→ mode:', mode);
          return;
        }

        // Handle MODE button press (Xbox Guide) — cycle mode on dual-mode controllers
        if (buttonName === 'MODE') {
          // Vortex XP107 toggles via physical switch; we just log here.
          console.log('[useGamepadLoop] MODE button pressed (controller mode toggle)');
          return;
        }

        const isPressed = value === 1;

        // Normalize button names to match profile hardwareKey format
        // Evdev may emit LB/RB, but profiles use L1/R1 (or vice versa)
        // Also handle LT/RT → L2/R2 (already fixed in evdev, but keep for safety)
        const buttonAliases: Record<string, string> = {
          'LB': 'L1', 'RB': 'R1',
          'LT': 'L2', 'RT': 'R2',
          'L1': 'LB', 'R1': 'RB',
          'L2': 'LT', 'R2': 'RT',
        };
        // Try original name first, then alias
        let mapping = mapProfile?.mappings?.find((m: any) => m.hardwareKey === buttonName);
        if (!mapping && buttonAliases[buttonName]) {
          mapping = mapProfile?.mappings?.find((m: any) => m.hardwareKey === buttonAliases[buttonName]);
        }
        // Also try with BUTTON_ prefix
        if (!mapping) {
          mapping = mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'BUTTON_' + buttonName);
        }

        if (!mapping || !mapping.x || !mapping.y) {
          lastState.current[buttonName] = isPressed;
          return;
        }

        const wasPressed = lastState.current[buttonName];

        if (isPressed && !wasPressed) {
          const pointer = pointers.current.find(p => !p.isActive && p.type === 'button');
          if (pointer) {
            pointer.isActive = true;
            pointer.virtualKey = buttonName;
            await TouchInjection.touchDown({ pointerId: pointer.id, x: mapping.x, y: mapping.y });
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

      axisListener = await TouchInjection.addListener('onGamepadAxis', async ({ axes }) => {
        const lx = axes[0] || 0;
        const ly = axes[1] || 0;
        const rx = axes[2] || 0;
        const ry = axes[3] || 0;
        const l2Analog = axes[4] ?? -1;
        const r2Analog = axes[5] ?? -1;

        const deadzone = mapProfile?.deadzone ?? 0.15;

        // ============================================================
        // 1. Left Stick (Pointer 0) — uses leftJoystick config from profile
        // ============================================================
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

        // ============================================================
        // 2. Right Stick (Pointer 1) — uses rightJoystick config
        // ============================================================
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

        // ============================================================
        // 3. L2 (Analog to Button)
        // ============================================================
        const mapL2 = mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'L2') || mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'LT');
        if (mapL2 && mapL2.x && mapL2.y) {
           const isL2Pressed = l2Analog > 0.0;
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

        // ============================================================
        // 4. R2 (Analog to Button)
        // ============================================================
        const mapR2 = mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'R2') || mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'RT');
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

      // ============================================================
      // Gyroscope listener — convert rotational rate to camera swipe
      // ============================================================
      gyroListener = await TouchInjection.addListener('onGyroData', async ({ x, y, z, timestamp }) => {
        const gyroCfg = mapProfile?.gyroMapping;
        if (!gyroCfg?.enabled) return;

        // Throttle to ~60Hz max
        if (timestamp - gyroLastEmitRef.current < 16) return;
        gyroLastEmitRef.current = timestamp;

        // Apply deadzone
        const deadzone = gyroCfg.deadzone ?? 0.05;
        let gx = Math.abs(x) > deadzone ? x : 0;
        let gy = Math.abs(y) > deadzone ? y : 0;
        let gz = Math.abs(z) > deadzone ? z : 0;

        // Apply inversion
        if (gyroCfg.invertX) gx = -gx;
        if (gyroCfg.invertY) gy = -gy;

        // Exponential smoothing
        const smooth = gyroCfg.smoothing ?? 0.3;
        gyroSmoothedRef.current.x = gyroSmoothedRef.current.x * smooth + gx * (1 - smooth);
        gyroSmoothedRef.current.y = gyroSmoothedRef.current.y * smooth + gy * (1 - smooth);
        gyroSmoothedRef.current.z = gyroSmoothedRef.current.z * smooth + gz * (1 - smooth);

        // Convert rad/s → pixel offset using sensitivity
        const sensX = gyroCfg.sensitivityX ?? 800;
        const sensY = gyroCfg.sensitivityY ?? 600;
        const dx = gyroSmoothedRef.current.y * sensX / 60;  // /60 because ~60Hz
        const dy = gyroSmoothedRef.current.x * sensY / 60;

        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;

        // Inject as right-stick move (camera control)
        const tx = gyroCfg.targetX + dx;
        const ty = gyroCfg.targetY + dy;
        const rightPointer = pointers.current.find(p => p.id === 1)!;
        if (!rightPointer.isActive) {
          rightPointer.isActive = true;
          await TouchInjection.touchDown({ pointerId: 1, x: gyroCfg.targetX, y: gyroCfg.targetY });
        }
        await TouchInjection.touchMove({ pointerId: 1, x: tx, y: ty });
      });
    };

    setupListeners();

    return () => {
      buttonListener?.remove();
      axisListener?.remove();
      gyroListener?.remove();
    };
  }, [mapProfile, active]);
}
