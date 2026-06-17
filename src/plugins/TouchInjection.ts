import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface AntiBanConfigPayload {
  enabled: boolean;
  coordinateJitter: number;
  timingJitter: number;
  pressureVariance: number;
  sizeVariance: number;
  strokeDurationJitter: number;
  microPauseProbability: number;
  microPauseMaxMs: number;
}

export interface TouchInjectionPluginType {
  bindService(): Promise<void>;
  unbindService(): Promise<void>;
  startGamepadListener(): Promise<void>;
  stopGamepadListener(): Promise<void>;
  startOverlay(options: { profile: any }): Promise<void>;
  stopOverlay(): Promise<void>;

  // checkPermission — returns { granted, binderAlive, reason }
  checkPermission(): Promise<{ granted: boolean; binderAlive?: boolean; reason?: string }>;

  // requestPermission — shows Shizuku permission dialog
  requestPermission(): Promise<{ granted: boolean; message?: string }>;

  touchDown(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchMove(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchUp(options: { pointerId: number }): Promise<void>;
  injectTap(options: { x: number; y: number }): Promise<void>;

  setAntiBanConfig(options: AntiBanConfigPayload): Promise<void>;
  startMacroCapture(): Promise<void>;
  stopMacroCapture(): Promise<void>;

  addListener(eventName: 'onGamepadButton', listenerFunc: (data: { buttonName: string; value: number; pressure: number }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onGamepadAxis', listenerFunc: (data: { axes: number[] }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onGyroData', listenerFunc: (data: { x: number; y: number; z: number; timestamp: number }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onForegroundAppChanged', listenerFunc: (data: { packageName: string; timestamp: number }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onMacroCapture', listenerFunc: (data: any) => void): Promise<PluginListenerHandle>;

  // Shizuku lifecycle events
  addListener(eventName: 'onShizukuBinderReceived', listenerFunc: (data: { binderAlive: boolean }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onShizukuBinderDead', listenerFunc: (data: { binderAlive: boolean }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onShizukuPermissionResult', listenerFunc: (data: { granted: boolean; requestCode: number }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onShizukuServiceConnected', listenerFunc: (data: { connected: boolean }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onShizukuServiceDisconnected', listenerFunc: (data: { connected: boolean }) => void): Promise<PluginListenerHandle>;
}

const TouchInjection = registerPlugin<TouchInjectionPluginType>('TouchInjection');

export default TouchInjection;
