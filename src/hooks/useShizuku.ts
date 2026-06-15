import React from 'react';
import { Capacitor, registerPlugin } from '@capacitor/core';
import { ShizukuState } from '../types';

export interface ShizukuPluginInterface {
  checkStatus(): Promise<{ isRunning: boolean; hasPermission: boolean }>;
  requestPermission(): Promise<void>;
  executeCommand(options: { command: string }): Promise<{ output: string; error: string; exitCode: number }>;
  startDaemon(): Promise<{ success: boolean }>;
  stopDaemon(): Promise<{ success: boolean }>;
  injectInput(options: { command: string }): Promise<{ success: boolean }>;
}

const ShizukuPlugin = registerPlugin<ShizukuPluginInterface>('Shizuku');

export function useShizuku() {
  const checkShizukuStatus = async (currentState: ShizukuState): Promise<ShizukuState> => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const { isRunning, hasPermission } = await ShizukuPlugin.checkStatus();
        return {
          ...currentState,
          daemonRunning: isRunning,
          status: hasPermission ? 'CONNECTED_SHIZUKU' : (isRunning ? 'CHECKING' : 'DISCONNECTED')
        };
      } catch (err) {
        console.error("Native Shizuku check error", err);
        return currentState;
      }
    }
    return currentState;
  };

  const requestShizukuPermission = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        await ShizukuPlugin.requestPermission();
      } catch (err) {
        console.error("Native request permission error", err);
      }
    }
  };

  const executeShizukuCommand = async (command: string): Promise<{ output: string; error: string; exitCode: number } | null> => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const result = await ShizukuPlugin.executeCommand({ command });
        return result;
      } catch (err) {
        console.error("Native execute command error", err);
        return null;
      }
    }
    return null;
  };

  const startDaemon = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const result = await ShizukuPlugin.startDaemon();
        return result.success;
      } catch (err) {
        console.error("Native start daemon error", err);
        return false;
      }
    }
    return false;
  };

  const stopDaemon = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const result = await ShizukuPlugin.stopDaemon();
        return result.success;
      } catch (err) {
        console.error("Native stop daemon error", err);
        return false;
      }
    }
    return false;
  };

  const injectInput = React.useCallback(async (command: string) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        const result = await ShizukuPlugin.injectInput({ command });
        return result.success;
      } catch (err) {
        console.error("Native inject input error", err);
        return false;
      }
    }
    return false;
  }, []);

  return { checkShizukuStatus, requestShizukuPermission, executeShizukuCommand, startDaemon, stopDaemon, injectInput };
}
