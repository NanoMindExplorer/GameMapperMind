import { Capacitor } from '@capacitor/core';
import GameMapper from '../plugins/GameMapper';
import { ShizukuState } from '../types';

// Normalize hardware button names (e.g., "A" → "BUTTON_A")
export const HARDWARE_KEY_ALIASES: Record<string, string> = {
  'A': 'BUTTON_A', 'B': 'BUTTON_B', 'X': 'BUTTON_X', 'Y': 'BUTTON_Y',
  'LB': 'BUTTON_L1', 'RB': 'BUTTON_R1', 'LT': 'BUTTON_L2', 'RT': 'BUTTON_R2',
  'SELECT': 'BUTTON_SELECT', 'START': 'BUTTON_START', 'L3': 'BUTTON_L3', 'R3': 'BUTTON_R3',
  'UP': 'DPAD_UP', 'DOWN': 'DPAD_DOWN', 'LEFT': 'DPAD_LEFT', 'RIGHT': 'DPAD_RIGHT',
};

export function normalizeHardwareKey(raw: string): string {
  if (!raw) return raw;
  if (raw.startsWith('BUTTON_') || raw.startsWith('DPAD_') || raw === 'L_STICK' || raw === 'R_STICK') return raw;
  return HARDWARE_KEY_ALIASES[raw.toUpperCase()] ?? raw;
}

const BUTTON_POINTER_POOL_START = 10;
const BUTTON_POINTER_POOL_END = 19;
const buttonPointerPool: Map<string, number> = new Map();

function allocateButtonPointer(virtualKey: string): number | null {
  if (buttonPointerPool.has(virtualKey)) return buttonPointerPool.get(virtualKey)!;
  for (let id = BUTTON_POINTER_POOL_START; id <= BUTTON_POINTER_POOL_END; id++) {
    let inUse = false;
    for (const v of buttonPointerPool.values()) { if (v === id) { inUse = true; break; } }
    if (!inUse) { buttonPointerPool.set(virtualKey, id); return id; }
  }
  return null;
}

function releaseButtonPointer(virtualKey: string): number | null {
  const id = buttonPointerPool.get(virtualKey);
  if (id !== undefined) { buttonPointerPool.delete(virtualKey); return id; }
  return null;
}

export const useShizuku = () => {
  const checkShizukuStatus = async (currentState: ShizukuState): Promise<ShizukuState> => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const result = await GameMapper.checkShizukuStatus();
        if (result.granted) {
          return { ...currentState, daemonRunning: true, status: 'CONNECTED_SHIZUKU' };
        }
        return { ...currentState, daemonRunning: false, status: 'DISCONNECTED' };
      } catch (err) { console.error('Native check error', err); }
    }
    return currentState;
  };

  const bindAndStart = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try { await GameMapper.startDaemon(); return true; }
      catch (e) { console.error('Bind failed', e); }
    }
    return false;
  };

  const executeShizukuCommand = async (command: string) => {
    return { output: 'Legacy command disabled', error: '', exitCode: 0 };
  };

  const requestShizukuPermission = async (): Promise<{ success: boolean; error?: string }> => {
    try {
      const checkResult = await GameMapper.checkShizukuStatus();
      if (checkResult.granted) {
        try { await GameMapper.startDaemon(); } catch (e) { console.warn('Auto-bind failed', e); }
        return { success: true };
      }
      if (checkResult.binderAlive === false) {
        return { success: false, error: 'Shizuku app is not running. Please open Shizuku and start the service.' };
      }
      const result = await GameMapper.requestShizukuPermission();
      if (result.granted) {
        try { await GameMapper.startDaemon(); } catch (e) { console.warn('Auto-bind after permission failed', e); }
        return { success: true };
      }
      return { success: false, error: result.message || 'Waiting for Shizuku dialog approval...' };
    } catch (e: any) { return { success: false, error: e.message }; }
  };

  const startDaemon = async () => { return await bindAndStart(); };

  const stopDaemon = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try { await GameMapper.stopDaemon(); } catch (e) { console.error('Stop failed', e); }
    }
    return true;
  };

  const injectInput = async (cmd: string) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      const parts = cmd.trim().split(/\s+/);
      const action = parts[0]?.toLowerCase();
      const x = parseFloat(parts[1] ?? '0');
      const y = parseFloat(parts[2] ?? '0');
      const virtualKey = parts[3];

      try {
        if (action === 'down') {
          const id = virtualKey ? allocateButtonPointer(virtualKey) ?? 99 : 99;
          await GameMapper.injectTap({ x, y, displayId: 0 }); // Tap for instant press
        } else if (action === 'move') {
          const id = virtualKey ? buttonPointerPool.get(virtualKey) ?? 99 : 99;
          // For move, we use swipe with same start/end (instant move)
          await GameMapper.injectSwipe({ startX: x, startY: y, endX: x, endY: y, durationMs: 1, displayId: 0 });
        } else if (action === 'up') {
          const id = virtualKey ? releaseButtonPointer(virtualKey) ?? 99 : 99;
          // Touch up is implicit in tap (down+up). For hold, we need injectTouchUp
          await GameMapper.injectTouchUp({ pointerId: id, displayId: 0 });
        } else if (action === 'tap') {
          await GameMapper.injectTap({ x, y, displayId: 0 });
        }
        return true;
      } catch (e) { console.error('Injection error', e); }
    }
    return false;
  };

  const checkBattery = async () => true;
  const requestBatteryIgnore = async () => false;

  return {
    checkShizukuStatus, requestShizukuPermission, executeShizukuCommand,
    startDaemon, stopDaemon, injectInput, checkBattery, requestBatteryIgnore,
    resetButtonPointers: () => buttonPointerPool.clear(),
  };
};
