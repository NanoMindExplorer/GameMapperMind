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
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') {
      return { output: 'Shell commands only available on native Android', error: 'Not native platform', exitCode: -1 };
    }
    try {
      const result = await GameMapper.executeShellCommand({ command });
      return { output: result.output, error: result.error || '', exitCode: result.exitCode };
    } catch (e: any) {
      console.error('[useShizuku] executeShellCommand failed:', e);
      return { output: '', error: e.message || 'Shizuku command execution failed. Is Shizuku running?', exitCode: -1 };
    }
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

  // GMM-AEC-002: injectInput deprecated — Path A (JS-side injection) dihapus
  // Semua injection sekarang via native pipeline (Path B):
  //   evdev → GameMapperUserService → InputPipelineWorker → TouchInjector
  // Method ini di-keep untuk backward compatibility tapi hanya log warning.
  const injectInput = async (cmd: string) => {
    console.warn('[DEPRECATED] injectInput called — use native pipeline (Path B) instead. cmd:', cmd);
    return false;
  };

  const checkBattery = async () => true;
  const requestBatteryIgnore = async () => false;

  return {
    checkShizukuStatus, requestShizukuPermission, executeShizukuCommand,
    startDaemon, stopDaemon, injectInput, checkBattery, requestBatteryIgnore,
  };
};
