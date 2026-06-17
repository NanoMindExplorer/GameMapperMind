import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

// ============================================================
// GameMapperPlugin — TypeScript interface for native plugin
// ============================================================
// Matches the Kotlin GameMapperPlugin class.
// Plugin name: "GameMapper" (registered in MainActivity.kt)
// ============================================================

export interface GamepadDevice {
  deviceId: number;
  name: string;
  vendor: string;
  sources: number;
  isConnected: boolean;
}

export interface ShizukuStatus {
  granted: boolean;
  binderAlive?: boolean;
  version?: number;
  isPreV11?: boolean;
  reason?: string;
}

export interface AntiBanConfigPayload {
  enabled: boolean;
  coordinateJitter: number;
  timingJitterMs: number;
  pressureVariance: number;
  sizeVariance: number;
}

export interface GameMapperPluginType {
  // Shizuku integration
  checkShizukuStatus(): Promise<ShizukuStatus>;
  requestShizukuPermission(): Promise<{ granted: boolean; message?: string }>;

  // Daemon lifecycle
  startDaemon(options?: { profileJson?: string }): Promise<{ success: boolean; pid: number }>;
  stopDaemon(): Promise<{ success: boolean }>;

  // Touch injection
  injectTap(options: { x: number; y: number; displayId?: number }): Promise<void>;
  injectSwipe(options: {
    startX: number; startY: number;
    endX: number; endY: number;
    durationMs: number; displayId?: number;
  }): Promise<void>;
  injectMultiTouchDown(options: {
    pointerIds: number[];
    coords: { x: number; y: number }[];
    displayId?: number;
  }): Promise<void>;
  injectTouchUp(options: { pointerId: number; displayId?: number }): Promise<void>;

  // Gamepad info
  getConnectedGamepads(): Promise<{ devices: GamepadDevice[] }>;

  // Profile management
  setActiveProfile(options: { profileJson: string }): Promise<{ success: boolean; packageName: string }>;
  updateSwipeTrigger(options: {
    hardwareKey: string;
    direction: 'UP' | 'DOWN' | 'LEFT' | 'RIGHT';
    touchX: number;
    touchY: number;
  }): Promise<{ success: boolean }>;

  // Anti-ban
  setAntiBanConfig(options: AntiBanConfigPayload): Promise<void>;

  // Overlay
  startOverlay(): Promise<void>;
  stopOverlay(): Promise<void>;

  // Shell command execution via Shizuku
  executeShellCommand(options: { command: string }): Promise<{ output: string; error?: string; exitCode: number }>;

  // Listeners — Shizuku lifecycle
  addListener(
    eventName: 'onShizukuBinderReceived',
    listenerFunc: (data: { binderAlive: boolean }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onShizukuBinderDead',
    listenerFunc: (data: { binderAlive: boolean }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onShizukuPermissionGranted',
    listenerFunc: (data: { granted: boolean }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onShizukuPermissionDenied',
    listenerFunc: (data: { granted: boolean }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onServiceConnected',
    listenerFunc: (data: { connected: boolean; gamepadReadStarted?: boolean }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onServiceDisconnected',
    listenerFunc: (data: { connected: boolean }) => void
  ): Promise<PluginListenerHandle>;

  // Listeners — Gamepad events (forwarded from evdev via UserService)
  addListener(
    eventName: 'onGamepadButton',
    listenerFunc: (data: { buttonName: string; value: number; pressure: number }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onGamepadAxis',
    listenerFunc: (data: { axes: number[] }) => void
  ): Promise<PluginListenerHandle>;

  // Listeners — Profile changes
  addListener(
    eventName: 'onProfileChanged',
    listenerFunc: (data: { packageName: string }) => void
  ): Promise<PluginListenerHandle>;

  // Listeners — Foreground app changes
  addListener(
    eventName: 'onForegroundAppChanged',
    listenerFunc: (data: { packageName: string; timestamp: number }) => void
  ): Promise<PluginListenerHandle>;
}

const GameMapper = registerPlugin<GameMapperPluginType>('GameMapper');

export default GameMapper;
