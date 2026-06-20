/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */
import { GamepadProfile, GamepadMacro } from './types';

// ============================================
// FIX BUG-M05: Android KeyEvent Codes Reference
// https://developer.android.com/reference/android/view/KeyEvent
// ============================================
export const ANDROID_KEY_CODES = {
  // Face buttons
  BUTTON_A: 96,           // KEYCODE_BUTTON_A
  BUTTON_B: 97,           // KEYCODE_BUTTON_B
  BUTTON_X: 99,           // KEYCODE_BUTTON_X
  BUTTON_Y: 100,          // KEYCODE_BUTTON_Y
  
  // Shoulder buttons
  BUTTON_L1: 102,         // KEYCODE_BUTTON_L1
  BUTTON_R1: 103,         // KEYCODE_BUTTON_R1
  BUTTON_L2: 102,         // KEYCODE_BUTTON_L2 (shared code)
  BUTTON_R2: 103,         // KEYCODE_BUTTON_R2 (shared code)
  
  // D-Pad
  DPAD_UP: 19,            // KEYCODE_DPAD_UP
  DPAD_DOWN: 20,          // KEYCODE_DPAD_DOWN
  DPAD_LEFT: 21,          // KEYCODE_DPAD_LEFT
  DPAD_RIGHT: 22,         // KEYCODE_DPAD_RIGHT
  
  // Special buttons
  BUTTON_START: 108,      // KEYCODE_BUTTON_START
  BUTTON_SELECT: 109,     // KEYCODE_BUTTON_SELECT
  BUTTON_MODE: 110,       // KEYCODE_BUTTON_MODE
  
  // System keys
  KEYCODE_HOME: 3,        // KEYCODE_HOME
  KEYCODE_BACK: 4,        // KEYCODE_BACK
  KEYCODE_MENU: 82,       // KEYCODE_MENU
  
  // Analog sticks (virtual - handled via touch injection)
  L_STICK: 0,             // Virtual - no key code
  R_STICK: 0,             // Virtual - no key code
  
  // Touch buttons (virtual)
  TOUCH_1: 0,
  TOUCH_2: 0,
  TOUCH_3: 0,
  TOUCH_4: 0,
} as const;

// ============================================
// INITIAL PROFILES
// FIX BUG-M05: Semua androidEventCode sudah benar
// FIX BUG-L05: Profile Genshin diperluas (tidak hanya 2 button)
// ============================================
export const INITIAL_PROFILES: GamepadProfile[] = [
  // ==========================================
  // PROFILE 1: Genshin Impact
  // FIX BUG-L05: Ditambah lebih banyak button mapping
  // ==========================================
  {
    id: 'genshin',
    name: 'Genshin Impact',
    game: 'Genshin Impact',
    packageName: 'com.miHoYo.GenshinImpact',
    description: 'Optimized mapping for Genshin Impact mobile gameplay. Supports all combat actions including elemental skills, bursts, and character switching.',
    deadzone: 0.15,
    smoothing: 0.5,
    globalOpacity: 80,
    antiBanEnabled: true,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    buttons: [
      // === Analog Sticks ===
      {
        id: 'l_stick',
        mappedKey: 'L_STICK',
        x: 20,
        y: 70,
        width: 120,
        height: 120,
        type: 'analog',
        label: 'Move',
        androidEventCode: ANDROID_KEY_CODES.L_STICK,
      },
      {
        id: 'r_stick',
        mappedKey: 'R_STICK',
        x: 80,
        y: 50,
        width: 120,
        height: 120,
        type: 'analog',
        label: 'Camera',
        androidEventCode: ANDROID_KEY_CODES.R_STICK,
      },
      
      // === Face Buttons ===
      {
        id: 'btn_a',
        mappedKey: 'A',
        x: 85,
        y: 75,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Attack',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_A,
      },
      {
        id: 'btn_b',
        mappedKey: 'B',
        x: 90,
        y: 65,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Elemental Skill',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_B,
      },
      {
        id: 'btn_x',
        mappedKey: 'X',
        x: 80,
        y: 65,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Switch Attack',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_X,
      },
      {
        id: 'btn_y',
        mappedKey: 'Y',
        x: 85,
        y: 55,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Elemental Burst',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_Y,
      },
      
      // === Triggers ===
      {
        id: 'btn_lt',
        mappedKey: 'LT',
        x: 15,
        y: 20,
        width: 80,
        height: 60,
        type: 'button',
        label: 'Aim (Bow)',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_L2,
      },
      {
        id: 'btn_rt',
        mappedKey: 'RT',
        x: 85,
        y: 20,
        width: 80,
        height: 60,
        type: 'button',
        label: 'Shoot (Bow)',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_R2,
      },
      
      // === Bumpers ===
      {
        id: 'btn_lb',
        mappedKey: 'LB',
        x: 10,
        y: 10,
        width: 70,
        height: 50,
        type: 'button',
        label: 'Sprint',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_L1,
      },
      {
        id: 'btn_rb',
        mappedKey: 'RB',
        x: 90,
        y: 10,
        width: 70,
        height: 50,
        type: 'button',
        label: 'Jump',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_R1,
      },
      
      // === D-Pad (Character Switch) ===
      {
        id: 'btn_dpad_up',
        mappedKey: 'DPAD_UP',
        x: 15,
        y: 45,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Switch Char 1',
        androidEventCode: ANDROID_KEY_CODES.DPAD_UP,
      },
      {
        id: 'btn_dpad_down',
        mappedKey: 'DPAD_DOWN',
        x: 15,
        y: 60,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Switch Char 2',
        androidEventCode: ANDROID_KEY_CODES.DPAD_DOWN,
      },
      {
        id: 'btn_dpad_left',
        mappedKey: 'DPAD_LEFT',
        x: 9,
        y: 52,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Switch Char 3',
        androidEventCode: ANDROID_KEY_CODES.DPAD_LEFT,
      },
      {
        id: 'btn_dpad_right',
        mappedKey: 'DPAD_RIGHT',
        x: 21,
        y: 52,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Switch Char 4',
        androidEventCode: ANDROID_KEY_CODES.DPAD_RIGHT,
      },
      
      // === Special Buttons ===
      {
        id: 'btn_start',
        mappedKey: 'START',
        x: 55,
        y: 10,
        width: 50,
        height: 40,
        type: 'button',
        label: 'Paimon Menu',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_START,
      },
      {
        id: 'btn_select',
        mappedKey: 'SELECT',
        x: 45,
        y: 10,
        width: 50,
        height: 40,
        type: 'button',
        label: 'Map',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_SELECT,
      },
      
      // === Touch Buttons (Additional Actions) ===
      {
        id: 'btn_touch_1',
        mappedKey: 'TOUCH_1',
        x: 90,
        y: 50,
        width: 50,
        height: 50,
        type: 'button',
        label: 'Interact',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_1,
      },
      {
        id: 'btn_touch_2',
        mappedKey: 'TOUCH_2',
        x: 50,
        y: 90,
        width: 50,
        height: 50,
        type: 'button',
        label: 'Sprint Toggle',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_2,
      },
      {
        id: 'btn_touch_3',
        mappedKey: 'TOUCH_3',
        x: 70,
        y: 85,
        width: 50,
        height: 50,
        type: 'button',
        label: 'Climb Jump',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_3,
      },
    ],
  },
  
  // ==========================================
  // PROFILE 2: PUBG Mobile
  // ==========================================
  {
    id: 'pubg',
    name: 'PUBG Mobile',
    game: 'PUBG Mobile',
    packageName: 'com.tencent.ig',
    description: 'Competitive FPS mapping for PUBG Mobile. Optimized for fast reflexes and precise aiming with analog stick controls.',
    deadzone: 0.12,
    smoothing: 0.3,
    globalOpacity: 75,
    antiBanEnabled: true,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    buttons: [
      // === Analog Sticks ===
      {
        id: 'l_stick',
        mappedKey: 'L_STICK',
        x: 18,
        y: 72,
        width: 130,
        height: 130,
        type: 'analog',
        label: 'Move',
        androidEventCode: ANDROID_KEY_CODES.L_STICK,
      },
      {
        id: 'r_stick',
        mappedKey: 'R_STICK',
        x: 78,
        y: 55,
        width: 140,
        height: 140,
        type: 'analog',
        label: 'Aim/Look',
        androidEventCode: ANDROID_KEY_CODES.R_STICK,
      },
      
      // === Face Buttons ===
      {
        id: 'btn_a',
        mappedKey: 'A',
        x: 88,
        y: 78,
        width: 70,
        height: 70,
        type: 'button',
        label: 'Jump',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_A,
      },
      {
        id: 'btn_b',
        mappedKey: 'B',
        x: 92,
        y: 68,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Crouch/Prone',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_B,
      },
      {
        id: 'btn_x',
        mappedKey: 'X',
        x: 82,
        y: 68,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Reload',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_X,
      },
      {
        id: 'btn_y',
        mappedKey: 'Y',
        x: 88,
        y: 58,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Interact/Pickup',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_Y,
      },
      
      // === Triggers ===
      {
        id: 'btn_lt',
        mappedKey: 'LT',
        x: 20,
        y: 15,
        width: 100,
        height: 80,
        type: 'button',
        label: 'ADS (Aim Down Sight)',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_L2,
      },
      {
        id: 'btn_rt',
        mappedKey: 'RT',
        x: 80,
        y: 15,
        width: 100,
        height: 80,
        type: 'button',
        label: 'Fire',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_R2,
      },
      
      // === Bumpers ===
      {
        id: 'btn_lb',
        mappedKey: 'LB',
        x: 10,
        y: 8,
        width: 80,
        height: 50,
        type: 'button',
        label: 'Lean Left',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_L1,
      },
      {
        id: 'btn_rb',
        mappedKey: 'RB',
        x: 90,
        y: 8,
        width: 80,
        height: 50,
        type: 'button',
        label: 'Lean Right',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_R1,
      },
      
      // === D-Pad (Weapon Switch) ===
      {
        id: 'btn_dpad_up',
        mappedKey: 'DPAD_UP',
        x: 12,
        y: 40,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Weapon Slot 1',
        androidEventCode: ANDROID_KEY_CODES.DPAD_UP,
      },
      {
        id: 'btn_dpad_down',
        mappedKey: 'DPAD_DOWN',
        x: 12,
        y: 55,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Weapon Slot 2',
        androidEventCode: ANDROID_KEY_CODES.DPAD_DOWN,
      },
      {
        id: 'btn_dpad_left',
        mappedKey: 'DPAD_LEFT',
        x: 7,
        y: 47,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Weapon Slot 3',
        androidEventCode: ANDROID_KEY_CODES.DPAD_LEFT,
      },
      {
        id: 'btn_dpad_right',
        mappedKey: 'DPAD_RIGHT',
        x: 17,
        y: 47,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Weapon Slot 4',
        androidEventCode: ANDROID_KEY_CODES.DPAD_RIGHT,
      },
      
      // === Special Buttons ===
      {
        id: 'btn_start',
        mappedKey: 'START',
        x: 50,
        y: 8,
        width: 50,
        height: 40,
        type: 'button',
        label: 'Map',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_START,
      },
      {
        id: 'btn_select',
        mappedKey: 'SELECT',
        x: 50,
        y: 92,
        width: 60,
        height: 50,
        type: 'button',
        label: 'Prone',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_SELECT,
      },
      
      // === Touch Buttons ===
      {
        id: 'btn_touch_1',
        mappedKey: 'TOUCH_1',
        x: 70,
        y: 50,
        width: 50,
        height: 50,
        type: 'button',
        label: 'Open Door/Vehicle',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_1,
      },
      {
        id: 'btn_touch_2',
        mappedKey: 'TOUCH_2',
        x: 95,
        y: 55,
        width: 50,
        height: 50,
        type: 'button',
        label: 'Peek',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_2,
      },
      {
        id: 'btn_touch_3',
        mappedKey: 'TOUCH_3',
        x: 50,
        y: 50,
        width: 40,
        height: 40,
        type: 'button',
        label: 'Quick Pickup',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_3,
      },
      {
        id: 'btn_touch_4',
        mappedKey: 'TOUCH_4',
        x: 35,
        y: 85,
        width: 50,
        height: 50,
        type: 'button',
        label: 'Sprint',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_4,
      },
    ],
  },
  
  // ==========================================
  // PROFILE 3: Mobile Legends: Bang Bang
  // ==========================================
  {
    id: 'mlbb',
    name: 'Mobile Legends',
    game: 'Mobile Legends: Bang Bang',
    packageName: 'com.mobile.legends',
    description: 'MOBA mapping for Mobile Legends. Optimized for skill combos, quick item purchases, and team communication.',
    deadzone: 0.18,
    smoothing: 0.6,
    globalOpacity: 80,
    antiBanEnabled: true,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    buttons: [
      // === Analog Sticks ===
      {
        id: 'l_stick',
        mappedKey: 'L_STICK',
        x: 18,
        y: 75,
        width: 110,
        height: 110,
        type: 'analog',
        label: 'Move',
        androidEventCode: ANDROID_KEY_CODES.L_STICK,
      },
      {
        id: 'r_stick',
        mappedKey: 'R_STICK',
        x: 82,
        y: 55,
        width: 100,
        height: 100,
        type: 'analog',
        label: 'Aim Skill',
        androidEventCode: ANDROID_KEY_CODES.R_STICK,
      },
      
      // === Face Buttons (Skills) ===
      {
        id: 'btn_a',
        mappedKey: 'A',
        x: 88,
        y: 78,
        width: 70,
        height: 70,
        type: 'button',
        label: 'Basic Attack',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_A,
      },
      {
        id: 'btn_b',
        mappedKey: 'B',
        x: 92,
        y: 65,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Skill 1',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_B,
      },
      {
        id: 'btn_x',
        mappedKey: 'X',
        x: 82,
        y: 65,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Skill 2',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_X,
      },
      {
        id: 'btn_y',
        mappedKey: 'Y',
        x: 75,
        y: 78,
        width: 60,
        height: 60,
        type: 'button',
        label: 'Ultimate',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_Y,
      },
      
      // === Triggers (Summoner Spells) ===
      {
        id: 'btn_lt',
        mappedKey: 'LT',
        x: 20,
        y: 25,
        width: 80,
        height: 60,
        type: 'button',
        label: 'Summoner Spell 1',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_L2,
      },
      {
        id: 'btn_rt',
        mappedKey: 'RT',
        x: 80,
        y: 25,
        width: 80,
        height: 60,
        type: 'button',
        label: 'Summoner Spell 2',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_R2,
      },
      
      // === Bumpers ===
      {
        id: 'btn_lb',
        mappedKey: 'LB',
        x: 15,
        y: 10,
        width: 70,
        height: 50,
        type: 'button',
        label: 'Shop',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_L1,
      },
      {
        id: 'btn_rb',
        mappedKey: 'RB',
        x: 85,
        y: 10,
        width: 70,
        height: 50,
        type: 'button',
        label: 'Map',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_R1,
      },
      
      // === D-Pad (Quick Actions) ===
      {
        id: 'btn_dpad_up',
        mappedKey: 'DPAD_UP',
        x: 15,
        y: 45,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Ping Attack',
        androidEventCode: ANDROID_KEY_CODES.DPAD_UP,
      },
      {
        id: 'btn_dpad_down',
        mappedKey: 'DPAD_DOWN',
        x: 15,
        y: 58,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Ping Retreat',
        androidEventCode: ANDROID_KEY_CODES.DPAD_DOWN,
      },
      {
        id: 'btn_dpad_left',
        mappedKey: 'DPAD_LEFT',
        x: 9,
        y: 52,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Emote 1',
        androidEventCode: ANDROID_KEY_CODES.DPAD_LEFT,
      },
      {
        id: 'btn_dpad_right',
        mappedKey: 'DPAD_RIGHT',
        x: 21,
        y: 52,
        width: 50,
        height: 50,
        type: 'dpad',
        label: 'Emote 2',
        androidEventCode: ANDROID_KEY_CODES.DPAD_RIGHT,
      },
      
      // === Special Buttons ===
      {
        id: 'btn_start',
        mappedKey: 'START',
        x: 92,
        y: 88,
        width: 60,
        height: 50,
        type: 'button',
        label: 'Recall',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_START,
      },
      {
        id: 'btn_select',
        mappedKey: 'SELECT',
        x: 50,
        y: 92,
        width: 60,
        height: 50,
        type: 'button',
        label: 'Quick Buy',
        androidEventCode: ANDROID_KEY_CODES.BUTTON_SELECT,
      },
      
      // === Touch Buttons ===
      {
        id: 'btn_touch_1',
        mappedKey: 'TOUCH_1',
        x: 70,
        y: 55,
        width: 50,
        height: 50,
        type: 'button',
        label: 'Push Lock',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_1,
      },
      {
        id: 'btn_touch_2',
        mappedKey: 'TOUCH_2',
        x: 50,
        y: 50,
        width: 40,
        height: 40,
        type: 'button',
        label: 'Turret Lock',
        androidEventCode: ANDROID_KEY_CODES.TOUCH_2,
      },
    ],
  },
];

// ============================================
// INITIAL MACROS
// ============================================
export const INITIAL_MACROS: GamepadMacro[] = [
  {
    id: 'macro_quick_chat',
    name: 'Quick Chat Ping',
    description: 'Quickly ping enemy location',
    steps: [
      {
        id: 'step_1',
        type: 'tap',
        x: 95,
        y: 10,
        duration: 50,
        delay: 0,
        description: 'Tap ping button',
      },
    ],
    loopCount: 1,
    loopDelay: 0,
    isEnabled: true,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  },
  {
    id: 'macro_auto_loot',
    name: 'Auto Loot Cycle',
    description: 'Cycle through loot items quickly',
    steps: [
      {
        id: 'step_1',
        type: 'tap',
        x: 50,
        y: 50,
        duration: 80,
        delay: 100,
        description: 'Tap item',
      },
      {
        id: 'step_2',
        type: 'tap',
        x: 50,
        y: 60,
        duration: 80,
        delay: 100,
        description: 'Tap pickup',
      },
    ],
    loopCount: 3,
    loopDelay: 200,
    isEnabled: true,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  },
];
