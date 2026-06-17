import { useEffect, useRef } from 'react';
import GameMapper from '../plugins/GameMapper';
import { getButtonMappingForMode, detectControllerMode } from '../defaults';
import type { ControllerMode } from '../types';
import { Capacitor } from '@capacitor/core';

interface PointerState {
  id: number;
  isActive: boolean;
  type: 'analog' | 'button';
  virtualKey?: string;
}

export function useGamepadLoop(mapProfile: any, active: boolean) {
  const lastState = useRef<Record<string, boolean>>({});
  const pointers = useRef<PointerState[]>([
    { id: 0, isActive: false, type: 'analog' },
    { id: 1, isActive: false, type: 'analog' },
    ...Array.from({ length: 8 }, (_, i) => ({ id: i + 2, isActive: false, type: 'button' as const }))
  ]);
  const controllerModeRef = useRef<ControllerMode>('XBOX');
  const detectedButtonMappingRef = useRef<string[]>(getButtonMappingForMode('XBOX'));
  const gyroLastEmitRef = useRef<number>(0);
  const gyroSmoothedRef = useRef<{ x: number; y: number; z: number }>({ x: 0, y: 0, z: 0 });

  // Push anti-ban config to native daemon whenever profile changes
  useEffect(() => {
    if (!active) return;
    const cfg = mapProfile?.antiBanConfig;
    const enabled = !!mapProfile?.antiBanEnabled && !!cfg?.enabled;
    if (cfg && Capacitor.isNativePlatform()) {
      GameMapper.setAntiBanConfig({
        enabled,
        coordinateJitter: cfg.coordinateJitter ?? 4,
        timingJitterMs: cfg.timingJitter ?? 3,
        pressureVariance: cfg.pressureVariance ?? 0.15,
        sizeVariance: cfg.sizeVariance ?? 0.10,
      }).catch((e: any) => console.warn('setAntiBanConfig failed', e));
    }
    // Also push profile to native pipeline for evdev-based injection
    if (mapProfile && Capacitor.isNativePlatform()) {
      try {
        const profileJson = buildProfileJson(mapProfile);
        GameMapper.setActiveProfile({ profileJson }).catch((e: any) => console.warn('setActiveProfile failed', e));
      } catch (e) { console.warn('Failed to build profile JSON', e); }
    }
  }, [mapProfile, active]);

  useEffect(() => {
    if (!active) return;
    let buttonListener: any;
    let axisListener: any;
    let gyroListener: any;

    const setupListeners = async () => {
      if (!Capacitor.isNativePlatform()) return;

      buttonListener = await GameMapper.addListener('onGamepadButton', async ({ buttonName, value }) => {
        if (buttonName.startsWith('CONTROLLER_ID:')) {
          const id = buttonName.substring('CONTROLLER_ID:'.length);
          const { mode } = detectControllerMode(id);
          controllerModeRef.current = mode;
          detectedButtonMappingRef.current = getButtonMappingForMode(mode);
          console.log('[useGamepadLoop] Controller detected:', id, '→ mode:', mode);
          return;
        }
        if (buttonName === 'MODE') { console.log('[useGamepadLoop] MODE button pressed'); return; }

        const isPressed = value === 1;
        const mapping = mapProfile?.mappings?.find((m: any) => m.hardwareKey === buttonName);
        // Try alias
        if (!mapping) {
          const aliases: Record<string, string> = { 'LB': 'L1', 'RB': 'R1', 'LT': 'L2', 'RT': 'R2', 'L1': 'LB', 'R1': 'RB', 'L2': 'LT', 'R2': 'RT' };
          if (aliases[buttonName]) {
            const m2 = mapProfile?.mappings?.find((m: any) => m.hardwareKey === aliases[buttonName]);
            if (m2) { m2 && handleButtonPress(buttonName, isPressed, m2); return; }
          }
        }
        if (!mapping || !mapping.x || !mapping.y) { lastState.current[buttonName] = isPressed; return; }
        handleButtonPress(buttonName, isPressed, mapping);
      });

      axisListener = await GameMapper.addListener('onGamepadAxis', async ({ axes }) => {
        const lx = axes[0] || 0, ly = axes[1] || 0;
        const rx = axes[2] || 0, ry = axes[3] || 0;
        const l2Analog = axes[4] ?? -1, r2Analog = axes[5] ?? -1;
        const deadzone = mapProfile?.deadzone ?? 0.15;

        // Left Stick (Pointer 0)
        const lMag = Math.sqrt(lx * lx + ly * ly);
        const lCenterX = mapProfile?.leftJoystick?.centerX ?? 250;
        const lCenterY = mapProfile?.leftJoystick?.centerY ?? 500;
        const lRadius = mapProfile?.leftJoystick?.radius ?? 150;
        const leftPointer = pointers.current.find(p => p.id === 0)!;
        if (lMag > deadzone) {
          const targetX = lCenterX + (lx * lRadius), targetY = lCenterY + (ly * lRadius);
          if (!leftPointer.isActive) { leftPointer.isActive = true; await GameMapper.injectSwipe({ startX: lCenterX, startY: lCenterY, endX: targetX, endY: targetY, durationMs: 1, displayId: 0 }); }
          else { await GameMapper.injectSwipe({ startX: targetX, startY: targetY, endX: targetX, endY: targetY, durationMs: 1, displayId: 0 }); }
        } else if (leftPointer.isActive) { leftPointer.isActive = false; await GameMapper.injectTouchUp({ pointerId: 0, displayId: 0 }); }

        // Right Stick (Pointer 1)
        const rMag = Math.sqrt(rx * rx + ry * ry);
        const rCenterX = mapProfile?.rightJoystick?.centerX ?? 700;
        const rCenterY = mapProfile?.rightJoystick?.centerY ?? 500;
        const rRadius = mapProfile?.rightJoystick?.radius ?? 150;
        const rightPointer = pointers.current.find(p => p.id === 1)!;
        if (rMag > deadzone) {
          const targetX = rCenterX + (rx * rRadius), targetY = rCenterY + (ry * rRadius);
          if (!rightPointer.isActive) { rightPointer.isActive = true; await GameMapper.injectSwipe({ startX: rCenterX, startY: rCenterY, endX: targetX, endY: targetY, durationMs: 1, displayId: 0 }); }
          else { await GameMapper.injectSwipe({ startX: targetX, startY: targetY, endX: targetX, endY: targetY, durationMs: 1, displayId: 0 }); }
        } else if (rightPointer.isActive) { rightPointer.isActive = false; await GameMapper.injectTouchUp({ pointerId: 1, displayId: 0 }); }

        // L2 analog
        const mapL2 = mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'L2' || m.hardwareKey === 'LT');
        if (mapL2 && mapL2.x && mapL2.y) {
          const isL2Pressed = l2Analog > 0.0;
          const wasL2Pressed = lastState.current['L2_ANALOG'];
          if (isL2Pressed && !wasL2Pressed) { const p = pointers.current.find(c => !c.isActive && c.type === 'button'); if (p) { p.isActive = true; p.virtualKey = 'L2_ANALOG'; await GameMapper.injectTap({ x: mapL2.x, y: mapL2.y, displayId: 0 }); } }
          else if (!isL2Pressed && wasL2Pressed) { const p = pointers.current.find(c => c.isActive && c.virtualKey === 'L2_ANALOG'); if (p) { p.isActive = false; p.virtualKey = undefined; await GameMapper.injectTouchUp({ pointerId: p.id, displayId: 0 }); } }
          lastState.current['L2_ANALOG'] = isL2Pressed;
        }
        // R2 analog
        const mapR2 = mapProfile?.mappings?.find((m: any) => m.hardwareKey === 'R2' || m.hardwareKey === 'RT');
        if (mapR2 && mapR2.x && mapR2.y) {
          const isR2Pressed = r2Analog > 0.0;
          const wasR2Pressed = lastState.current['R2_ANALOG'];
          if (isR2Pressed && !wasR2Pressed) { const p = pointers.current.find(c => !c.isActive && c.type === 'button'); if (p) { p.isActive = true; p.virtualKey = 'R2_ANALOG'; await GameMapper.injectTap({ x: mapR2.x, y: mapR2.y, displayId: 0 }); } }
          else if (!isR2Pressed && wasR2Pressed) { const p = pointers.current.find(c => c.isActive && c.virtualKey === 'R2_ANALOG'); if (p) { p.isActive = false; p.virtualKey = undefined; await GameMapper.injectTouchUp({ pointerId: p.id, displayId: 0 }); } }
          lastState.current['R2_ANALOG'] = isR2Pressed;
        }
      });
    };

    setupListeners();
    return () => { buttonListener?.remove(); axisListener?.remove(); gyroListener?.remove(); };
  }, [mapProfile, active]);

  async function handleButtonPress(buttonName: string, isPressed: boolean, mapping: any) {
    const wasPressed = lastState.current[buttonName];
    if (isPressed && !wasPressed) {
      const pointer = pointers.current.find(p => !p.isActive && p.type === 'button');
      if (pointer) { pointer.isActive = true; pointer.virtualKey = buttonName; await GameMapper.injectTap({ x: mapping.x, y: mapping.y, displayId: 0 }); }
    } else if (!isPressed && wasPressed) {
      const pointer = pointers.current.find(p => p.isActive && p.type === 'button' && p.virtualKey === buttonName);
      if (pointer) { pointer.isActive = false; pointer.virtualKey = undefined; await GameMapper.injectTouchUp({ pointerId: pointer.id, displayId: 0 }); }
    }
    lastState.current[buttonName] = isPressed;
  }

  // Build JSON profile for native pipeline (InputPipelineWorker)
  function buildProfileJson(profile: any): string {
    const screenW = window.screen.width > window.screen.height ? window.screen.width : window.screen.height;
    const screenH = window.screen.width > window.screen.height ? window.screen.height : window.screen.width;
    const buttons = (profile.mappings || []).map((m: any) => ({
      hardwareKey: m.hardwareKey,
      touchX: m.x / screenW,
      touchY: m.y / screenH,
      actionType: 'tap',
      swipeDirection: null,
    }));
    const leftStick = profile.leftJoystick ? {
      centerX: profile.leftJoystick.centerX / screenW,
      centerY: profile.leftJoystick.centerY / screenH,
      radius: profile.leftJoystick.radius / screenW,
      deadzone: profile.deadzone ?? 0.15,
      smoothing: profile.smoothing ?? 0.3,
    } : null;
    const rightStick = profile.rightJoystick ? {
      centerX: profile.rightJoystick.centerX / screenW,
      centerY: profile.rightJoystick.centerY / screenH,
      radius: profile.rightJoystick.radius / screenW,
      deadzone: profile.deadzone ?? 0.15,
      smoothing: profile.smoothing ?? 0.3,
    } : null;
    return JSON.stringify({
      packageName: profile.packageName || '',
      screenWidth: screenW,
      screenHeight: screenH,
      displayId: 0,
      buttons,
      leftStick,
      rightStick,
    });
  }
}
