/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import { GamepadProfile, GamepadMacro, GyroCalibrationState } from './types';

export const INITIAL_PROFILES: GamepadProfile[] = [
  {
    id: 'genshin',
    name: 'Genshin Impact',
    packageName: 'com.miHoYo.GenshinImpact',
    description: 'Optimal layout for exploration, combat, fast menu navigation and high-fidelity camera pan using physical gyroscope integration.',
    gyroSensitivity: 1.5,
    deadzone: 0.08,
    smoothing: 0.25,
    isCustom: false,
    buttons: [
      { id: 'btn_attack', label: 'Attack (A)', type: 'button', x: 86, y: 76, width: 64, height: 64, mappedKey: 'BUTTON_A', androidEventCode: 96, opacity: 0.7 },
      { id: 'btn_skill', label: 'E-Skill (X)', type: 'button', x: 80, y: 60, width: 56, height: 56, mappedKey: 'BUTTON_X', androidEventCode: 99, opacity: 0.7 },
      { id: 'btn_burst', label: 'Q-Burst (Y)', type: 'button', x: 88, y: 44, width: 56, height: 56, mappedKey: 'BUTTON_Y', androidEventCode: 100, opacity: 0.7 },
      { id: 'btn_dash', label: 'Dash (B)', type: 'button', x: 93, y: 62, width: 56, height: 56, mappedKey: 'BUTTON_B', androidEventCode: 97, opacity: 0.7 },
      { id: 'stick_l', label: 'L-Stick Move', type: 'analog_stick', x: 18, y: 68, width: 140, height: 140, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.1 },
      { id: 'gyro_pan', label: 'Camera (Gyro)', type: 'gyro_area', x: 60, y: 35, width: 280, height: 180, mappedKey: 'GYRO', androidEventCode: 1, opacity: 0.3, sensitivity: 1.8 }
    ]
  },
  {
    id: 'pubg',
    name: 'PUBG Mobile',
    packageName: 'com.tencent.ig',
    description: 'Precision shooter mapping. Dedicated flick-stick emulator, scope modifiers, and macro bindings for recoil recovery compensation.',
    gyroSensitivity: 2.2,
    deadzone: 0.05,
    smoothing: 0.15,
    isCustom: false,
    buttons: [
      { id: 'btn_fire', label: 'Fire (RT)', type: 'button', x: 88, y: 70, width: 70, height: 70, mappedKey: 'BUTTON_R2', androidEventCode: 105, opacity: 0.8 },
      { id: 'btn_scope', label: 'Scope (LT)', type: 'button', x: 10, y: 25, width: 60, height: 60, mappedKey: 'BUTTON_L2', androidEventCode: 104, opacity: 0.8 },
      { id: 'btn_reload', label: 'Reload (X)', type: 'button', x: 82, y: 88, width: 52, height: 52, mappedKey: 'BUTTON_X', androidEventCode: 99, opacity: 0.7 },
      { id: 'btn_jump', label: 'Jump (A)', type: 'button', x: 92, y: 48, width: 52, height: 52, mappedKey: 'BUTTON_A', androidEventCode: 96, opacity: 0.7 },
      { id: 'stick_movement', label: 'Movement', type: 'analog_stick', x: 15, y: 70, width: 130, height: 130, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.4 },
      { id: 'btn_recoil_macro', label: 'Instant Spray Multi-Tap', type: 'macro', x: 74, y: 70, width: 56, height: 56, mappedKey: 'BUTTON_R1', androidEventCode: 103, opacity: 0.8, macroId: 'recoil_spray' }
    ]
  },
  {
    id: 'codm',
    name: 'Call of Duty: Mobile',
    packageName: 'com.activision.callofduty.shooter',
    description: 'Competitive multiplayer layout. High responsive look/panning calibration. Zero latency gyroscope settings.',
    gyroSensitivity: 1.8,
    deadzone: 0.06,
    smoothing: 0.2,
    isCustom: false,
    buttons: [
      { id: 'btn_shoot', label: 'Aim & Shoot (RT)', type: 'button', x: 85, y: 75, width: 68, height: 68, mappedKey: 'BUTTON_R2', androidEventCode: 105, opacity: 0.75 },
      { id: 'btn_slide', label: 'Slide / Crouch (B)', type: 'button', x: 78, y: 88, width: 55, height: 55, mappedKey: 'BUTTON_B', androidEventCode: 97, opacity: 0.65 },
      { id: 'btn_jump', label: 'Jump (A)', type: 'button', x: 92, y: 62, width: 55, height: 55, mappedKey: 'BUTTON_A', androidEventCode: 96, opacity: 0.65 },
      { id: 'stick_l_cod', label: 'Move Stick', type: 'analog_stick', x: 18, y: 70, width: 135, height: 135, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5 }
    ]
  },
  {
    id: 'efootball',
    name: 'eFootball™ Mobile 2026',
    packageName: 'jp.konami.pesam',
    description: 'BETA LAYOUT with Turn-Off Gyro (0.0x Scaling). Standard tactical mapping for direct screen touch interception, enabling instant transition from possession attack to penalty space defenders.',
    gyroSensitivity: 0.0,
    deadzone: 0.08,
    smoothing: 0.35,
    isCustom: false,
    buttons: [
      { id: 'ef_dribble', label: 'L-Stick: Gerakan Pemain (Dribble)', type: 'analog_stick', x: 15, y: 70, width: 140, height: 140, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.06 },
      { id: 'ef_pass', label: 'A: Ground Pass (Att) / Call Pressure (Def)', type: 'button', x: 86, y: 82, width: 62, height: 62, mappedKey: 'BUTTON_A', androidEventCode: 96, opacity: 0.75 },
      { id: 'ef_shoot', label: 'X: Shoot (Att) / Tackle (Def)', type: 'button', x: 92, y: 64, width: 58, height: 58, mappedKey: 'BUTTON_X', androidEventCode: 99, opacity: 0.75 },
      { id: 'ef_through', label: 'Y: Through Ball (Att) / GK Rush (Def)', type: 'button', x: 78, y: 70, width: 56, height: 56, mappedKey: 'BUTTON_Y', androidEventCode: 100, opacity: 0.75 },
      { id: 'ef_loft', label: 'B: Lofted Pass (Att) / Pressure (Def)', type: 'button', x: 92, y: 46, width: 56, height: 56, mappedKey: 'BUTTON_B', androidEventCode: 97, opacity: 0.75 },
      { id: 'ef_dash_rb', label: 'R1/RB: Dash (Att) / Chase (Def)', type: 'button', x: 74, y: 46, width: 52, height: 52, mappedKey: 'BUTTON_R1', androidEventCode: 102, opacity: 0.70 },
      { id: 'ef_knock_rt', label: 'R2/RT: Knock-on (Att) / Match-up (Def)', type: 'button', x: 74, y: 65, width: 52, height: 52, mappedKey: 'BUTTON_R2', androidEventCode: 103, opacity: 0.70 },
      { id: 'ef_cursor_lb', label: 'L1/LB: Ganti Kursor Manual (Att/Def)', type: 'button', x: 15, y: 30, width: 50, height: 50, mappedKey: 'BUTTON_L1', androidEventCode: 101, opacity: 0.65 },
      { id: 'ef_swipe_special', label: 'Special: Stunning Shot 4-Way Swipe', type: 'macro', x: 92, y: 64, width: 72, height: 72, mappedKey: 'BUTTON_R3', androidEventCode: 107, opacity: 0.40, macroId: 'stunning_swipe' }
    ]
  }
];

export const INITIAL_MACROS: GamepadMacro[] = [
  {
    id: 'recoil_spray',
    name: 'Anti-Recoil Spray & Lean',
    triggerKey: 'R1 (Right Bumper)',
    playbackSpeed: 1.0,
    actions: [
      { id: 'a1', type: 'touch_down', x: 880, y: 700, pointerId: 1, delayMs: 0 },
      { id: 'a2', type: 'touch_move', x: 880, y: 720, pointerId: 1, delayMs: 40 },
      { id: 'a3', type: 'touch_move', x: 880, y: 740, pointerId: 1, delayMs: 40 },
      { id: 'a4', type: 'touch_move', x: 881, y: 760, pointerId: 1, delayMs: 40 },
      { id: 'a5', type: 'touch_up', x: 881, y: 760, pointerId: 1, delayMs: 20 },
      // Optional side button tap
      { id: 'a6', type: 'touch_down', x: 100, y: 250, pointerId: 2, delayMs: 30 },
      { id: 'a7', type: 'touch_up', x: 100, y: 250, pointerId: 2, delayMs: 50 }
    ]
  },
  {
    id: 'quick_turn',
    name: '180 Flick Turn',
    triggerKey: 'R3 (Right Stick Press)',
    playbackSpeed: 1.5,
    actions: [
      { id: 'f1', type: 'touch_down', x: 600, y: 500, pointerId: 1, delayMs: 0 },
      { id: 'f2', type: 'touch_move', x: 450, y: 500, pointerId: 1, delayMs: 16 },
      { id: 'f3', type: 'touch_move', x: 300, y: 500, pointerId: 1, delayMs: 16 },
      { id: 'f4', type: 'touch_move', x: 150, y: 500, pointerId: 1, delayMs: 16 },
      { id: 'f5', type: 'touch_up', pointerId: 1, delayMs: 16 }
    ]
  },
  {
    id: 'stunning_swipe',
    name: 'eFootball Stunning Shot Swipe (4-Way Slide)',
    triggerKey: 'R3 (Right Stick Press) or Combo',
    playbackSpeed: 1.0,
    actions: [
      { id: 'ss1', type: 'touch_down', x: 920, y: 640, pointerId: 1, delayMs: 0 },
      { id: 'ss2', type: 'touch_move', x: 960, y: 640, pointerId: 1, delayMs: 25 },
      { id: 'ss3', type: 'touch_move', x: 1000, y: 640, pointerId: 1, delayMs: 25 },
      { id: 'ss4', type: 'touch_up', x: 1000, y: 640, pointerId: 1, delayMs: 15 }
    ]
  }
];

export const INITIAL_CALIBRATION: GyroCalibrationState = {
  offsetX: -0.0125,
  offsetY: 0.0084,
  offsetZ: 0.0031,
  samplesCollected: 512,
  noiseLevel: 0.0019,
  lastCalibrated: '2026-06-13 14:15:22'
};

export const DEVICE_RAW_NODES = [
  { path: '/dev/input/event1', name: 'Vortex XP107 DualMode Gamepad', type: 'XInput / evdev' },
  { path: '/dev/input/event2', name: 'Sony Interactive Entertainment DualSense Wireless Controller', type: 'PS5 / evdev' },
  { path: '/dev/input/event3', name: 'Microsoft Xbox Series X Controller', type: 'Bluetooth Input' },
  { path: '/dev/input/event4', name: 'Nintendo Switch Pro Controller', type: 'DInput / HID' },
  { path: '/dev/input/event5', name: 'Vortex Gyroscopic Motion Sensor Unit', type: 'IMU / evdev' }
];
