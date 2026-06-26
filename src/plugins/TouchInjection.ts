import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface TouchInjectionPluginType {
  bindService(): Promise<void>;
  unbindService(): Promise<void>;
  startGamepadListener(): Promise<void>;
  stopGamepadListener(): Promise<void>;
  updateActiveProfile(options: { profileJson: string }): Promise<void>;
  startOverlay(options: { profile: any }): Promise<void>;
  stopOverlay(): Promise<void>;
  checkPermission(): Promise<{ granted: boolean, isBound?: boolean, touchServiceAlive?: boolean }>;
  requestPermission(): Promise<{ granted: boolean, requested?: boolean }>;
  checkDaemonRunning(): Promise<{ daemonRunning: boolean }>;
  runDiagnostics(): Promise<{ report: string }>;
  executeShizukuCommand(options: { command: string }): Promise<{ output: string, error: string, exitCode: number }>;
  checkBattery(): Promise<{ isIgnoring: boolean }>;
  requestBatteryIgnore(): Promise<void>;
  
  touchDown(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchMove(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchUp(options: { pointerId: number }): Promise<void>;
  injectTap(options: { x: number; y: number; duration?: number }): Promise<void>;

  addListener(
    eventName: 'onGamepadButton', 
    listenerFunc: (data: { buttonName: string; value: number; pressure: number }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onGamepadAxis', 
    listenerFunc: (data: { axes: number[] }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onGamepadFeedback', 
    listenerFunc: (data: { type: string, intensity: number, duration: number }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onShizukuPermissionResult',
    listenerFunc: (data: { granted: boolean }) => void
  ): Promise<PluginListenerHandle>;
}

const TouchInjection = registerPlugin<TouchInjectionPluginType>('TouchInjection');

export default TouchInjection;
