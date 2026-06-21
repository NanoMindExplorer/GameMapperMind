/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 * 
 * defaults.ts - Default Values and Constants
 * 
 * Berisi semua default values, constants, dan helper functions
 * untuk membuat profile dan macro baru.
 * 
 * FIX BUG-L03: Tambah bgDimLevel ke default profile
 * FIX BUG-L04: Tambah endX/endY ke default macro step
 * FIX: Tambah DEVICE_RAW_NODES untuk GamepadTester
 * 
 * Digunakan oleh:
 * - src/App.tsx
 * - src/components/GamepadTester.tsx
 * - src/components/OverlayWysiwyg.tsx
 * - src/components/MacroEngine.tsx
 * - src/hooks/useGamepadLoop.ts
 */

import type { GamepadProfile, GamepadMacro, MacroStep, ButtonMapping, AntiBanConfig } from './types';

// ============================================
// DEFAULT GAMEPAD PROFILE
// ============================================

/**
 * Default profile untuk gamepad mapping
 * 
 * Profile ini berisi konfigurasi default untuk:
 * - Deadzone analog stick (15%)
 * - Smoothing factor (50%)
 * - Global opacity (80%)
 * - Background dim level (50%) - FIX BUG-L03
 * - Anti-ban randomization (enabled)
 * - 12 button mappings default
 */
export const DEFAULT_GAMEPAD_PROFILE: GamepadProfile = {
  id: 'default-profile',
  name: 'Default Profile',
  game: 'Generic Game',
  packageName: '',
  description: 'Default gamepad mapping profile',
  
  // Analog settings
  deadzone: 0.15,
  smoothing: 0.5,
  
  // Visual settings
  globalOpacity: 80,
  bgDimLevel: 50, // FIX BUG-L03: Background dim level
  
  // Anti-ban settings
  antiBanEnabled: true,
  
  // Timestamps
  createdAt: Date.now(),
  updatedAt: Date.now(),
  
  // Default button mappings
  buttons: [
    // Face buttons
    {
      id: 'btn-a',
      mappedKey: 'A',
      x: 75,
      y: 60,
      width: 60,
      height: 60,
      type: 'button',
      label: 'A',
      androidEventCode: 96, // KEYCODE_BUTTON_A
    },
    {
      id: 'btn-b',
      mappedKey: 'B',
      x: 85,
      y: 50,
      width: 60,
      height: 60,
      type: 'button',
      label: 'B',
      androidEventCode: 97, // KEYCODE_BUTTON_B
    },
    {
      id: 'btn-x',
      mappedKey: 'X',
      x: 65,
      y: 50,
      width: 60,
      height: 60,
      type: 'button',
      label: 'X',
      androidEventCode: 99, // KEYCODE_BUTTON_X
    },
    {
      id: 'btn-y',
      mappedKey: 'Y',
      x: 75,
      y: 40,
      width: 60,
      height: 60,
      type: 'button',
      label: 'Y',
      androidEventCode: 100, // KEYCODE_BUTTON_Y
    },
    
    // Shoulder buttons
    {
      id: 'btn-lb',
      mappedKey: 'LB',
      x: 15,
      y: 15,
      width: 80,
      height: 40,
      type: 'button',
      label: 'LB',
      androidEventCode: 102, // KEYCODE_BUTTON_L1
    },
    {
      id: 'btn-rb',
      mappedKey: 'RB',
      x: 85,
      y: 15,
      width: 80,
      height: 40,
      type: 'button',
      label: 'RB',
      androidEventCode: 103, // KEYCODE_BUTTON_R1
    },
    
    // Triggers
    {
      id: 'btn-lt',
      mappedKey: 'LT',
      x: 15,
      y: 5,
      width: 80,
      height: 30,
      type: 'button',
      label: 'LT',
      androidEventCode: 102, // KEYCODE_BUTTON_L2
    },
    {
      id: 'btn-rt',
      mappedKey: 'RT',
      x: 85,
      y: 5,
      width: 80,
      height: 30,
      type: 'button',
      label: 'RT',
      androidEventCode: 103, // KEYCODE_BUTTON_R2
    },
    
    // D-Pad
    {
      id: 'btn-dpad-up',
      mappedKey: 'DPAD_UP',
      x: 20,
      y: 50,
      width: 50,
      height: 50,
      type: 'dpad',
      label: '↑',
      androidEventCode: 19, // KEYCODE_DPAD_UP
    },
    {
      id: 'btn-dpad-down',
      mappedKey: 'DPAD_DOWN',
      x: 20,
      y: 70,
      width: 50,
      height: 50,
      type: 'dpad',
      label: '↓',
      androidEventCode: 20, // KEYCODE_DPAD_DOWN
    },
    {
      id: 'btn-dpad-left',
      mappedKey: 'DPAD_LEFT',
      x: 10,
      y: 60,
      width: 50,
      height: 50,
      type: 'dpad',
      label: '←',
      androidEventCode: 21, // KEYCODE_DPAD_LEFT
    },
    {
      id: 'btn-dpad-right',
      mappedKey: 'DPAD_RIGHT',
      x: 30,
      y: 60,
      width: 50,
      height: 50,
      type: 'dpad',
      label: '→',
      androidEventCode: 22, // KEYCODE_DPAD_RIGHT
    },
  ],
};

// ============================================
// DEFAULT MACROS
// ============================================

/**
 * Default macros untuk quick actions
 * 
 * Macros ini bisa di-enable/disable sesuai kebutuhan.
 * Setiap macro berisi sequence of steps yang bisa di-playback.
 */
export const DEFAULT_MACROS: GamepadMacro[] = [
  {
    id: 'macro-rapid-fire',
    name: 'Rapid Fire',
    description: 'Auto-fire dengan interval 100ms',
    steps: [
      {
        id: 'step-1',
        type: 'tap',
        x: 75,
        y: 60,
        duration: 50,
        delay: 100,
        description: 'Tap A button',
      },
    ],
    loopCount: 0, // Infinite loop
    loopDelay: 0,
    isEnabled: false,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  },
  {
    id: 'macro-auto-run',
    name: 'Auto Run',
    description: 'Hold left stick forward',
    steps: [
      {
        id: 'step-1',
        type: 'hold',
        x: 20,
        y: 60,
        duration: 10000, // 10 seconds
        delay: 0,
        description: 'Hold left stick up',
      },
    ],
    loopCount: 1,
    loopDelay: 0,
    isEnabled: false,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  },
];

// ============================================
// DEFAULT ANTI-BAN CONFIG
// ============================================

/**
 * Default anti-ban configuration
 * 
 * Konfigurasi ini menambahkan randomization untuk
 * mencegah deteksi oleh anti-cheat system.
 */
export const DEFAULT_ANTI_BAN_CONFIG: AntiBanConfig = {
  enabled: true,
  coordinateJitter: 5, // ±5 pixel
  timingJitter: 10, // ±10%
  pressureVariation: true,
  sizeVariation: true,
  humanizeAnalog: true,
};

// ============================================
// HELPER FUNCTIONS
// ============================================

/**
 * Create default profile dengan custom name
 */
export function createDefaultProfile(name: string = 'New Profile'): GamepadProfile {
  return {
    ...DEFAULT_GAMEPAD_PROFILE,
    id: `profile-${Date.now()}`,
    name,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    buttons: DEFAULT_GAMEPAD_PROFILE.buttons.map(btn => ({
      ...btn,
      id: `btn-${Math.random().toString(36).substr(2, 9)}`,
    })),
  };
}

/**
 * Create empty profile (tanpa button mappings)
 */
export function createEmptyProfile(name: string = 'Empty Profile'): GamepadProfile {
  return {
    ...DEFAULT_GAMEPAD_PROFILE,
    id: `profile-${Date.now()}`,
    name,
    buttons: [],
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
}

/**
 * Create default macro step
 */
export function createDefaultStep(type: 'tap' | 'swipe' | 'hold' | 'wait' = 'tap'): MacroStep {
  return {
    id: `step-${Date.now()}`,
    type,
    x: 50,
    y: 50,
    endX: type === 'swipe' ? 60 : undefined, // FIX BUG-L04
    endY: type === 'swipe' ? 60 : undefined, // FIX BUG-L04
    duration: type === 'tap' ? 50 : type === 'swipe' ? 300 : type === 'hold' ? 500 : 0,
    delay: 0,
    description: `${type} action`,
  };
}

/**
 * Create default button mapping
 */
export function createDefaultButton(mappedKey: string, x: number, y: number): ButtonMapping {
  return {
    id: `btn-${Math.random().toString(36).substr(2, 9)}`,
    mappedKey,
    x,
    y,
    width: 60,
    height: 60,
    type: 'button',
    label: mappedKey,
    androidEventCode: 0,
  };
}

/**
 * Validate profile structure
 * 
 * @returns true jika valid, false jika tidak
 */
export function validateProfile(profile: GamepadProfile): boolean {
  // Check required fields
  if (!profile.id || !profile.name || !profile.game) {
    return false;
  }
  
  // Check numeric ranges
  if (profile.deadzone < 0 || profile.deadzone > 1) {
    return false;
  }
  if (profile.smoothing < 0 || profile.smoothing > 1) {
    return false;
  }
  if (profile.globalOpacity < 0 || profile.globalOpacity > 100) {
    return false;
  }
  if (profile.bgDimLevel !== undefined && (profile.bgDimLevel < 0 || profile.bgDimLevel > 100)) {
    return false;
  }
  
  // Check buttons
  if (!Array.isArray(profile.buttons)) {
    return false;
  }
  
  for (const btn of profile.buttons) {
    if (!btn.id || !btn.mappedKey) {
      return false;
    }
    if (btn.x < 0 || btn.x > 100 || btn.y < 0 || btn.y > 100) {
      return false;
    }
  }
  
  return true;
}

/**
 * Validate macro structure
 */
export function validateMacro(macro: GamepadMacro): boolean {
  if (!macro.id || !macro.name) {
    return false;
  }
  if (!Array.isArray(macro.steps)) {
    return false;
  }
  if (macro.loopCount < 0) {
    return false;
  }
  
  for (const step of macro.steps) {
    if (!step.id || !step.type) {
      return false;
    }
    if (step.x < 0 || step.x > 100 || step.y < 0 || step.y > 100) {
      return false;
    }
    if (step.type === 'swipe') {
      if (step.endX === undefined || step.endY === undefined) {
        return false;
      }
      if (step.endX < 0 || step.endX > 100 || step.endY < 0 || step.endY > 100) {
        return false;
      }
    }
  }
  
  return true;
}

/**
 * Clone profile dengan new ID
 */
export function cloneProfile(profile: GamepadProfile): GamepadProfile {
  return {
    ...profile,
    id: `profile-${Date.now()}`,
    name: `${profile.name} (Copy)`,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    buttons: profile.buttons.map(btn => ({
      ...btn,
      id: `btn-${Math.random().toString(36).substr(2, 9)}`,
    })),
  };
}

/**
 * Clone macro dengan new ID
 */
export function cloneMacro(macro: GamepadMacro): GamepadMacro {
  return {
    ...macro,
    id: `macro-${Date.now()}`,
    name: `${macro.name} (Copy)`,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    steps: macro.steps.map(step => ({
      ...step,
      id: `step-${Math.random().toString(36).substr(2, 9)}`,
    })),
  };
}

// ============================================
// CONSTANTS
// ============================================

/**
 * Maximum limits
 */
export const MAX_MACROS = 50;
export const MAX_STEPS_PER_MACRO = 100;
export const MAX_BUTTONS_PER_PROFILE = 50;
export const MAX_PROFILES = 20;

/**
 * Default timing values (ms)
 */
export const DEFAULT_TAP_DURATION = 50;
export const DEFAULT_SWIPE_DURATION = 300;
export const DEFAULT_HOLD_DURATION = 500;
export const DEFAULT_DELAY = 0;
export const DEFAULT_LOOP_DELAY = 100;

/**
 * Screen percentage boundaries
 */
export const MIN_POSITION = 0;
export const MAX_POSITION = 100;

/**
 * Button size limits (pixels)
 */
export const MIN_BUTTON_SIZE = 20;
export const MAX_BUTTON_SIZE = 200;
export const DEFAULT_BUTTON_SIZE = 60;

// ============================================
// DEVICE RAW NODES (untuk GamepadTester)
// ============================================

/**
 * Device raw nodes untuk gamepad testing
 * 
 * Digunakan oleh GamepadTester component untuk menampilkan
 * informasi raw input dari gamepad.
 * 
 * Setiap node merepresentasikan satu axis atau button dari gamepad.
 */
export const DEVICE_RAW_NODES = [
  {
    id: 'left_stick_x',
    name: 'Left Stick X',
    type: 'axis',
    index: 0,
    min: -1,
    max: 1,
    defaultValue: 0,
  },
  {
    id: 'left_stick_y',
    name: 'Left Stick Y',
    type: 'axis',
    index: 1,
    min: -1,
    max: 1,
    defaultValue: 0,
  },
  {
    id: 'right_stick_x',
    name: 'Right Stick X',
    type: 'axis',
    index: 2,
    min: -1,
    max: 1,
    defaultValue: 0,
  },
  {
    id: 'right_stick_y',
    name: 'Right Stick Y',
    type: 'axis',
    index: 3,
    min: -1,
    max: 1,
    defaultValue: 0,
  },
  {
    id: 'left_trigger',
    name: 'Left Trigger',
    type: 'axis',
    index: 4,
    min: 0,
    max: 1,
    defaultValue: 0,
  },
  {
    id: 'right_trigger',
    name: 'Right Trigger',
    type: 'axis',
    index: 5,
    min: 0,
    max: 1,
    defaultValue: 0,
  },
  {
    id: 'dpad_x',
    name: 'D-Pad X',
    type: 'axis',
    index: 6,
    min: -1,
    max: 1,
    defaultValue: 0,
  },
  {
    id: 'dpad_y',
    name: 'D-Pad Y',
    type: 'axis',
    index: 7,
    min: -1,
    max: 1,
    defaultValue: 0,
  },
] as const;

// ============================================
// INITIAL DATA (untuk App.tsx)
// ============================================

/**
 * Initial profiles untuk state awal aplikasi
 * Digunakan oleh App.tsx sebagai default state
 */
export const INITIAL_PROFILES: GamepadProfile[] = [
  DEFAULT_GAMEPAD_PROFILE,
];

/**
 * Initial macros untuk state awal aplikasi
 * Digunakan oleh App.tsx sebagai default state
 */
export const INITIAL_MACROS: GamepadMacro[] = DEFAULT_MACROS;

// ============================================
// EXPORT SUMMARY
// ============================================

/**
 * Summary of all exports:
 * 
 * Constants:
 * - DEFAULT_GAMEPAD_PROFILE
 * - DEFAULT_MACROS
 * - DEFAULT_ANTI_BAN_CONFIG
 * - DEVICE_RAW_NODES (NEW)
 * - MAX_MACROS, MAX_STEPS_PER_MACRO, MAX_BUTTONS_PER_PROFILE, MAX_PROFILES
 * - DEFAULT_TAP_DURATION, DEFAULT_SWIPE_DURATION, DEFAULT_HOLD_DURATION, DEFAULT_DELAY, DEFAULT_LOOP_DELAY
 * - MIN_POSITION, MAX_POSITION
 * - MIN_BUTTON_SIZE, MAX_BUTTON_SIZE, DEFAULT_BUTTON_SIZE
 * 
 * Functions:
 * - createDefaultProfile()
 * - createEmptyProfile()
 * - createDefaultStep()
 * - createDefaultButton()
 * - validateProfile()
 * - validateMacro()
 * - cloneProfile()
 * - cloneMacro()
 */
