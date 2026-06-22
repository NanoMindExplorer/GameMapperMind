/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

export interface VirtualButton {
  id: string;
  label: string;
  type: 'button' | 'analog_stick' | 'dpad' | 'gyro_area' | 'macro' | 'swipe';
  x: number; // percentage (0 - 100)
  y: number; // percentage (0 - 100)
  width: number; // px
  height: number; // px
  mappedKey: string; // e.g., "BUTTON_A", "BUTTON_X", "STICK_L"
  androidEventCode: number;
  opacity: number;
  macroId?: string;
  deadzone?: number;
  sensitivity?: number;
  swipeDirection?: 'UP' | 'DOWN' | 'LEFT' | 'RIGHT';
  swipeDuration?: number;
}

export interface GamepadProfile {
  id: string;
  name: string;
  packageName: string;
  icon?: string;
  description: string;
  buttons: VirtualButton[];
  gyroSensitivity: number;
  deadzone: number;
  smoothing: number; // exponential smoothing factor
  isCustom: boolean;
  globalOpacity?: number; // Master opacity for all virtual buttons
  antiBanEnabled?: boolean; // Anti-ban humanized randomization
  screenshotMode?: string;
  customScreenshotUrl?: string;
}

export interface MacroAction {
  id: string;
  type: 'touch_down' | 'touch_move' | 'touch_up' | 'delay';
  x?: number; // scale 0 - 1000 for high resolution
  y?: number;
  delayMs?: number;
  pointerId: number;
}

export interface GamepadMacro {
  id: string;
  name: string;
  actions: MacroAction[];
  triggerKey: string;
  playbackSpeed: number; // multiplier e.g. 1.0, 1.5
}

export interface GyroCalibrationState {
  offsetX: number;
  offsetY: number;
  offsetZ: number;
  samplesCollected: number;
  noiseLevel: number; // RMS noise level after calibration
  lastCalibrated: string;
}

export interface ShizukuState {
  status: 'DISCONNECTED' | 'CHECKING' | 'CONNECTED_SHIZUKU' | 'CONNECTED_ADB';
  daemonRunning: boolean;
  daemonVersion: string;
  logLines: string[];
}

