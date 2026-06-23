import { Capacitor } from '@capacitor/core';
import React from 'react';
import { PointerIdRange } from '../types/pointer';
import TouchInjection from '../plugins/TouchInjection';
import { ShizukuState } from '../types';

export const useShizuku = () => {
  const [recoveryState, setRecoveryState] = React.useState<'INSTALLED' | 'RUNNING' | 'PERMISSION' | 'BOUND' | 'DAEMON_ALIVE'>('INSTALLED');
  const [retryCount, setRetryCount] = React.useState(0);

  const checkShizukuStatus = async (currentState: ShizukuState): Promise<ShizukuState> => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const { granted, touchServiceAlive, isBound } = await TouchInjection.checkPermission();
        const { daemonRunning } = await TouchInjection.checkDaemonRunning();
        
        let newState = 'INSTALLED';
        if (granted && touchServiceAlive && daemonRunning) newState = 'DAEMON_ALIVE';
        else if (granted && isBound) newState = 'BOUND';
        else if (granted) newState = 'PERMISSION';
        else newState = 'RUNNING';

        if (granted && (!touchServiceAlive || !daemonRunning)) {
            await bindAndStart();
        }

        return {
          ...currentState,
          daemonRunning: !!daemonRunning, 
          status: granted ? 'CONNECTED_SHIZUKU' : 'DISCONNECTED',
          recoveryState: newState as any
        };
      } catch (err) {
        console.error("Native check error", err);
        return { ...currentState, recoveryState: 'INSTALLED' } as any;
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

  const requestShizukuPermission = async (): Promise<{ success: boolean; error?: string }> => {
    try {
        const { granted, requested } = await TouchInjection.requestPermission();
        if (granted) return { success: true };
        if (requested) return { success: false, error: "Permission requested, waiting for user response." };
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

  const injectInput = async (action: 'down' | 'move' | 'up' | 'tap', x?: number, y?: number, pointerId: number = PointerIdRange.TAP, duration: number = 60) => {
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
          await TouchInjection.injectTap({ x, y, duration });
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

