// ============================================================
// PROFILE TEMPLATE — Copy this file to create your own profile
// ============================================================
// INSTRUCTIONS:
// 1. Copy this file: cp _template.ts myGameName.ts
// 2. Rename the export const (e.g., MY_GAME_NAME)
// 3. Edit all the values below
// 4. Add your import to index.ts in this folder
// 5. Submit a Pull Request
//
// COORDINATE SYSTEM:
// - x, y: percentage of screen (0-100)
//   x=0 is left edge, x=100 is right edge
//   y=0 is top edge, y=100 is bottom edge
// - For landscape mode (tablet horizontal):
//   Left stick usually at x=15, y=78
//   Right stick usually at x=82, y=78
//   Action buttons on right side: x=70-95, y=50-85
// ============================================================

import { GamepadProfile } from '../types';
import { DEFAULT_ANTI_BAN, DEFAULT_GYRO_MAPPING } from '../defaults';

// Replace YOUR_GAME with your game's name (uppercase)
export const YOUR_GAME: GamepadProfile = {
  // === Basic Info ===
  id: 'your_game_id',                    // unique ID (lowercase, no spaces)
  name: 'Your Game Name',                // display name
  packageName: 'com.example.yourgame',   // Android package name
  description: 'Short description of your game',
  isCustom: false,

  // === Sensitivity Settings ===
  gyroSensitivity: 1.0,    // gyro multiplier (1.0 = default)
  deadzone: 0.15,          // stick deadzone (0.1-0.25 recommended)
  smoothing: 0.5,          // movement smoothing (0=none, 1=max)

  // === Virtual Buttons (for WYSIWYG editor) ===
  // Each button = one gamepad input mapped to a screen position
  buttons: [
    // Left analog stick (movement)
    {
      id: 'l_stick',
      label: 'Move',
      type: 'analog_stick',
      x: 14,           // % from left
      y: 78,           // % from top
      width: 140,      // px size
      height: 140,
      mappedKey: 'L_STICK',
      androidEventCode: 0,
      opacity: 0.5,
      deadzone: 0.1,
    },
    // Right analog stick (camera/look)
    {
      id: 'r_stick',
      label: 'Camera',
      type: 'analog_stick',
      x: 82,
      y: 78,
      width: 130,
      height: 130,
      mappedKey: 'R_STICK',
      androidEventCode: 0,
      opacity: 0.5,
      deadzone: 0.1,
    },
    // Action buttons (A, B, X, Y, LB, RB, LT, RT, etc.)
    {
      id: 'action_a',
      label: 'Jump',
      type: 'button',
      x: 88,
      y: 78,
      width: 56,
      height: 56,
      mappedKey: 'BUTTON_A',
      androidEventCode: 96,
      opacity: 0.85,
    },
    {
      id: 'action_b',
      label: 'Attack',
      type: 'button',
      x: 84,
      y: 66,
      width: 56,
      height: 56,
      mappedKey: 'BUTTON_B',
      androidEventCode: 97,
      opacity: 0.85,
    },
    {
      id: 'action_x',
      label: 'Reload',
      type: 'button',
      x: 78,
      y: 82,
      width: 56,
      height: 56,
      mappedKey: 'BUTTON_X',
      androidEventCode: 99,
      opacity: 0.85,
    },
    {
      id: 'action_y',
      label: 'Skill',
      type: 'button',
      x: 72,
      y: 72,
      width: 56,
      height: 56,
      mappedKey: 'BUTTON_Y',
      androidEventCode: 100,
      opacity: 0.85,
    },
    // Shoulder buttons
    {
      id: 'lb',
      label: 'LB',
      type: 'button',
      x: 8,
      y: 60,
      width: 70,
      height: 36,
      mappedKey: 'BUTTON_L1',
      androidEventCode: 101,
      opacity: 0.75,
    },
    {
      id: 'rb',
      label: 'RB',
      type: 'button',
      x: 90,
      y: 55,
      width: 70,
      height: 36,
      mappedKey: 'BUTTON_R1',
      androidEventCode: 102,
      opacity: 0.75,
    },
    // Triggers (analog)
    {
      id: 'lt',
      label: 'LT',
      type: 'button',
      x: 8,
      y: 70,
      width: 75,
      height: 36,
      mappedKey: 'BUTTON_L2',
      androidEventCode: 104,
      opacity: 0.8,
    },
    {
      id: 'rt',
      label: 'RT',
      type: 'button',
      x: 90,
      y: 65,
      width: 75,
      height: 36,
      mappedKey: 'BUTTON_R2',
      androidEventCode: 105,
      opacity: 0.8,
    },
    // System buttons
    {
      id: 'start',
      label: 'Start',
      type: 'button',
      x: 96,
      y: 8,
      width: 45,
      height: 24,
      mappedKey: 'BUTTON_START',
      androidEventCode: 108,
      opacity: 0.6,
    },
    {
      id: 'select',
      label: 'Select',
      type: 'button',
      x: 4,
      y: 8,
      width: 45,
      height: 24,
      mappedKey: 'BUTTON_SELECT',
      androidEventCode: 109,
      opacity: 0.6,
    },
  ],

  // === Hardware Mappings (absolute pixels for 2800x1840 screen) ===
  // These are used by useGamepadLoop for native evdev input.
  // Format: { hardwareKey: 'A', x: <pixel_x>, y: <pixel_y> }
  // hardwareKey values: A, B, X, Y, LB, RB, L2, R2, L3, R3,
  //                     START, SELECT, UP, DOWN, LEFT, RIGHT
  mappings: [
    // Example for 2800x1840 resolution:
    // { hardwareKey: 'A', x: 2464, y: 1435 },  // 88% of 2800, 78% of 1840
    // { hardwareKey: 'B', x: 2352, y: 1214 },
    // { hardwareKey: 'X', x: 2184, y: 1509 },
    // { hardwareKey: 'Y', x: 2016, y: 1325 },
  ],

  // === Joystick Config (absolute pixels for 2800x1840 screen) ===
  leftJoystick: {
    centerX: 392,     // 14% of 2800
    centerY: 1435,    // 78% of 1840
    radius: 160,
  },
  rightJoystick: {
    centerX: 2296,    // 82% of 2800
    centerY: 1435,    // 78% of 1840
    radius: 160,
  },

  // === Anti-ban (optional) ===
  antiBanEnabled: false,
  antiBanConfig: { ...DEFAULT_ANTI_BAN },

  // === Gyro (optional — enable for FPS games) ===
  gyroMapping: {
    ...DEFAULT_GYRO_MAPPING,
    enabled: false,  // set to true for FPS/aiming games
  },

  // === Auto-start ===
  autoStartEnabled: true,
};
