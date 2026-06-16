import { Capacitor } from '@capacitor/core';
import TouchInjection from '../plugins/TouchInjection';
import { ShizukuState } from '../types';

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
    // Stub
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

  const injectInput = async (cmd: string) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      const parts = cmd.split(' ');
      const action = parts[0];
      const x = parseFloat(parts[1]);
      const y = parseFloat(parts[2]);
      
      try {
        if (action === 'down') {
          await TouchInjection.touchDown({ pointerId: 99, x, y });
        } else if (action === 'move') {
          await TouchInjection.touchMove({ pointerId: 99, x, y });
        } else if (action === 'up') {
          await TouchInjection.touchUp({ pointerId: 99 });
        } else if (action === 'tap') {
          await TouchInjection.injectTap({ x, y });
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

  return { checkShizukuStatus, requestShizukuPermission, executeShizukuCommand, startDaemon, stopDaemon, injectInput, checkBattery, requestBatteryIgnore };
};

