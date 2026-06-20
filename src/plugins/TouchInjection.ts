/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 * 
 * TouchInjection - Capacitor Plugin Interface
 * 
 * Interface definition untuk native Android plugin yang menangani:
 * - Shizuku/ADB bridge untuk touch injection
 * - Gamepad input listener
 * - Overlay service management
 * - Native mapping service (FIX BUG-M12: Implementasi lengkap)
 * 
 * Digunakan oleh:
 * - src/hooks/useGamepadLoop.ts
 * - src/hooks/useShizuku.ts
 * - src/components/MacroEngine.tsx
 * - src/components/OverlayWysiwyg.tsx
 * - src/App.tsx
 */
import { registerPlugin } from '@capacitor/core';

// ============================================
// TYPE DEFINITIONS
// ============================================

/**
 * Status koneksi Shizuku/ADB
 */
export type ShizukuConnectionStatus = 'CONNECTED_SHIZUKU' | 'CONNECTED_ADB' | 'DISCONNECTED';

/**
 * Response dari checkShizukuStatus()
 */
export interface ShizukuStatusResponse {
  status: ShizukuConnectionStatus;
  daemonRunning: boolean;
  daemonVersion: string;
  /** Timestamp terakhir kali status diupdate (epoch ms) */
  lastUpdated?: number;
  /** Informasi tambahan dari daemon */
  extra?: Record<string, any>;
}

/**
 * Options untuk touchDown
 */
export interface TouchDownOptions {
  /** ID unik untuk pointer (0-19) */
  pointerId: number;
  /** Koordinat X dalam pixel */
  x: number;
  /** Koordinat Y dalam pixel */
  y: number;
  /** Tekanan sentuhan (0.0 - 1.0), default 1.0 */
  pressure?: number;
  /** Ukuran area sentuhan dalam pixel, default 32 */
  size?: number;
}

/**
 * Options untuk touchMove
 */
export interface TouchMoveOptions {
  /** ID pointer yang sama dengan touchDown */
  pointerId: number;
  /** Koordinat X baru dalam pixel */
  x: number;
  /** Koordinat Y baru dalam pixel */
  y: number;
  /** Tekanan sentuhan (0.0 - 1.0) */
  pressure?: number;
  /** Ukuran area sentuhan dalam pixel */
  size?: number;
}

/**
 * Options untuk touchUp
 */
export interface TouchUpOptions {
  /** ID pointer yang akan dilepas */
  pointerId: number;
}

/**
 * Options untuk multiTouch (inject multiple pointers sekaligus)
 */
export interface MultiTouchOptions {
  touches: Array<{
    id: number;
    x: number;
    y: number;
    pressure?: number;
    size?: number;
  }>;
}

/**
 * Options untuk executeShizukuCommand
 * FIX BUG-C05: Command harus divalidasi di sisi caller
 */
export interface ShizukuCommandOptions {
  /** Command string yang sudah disanitasi */
  command: string;
  /** Timeout dalam ms (default: 5000) */
  timeout?: number;
}

/**
 * Response dari executeShizukuCommand
 */
export interface ShizukuCommandResponse {
  success: boolean;
  output?: string;
  error?: string;
  exitCode?: number;
}

/**
 * Options untuk startOverlay
 */
export interface OverlayStartOptions {
  /** Profile JSON string yang sudah di-serialize */
  profileJson: string;
  /** Apakah overlay harus clickable (default: false) */
  clickable?: boolean;
  /** Opacity overlay (0-100, default: 80) */
  opacity?: number;
}

/**
 * Options untuk updateNativeProfile
 * FIX BUG-M12: Implementasi lengkap
 */
export interface NativeMappingOptions {
  /** Profile JSON string */
  profileJson: string;
  /** Apakah harus langsung apply (default: true) */
  applyImmediately?: boolean;
}

/**
 * Status dari native mapping service
 * FIX BUG-M12: Response yang informatif
 */
export interface NativeMappingStatus {
  /** Apakah service sedang berjalan */
  isRunning: boolean;
  /** Latency rata-rata dalam ms */
  latency: number;
  /** Jumlah pointer aktif saat ini */
  activePointers: number;
  /** Uptime service dalam ms */
  uptime: number;
  /** Total touch events yang diinject */
  totalInjections: number;
  /** Timestamp terakhir ada aktivitas */
  lastActivity: number;
  /** Error message jika ada */
  lastError?: string;
}

/**
 * Event data untuk gamepad button
 */
export interface GamepadButtonEvent {
  /** Nama button (A, B, X, Y, LB, RB, LT, RT, dll) */
  buttonName: string;
  /** Value 0 atau 1 (atau 0.0-1.0 untuk analog trigger) */
  value: number;
  /** Index gamepad (untuk multi-gamepad support) */
  gamepadIndex?: number;
  /** Timestamp event */
  timestamp?: number;
}

/**
 * Event data untuk gamepad axis
 */
export interface GamepadAxisEvent {
  /** Array axes values [LX, LY, RX, RY, LT, RT] */
  axes: number[];
  /** Index gamepad */
  gamepadIndex?: number;
  /** Timestamp event */
  timestamp?: number;
}

/**
 * Event data untuk gamepad connected
 */
export interface GamepadConnectedEvent {
  /** Nama gamepad */
  name: string;
  /** ID unik gamepad */
  id: string;
  /** Vendor ID */
  vendorId?: string;
  /** Product ID */
  productId?: string;
  /** Jumlah button */
  buttonCount: number;
  /** Jumlah axis */
  axisCount: number;
  /** Apakah mendukung rumble */
  hasRumble: boolean;
  /** Index gamepad */
  gamepadIndex?: number;
}

/**
 * Event data untuk gamepad disconnected
 */
export interface GamepadDisconnectedEvent {
  /** Nama gamepad */
  name: string;
  /** ID unik gamepad */
  id: string;
  /** Index gamepad */
  gamepadIndex?: number;
}

/**
 * Event data untuk native mapping status change
 */
export interface NativeMappingStatusEvent {
  /** Status baru */
  status: 'STARTED' | 'STOPPED' | 'ERROR' | 'PROFILE_UPDATED';
  /** Pesan tambahan */
  message?: string;
  /** Detail status */
  details?: NativeMappingStatus;
}

/**
 * Event data untuk daemon status change
 */
export interface DaemonStatusEvent {
  /** Apakah daemon running */
  running: boolean;
  /** Versi daemon */
  version?: string;
  /** Pesan error jika ada */
  error?: string;
}

/**
 * Event data untuk touch injection (debug)
 */
export interface TouchInjectionEvent {
  /** Jenis aksi */
  action: 'down' | 'move' | 'up' | 'multi';
  /** Pointer ID */
  pointerId: number;
  /** Koordinat X */
  x: number;
  /** Koordinat Y */
  y: number;
  /** Timestamp */
  timestamp: number;
  /** Latency dari request ke eksekusi (ms) */
  latency?: number;
}

// ============================================
// EVENT NAMES
// ============================================
export const TOUCH_INJECTION_EVENTS = {
  GAMEPAD_BUTTON: 'onGamepadButton',
  GAMEPAD_AXIS: 'onGamepadAxis',
  GAMEPAD_CONNECTED: 'onGamepadConnected',
  GAMEPAD_DISCONNECTED: 'onGamepadDisconnected',
  NATIVE_MAPPING_STATUS: 'onNativeMappingStatus',
  DAEMON_STATUS: 'onDaemonStatus',
  TOUCH_INJECTION: 'onTouchInjection',
} as const;

// ============================================
// PLUGIN INTERFACE
// ============================================

/**
 * TouchInjectionPlugin - Main plugin interface
 * 
 * Semua method mengembalikan Promise dan bisa throw error.
 * Pastikan untuk handle error dengan try/catch.
 */
export interface TouchInjectionPlugin {
  // ==========================================
  // SERVICE LIFECYCLE
  // ==========================================

  /**
   * Bind ke native service
   * Harus dipanggil sebelum menggunakan method lain
   */
  bindService(): Promise<{ success: boolean; message: string }>;

  /**
   * Unbind dari native service
   * Panggil saat app akan ditutup
   */
  unbindService(): Promise<{ success: boolean; message: string }>;

  // ==========================================
  // SHIZUKU INTEGRATION
  // ==========================================

  /**
   * Cek status koneksi Shizuku/ADB
   */
  checkShizukuStatus(): Promise<ShizukuStatusResponse>;

  /**
   * Eksekusi command via Shizuku
   * FIX BUG-C05: Command harus sudah divalidasi di caller
   */
  executeShizukuCommand(options: ShizukuCommandOptions): Promise<ShizukuCommandResponse>;

  // ==========================================
  // TOUCH INJECTION
  // ==========================================

  /**
   * Inject touch down event
   */
  touchDown(options: TouchDownOptions): Promise<void>;

  /**
   * Inject touch move event
   */
  touchMove(options: TouchMoveOptions): Promise<void>;

  /**
   * Inject touch up event
   */
  touchUp(options: TouchUpOptions): Promise<void>;

  /**
   * Inject multiple touch events sekaligus
   * Berguna untuk multi-touch gestures
   */
  multiTouch(options: MultiTouchOptions): Promise<void>;

  /**
   * Inject key event (untuk button mapping)
   */
  injectKeyEvent(options: { keyCode: number; action: 'down' | 'up' }): Promise<void>;

  // ==========================================
  // GAMEPAD LISTENER
  // ==========================================

  /**
   * Start listening untuk gamepad events
   * Akan emit events: onGamepadButton, onGamepadAxis, 
   * onGamepadConnected, onGamepadDisconnected
   */
  startGamepadListener(): Promise<{ success: boolean }>;

  /**
   * Stop listening gamepad events
   */
  stopGamepadListener(): Promise<{ success: boolean }>;

  /**
   * Get daftar gamepad yang terkoneksi
   */
  getConnectedGamepads(): Promise<{
    gamepads: Array<{
      id: string;
      name: string;
      index: number;
      buttonCount: number;
      axisCount: number;
    }>;
  }>;

  // ==========================================
  // NATIVE MAPPING SERVICE
  // FIX BUG-M12: Implementasi lengkap
  // ==========================================

  /**
   * Start native mapping service
   * Service ini menangani gamepad-to-touch mapping di native layer
   * untuk latency terendah (< 8ms)
   */
  startNativeMapping(): Promise<{ success: boolean; message: string }>;

  /**
   * Stop native mapping service
   */
  stopNativeMapping(): Promise<{ success: boolean; message: string }>;

  /**
   * Update profile yang digunakan native mapping
   * Bisa dipanggil saat native mapping sedang berjalan
   */
  updateNativeProfile(options: NativeMappingOptions): Promise<{ success: boolean }>;

  /**
   * Get status native mapping service
   */
  getNativeMappingStatus(): Promise<NativeMappingStatus>;

  /**
   * Set deadzone untuk analog stick
   */
  setDeadzone(options: { deadzone: number }): Promise<{ success: boolean }>;

  /**
   * Set smoothing factor untuk analog stick
   */
  setSmoothing(options: { smoothing: number }): Promise<{ success: boolean }>;

  // ==========================================
  // OVERLAY SERVICE
  // ==========================================

  /**
   * Start overlay service (floating window)
   */
  startOverlay(options: OverlayStartOptions): Promise<{ success: boolean }>;

  /**
   * Stop overlay service
   */
  stopOverlay(): Promise<{ success: boolean }>;

  /**
   * Update overlay profile
   */
  updateOverlay(options: { profileJson: string }): Promise<{ success: boolean }>;

  /**
   * Set overlay visibility
   */
  setOverlayVisibility(options: { visible: boolean }): Promise<{ success: boolean }>;

  /**
   * Set overlay opacity
   */
  setOverlayOpacity(options: { opacity: number }): Promise<{ success: boolean }>;

  // ==========================================
  // DAEMON MANAGEMENT
  // ==========================================

  /**
   * Start touch daemon service
   */
  startDaemon(): Promise<{ success: boolean }>;

  /**
   * Stop touch daemon service
   */
  stopDaemon(): Promise<{ success: boolean }>;

  /**
   * Restart touch daemon service
   */
  restartDaemon(): Promise<{ success: boolean }>;

  /**
   * Check battery optimization status
   * Return true jika battery optimization disabled (recommended)
   */
  checkBattery(): Promise<boolean>;

  /**
   * Request ignore battery optimization
   */
  requestBatteryOptimizationExemption(): Promise<{ granted: boolean }>;

  // ==========================================
  // EVENT LISTENERS
  // ==========================================

  /**
   * Add listener untuk gamepad button events
   */
  addListener(
    eventName: typeof TOUCH_INJECTION_EVENTS.GAMEPAD_BUTTON,
    listenerFunc: (data: GamepadButtonEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Add listener untuk gamepad axis events
   */
  addListener(
    eventName: typeof TOUCH_INJECTION_EVENTS.GAMEPAD_AXIS,
    listenerFunc: (data: GamepadAxisEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Add listener untuk gamepad connected events
   */
  addListener(
    eventName: typeof TOUCH_INJECTION_EVENTS.GAMEPAD_CONNECTED,
    listenerFunc: (data: GamepadConnectedEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Add listener untuk gamepad disconnected events
   */
  addListener(
    eventName: typeof TOUCH_INJECTION_EVENTS.GAMEPAD_DISCONNECTED,
    listenerFunc: (data: GamepadDisconnectedEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Add listener untuk native mapping status events
   */
  addListener(
    eventName: typeof TOUCH_INJECTION_EVENTS.NATIVE_MAPPING_STATUS,
    listenerFunc: (data: NativeMappingStatusEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Add listener untuk daemon status events
   */
  addListener(
    eventName: typeof TOUCH_INJECTION_EVENTS.DAEMON_STATUS,
    listenerFunc: (data: DaemonStatusEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Add listener untuk touch injection debug events
   */
  addListener(
    eventName: typeof TOUCH_INJECTION_EVENTS.TOUCH_INJECTION,
    listenerFunc: (data: TouchInjectionEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners
   */
  removeAllListeners(): Promise<void>;

  // ==========================================
  // UTILITY / DEBUG
  // ==========================================

  /**
   * Get plugin version
   */
  getVersion(): Promise<{ version: string }>;

  /**
   * Get performance stats
   */
  getPerformanceStats(): Promise<{
    avgLatency: number;
    maxLatency: number;
    totalInjections: number;
    droppedFrames: number;
    uptime: number;
  }>;

  /**
   * Reset all state (kill switch)
   * FIX BUG-M11: Harus instant tanpa delay
   */
  resetAll(): Promise<{ success: boolean }>;

  /**
   * Ping daemon untuk cek responsiveness
   */
  ping(): Promise<{ pong: boolean; latency: number }>;
}

// ============================================
// PLUGIN LISTENER HANDLE
// ============================================
export interface PluginListenerHandle {
  remove: () => Promise<void>;
}

// ============================================
// REGISTER PLUGIN
// ============================================

/**
 * TouchInjection plugin instance
 * 
 * Usage:
 * ```typescript
 * import TouchInjection from '../plugins/TouchInjection';
 * 
 * // Check status
 * const status = await TouchInjection.checkShizukuStatus();
 * 
 * // Inject touch
 * await TouchInjection.touchDown({ pointerId: 0, x: 100, y: 200 });
 * await TouchInjection.touchUp({ pointerId: 0 });
 * 
 * // Listen events
 * const handle = await TouchInjection.addListener('onGamepadButton', (data) => {
 *   console.log('Button:', data.buttonName, data.value);
 * });
 * ```
 */
const TouchInjection = registerPlugin<TouchInjectionPlugin>('TouchInjection', {
  web: () => import('./TouchInjectionWeb').then(m => new m.TouchInjectionWeb()),
});

export default TouchInjection;
