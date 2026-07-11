/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

// INTERACTION-EXPANSION: Flexible trigger system.
// trigger.inputs[] allows any physical gamepad button (including non-standard ones
// detected via raw evdev) to be assigned as trigger. Chord = multiple buttons must
// all be pressed simultaneously.
export interface Trigger {
  type: 'button' | 'chord' | 'axis';
  inputs: string[];          // ["A"] or ["LB","RB"] for chord, ["LT"] for axis
  axisThreshold?: number;    // for axis trigger: 0.0-1.0 threshold (default 0.5)
}

export type InteractionType = 'tap' | 'hold' | 'swipe' | 'turbo' | 'toggle' | 'charge' | 'gesture' | 'macro';

export interface GesturePoint {
  x: number;   // percentage 0-100
  y: number;   // percentage 0-100
  delayMs: number;  // delay before moving to this point
}

export interface VirtualButton {
  id: string;
  label: string;
  type: 'button' | 'analog_stick' | 'dpad' | 'gyro_area' | 'macro' | 'swipe';
  x: number; // percentage (0 - 100)
  y: number; // percentage (0 - 100)
  width: number; // px
  height: number; // px
  mappedKey: string; // e.g., "BUTTON_A", "BUTTON_X", "STICK_L" (legacy, kept for backward compat)
  androidEventCode: number;
  opacity: number;
  macroId?: string;
  deadzone?: number;
  smoothing?: number; // per-stick exponential smoothing (0-0.95). Native reads this per-button,
                       // NOT the profile-level GamepadProfile.smoothing field below.
  sensitivity?: number;
  radius?: number;
  swipeDirection?: 'UP' | 'DOWN' | 'LEFT' | 'RIGHT';
  swipeDuration?: number;
  inputSource?: 'TOUCHSCREEN' | 'MOUSE' | 'STYLUS' | 'GAMEPAD';
  toolType?: 'FINGER' | 'STYLUS';
  tapDuration?: number;
  player?: 1 | 2 | 3 | 4;
  sensitivityCurve?: 'linear' | 'exponential' | 'parabolic' | 'concave' | 'custom';
  curvePoints?: number[][];

  // INTERACTION-EXPANSION: New fields for flexible trigger + interaction types.
  // If trigger is present, it takes precedence over mappedKey for injection evaluation.
  trigger?: Trigger;
  interactionType?: InteractionType;  // default: 'hold' for buttons, 'joystick' for analog_stick
  repeatIntervalMs?: number;          // turbo: interval between auto-taps (default 50ms)
  chargeThresholdMs?: number;         // charge: hold duration before action fires (default 500ms)
  gesturePoints?: GesturePoint[];     // gesture: sequence of touch points [{x,y,delayMs}]
  stickMode?: 'joystick' | 'drag';    // analog stick: 'joystick' (virtual) or 'drag' (continuous move)
  swipeEndX?: number;                 // swipe end X (percentage 0-100)
  swipeEndY?: number;                 // swipe end Y (percentage 0-100)
  swipeReturn?: boolean;              // swipe: return to start position after reaching end
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
  orientation?: 'landscape' | 'portrait' | 'auto';
  portraitButtons?: VirtualButton[];
  hapticIntensity?: number;
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
  mode?: 'shizuku' | 'desktop' | 'adb';
  daemonRunning: boolean;
  daemonVersion: string;
  logLines: string[];
  recoveryState?: 'INSTALLED' | 'RUNNING' | 'PERMISSION' | 'BOUND' | 'DAEMON_ALIVE';
}

