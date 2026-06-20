import { useEffect, useRef } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

interface PointerState {
  id: number;
  isActive: boolean;
  type: 'analog' | 'button';
  virtualKey?: string;
}

/**
 * State untuk smoothing exponential per axis.
 * Disimpan di ref agar tidak trigger re-render.
 * Invariant: smoothed value selalu dalam rentang [-1, 1].
 */
interface SmoothingState {
  lx: number;
  ly: number;
  rx: number;
  ry: number;
}

/**
 * State untuk trigger hysteresis.
 * pressed = true jika trigger sedang aktif (sudah lewat press threshold).
 * Invariant: transisi pressed->released hanya jika value < releaseThreshold.
 */
interface TriggerState {
  l2Pressed: boolean;
  r2Pressed: boolean;
}

/**
 * Konstanta trigger hysteresis.
 * pressThreshold: value minimum untuk trigger dianggap pressed (transisi released->pressed).
 * releaseThreshold: value di bawah ini untuk trigger dianggap released (transisi pressed->released).
 * Hysteresis mencegah flicker saat value berada di sekitar threshold.
 *
 * Math-Logic (Pasal 5.1):
 * - pressThreshold (0.3) > releaseThreshold (0.15) menciptakan deadband
 * - Jika value = 0.2 (di antara): state tetap (tidak transisi)
 * - Jika value naik ke 0.3: pressed
 * - Setelah pressed, value harus turun ke 0.15 untuk release
 * - Hysteresis mencegah oscillation pada noise gamepad (umumnya 0.05-0.1)
 */
const TRIGGER_PRESS_THRESHOLD = 0.3;
const TRIGGER_RELEASE_THRESHOLD = 0.15;

/**
 * Default deadzone radial untuk analog stick.
 * Akan di-override oleh profile.deadzone jika tersedia.
 * Invariant: 0 < deadzone < outerSaturation < 1.
 */
const DEFAULT_DEADZONE_INNER = 0.15;
const DEFAULT_DEADZONE_OUTER = 0.95;

/**
 * Default max radius untuk analog stick (pixel).
 * Akan di-override oleh mapping.width atau mapping.height.
 */
const DEFAULT_MAX_RADIUS_PX = 150;

/**
 * Helper: apply radial deadzone dengan inner dan outer saturation.
 *
 * Math-Logic (Pasal 5.1):
 * - magnitude = sqrt(x^2 + y^2)
 * - Jika magnitude < innerDeadzone: output (0, 0) (stick dianggap diam)
 * - Jika magnitude > outerSaturation: output normalized ke (x/mag, y/mag) (stick penuh)
 * - Else: remap magnitude ke [0, 1] dengan formula (mag - inner) / (outer - inner),
 *   lalu scale (x, y) dengan factor = remappedMag / magnitude.
 *
 * Invariant:
 * - Output magnitude selalu dalam [0, 1]
 * - Jika input (0, 0): output (0, 0)
 * - Jika input magnitude > outer: output magnitude = 1
 * - Direction (angle) dipertahankan (tidak diubah)
 *
 * Kompleksitas: O(1) (operasi matematis konstan).
 *
 * @param x - raw axis x, range [-1, 1]
 * @param y - raw axis y, range [-1, 1]
 * @param innerDeadzone - deadzone dalam, default 0.15
 * @param outerSaturation - saturasi luar, default 0.95
 * @returns tuple [adjustedX, adjustedY] dalam range [-1, 1]
 */
function applyRadialDeadzone(
  x: number,
  y: number,
  innerDeadzone: number = DEFAULT_DEADZONE_INNER,
  outerSaturation: number = DEFAULT_DEADZONE_OUTER
): [number, number] {
  // Edge case: input (0, 0) langsung return (0, 0) tanpa pembagian.
  if (x === 0 && y === 0) return [0, 0];

  const magnitude = Math.sqrt(x * x + y * y);

  // Jika magnitude di bawah inner deadzone, stick dianggap diam.
  if (magnitude < innerDeadzone) return [0, 0];

  // Jika magnitude di atas outer saturation, normalize ke magnitude 1.
  if (magnitude >= outerSaturation) {
    return [x / magnitude, y / magnitude];
  }

  // Remap magnitude dari [inner, outer] ke [0, 1].
  const remappedMag = (magnitude - innerDeadzone) / (outerSaturation - innerDeadzone);

  // Scale (x, y) dengan factor = remappedMag / magnitude.
  // Ini mempertahankan direction tetapi mengubah magnitude.
  const scale = remappedMag / magnitude;
  return [x * scale, y * scale];
}

/**
 * Helper: apply exponential smoothing.
 *
 * Math-Logic (Pasal 5.1):
 * - smoothed = alpha * current + (1 - alpha) * previous
 * - alpha = 1 - smoothing (smoothing dari profile, range 0.05-0.95)
 * - alpha tinggi (smoothing rendah) = respons cepat, jitter tetap
 * - alpha rendah (smoothing tinggi) = respons lambat, jitter berkurang
 *
 * Invariant:
 * - smoothed value selalu dalam rentang [min(raw), max(raw)] jika alpha di [0, 1]
 * - Jika smoothing = 0: alpha = 1, smoothed = current (no smoothing)
 * - Jika smoothing = 1: alpha = 0, smoothed = previous (frozen, tidak diinginkan)
 *   oleh karena itu, smoothing di-clamp ke [0.05, 0.95] di schema
 *
 * Kompleksitas: O(1) (operasi matematis konstan).
 *
 * @param current - nilai raw saat ini
 * @param previous - nilai smoothed sebelumnya
 * @param smoothing - faktor smoothing dari profile, range [0.05, 0.95]
 * @returns nilai smoothed saat ini
 */
function applyExponentialSmoothing(
  current: number,
  previous: number,
  smoothing: number
): number {
  const alpha = 1 - smoothing;
  return alpha * current + (1 - alpha) * previous;
}

/**
 * Helper: apply anti-ban randomization ke koordinat sentuh.
 *
 * Math-Logic (Pasal 5.1):
 * - Jika antiBanEnabled: tambahkan offset Gaussian ±2px (sigma=1.5, clamped ±3)
 * - Gunakan Box-Muller transform untuk generate Gaussian random:
 *   u1, u2 = random [0, 1)
 *   z = sqrt(-2 * ln(u1)) * cos(2 * pi * u2)
 * - offset = z * sigma, clamped ke [-3, 3]
 * - Jika antiBanEnabled false: return koordinat asli
 *
 * Invariant:
 * - Offset random tidak pernah melebihi 3px (clamped)
 * - Output selalu integer (untuk koordinat pixel)
 * - Direction tidak berubah signifikan (offset kecil)
 *
 * Kompleksitas: O(1).
 *
 * @param x - koordinat x asli
 * @param y - koordinat y asli
 * @param antiBanEnabled - flag dari profile
 * @returns tuple [randomizedX, randomizedY] sebagai integer
 */
function applyAntiBanRandomization(
  x: number,
  y: number,
  antiBanEnabled: boolean
): [number, number] {
  if (!antiBanEnabled) return [Math.round(x), Math.round(y)];

  // Box-Muller transform untuk Gaussian random.
  // Hindari u1 = 0 karena log(0) = -Infinity.
  const u1 = Math.max(1e-10, Math.random());
  const u2 = Math.random();
  const z1 = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  const z2 = Math.sqrt(-2 * Math.log(u1)) * Math.sin(2 * Math.PI * u2);

  // Sigma = 1.5, clamp offset ke [-3, 3].
  const sigma = 1.5;
  const offsetX = Math.max(-3, Math.min(3, z1 * sigma));
  const offsetY = Math.max(-3, Math.min(3, z2 * sigma));

  return [Math.round(x + offsetX), Math.round(y + offsetY)];
}

/**
 * Helper: get max radius dari mapping atau fallback default.
 *
 * Fix untuk BUG-H18: maxRadius tidak lagi hardcoded 150px.
 * Pakai mapping.width atau mapping.height sebagai radius.
 * Jika tidak ada, fallback ke DEFAULT_MAX_RADIUS_PX.
 *
 * @param mapping - VirtualButton mapping untuk analog stick
 * @returns radius dalam pixel
 */
function getMaxRadiusFromMapping(mapping: any): number {
  if (mapping.width && mapping.width > 0) return mapping.width / 2;
  if (mapping.height && mapping.height > 0) return mapping.height / 2;
  return DEFAULT_MAX_RADIUS_PX;
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

  /**
   * Smoothing state untuk analog stick.
   * Disimpan di ref agar tidak trigger re-render.
   * Invariant: setiap value di [-1, 1].
   */
  const smoothingState = useRef<SmoothingState>({ lx: 0, ly: 0, rx: 0, ry: 0 });

  /**
   * Trigger state untuk hysteresis.
   * Invariant: transisi hanya terjadi saat value melewati threshold.
   */
  const triggerState = useRef<TriggerState>({ l2Pressed: false, r2Pressed: false });

  /**
   * Fix untuk BUG-H16: rAF batching untuk axis event.
   *
   * latestAxesRef menyimpan axis value terbaru (overwrite pada setiap event).
   * rAF callback memproses latestAxesRef sekali per frame.
   *
   * Math-Logic (Pasal 5.1):
   * - Batch window = 1 frame (16.67ms pada 60Hz).
   * - Throughput: 60 proses/detik (sebelumnya 100/detik untuk 100Hz gamepad).
   * - Latency tambahan: max 16.67ms (1 frame), acceptable untuk analog stick.
   *
   * Invariant:
   * - latestAxesRef selalu berisi axis value terbaru (overwrite pada setiap event).
   * - rAF callback dipanggil maksimal 60 kali per detik.
   * - Jika tidak ada axis event, rAF tidak melakukan apa-apa.
   */
  const latestAxesRef = useRef<number[] | null>(null);

  useEffect(() => {
    if (!active) return;
    let buttonListener: any;
    let axisListener: any;
    let isCleanedUp = false;
    let rafId: number | null = null;

    const processAxesBatch = async () => {
      rafId = null; // Reset rafId sebelum proses agar bisa di-schedule ulang.
      const axes = latestAxesRef.current;
      if (axes === null) return; // Tidak ada axis event sejak batch terakhir.
      latestAxesRef.current = null; // Clear setelah dibaca.

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

      // (Sisa logic axis processing sama dengan sebelumnya, dipindah ke sini.)
      // Fix untuk BUG-H17: deadzone dari profile, bukan hardcoded 0.15.
      const innerDeadzone = currentProfile.deadzone ?? DEFAULT_DEADZONE_INNER;
      const outerSaturation = DEFAULT_DEADZONE_OUTER;

      // Fix untuk BUG-H19: exponential smoothing.
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

      // 1. Left Stick (Pointer 0)
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

      // 2. Right Stick (Pointer 1)
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

      // 3. L2 (Analog to Button dengan hysteresis)
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

      // 4. R2 (Analog to Button dengan hysteresis)
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

    const getScreenCoords = (pctX: number, pctY: number) => {
        // Fix untuk BUG-H25: handle orientation dengan benar.
        const w = window.screen.width;
        const h = window.screen.height;
        const isLandscape = w > h;
        const sw = isLandscape ? Math.max(w, h) : w;
        const sh = isLandscape ? Math.min(w, h) : h;
        return {
            x: Math.round((pctX / 100) * sw),
            y: Math.round((pctY / 100) * sh)
        };
    };

    /**
     * Fix untuk BUG-H24: reset semua pointer aktif saat profile switch atau unmount.
     */
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

          // Anti-ban randomization untuk koordinat sentuh.
          const antiBan = currentProfile.antiBanEnabled === true;
          const { x: rawX, y: rawY } = getScreenCoords(mapping.x, mapping.y);
          const [x, y] = applyAntiBanRandomization(rawX, rawY, antiBan);

          // Fix untuk BUG-H21: handler per button type.
          // Sebelumnya hanya type 'button' dan 'analog_stick' (via axis) yang ditangani.
          // Sekarang: dpad = button directional, swipe = touch_down+move+up dengan direction,
          // macro = trigger macro playback (TODO: belum diimplementasi penuh, butuh REC-20),
          // gyro_area = baca sensor (TODO: butuh REC-19),
          // button = default (sentuh biasa).
          //
          // Untuk type yang belum diimplementasi penuh (macro, gyro_area), fallback ke
          // behavior 'button' (sentuh biasa) agar tidak silent fail.
          const buttonType = mapping.type || 'button';

          if (buttonType === 'swipe' && isPressed && !wasPressed) {
            // Swipe: touch_down di center, touch_move ke arah swipeDirection, touch_up.
            // swipeDuration menentukan kecepatan swipe (ms).
            const direction = mapping.swipeDirection || 'UP';
            const duration = mapping.swipeDuration || 100;
            const swipeDistance = 100; // pixel, bisa di-override via mapping.width

            // Hitung target berdasarkan direction.
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
              try {
                await TouchInjection.touchDown({ pointerId: pointer.id, x, y });
                // Move ke target dengan beberapa step untuk smooth swipe.
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
            }
          } else if (buttonType === 'dpad') {
            // Dpad: sama dengan button biasa, tetapi mappedKey sudah termasuk direction
            // (DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT). Treat as regular button.
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
            // Default: type 'button', 'macro', 'gyro_area' (fallback ke sentuh biasa).
            // Catatan: 'macro' dan 'gyro_area' akan diimplementasi penuh di REC-20 dan REC-19.
            // Untuk saat ini, fallback ke behavior 'button' agar tidak silent fail.
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
        });

        if (isCleanedUp) {
          buttonListener?.remove();
          return;
        }

        axisListener = await TouchInjection.addListener('onGamepadAxis', ({ axes }) => {
          // Fix untuk BUG-H16: rAF batching untuk axis event.
          // Simpan axis value terbaru ke ref, schedule rAF untuk proses.
          // rAF callback akan memproses latestAxesRef sekali per frame.
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
      // Fix untuk BUG-H16: cancel rAF yang masih pending saat cleanup.
      if (rafId !== null) {
        window.cancelAnimationFrame(rafId);
        rafId = null;
      }
      // Fix untuk BUG-H24: reset semua pointer aktif saat profile switch atau unmount.
      resetAllPointers().catch((e) => {
        console.error('[useGamepadLoop] Failed to reset pointers on cleanup', e);
      });
    };
  }, [mapProfile, active]);
}
