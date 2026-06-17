import { GamepadProfile, GamepadMacro, AntiBanConfig, GyroMapping, ControllerMode } from './types';

// ============================================================
// Default anti-ban configuration — applied to all profiles
// where antiBanEnabled = true but antiBanConfig is undefined.
// ============================================================
export const DEFAULT_ANTI_BAN: AntiBanConfig = {
  enabled: true,
  coordinateJitter: 4,     // ±4 px radial randomization
  timingJitter: 3,         // ±3 ms
  pressureVariance: 0.15,  // 0..0.15 around 1.0
  sizeVariance: 0.10,
  strokeDurationJitter: 12,
  microPauseProbability: 0.02,
  microPauseMaxMs: 45,
};

// ============================================================
// Default gyro mapping — camera control via right-stick zone
// ============================================================
export const DEFAULT_GYRO_MAPPING: GyroMapping = {
  enabled: false,
  mode: 'camera',
  sensitivityX: 800,   // rad/s → px/s multiplier
  sensitivityY: 600,
  invertX: false,
  invertY: false,
  deadzone: 0.05,       // ~3 deg/s
  smoothing: 0.3,
  targetX: 1530,        // matches rightJoystick default
  targetY: 540,
  targetRadius: 150,
};

// Helper to build standard button list for a profile.
function standardGenshinButtons() {
  return [
    { id: 'a',         label: 'Attack',    type: 'button' as const,       x: 85, y: 75, width: 56, height: 56, mappedKey: 'BUTTON_A',     androidEventCode: 96,  opacity: 0.8 },
    { id: 'b',         label: 'B',         type: 'button' as const,       x: 90, y: 65, width: 56, height: 56, mappedKey: 'BUTTON_B',     androidEventCode: 97,  opacity: 0.8 },
    { id: 'x',         label: 'X',         type: 'button' as const,       x: 80, y: 65, width: 56, height: 56, mappedKey: 'BUTTON_X',     androidEventCode: 99,  opacity: 0.8 },
    { id: 'y',         label: 'Y',         type: 'button' as const,       x: 88, y: 55, width: 56, height: 56, mappedKey: 'BUTTON_Y',     androidEventCode: 100, opacity: 0.8 },
    { id: 'l1',        label: 'LB',        type: 'button' as const,       x: 8,  y: 25, width: 70, height: 36, mappedKey: 'BUTTON_L1',    androidEventCode: 101, opacity: 0.8 },
    { id: 'r1',        label: 'RB',        type: 'button' as const,       x: 90, y: 25, width: 70, height: 36, mappedKey: 'BUTTON_R1',    androidEventCode: 102, opacity: 0.8 },
    { id: 'l2',        label: 'LT',        type: 'button' as const,       x: 8,  y: 15, width: 75, height: 36, mappedKey: 'BUTTON_L2',    androidEventCode: 104, opacity: 0.8 },
    { id: 'r2',        label: 'RT',        type: 'button' as const,       x: 90, y: 15, width: 75, height: 36, mappedKey: 'BUTTON_R2',    androidEventCode: 105, opacity: 0.8 },
    { id: 'start',     label: 'Start',     type: 'button' as const,       x: 90, y: 5,  width: 45, height: 24, mappedKey: 'BUTTON_START',  androidEventCode: 108, opacity: 0.6 },
    { id: 'select',    label: 'Select',    type: 'button' as const,       x: 8,  y: 5,  width: 45, height: 24, mappedKey: 'BUTTON_SELECT', androidEventCode: 109, opacity: 0.6 },
    { id: 'l_stick',   label: 'Move',      type: 'analog_stick' as const, x: 15, y: 70, width: 140, height: 140, mappedKey: 'L_STICK',   androidEventCode: 0,   opacity: 0.5, deadzone: 0.1 },
    { id: 'r_stick',   label: 'Camera',    type: 'analog_stick' as const, x: 80, y: 40, width: 140, height: 140, mappedKey: 'R_STICK',   androidEventCode: 0,   opacity: 0.5, deadzone: 0.1 },
  ];
}

function standardMappings() {
  return [
    { hardwareKey: 'A',      x: 1620, y: 810 },
    { hardwareKey: 'B',      x: 1728, y: 702 },
    { hardwareKey: 'X',      x: 1536, y: 702 },
    { hardwareKey: 'Y',      x: 1680, y: 594 },
    { hardwareKey: 'LB',     x: 150,  y: 270 },
    { hardwareKey: 'RB',     x: 1728, y: 270 },
    { hardwareKey: 'L2',     x: 150,  y: 162 },
    { hardwareKey: 'R2',     x: 1728, y: 162 },
    { hardwareKey: 'START',  x: 1728, y: 54 },
    { hardwareKey: 'SELECT', x: 150,  y: 54 },
    { hardwareKey: 'L3',     x: 0,    y: 0 },
    { hardwareKey: 'R3',     x: 0,    y: 0 },
    { hardwareKey: 'UP',     x: 0,    y: 0 },
    { hardwareKey: 'DOWN',   x: 0,    y: 0 },
    { hardwareKey: 'LEFT',   x: 0,    y: 0 },
    { hardwareKey: 'RIGHT',  x: 0,    y: 0 },
  ];
}

// ============================================================
// Controller detection — determine mode from gamepad.id string
// Vortex XP107 is dual-mode (Xbox / Switch), auto-detect by
// looking at BTN_MODE / Nintendo-specific identifiers.
// ============================================================
export function detectControllerMode(gamepadId: string): {
  mode: ControllerMode;
  vendor: string;
  isDualMode: boolean;
} {
  const id = (gamepadId || '').toLowerCase();

  // Vortex XP107 dual-mode detection (primary target hardware)
  if (id.includes('vortex') || id.includes('xp107')) {
    // XP107 toggles between Xbox/Switch mode via physical switch on the controller.
    // The gamepad.id string contains the active mode identifier:
    //   - Xbox mode:  usually "Xbox" or "X-Input" or vendor 045e
    //   - Switch mode: usually "Switch" or "Pro Controller" or vendor 057e
    if (id.includes('switch') || id.includes('pro controller') || id.includes('057e')) {
      return { mode: 'VORTEX_XP107', vendor: 'Vortex', isDualMode: true };
      // Note: even though we return VORTEX_XP107 mode, the button mapping
      // internally uses Switch convention (A/B swapped). The useGamepadLoop
      // hook checks isDualMode + Switch identifier to apply the swap.
    }
    return { mode: 'VORTEX_XP107', vendor: 'Vortex', isDualMode: true };
  }

  // Generic Xbox detection
  if (id.includes('xbox') || id.includes('045e') || id.includes('x-input')) {
    return { mode: 'XBOX', vendor: 'Microsoft', isDualMode: false };
  }

  // Nintendo Switch Pro / Joy-Con detection
  if (id.includes('switch') || id.includes('pro controller') || id.includes('joy-con') || id.includes('057e')) {
    return { mode: 'SWITCH', vendor: 'Nintendo', isDualMode: false };
  }

  // Sony DualSense / DualShock
  if (id.includes('dualsense') || id.includes('dualshock') || id.includes('sony') || id.includes('054c')) {
    return { mode: 'XBOX', vendor: 'Sony', isDualMode: false };
    // DualSense uses Xbox-like layout in Android
  }

  // Razer Kishi / 8BitDo / GameSir / Gulikit — all use Xbox layout
  if (id.includes('razer') || id.includes('kishi') || id.includes('8bitdo') ||
      id.includes('gamesir') || id.includes('guli') || id.includes('flydigi')) {
    return { mode: 'XBOX', vendor: 'Generic', isDualMode: false };
  }

  return { mode: 'GENERIC', vendor: 'Unknown', isDualMode: false };
}

// ============================================================
// Button name remapper — converts hardware button index to
// logical button name based on controller mode.
// XBOX layout:    index 0=A, 1=B, 2=X, 3=Y
// SWITCH layout:  index 0=B, 1=A, 2=Y, 3=X
// ============================================================
export const BUTTON_MAPPING_XBOX = [
  'A', 'B', 'X', 'Y',                              // 0-3
  'LB', 'RB', 'LT', 'RT',                          // 4-7
  'SELECT', 'START',                               // 8-9
  'L3', 'R3',                                      // 10-11
  'UP', 'DOWN', 'LEFT', 'RIGHT',                   // 12-15
];

export const BUTTON_MAPPING_SWITCH = [
  'B', 'A', 'Y', 'X',                              // 0-3 (Nintendo order)
  'LB', 'RB', 'LT', 'RT',                          // 4-7
  'SELECT', 'START',                               // 8-9
  'L3', 'R3',                                      // 10-11
  'UP', 'DOWN', 'LEFT', 'RIGHT',                   // 12-15
];

export function getButtonMappingForMode(mode: ControllerMode): string[] {
  if (mode === 'SWITCH') return BUTTON_MAPPING_SWITCH;
  // VORTEX_XP107 + XBOX + GENERIC all use Xbox layout by default
  return BUTTON_MAPPING_XBOX;
}

// Import comprehensive preset profiles for 7 popular mobile games
import { PRESET_PROFILES } from './gameProfiles';
// Import community-contributed profiles (safe: returns [] if no profiles added yet)
import { COMMUNITY_PROFILES } from './communityProfiles/index';

export const INITIAL_PROFILES: GamepadProfile[] = [
  ...PRESET_PROFILES,
  ...COMMUNITY_PROFILES,
];

export const INITIAL_MACROS: GamepadMacro[] = [];

export const DEVICE_RAW_NODES = [
  { path: '/dev/input/event1', name: 'Generic Gamepad', type: 'Gamepad' },
  { path: '/dev/input/event2', name: 'Touchscreen',     type: 'Touch'   },
];
