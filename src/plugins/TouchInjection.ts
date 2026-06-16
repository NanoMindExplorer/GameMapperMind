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
  checkPermission(): Promise<{ granted: boolean }>;

  touchDown(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchMove(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchUp(options: { pointerId: number }): Promise<void>;
  injectTap(options: { x: number; y: number }): Promise<void>;

  // Anti-ban configuration push
  setAntiBanConfig(options: AntiBanConfigPayload): Promise<void>;

  // Real macro capture — start/stop intercepting MotionEvents on the screen
  startMacroCapture(): Promise<void>;
  stopMacroCapture(): Promise<void>;

  // Listeners
  addListener(
    eventName: 'onGamepadButton',
    listenerFunc: (data: { buttonName: string; value: number; pressure: number }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onGamepadAxis',
    listenerFunc: (data: { axes: number[] }) => void
  ): Promise<PluginListenerHandle>;

  // Gyroscope data (rad/s) — emitted by GamepadListenerService when
  // the connected controller exposes ABS_RX/ABS_RY/ABS_RZ or when the
  // device's built-in gyro is used as fallback.
  addListener(
    eventName: 'onGyroData',
    listenerFunc: (data: { x: number; y: number; z: number; timestamp: number }) => void
  ): Promise<PluginListenerHandle>;

  // Foreground package change — emitted by TouchAccessibilityService
  // when the user switches apps. Used for auto-start game detection.
  addListener(
    eventName: 'onForegroundAppChanged',
    listenerFunc: (data: { packageName: string; timestamp: number }) => void
  ): Promise<PluginListenerHandle>;

  // Real macro capture events — emitted while startMacroCapture() is active
  addListener(
    eventName: 'onMacroCapture',
    listenerFunc: (data: {
      action: 'down' | 'move' | 'up';
      pointerId: number;
      x: number;
      y: number;
      pressure: number;
      size: number;
      timestamp: number;
    }) => void
  ): Promise<PluginListenerHandle>;
}

const TouchInjection = registerPlugin<TouchInjectionPluginType>('TouchInjection');

export default TouchInjection;
