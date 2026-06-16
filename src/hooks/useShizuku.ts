import { Capacitor } from '@capacitor/core';
import TouchInjection from '../plugins/TouchInjection';
import { ShizukuState } from '../types';

// Normalize the various button-name dialects used across the codebase
// (Web Gamepad API returns "A"/"B"/"LB"; profile.mappedKey uses "BUTTON_A"
// / "BUTTON_B" / "BUTTON_L1"). Without normalization, virtual buttons
// never highlight when the user presses a hardware button. (Issue #29 fix.)
export const HARDWARE_KEY_ALIASES: Record<string, string> = {
  // Standard web gamepad API button names → profile mappedKey
  'A': 'BUTTON_A',
  'B': 'BUTTON_B',
  'X': 'BUTTON_X',
  'Y': 'BUTTON_Y',
  'LB': 'BUTTON_L1',
  'RB': 'BUTTON_R1',
  'LT': 'BUTTON_L2',
  'RT': 'BUTTON_R2',
  'SELECT': 'BUTTON_SELECT',
  'START': 'BUTTON_START',
  'L3': 'BUTTON_L3',
  'R3': 'BUTTON_R3',
  'UP': 'DPAD_UP',
  'DOWN': 'DPAD_DOWN',
  'LEFT': 'DPAD_LEFT',
  'RIGHT': 'DPAD_RIGHT',
};

export function normalizeHardwareKey(raw: string): string {
  if (!raw) return raw;
  if (raw.startsWith('BUTTON_') || raw.startsWith('DPAD_') || raw === 'L_STICK' || raw === 'R_STICK') {
    return raw;
  }
  return HARDWARE_KEY_ALIASES[raw.toUpperCase()] ?? raw;
}

// Multi-pointer pool for injectInput. The original code hardcoded pointerId=99
// for every event, which made multitouch impossible (e.g. pressing A+B at the
// same time would cancel one of them). We now allocate pointer IDs from a
// small pool of "button" slots reserved for button-style injections.
// Pointer IDs 0-1 are reserved for analog sticks in useGamepadLoop; here we
// use 10..19 so we never collide with the stick pointers. (Issue #12 fix.)
const BUTTON_POINTER_POOL_START = 10;
const BUTTON_POINTER_POOL_END = 19;
const buttonPointerPool: Map<string, number> = new Map(); // virtualKey → pointerId

function allocateButtonPointer(virtualKey: string): number | null {
  if (buttonPointerPool.has(virtualKey)) return buttonPointerPool.get(virtualKey)!;
  for (let id = BUTTON_POINTER_POOL_START; id <= BUTTON_POINTER_POOL_END; id++) {
    let inUse = false;
    for (const v of buttonPointerPool.values()) {
      if (v === id) { inUse = true; break; }
    }
    if (!inUse) {
      buttonPointerPool.set(virtualKey, id);
      return id;
    }
  }
  return null; // pool exhausted
}

function releaseButtonPointer(virtualKey: string): number | null {
  const id = buttonPointerPool.get(virtualKey);
  if (id !== undefined) {
    buttonPointerPool.delete(virtualKey);
    return id;
  }
  return null;
}

export const useShizuku = () => {
  const checkShizukuStatus = async (currentState: ShizukuState): Promise<ShizukuState> => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const { granted } = await TouchInjection.checkPermission();
        return {
          ...currentState,
          daemonRunning: granted,
          status: granted ? 'CONNECTED_SHIZUKU' : 'DISCONNECTED'
        };
      } catch (err) {
        console.error("Native check error", err);
      }
    }
    return currentState;
  };

  const bindAndStart = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
        try {
          await TouchInjection.bindService();
          await TouchInjection.startGamepadListener();
          return true;
        } catch(e) {
          console.error("Bind failed", e);
        }
    }
    return false;
  };

  const executeShizukuCommand = async (command: string) => {
    return { output: 'Legacy command disabled', error: '', exitCode: 0 };
  };

  const requestShizukuPermission = async (): Promise<{ success: boolean; error?: string }> => {
    try {
        const { granted } = await TouchInjection.checkPermission();
        if (granted) return { success: true };
        return { success: false, error: "Permission denied" };
    } catch (e: any) {
        return { success: false, error: e.message };
    }
  };

  const startDaemon = async () => {
    return await bindAndStart();
  };

  const stopDaemon = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
        await TouchInjection.unbindService().catch(()=>{});
        await TouchInjection.stopGamepadListener().catch(()=>{});
    }
    return true;
  };

  /**
   * Parse and forward an injection command. Accepted formats:
   *   "down <x> <y> [virtualKey]"  — touchDown with allocated pointerId
   *   "move <x> <y> [virtualKey]"  — touchMove
   *   "up <x> <y> [virtualKey]"    — touchUp + release pointer
   *   "tap <x> <y>"                — quick tap (uses reserved tap id 99)
   *
   * When `virtualKey` is omitted we fall back to id=99 to preserve backwards
   * compatibility with code paths that don't track per-button state.
   */
  const injectInput = async (cmd: string) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      const parts = cmd.trim().split(/\s+/);
      const action = parts[0]?.toLowerCase();
      const x = parseFloat(parts[1] ?? '0');
      const y = parseFloat(parts[2] ?? '0');
      const virtualKey = parts[3]; // optional

      try {
        if (action === 'down') {
          const id = virtualKey ? allocateButtonPointer(virtualKey) ?? 99 : 99;
          await TouchInjection.touchDown({ pointerId: id, x, y });
        } else if (action === 'move') {
          const id = virtualKey ? buttonPointerPool.get(virtualKey) ?? 99 : 99;
          await TouchInjection.touchMove({ pointerId: id, x, y });
        } else if (action === 'up') {
          const id = virtualKey ? releaseButtonPointer(virtualKey) ?? 99 : 99;
          await TouchInjection.touchUp({ pointerId: id });
        } else if (action === 'tap') {
          await TouchInjection.injectTap({ x, y });
        } else {
          console.warn('[useShizuku] Unknown inject action:', action);
        }
        return true;
      } catch (e) {
        console.error("Injection error", e);
      }
    }
    return false;
  };

  const checkBattery = async () => true;
  const requestBatteryIgnore = async () => false;

  return {
    checkShizukuStatus,
    requestShizukuPermission,
    executeShizukuCommand,
    startDaemon,
    stopDaemon,
    injectInput,
    checkBattery,
    requestBatteryIgnore,
    // Exposed for App.tsx / OverlayApp.tsx so they can reset the pool when
    // the user navigates between profiles or activates the kill-switch.
    resetButtonPointers: () => buttonPointerPool.clear(),
  };
};
