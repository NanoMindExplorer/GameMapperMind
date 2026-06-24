import { Capacitor } from '@capacitor/core';
import React from 'react';
import { PointerIdRange } from '../types/pointer';
import TouchInjection from '../plugins/TouchInjection';
import { ShizukuState } from '../types';

export const useShizuku = () => {
  const [recoveryState, setRecoveryState] = React.useState<'INSTALLED' | 'RUNNING' | 'PERMISSION' | 'BOUND' | 'DAEMON_ALIVE'>('INSTALLED');
  const [retryCount, setRetryCount] = React.useState(0);
  const isBindingRef = React.useRef(false);

  // BUG-FIX: Listen for onShizukuPermissionResult event.
  // Saat user grant permission via dialog Shizuku, native emit event ini.
  // Sebelumnya JS TIDAK mendengarkan → daemon tidak auto-start →
  // app hilang dari Shizuku management saat di-background.
  React.useEffect(() => {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return;
    let listener: any;
    TouchInjection.addListener('onShizukuPermissionResult', async (data: { granted: boolean }) => {
      if (data.granted) {
        // Permission granted via dialog — auto-start daemon.
        // Tunggu 1 detik agar Shizuku binder siap.
        await new Promise(r => setTimeout(r, 1000));
        if (!isBindingRef.current) {
          isBindingRef.current = true;
          try {
            await bindAndStart();
          } catch(e) {
            console.warn("Auto-start after permission dialog failed:", e);
          } finally {
            setTimeout(() => { isBindingRef.current = false; }, 5000);
          }
        }
      }
    }).then(l => { listener = l; });
    return () => {
      if (listener && listener.remove) listener.remove();
    };
  }, []);

  const checkShizukuStatus = async (currentState: ShizukuState): Promise<ShizukuState> => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const { granted, touchServiceAlive, isBound } = await TouchInjection.checkPermission();
        const { daemonRunning } = await TouchInjection.checkDaemonRunning();
        
        // BUG-FIX: Auto-rebind if permission granted but service not alive.
        // With daemon(true), service process persists even after app is killed.
        // But our binder connection (touchService) is null after app restart.
        // Re-bind to reconnect to the still-running daemon service.
        if (granted && !touchServiceAlive && !isBindingRef.current) {
            isBindingRef.current = true;
            try {
              await bindAndStart();
            } finally {
              setTimeout(() => { isBindingRef.current = false; }, 5000);
            }
        }

        // BUG-FIX: If service is alive but GamepadListener not running, start it.
        // This happens when daemon(true) service survives but foreground service
        // (GamepadListenerService) was killed. Without foreground service, app
        // process can be killed by OS → binder dies → app disappears from Shizuku.
        if (granted && touchServiceAlive && !daemonRunning) {
            try {
              await TouchInjection.startGamepadListener();
            } catch(e) {
              console.warn("Auto-start gamepad listener failed:", e);
            }
        }

        let newState: 'INSTALLED' | 'RUNNING' | 'PERMISSION' | 'BOUND' | 'DAEMON_ALIVE' = 'INSTALLED';
        if (granted && touchServiceAlive && daemonRunning) newState = 'DAEMON_ALIVE';
        else if (granted && isBound) newState = 'BOUND';
        else if (granted) newState = 'PERMISSION';
        else newState = 'RUNNING';

        return {
          ...currentState,
          daemonRunning: !!daemonRunning, 
          status: granted ? 'CONNECTED_SHIZUKU' : 'DISCONNECTED',
          recoveryState: newState
        };
      } catch (err) {
        console.error("Native check error", err);
        return { ...currentState, recoveryState: 'INSTALLED' as any };
      }
    }
    return currentState;
  };

  const bindAndStart = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
        try {
          await TouchInjection.bindService();
          // BUG-FIX: Reduced delay from 500ms to 200ms. 500ms was too long,
          // causing slow re-bind when app resumes from background.
          // 200ms is enough for service to initialize after onServiceConnected.
          await new Promise(r => setTimeout(r, 200));
          await TouchInjection.startGamepadListener();
          return true;
        } catch(e) {
          console.error("Bind failed", e);
          return false;
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
        if (granted) {
          // BUG-FIX: Auto-start daemon immediately after permission granted.
          // Without GamepadListenerService (foreground service) running, app process
          // can be killed by OS when backgrounded → binder connection dies →
          // app disappears from Shizuku management.
          // With foreground service running, app process stays alive → binder persists.
          try {
            await bindAndStart();
          } catch (e) {
            console.warn("Auto-start daemon after permission grant failed:", e);
          }
          return { success: true };
        }
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

