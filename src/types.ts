/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

// ============================================================
// Controller mode — determines button mapping convention.
// Different controllers expose different button orders:
//   - XBOX: A=0, B=1, X=2, Y=3 (Xbox layout)
//   - SWITCH: B=0, A=1, Y=2, X=3 (Nintendo layout, A/B swapped)
//   - GENERIC: default to Xbox layout
//   - VORTEX_XP107: dual-mode controller, auto-detect from gamepad.id
// ============================================================
export type ControllerMode = 'XBOX' | 'SWITCH' | 'GENERIC' | 'VORTEX_XP107';

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

export interface HardwareMapping {
  hardwareKey: string; // e.g., "A", "B", "L2", "R2"
  x: number;           // absolute pixel X
  y: number;           // absolute pixel Y
}

export interface JoystickConfig {
  centerX: number; // absolute pixel center X
  centerY: number; // absolute pixel center Y
  radius: number;  // pixel radius for stick range
}

// ============================================================
// Gyro → Touch mapping configuration
// ============================================================
export interface GyroMapping {
  enabled: boolean;
  // Mode determines what the gyro controls
  mode: 'camera' | 'aim' | 'steering' | 'disabled';
  // Sensitivity multiplier (rad/s → pixel/s)
  sensitivityX: number;
  sensitivityY: number;
  // Invert axes
  invertX: boolean;
  invertY: boolean;
  // Deadzone in rad/s — small movements ignored
  deadzone: number;
  // Smoothing (0=none, 1=max)
  smoothing: number;
  // Target touch zone for camera control (right-stick area)
  targetX: number; // absolute pixel X center
  targetY: number; // absolute pixel Y center
  targetRadius: number;
}

// ============================================================
// Anti-ban configuration — humanizes injected touch events
// ============================================================
export interface AntiBanConfig {
  enabled: boolean;
  // Coordinate jitter in pixels (±)
  coordinateJitter: number;
  // Timing jitter in milliseconds (±)
  timingJitter: number;
  // Touch pressure variance (0.0 - 1.0)
  pressureVariance: number;
  // Touch size variance (0.0 - 1.0)
  sizeVariance: number;
  // Randomize stroke duration (±ms)
  strokeDurationJitter: number;
  // Occasionally insert micro-pauses (probability 0.0 - 1.0)
  microPauseProbability: number;
  // Max micro-pause duration (ms)
  microPauseMaxMs: number;
}

export interface GamepadProfile {
  id: string;
  name: string;
  packageName: string;
  icon?: string;
  description: string;
  buttons: VirtualButton[];
  mappings?: HardwareMapping[];
  leftJoystick?: JoystickConfig;
  rightJoystick?: JoystickConfig;
  gyroSensitivity: number;
  deadzone: number;
  smoothing: number;
  isCustom: boolean;
  globalOpacity?: number;
  antiBanEnabled?: boolean;
  antiBanConfig?: AntiBanConfig;
  gyroMapping?: GyroMapping;
  screenshotMode?: string;
  customScreenshotUrl?: string;
  // Auto-start when this package is detected as foreground app
  autoStartEnabled?: boolean;
}

export interface MacroAction {
  id: string;
  type: 'touch_down' | 'touch_move' | 'touch_up' | 'delay';
  x?: number; // absolute pixel
  y?: number;
  delayMs?: number;
  pointerId: number;
  // Captured timestamp relative to recording start (ms)
  timestamp?: number;
  // For real-recorded actions: original pressure/size
  pressure?: number;
  size?: number;
}

export interface GamepadMacro {
  id: string;
  name: string;
  actions: MacroAction[];
  triggerKey: string;
  playbackSpeed: number;
  // Loop configuration
  loopCount: number; // 0 = no loop, -1 = infinite, N = N times
  // Recorded via real capture or manual UI
  recordedVia: 'manual' | 'real_capture';
  // Recording metadata
  recordedAt?: string;
  recordedDurationMs?: number;
}

export interface GyroCalibrationState {
  offsetX: number;
  offsetY: number;
  offsetZ: number;
  samplesCollected: number;
  noiseLevel: number;
  lastCalibrated: string;
}

export interface ShizukuState {
  status: 'DISCONNECTED' | 'CHECKING' | 'CONNECTED_SHIZUKU' | 'CONNECTED_ADB';
  daemonRunning: boolean;
  daemonVersion: string;
  logLines: string[];
}

// ============================================================
// Onboarding state — persisted in Preferences
// ============================================================
export interface OnboardingState {
  completed: boolean;
  currentStep: number; // 0-4 (5 steps total)
  steps: {
    welcome: boolean;
    installShizuku: boolean;
    grantPermissions: boolean;
    connectGamepad: boolean;
    calibrateProfile: boolean;
  };
  // Detected hardware info collected during onboarding
  detectedController?: {
    id: string;
    mode: ControllerMode;
    vendor: string;
  };
  // User skipped Shizuku setup (will use Accessibility fallback)
  shizukuSkipped?: boolean;
}

// ============================================================
// Game detection event — emitted by TouchAccessibilityService
// when foreground package changes
// ============================================================
export interface GameDetectionEvent {
  packageName: string;
  matchedProfileId?: string;
  timestamp: number;
}

// ============================================================
// Real macro capture event — emitted by AccessibilityService
// when capturing MotionEvents for macro recording
// ============================================================
export interface MacroCaptureEvent {
  action: 'down' | 'move' | 'up';
  pointerId: number;
  x: number;
  y: number;
  pressure: number;
  size: number;
  timestamp: number; // ms since recording start
}
