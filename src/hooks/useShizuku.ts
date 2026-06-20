import { Capacitor } from '@capacitor/core';
import TouchInjection from '../plugins/TouchInjection';
import { ShizukuState } from '../types';

export const useShizuku = () => {
  const checkShizukuStatus = async (currentState: ShizukuState): Promise<ShizukuState> => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const { granted } = await TouchInjection.checkPermission();
        const { daemonRunning } = await TouchInjection.checkDaemonRunning();
        return {
          ...currentState,
          daemonRunning: !!daemonRunning, 
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
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const result = await TouchInjection.executeShizukuCommand({ command });
        return { output: result.output, error: result.error, exitCode: result.exitCode };
      } catch (err: any) {
        return { output: '', error: err.message || 'Error', exitCode: -1 };
      }
    }
    return { output: 'Legacy command disabled', error: '', exitCode: 0 };
  };

  /**
   * Fix untuk BUG-L02: requestShizukuPermission return type konsisten dengan executeShizukuCommand.
   * Sebelumnya return {success, error?} tanpa exitCode, tetapi executeShizukuCommand
   * return {output, error, exitCode}. Sekarang tambah exitCode untuk konsistensi
   * dan debugging (exitCode 0 = success, -1 = error).
   */
  const requestShizukuPermission = async (): Promise<{ success: boolean; error?: string; exitCode: number }> => {
    try {
        const { granted, requested } = await TouchInjection.requestPermission();
        if (granted) return { success: true, exitCode: 0 };
        if (requested) return { success: false, error: "Permission requested, waiting for user response.", exitCode: 1 };
        return { success: false, error: "Permission denied", exitCode: 1 };
    } catch (e: any) {
        return { success: false, error: e.message, exitCode: -1 };
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

  const injectInput = async (action: 'down' | 'move' | 'up' | 'tap', x?: number, y?: number, pointerId: number = 99) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        if (action === 'down') {
          if (x === undefined || y === undefined || isNaN(x) || isNaN(y)) { console.error("Invalid coordinates"); return false; }
          await TouchInjection.touchDown({ pointerId, x, y });
        } else if (action === 'move') {
          if (x === undefined || y === undefined || isNaN(x) || isNaN(y)) { console.error("Invalid coordinates"); return false; }
          await TouchInjection.touchMove({ pointerId, x, y });
        } else if (action === 'up') {
          await TouchInjection.touchUp({ pointerId });
        } else if (action === 'tap') {
          if (x === undefined || y === undefined || isNaN(x) || isNaN(y)) { console.error("Invalid coordinates"); return false; }
          await TouchInjection.injectTap({ x, y });
        }
        return true;
      } catch (e) {
        console.error("Injection error", e);
      }
    }
    return false;
  };

  const checkBattery = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
        const { isIgnoring } = await TouchInjection.checkBattery();
        return isIgnoring;
    }
    return true;
  };
  const requestBatteryIgnore = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
        await TouchInjection.requestBatteryIgnore();
        return true;
    }
    return false;
  };

  return { checkShizukuStatus, requestShizukuPermission, executeShizukuCommand, startDaemon, stopDaemon, injectInput, checkBattery, requestBatteryIgnore };
};

