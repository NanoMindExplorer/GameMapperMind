/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 * 
 * TouchInjectionWeb - Web Fallback Implementation
 * 
 * Implementasi web untuk TouchInjection plugin.
 * Digunakan saat app berjalan di browser (development/testing)
 * atau di device tanpa native plugin.
 * 
 * Fitur:
 * - Gamepad API polling untuk input detection
 * - Touch injection simulation via CustomEvent
 * - Event listener system
 * - Native mapping simulation (FIX BUG-M12)
 * - Performance tracking
 * 
 * FIX BUG-M12: Implementasi lengkap untuk startNativeMapping/stopNativeMapping
 * FIX BUG-L02: checkBattery return false di non-native environment
 */
import { WebPlugin } from '@capacitor/core';
import type {
  TouchInjectionPlugin,
  ShizukuStatusResponse,
  ShizukuCommandOptions,
  ShizukuCommandResponse,
  TouchDownOptions,
  TouchMoveOptions,
  TouchUpOptions,
  MultiTouchOptions,
  OverlayStartOptions,
  NativeMappingOptions,
  NativeMappingStatus,
  GamepadButtonEvent,
  GamepadAxisEvent,
  GamepadConnectedEvent,
  GamepadDisconnectedEvent,
  PluginListenerHandle,
} from './TouchInjection';

// ============================================
// CONSTANTS
// ============================================
const GAMEPAD_POLLING_INTERVAL = 16; // ~60fps
const MAX_POINTERS = 20;
const DEFAULT_DEADZONE = 0.15;
const DEFAULT_SMOOTHING = 0.5;

// ============================================
// TOUCH INJECTION WEB IMPLEMENTATION
// ============================================
export class TouchInjectionWeb extends WebPlugin implements TouchInjectionPlugin {
  // State
  private listeners: Map<string, Set<Function>> = new Map();
  private isNativeMappingActive = false;
  private isGamepadListening = false;
  private isDaemonRunning = false;
  private isOverlayActive = false;
  
  // Gamepad polling
  private gamepadPollingId: number | null = null;
  private previousButtonStates: Map<number, boolean[]> = new Map();
  private previousAxisStates: Map<number, number[]> = new Map();
  
  // Native mapping state (FIX BUG-M12)
  private nativeMappingStartTime = 0;
  private nativeMappingProfile: any = null;
  private nativeMappingDeadzone = DEFAULT_DEADZONE;
  private nativeMappingSmoothing = DEFAULT_SMOOTHING;
  private nativeMappingTotalInjections = 0;
  private nativeMappingLastActivity = 0;
  private nativeMappingLatencies: number[] = [];
  
  // Performance tracking
  private performanceStats = {
    totalInjections: 0,
    latencies: [] as number[],
    startTime: Date.now(),
    droppedFrames: 0,
  };

  // Active touch points
  private activeTouches: Map<number, { x: number; y: number; downTime: number }> = new Map();

  constructor() {
    super();
    console.log('[TouchInjectionWeb] Plugin initialized (web fallback mode)');
  }

  // ==========================================
  // SERVICE LIFECYCLE
  // ==========================================

  async bindService(): Promise<{ success: boolean; message: string }> {
    console.log('[TouchInjectionWeb] bindService called');
    return { success: true, message: 'Web fallback - no native service binding required' };
  }

  async unbindService(): Promise<{ success: boolean; message: string }> {
    console.log('[TouchInjectionWeb] unbindService called');
    this.stopGamepadPolling();
    this.activeTouches.clear();
    return { success: true, message: 'Web fallback - service unbound' };
  }

  // ==========================================
  // SHIZUKU INTEGRATION
  // ==========================================

  async checkShizukuStatus(): Promise<ShizukuStatusResponse> {
    console.log('[TouchInjectionWeb] checkShizukuStatus called');
    // FIX: Return DISCONNECTED di web environment
    return {
      status: 'DISCONNECTED',
      daemonRunning: this.isDaemonRunning,
      daemonVersion: 'web-fallback-1.0.0',
      lastUpdated: Date.now(),
      extra: {
        environment: 'web',
        note: 'Shizuku not available in web environment. Use native Android build for touch injection.',
      },
    };
  }

  async executeShizukuCommand(options: ShizukuCommandOptions): Promise<ShizukuCommandResponse> {
    console.log('[TouchInjectionWeb] executeShizukuCommand:', options.command);
    // Web fallback - command tidak bisa dieksekusi
    return {
      success: false,
      error: 'Shizuku commands not available in web environment',
      exitCode: -1,
    };
  }

  // ==========================================
  // TOUCH INJECTION
  // ==========================================

  async touchDown(options: TouchDownOptions): Promise<void> {
    const startTime = performance.now();
    
    // Validate pointer ID
    if (options.pointerId < 0 || options.pointerId >= MAX_POINTERS) {
      throw new Error(`Invalid pointerId: ${options.pointerId}. Must be 0-${MAX_POINTERS - 1}`);
    }

    // Store active touch
    this.activeTouches.set(options.pointerId, {
      x: options.x,
      y: options.y,
      downTime: Date.now(),
    });

    // Dispatch custom event untuk visual debugging
    this.dispatchTouchInjectionEvent('down', options.pointerId, options.x, options.y, startTime);

    // Update performance stats
    this.updatePerformanceStats(startTime);
    this.nativeMappingTotalInjections++;
    this.nativeMappingLastActivity = Date.now();

    console.debug(`[TouchInjectionWeb] touchDown: id=${options.pointerId}, x=${options.x}, y=${options.y}`);
  }

  async touchMove(options: TouchMoveOptions): Promise<void> {
    const startTime = performance.now();
    
    const touch = this.activeTouches.get(options.pointerId);
    if (!touch) {
      console.warn(`[TouchInjectionWeb] touchMove: pointerId ${options.pointerId} not active`);
      return;
    }

    // Update position
    touch.x = options.x;
    touch.y = options.y;

    // Dispatch event
    this.dispatchTouchInjectionEvent('move', options.pointerId, options.x, options.y, startTime);

    // Update stats
    this.updatePerformanceStats(startTime);
    this.nativeMappingTotalInjections++;
    this.nativeMappingLastActivity = Date.now();

    console.debug(`[TouchInjectionWeb] touchMove: id=${options.pointerId}, x=${options.x}, y=${options.y}`);
  }

  async touchUp(options: TouchUpOptions): Promise<void> {
    const startTime = performance.now();
    
    const touch = this.activeTouches.get(options.pointerId);
    if (!touch) {
      console.warn(`[TouchInjectionWeb] touchUp: pointerId ${options.pointerId} not active`);
      return;
    }

    // Dispatch event dengan posisi terakhir
    this.dispatchTouchInjectionEvent('up', options.pointerId, touch.x, touch.y, startTime);

    // Remove from active touches
    this.activeTouches.delete(options.pointerId);

    // Update stats
    this.updatePerformanceStats(startTime);
    this.nativeMappingTotalInjections++;
    this.nativeMappingLastActivity = Date.now();

    console.debug(`[TouchInjectionWeb] touchUp: id=${options.pointerId}`);
  }

  async multiTouch(options: MultiTouchOptions): Promise<void> {
    const startTime = performance.now();

    for (const touch of options.touches) {
      if (touch.id >= 0 && touch.id < MAX_POINTERS) {
        this.activeTouches.set(touch.id, {
          x: touch.x,
          y: touch.y,
          downTime: Date.now(),
        });
        this.dispatchTouchInjectionEvent('multi', touch.id, touch.x, touch.y, startTime);
      }
    }

    this.updatePerformanceStats(startTime);
    this.nativeMappingTotalInjections += options.touches.length;
    this.nativeMappingLastActivity = Date.now();

    console.debug(`[TouchInjectionWeb] multiTouch: ${options.touches.length} touches`);
  }

  async injectKeyEvent(options: { keyCode: number; action: 'down' | 'up' }): Promise<void> {
    console.debug(`[TouchInjectionWeb] injectKeyEvent: keyCode=${options.keyCode}, action=${options.action}`);
    
    // Dispatch event untuk debugging
    window.dispatchEvent(new CustomEvent('touch-injection-key', {
      detail: {
        keyCode: options.keyCode,
        action: options.action,
        timestamp: Date.now(),
      },
    }));
  }

  // ==========================================
  // GAMEPAD LISTENER
  // ==========================================

  async startGamepadListener(): Promise<{ success: boolean }> {
    if (this.isGamepadListening) {
      console.warn('[TouchInjectionWeb] Gamepad listener already running');
      return { success: true };
    }

    console.log('[TouchInjectionWeb] Starting gamepad listener');
    this.isGamepadListening = true;

    // Register browser gamepad events
    window.addEventListener('gamepadconnected', this.handleGamepadConnected);
    window.addEventListener('gamepaddisconnected', this.handleGamepadDisconnected);

    // Start polling
    this.startGamepadPolling();

    return { success: true };
  }

  async stopGamepadListener(): Promise<{ success: boolean }> {
    if (!this.isGamepadListening) {
      return { success: true };
    }

    console.log('[TouchInjectionWeb] Stopping gamepad listener');
    this.isGamepadListening = false;

    window.removeEventListener('gamepadconnected', this.handleGamepadConnected);
    window.removeEventListener('gamepaddisconnected', this.handleGamepadDisconnected);

    this.stopGamepadPolling();
    this.previousButtonStates.clear();
    this.previousAxisStates.clear();

    return { success: true };
  }

  async getConnectedGamepads(): Promise<{
    gamepads: Array<{
      id: string;
      name: string;
      index: number;
      buttonCount: number;
      axisCount: number;
    }>;
  }> {
    const gamepads = navigator.getGamepads();
    const result: Array<{
      id: string;
      name: string;
      index: number;
      buttonCount: number;
      axisCount: number;
    }> = [];

    for (const gp of gamepads) {
      if (gp) {
        result.push({
          id: gp.id,
          name: gp.id,
          index: gp.index,
          buttonCount: gp.buttons.length,
          axisCount: gp.axes.length,
        });
      }
    }

    return { gamepads: result };
  }

  // ==========================================
  // NATIVE MAPPING SERVICE
  // FIX BUG-M12: Implementasi lengkap
  // ==========================================

  async startNativeMapping(): Promise<{ success: boolean; message: string }> {
    if (this.isNativeMappingActive) {
      console.warn('[TouchInjectionWeb] Native mapping already active');
      return { success: true, message: 'Already running' };
    }

    console.log('[TouchInjectionWeb] Starting native mapping service');
    this.isNativeMappingActive = true;
    this.nativeMappingStartTime = Date.now();
    this.nativeMappingTotalInjections = 0;
    this.nativeMappingLatencies = [];

    // Emit status event
    this.emitEvent('onNativeMappingStatus', {
      status: 'STARTED',
      message: 'Native mapping service started (web simulation)',
      details: await this.getNativeMappingStatus(),
    });

    return { success: true, message: 'Native mapping started (web simulation mode)' };
  }

  async stopNativeMapping(): Promise<{ success: boolean; message: string }> {
    if (!this.isNativeMappingActive) {
      return { success: true, message: 'Already stopped' };
    }

    console.log('[TouchInjectionWeb] Stopping native mapping service');
    this.isNativeMappingActive = false;

    // Emit status event
    this.emitEvent('onNativeMappingStatus', {
      status: 'STOPPED',
      message: 'Native mapping service stopped',
      details: await this.getNativeMappingStatus(),
    });

    return { success: true, message: 'Native mapping stopped' };
  }

  async updateNativeProfile(options: NativeMappingOptions): Promise<{ success: boolean }> {
    console.log('[TouchInjectionWeb] Updating native profile');

    try {
      this.nativeMappingProfile = JSON.parse(options.profileJson);
      
      // Apply deadzone dan smoothing dari profile
      if (this.nativeMappingProfile.deadzone !== undefined) {
        this.nativeMappingDeadzone = this.nativeMappingProfile.deadzone;
      }
      if (this.nativeMappingProfile.smoothing !== undefined) {
        this.nativeMappingSmoothing = this.nativeMappingProfile.smoothing;
      }

      // Emit event jika mapping aktif
      if (this.isNativeMappingActive) {
        this.emitEvent('onNativeMappingStatus', {
          status: 'PROFILE_UPDATED',
          message: `Profile updated: ${this.nativeMappingProfile.name || 'unknown'}`,
          details: await this.getNativeMappingStatus(),
        });
      }

      return { success: true };
    } catch (err) {
      console.error('[TouchInjectionWeb] Failed to parse profile:', err);
      return { success: false };
    }
  }

  async getNativeMappingStatus(): Promise<NativeMappingStatus> {
    const now = Date.now();
    const avgLatency = this.nativeMappingLatencies.length > 0
      ? this.nativeMappingLatencies.reduce((a, b) => a + b, 0) / this.nativeMappingLatencies.length
      : 0;

    return {
      isRunning: this.isNativeMappingActive,
      latency: Math.round(avgLatency * 100) / 100,
      activePointers: this.activeTouches.size,
      uptime: this.isNativeMappingActive ? now - this.nativeMappingStartTime : 0,
      totalInjections: this.nativeMappingTotalInjections,
      lastActivity: this.nativeMappingLastActivity,
      lastError: undefined,
    };
  }

  async setDeadzone(options: { deadzone: number }): Promise<{ success: boolean }> {
    if (options.deadzone < 0 || options.deadzone > 1) {
      console.error('[TouchInjectionWeb] Invalid deadzone value:', options.deadzone);
      return { success: false };
    }
    this.nativeMappingDeadzone = options.deadzone;
    console.log(`[TouchInjectionWeb] Deadzone set to ${options.deadzone}`);
    return { success: true };
  }

  async setSmoothing(options: { smoothing: number }): Promise<{ success: boolean }> {
    if (options.smoothing < 0 || options.smoothing > 1) {
      console.error('[TouchInjectionWeb] Invalid smoothing value:', options.smoothing);
      return { success: false };
    }
    this.nativeMappingSmoothing = options.smoothing;
    console.log(`[TouchInjectionWeb] Smoothing set to ${options.smoothing}`);
    return { success: true };
  }

  // ==========================================
  // OVERLAY SERVICE
  // ==========================================

  async startOverlay(options: OverlayStartOptions): Promise<{ success: boolean }> {
    console.log('[TouchInjectionWeb] Starting overlay service');
    this.isOverlayActive = true;

    // Dispatch event untuk visual overlay di web
    window.dispatchEvent(new CustomEvent('overlay-start', {
      detail: {
        profileJson: options.profileJson,
        clickable: options.clickable ?? false,
        opacity: options.opacity ?? 80,
      },
    }));

    return { success: true };
  }

  async stopOverlay(): Promise<{ success: boolean }> {
    console.log('[TouchInjectionWeb] Stopping overlay service');
    this.isOverlayActive = false;

    window.dispatchEvent(new CustomEvent('overlay-stop'));

    return { success: true };
  }

  async updateOverlay(options: { profileJson: string }): Promise<{ success: boolean }> {
    console.log('[TouchInjectionWeb] Updating overlay');

    window.dispatchEvent(new CustomEvent('overlay-update', {
      detail: { profileJson: options.profileJson },
    }));

    return { success: true };
  }

  async setOverlayVisibility(options: { visible: boolean }): Promise<{ success: boolean }> {
    console.log(`[TouchInjectionWeb] Overlay visibility: ${options.visible}`);
    
    window.dispatchEvent(new CustomEvent('overlay-visibility', {
      detail: { visible: options.visible },
    }));

    return { success: true };
  }

  async setOverlayOpacity(options: { opacity: number }): Promise<{ success: boolean }> {
    console.log(`[TouchInjectionWeb] Overlay opacity: ${options.opacity}`);
    
    window.dispatchEvent(new CustomEvent('overlay-opacity', {
      detail: { opacity: options.opacity },
    }));

    return { success: true };
  }

  // ==========================================
  // DAEMON MANAGEMENT
  // ==========================================

  async startDaemon(): Promise<{ success: boolean }> {
    console.log('[TouchInjectionWeb] Starting daemon');
    this.isDaemonRunning = true;

    this.emitEvent('onDaemonStatus', {
      running: true,
      version: 'web-fallback-1.0.0',
    });

    return { success: true };
  }

  async stopDaemon(): Promise<{ success: boolean }> {
    console.log('[TouchInjectionWeb] Stopping daemon');
    this.isDaemonRunning = false;

    this.emitEvent('onDaemonStatus', {
      running: false,
    });

    return { success: true };
  }

  async restartDaemon(): Promise<{ success: boolean }> {
    console.log('[TouchInjectionWeb] Restarting daemon');
    await this.stopDaemon();
    await this.startDaemon();
    return { success: true };
  }

  async checkBattery(): Promise<boolean> {
    // FIX BUG-L02: Return false di web/non-native environment
    console.log('[TouchInjectionWeb] checkBattery - returning false (web environment)');
    return false;
  }

  async requestBatteryOptimizationExemption(): Promise<{ granted: boolean }> {
    console.log('[TouchInjectionWeb] requestBatteryOptimizationExemption - not applicable in web');
    return { granted: false };
  }

  // ==========================================
  // EVENT LISTENERS
  // ==========================================

  async addListener(eventName: string, listenerFunc: Function): Promise<PluginListenerHandle> {
    if (!this.listeners.has(eventName)) {
      this.listeners.set(eventName, new Set());
    }
    this.listeners.get(eventName)!.add(listenerFunc);

    return {
      remove: async () => {
        this.listeners.get(eventName)?.delete(listenerFunc);
      },
    };
  }

  async removeAllListeners(): Promise<void> {
    this.listeners.clear();
    console.log('[TouchInjectionWeb] All listeners removed');
  }

  // ==========================================
  // UTILITY / DEBUG
  // ==========================================

  async getVersion(): Promise<{ version: string }> {
    return { version: '1.0.0-web-fallback' };
  }

  async getPerformanceStats(): Promise<{
    avgLatency: number;
    maxLatency: number;
    totalInjections: number;
    droppedFrames: number;
    uptime: number;
  }> {
    const latencies = this.performanceStats.latencies;
    const avgLatency = latencies.length > 0
      ? latencies.reduce((a, b) => a + b, 0) / latencies.length
      : 0;
    const maxLatency = latencies.length > 0
      ? Math.max(...latencies)
      : 0;

    return {
      avgLatency: Math.round(avgLatency * 100) / 100,
      maxLatency: Math.round(maxLatency * 100) / 100,
      totalInjections: this.performanceStats.totalInjections,
      droppedFrames: this.performanceStats.droppedFrames,
      uptime: Date.now() - this.performanceStats.startTime,
    };
  }

  async resetAll(): Promise<{ success: boolean }> {
    // FIX BUG-M11: Instant reset tanpa delay
    console.log('[TouchInjectionWeb] resetAll - instant reset');

    // Stop semua service
    this.isNativeMappingActive = false;
    this.isGamepadListening = false;
    this.isDaemonRunning = false;
    this.isOverlayActive = false;

    // Clear semua state
    this.activeTouches.clear();
    this.previousButtonStates.clear();
    this.previousAxisStates.clear();
    this.stopGamepadPolling();

    // Reset performance stats
    this.performanceStats = {
      totalInjections: 0,
      latencies: [],
      startTime: Date.now(),
      droppedFrames: 0,
    };

    // Reset native mapping stats
    this.nativeMappingStartTime = 0;
    this.nativeMappingProfile = null;
    this.nativeMappingTotalInjections = 0;
    this.nativeMappingLastActivity = 0;
    this.nativeMappingLatencies = [];

    // Remove all listeners
    this.listeners.clear();

    console.log('[TouchInjectionWeb] resetAll completed');
    return { success: true };
  }

  async ping(): Promise<{ pong: boolean; latency: number }> {
    const start = performance.now();
    // Simulate some processing
    await new Promise(r => setTimeout(r, 1));
    const latency = performance.now() - start;
    return { pong: true, latency };
  }

  // ==========================================
  // PRIVATE METHODS - GAMEPAD POLLING
  // ==========================================

  private handleGamepadConnected = (event: GamepadEvent) => {
    const gp = event.gamepad;
    console.log(`[TouchInjectionWeb] Gamepad connected: ${gp.id} (index: ${gp.index})`);

    const connectedEvent: GamepadConnectedEvent = {
      name: gp.id,
      id: gp.id,
      vendorId: undefined,
      productId: undefined,
      buttonCount: gp.buttons.length,
      axisCount: gp.axes.length,
      hasRumble: 'vibrationActuator' in gp,
      gamepadIndex: gp.index,
    };

    this.emitEvent('onGamepadConnected', connectedEvent);
  };

  private handleGamepadDisconnected = (event: GamepadEvent) => {
    const gp = event.gamepad;
    console.log(`[TouchInjectionWeb] Gamepad disconnected: ${gp.id} (index: ${gp.index})`);

    const disconnectedEvent: GamepadDisconnectedEvent = {
      name: gp.id,
      id: gp.id,
      gamepadIndex: gp.index,
    };

    this.previousButtonStates.delete(gp.index);
    this.previousAxisStates.delete(gp.index);

    this.emitEvent('onGamepadDisconnected', disconnectedEvent);
  };

  private startGamepadPolling() {
    const poll = () => {
      if (!this.isGamepadListening) return;

      const gamepads = navigator.getGamepads();
      
      for (const gp of gamepads) {
        if (!gp) continue;
        this.processGamepad(gp);
      }

      this.gamepadPollingId = requestAnimationFrame(poll);
    };

    this.gamepadPollingId = requestAnimationFrame(poll);
  }

  private stopGamepadPolling() {
    if (this.gamepadPollingId !== null) {
      cancelAnimationFrame(this.gamepadPollingId);
      this.gamepadPollingId = null;
    }
  }

  private processGamepad(gp: Gamepad) {
    // Process buttons
    const prevButtons = this.previousButtonStates.get(gp.index) || [];
    const currentButtons: boolean[] = [];

    gp.buttons.forEach((button, index) => {
      const isPressed = button.pressed || button.value > 0.5;
      currentButtons.push(isPressed);

      const wasPressed = prevButtons[index] || false;

      // Detect state change
      if (isPressed !== wasPressed) {
        const buttonName = this.mapButtonIndexToName(index);
        const buttonEvent: GamepadButtonEvent = {
          buttonName,
          value: isPressed ? (button.value > 0 ? button.value : 1) : 0,
          gamepadIndex: gp.index,
          timestamp: Date.now(),
        };

        this.emitEvent('onGamepadButton', buttonEvent);
      }
    });

    this.previousButtonStates.set(gp.index, currentButtons);

    // Process axes
    if (gp.axes.length >= 2) {
      const prevAxes = this.previousAxisStates.get(gp.index) || [];
      const currentAxes = Array.from(gp.axes);

      // Apply deadzone
      const processedAxes = currentAxes.map((value, index) => {
        const absValue = Math.abs(value);
        if (absValue < this.nativeMappingDeadzone) return 0;
        const sign = Math.sign(value);
        return sign * (absValue - this.nativeMappingDeadzone) / (1 - this.nativeMappingDeadzone);
      });

      // Check if axes changed significantly
      let axesChanged = false;
      for (let i = 0; i < processedAxes.length; i++) {
        const prev = prevAxes[i] || 0;
        const curr = processedAxes[i];
        if (Math.abs(curr - prev) > 0.01) {
          axesChanged = true;
          break;
        }
      }

      if (axesChanged) {
        const axisEvent: GamepadAxisEvent = {
          axes: processedAxes,
          gamepadIndex: gp.index,
          timestamp: Date.now(),
        };

        this.emitEvent('onGamepadAxis', axisEvent);
      }

      this.previousAxisStates.set(gp.index, processedAxes);
    }
  }

  private mapButtonIndexToName(index: number): string {
    // Standard gamepad mapping (W3C Gamepad API)
    const buttonMap: Record<number, string> = {
      0: 'A',
      1: 'B',
      2: 'X',
      3: 'Y',
      4: 'LB',
      5: 'RB',
      6: 'LT',
      7: 'RT',
      8: 'SELECT',
      9: 'START',
      10: 'L_STICK_CLICK',
      11: 'R_STICK_CLICK',
      12: 'DPAD_UP',
      13: 'DPAD_DOWN',
      14: 'DPAD_LEFT',
      15: 'DPAD_RIGHT',
      16: 'HOME',
      17: 'TOUCHPAD',
    };

    return buttonMap[index] || `BUTTON_${index}`;
  }

  // ==========================================
  // PRIVATE METHODS - HELPERS
  // ==========================================

  private emitEvent(eventName: string, data: any) {
    const listeners = this.listeners.get(eventName);
    if (listeners) {
      listeners.forEach(listener => {
        try {
          listener(data);
        } catch (err) {
          console.error(`[TouchInjectionWeb] Error in listener for ${eventName}:`, err);
        }
      });
    }
  }

  private dispatchTouchInjectionEvent(
    action: 'down' | 'move' | 'up' | 'multi',
    pointerId: number,
    x: number,
    y: number,
    startTime: number
  ) {
    const latency = performance.now() - startTime;

    // Dispatch DOM event untuk visual debugging
    window.dispatchEvent(new CustomEvent('touch-injection', {
      detail: {
        action,
        pointerId,
        x: Math.round(x),
        y: Math.round(y),
        timestamp: Date.now(),
        latency: Math.round(latency * 100) / 100,
      },
    }));

    // Emit ke plugin listeners
    this.emitEvent('onTouchInjection', {
      action,
      pointerId,
      x,
      y,
      timestamp: Date.now(),
      latency,
    });
  }

  private updatePerformanceStats(startTime: number) {
    const latency = performance.now() - startTime;
    this.performanceStats.totalInjections++;
    this.performanceStats.latencies.push(latency);

    // Keep only last 100 latency measurements
    if (this.performanceStats.latencies.length > 100) {
      this.performanceStats.latencies.shift();
    }

    // Track native mapping latencies
    if (this.isNativeMappingActive) {
      this.nativeMappingLatencies.push(latency);
      if (this.nativeMappingLatencies.length > 100) {
        this.nativeMappingLatencies.shift();
      }
    }
  }
}
