// GameMapperMind — Preset Profiles untuk Game Mobile Populer
// Dioptimalkan untuk tablet 12.2" (Huawei MatePad Pro 12.2)
// Resolusi landscape: 2800 x 1840 (3:2 aspect ratio)

import { GamepadProfile } from './types';
import { DEFAULT_ANTI_BAN, DEFAULT_GYRO_MAPPING } from './defaults';

const SCREEN_W = 2800;
const SCREEN_H = 1840;

function px(xPct: number, yPct: number): { x: number; y: number } {
  return { x: Math.round((xPct / 100) * SCREEN_W), y: Math.round((yPct / 100) * SCREEN_H) };
}

interface ButtonDef {
  id: string; label: string; hwKey: string; x: number; y: number;
  width?: number; height?: number; type?: 'button' | 'analog_stick';
  mappedKey: string; code?: number; opacity?: number;
}

function buildProfile(id: string, name: string, packageName: string, description: string,
  buttonDefs: ButtonDef[], leftStick: { x: number; y: number; radius: number },
  rightStick: { x: number; y: number; radius: number }, gyroEnabled: boolean = false,
): GamepadProfile {
  const buttons = buttonDefs.map(b => ({
    id: b.id, label: b.label, type: b.type || 'button' as const,
    x: b.x, y: b.y, width: b.width || 56, height: b.height || 56,
    mappedKey: b.mappedKey, androidEventCode: b.code || 0, opacity: b.opacity || 0.75,
  }));
  const mappings = buttonDefs.filter(b => b.type !== 'analog_stick').map(b => {
    const p = px(b.x, b.y); return { hardwareKey: b.hwKey, x: p.x, y: p.y };
  });
  const lP = px(leftStick.x, leftStick.y), rP = px(rightStick.x, rightStick.y);
  return {
    id, name, packageName, description, isCustom: false,
    gyroSensitivity: 1.0, deadzone: 0.15, smoothing: 0.5, buttons, mappings,
    leftJoystick: { centerX: lP.x, centerY: lP.y, radius: leftStick.radius },
    rightJoystick: { centerX: rP.x, centerY: rP.y, radius: rightStick.radius },
    antiBanEnabled: false, antiBanConfig: { ...DEFAULT_ANTI_BAN },
    gyroMapping: { ...DEFAULT_GYRO_MAPPING, enabled: gyroEnabled, targetX: rP.x, targetY: rP.y },
    autoStartEnabled: true,
  };
}

const PUBGM = buildProfile('pubgm', 'PUBG Mobile', 'com.tencent.ig',
  'PUBG Mobile — full FPS battle royale layout (tablet 12.2")',
  [
    { id: 'l_stick', label: 'Move', hwKey: 'L_STICK', x: 14, y: 78, width: 130, height: 130, type: 'analog_stick', mappedKey: 'L_STICK', opacity: 0.5 },
    { id: 'r_stick', label: 'Look', hwKey: 'R_STICK', x: 82, y: 78, width: 130, height: 130, type: 'analog_stick', mappedKey: 'R_STICK', opacity: 0.5 },
    { id: 'fire', label: 'Fire', hwKey: 'R2', x: 88, y: 70, mappedKey: 'BUTTON_R2', code: 105, opacity: 0.85 },
    { id: 'aim', label: 'Aim', hwKey: 'L2', x: 78, y: 68, mappedKey: 'BUTTON_L2', code: 104, opacity: 0.85 },
    { id: 'reload', label: 'Reload', hwKey: 'X', x: 72, y: 75, mappedKey: 'BUTTON_X', code: 99 },
    { id: 'jump', label: 'Jump', hwKey: 'A', x: 90, y: 58, mappedKey: 'BUTTON_A', code: 96 },
    { id: 'crouch', label: 'Crouch', hwKey: 'B', x: 84, y: 52, mappedKey: 'BUTTON_B', code: 97 },
    { id: 'prone', label: 'Prone', hwKey: 'Y', x: 76, y: 50, mappedKey: 'BUTTON_Y', code: 100 },
    { id: 'lean_l', label: 'Lean L', hwKey: 'LB', x: 10, y: 58, mappedKey: 'BUTTON_L1', code: 101 },
    { id: 'lean_r', label: 'Lean R', hwKey: 'RB', x: 16, y: 58, mappedKey: 'BUTTON_R1', code: 102 },
    { id: 'sprint', label: 'Sprint', hwKey: 'L3', x: 22, y: 82, mappedKey: 'BUTTON_L3', code: 103 },
    { id: 'scope', label: 'Scope', hwKey: 'R3', x: 86, y: 62, mappedKey: 'BUTTON_R3', code: 106 },
    { id: 'inventory', label: 'Bag', hwKey: 'Y', x: 94, y: 25, mappedKey: 'BUTTON_Y', code: 100 },
    { id: 'map', label: 'Map', hwKey: 'SELECT', x: 4, y: 8, mappedKey: 'BUTTON_SELECT', code: 109, width: 45, height: 24 },
    { id: 'grenade', label: 'Throw', hwKey: 'UP', x: 68, y: 45, mappedKey: 'DPAD_UP', code: 106 },
    { id: 'smoke', label: 'Smoke', hwKey: 'RIGHT', x: 66, y: 50, mappedKey: 'DPAD_RIGHT', code: 109 },
    { id: 'heal', label: 'Heal', hwKey: 'DOWN', x: 64, y: 55, mappedKey: 'DPAD_DOWN', code: 107 },
    { id: 'pickup', label: 'Pick', hwKey: 'A', x: 50, y: 60, mappedKey: 'BUTTON_A', code: 96 },
    { id: 'weapon1', label: 'W1', hwKey: 'UP', x: 88, y: 35, mappedKey: 'DPAD_UP', code: 106, width: 40, height: 40 },
    { id: 'weapon2', label: 'W2', hwKey: 'DOWN', x: 88, y: 42, mappedKey: 'DPAD_DOWN', code: 107, width: 40, height: 40 },
    { id: 'vehicle', label: 'Drive', hwKey: 'A', x: 60, y: 80, mappedKey: 'BUTTON_A', code: 96 },
    { id: 'brake', label: 'Brake', hwKey: 'B', x: 70, y: 82, mappedKey: 'BUTTON_B', code: 97 },
    { id: 'door', label: 'Door', hwKey: 'X', x: 50, y: 45, mappedKey: 'BUTTON_X', code: 99 },
  ], { x: 14, y: 78, radius: 160 }, { x: 82, y: 78, radius: 160 }, true);

const FREEFIRE = buildProfile('freefire', 'Free Fire', 'com.dts.freefireth',
  'Garena Free Fire — battle royale (tablet 12.2")',
  [
    { id: 'l_stick', label: 'Move', hwKey: 'L_STICK', x: 15, y: 78, width: 130, height: 130, type: 'analog_stick', mappedKey: 'L_STICK', opacity: 0.5 },
    { id: 'r_stick', label: 'Look', hwKey: 'R_STICK', x: 82, y: 78, width: 130, height: 130, type: 'analog_stick', mappedKey: 'R_STICK', opacity: 0.5 },
    { id: 'fire', label: 'Fire', hwKey: 'R2', x: 90, y: 72, mappedKey: 'BUTTON_R2', code: 105, opacity: 0.85 },
    { id: 'aim', label: 'Aim', hwKey: 'L2', x: 78, y: 68, mappedKey: 'BUTTON_L2', code: 104, opacity: 0.85 },
    { id: 'reload', label: 'Reload', hwKey: 'X', x: 72, y: 76, mappedKey: 'BUTTON_X', code: 99 },
    { id: 'jump', label: 'Jump', hwKey: 'A', x: 90, y: 60, mappedKey: 'BUTTON_A', code: 96 },
    { id: 'crouch', label: 'Crouch', hwKey: 'B', x: 85, y: 55, mappedKey: 'BUTTON_B', code: 97 },
    { id: 'prone', label: 'Prone', hwKey: 'Y', x: 77, y: 53, mappedKey: 'BUTTON_Y', code: 100 },
    { id: 'sprint', label: 'Sprint', hwKey: 'L3', x: 23, y: 82, mappedKey: 'BUTTON_L3', code: 103 },
    { id: 'scope', label: 'Scope', hwKey: 'R3', x: 86, y: 64, mappedKey: 'BUTTON_R3', code: 106 },
    { id: 'grenade', label: 'Grenade', hwKey: 'UP', x: 68, y: 48, mappedKey: 'DPAD_UP', code: 106 },
    { id: 'medkit', label: 'Medkit', hwKey: 'LEFT', x: 66, y: 52, mappedKey: 'DPAD_LEFT', code: 108 },
    { id: 'weapon1', label: 'W1', hwKey: 'UP', x: 88, y: 35, mappedKey: 'DPAD_UP', code: 106, width: 40, height: 40 },
    { id: 'weapon2', label: 'W2', hwKey: 'DOWN', x: 88, y: 42, mappedKey: 'DPAD_DOWN', code: 107, width: 40, height: 40 },
    { id: 'map', label: 'Map', hwKey: 'SELECT', x: 4, y: 8, mappedKey: 'BUTTON_SELECT', code: 109, width: 45, height: 24 },
    { id: 'run', label: 'Run', hwKey: 'LB', x: 10, y: 60, mappedKey: 'BUTTON_L1', code: 101 },
    { id: 'pickup', label: 'Pick', hwKey: 'A', x: 50, y: 60, mappedKey: 'BUTTON_A', code: 96 },
  ], { x: 15, y: 78, radius: 160 }, { x: 82, y: 78, radius: 160 }, true);

const CODM = buildProfile('codm', 'Call of Duty Mobile', 'com.activision.callofduty.shooter',
  'COD Mobile — FPS + BR mode (tablet 12.2")',
  [
    { id: 'l_stick', label: 'Move', hwKey: 'L_STICK', x: 14, y: 78, width: 130, height: 130, type: 'analog_stick', mappedKey: 'L_STICK', opacity: 0.5 },
    { id: 'r_stick', label: 'Look', hwKey: 'R_STICK', x: 82, y: 78, width: 130, height: 130, type: 'analog_stick', mappedKey: 'R_STICK', opacity: 0.5 },
    { id: 'fire', label: 'Fire', hwKey: 'R2', x: 90, y: 72, mappedKey: 'BUTTON_R2', code: 105, opacity: 0.85 },
    { id: 'aim', label: 'Aim ADS', hwKey: 'L2', x: 78, y: 68, mappedKey: 'BUTTON_L2', code: 104, opacity: 0.85 },
    { id: 'reload', label: 'Reload', hwKey: 'X', x: 72, y: 76, mappedKey: 'BUTTON_X', code: 99 },
    { id: 'jump', label: 'Jump', hwKey: 'A', x: 90, y: 60, mappedKey: 'BUTTON_A', code: 96 },
    { id: 'slide', label: 'Slide', hwKey: 'B', x: 85, y: 55, mappedKey: 'BUTTON_B', code: 97 },
    { id: 'crouch', label: 'Crouch', hwKey: 'Y', x: 77, y: 53, mappedKey: 'BUTTON_Y', code: 100 },
    { id: 'grenade', label: 'Nade', hwKey: 'LB', x: 10, y: 58, mappedKey: 'BUTTON_L1', code: 101 },
    { id: 'tactical', label: 'Tactical', hwKey: 'RB', x: 16, y: 58, mappedKey: 'BUTTON_R1', code: 102 },
    { id: 'melee', label: 'Melee', hwKey: 'R3', x: 86, y: 64, mappedKey: 'BUTTON_R3', code: 106 },
    { id: 'sprint', label: 'Sprint', hwKey: 'L3', x: 23, y: 82, mappedKey: 'BUTTON_L3', code: 103 },
    { id: 'weapon1', label: 'Primary', hwKey: 'UP', x: 88, y: 35, mappedKey: 'DPAD_UP', code: 106, width: 40, height: 40 },
    { id: 'weapon2', label: 'Secondary', hwKey: 'DOWN', x: 88, y: 42, mappedKey: 'DPAD_DOWN', code: 107, width: 40, height: 40 },
    { id: 'scorestreak', label: 'Score', hwKey: 'LEFT', x: 66, y: 52, mappedKey: 'DPAD_LEFT', code: 108 },
    { id: 'operator', label: 'Operator', hwKey: 'RIGHT', x: 64, y: 48, mappedKey: 'DPAD_RIGHT', code: 109 },
    { id: 'map', label: 'Map', hwKey: 'SELECT', x: 4, y: 8, mappedKey: 'BUTTON_SELECT', code: 109, width: 45, height: 24 },
    { id: 'menu', label: 'Menu', hwKey: 'START', x: 96, y: 8, mappedKey: 'BUTTON_START', code: 108, width: 45, height: 24 },
  ], { x: 14, y: 78, radius: 160 }, { x: 82, y: 78, radius: 160 }, true);

const MOBILE_LEGENDS = buildProfile('mlbb', 'Mobile Legends', 'com.mobile.legends',
  'Mobile Legends Bang Bang — MOBA 5v5 (tablet 12.2")',
  [
    { id: 'l_stick', label: 'Move', hwKey: 'L_STICK', x: 14, y: 78, width: 140, height: 140, type: 'analog_stick', mappedKey: 'L_STICK', opacity: 0.5 },
    { id: 'r_stick', label: 'Camera', hwKey: 'R_STICK', x: 82, y: 30, width: 100, height: 100, type: 'analog_stick', mappedKey: 'R_STICK', opacity: 0.4 },
    { id: 'attack', label: 'Attack', hwKey: 'A', x: 88, y: 78, mappedKey: 'BUTTON_A', code: 96, opacity: 0.85 },
    { id: 'skill1', label: 'Skill 1', hwKey: 'X', x: 78, y: 82, mappedKey: 'BUTTON_X', code: 99, opacity: 0.85 },
    { id: 'skill2', label: 'Skill 2', hwKey: 'B', x: 84, y: 66, mappedKey: 'BUTTON_B', code: 97, opacity: 0.85 },
    { id: 'skill3', label: 'Ultimate', hwKey: 'Y', x: 72, y: 72, mappedKey: 'BUTTON_Y', code: 100, opacity: 0.85 },
    { id: 'skill4', label: 'Enhance', hwKey: 'RB', x: 90, y: 55, mappedKey: 'BUTTON_R1', code: 102, opacity: 0.8 },
    { id: 'recall', label: 'Recall', hwKey: 'LB', x: 8, y: 60, mappedKey: 'BUTTON_L1', code: 101, opacity: 0.7 },
    { id: 'shop', label: 'Shop', hwKey: 'SELECT', x: 4, y: 8, mappedKey: 'BUTTON_SELECT', code: 109, width: 45, height: 24 },
    { id: 'map', label: 'Map', hwKey: 'START', x: 96, y: 8, mappedKey: 'BUTTON_START', code: 108, width: 45, height: 24 },
    { id: 'target', label: 'Target', hwKey: 'L2', x: 70, y: 80, mappedKey: 'BUTTON_L2', code: 104 },
    { id: 'target2', label: 'Target Min', hwKey: 'R2', x: 76, y: 86, mappedKey: 'BUTTON_R2', code: 105 },
    { id: 'emote', label: 'Emote', hwKey: 'UP', x: 5, y: 50, mappedKey: 'DPAD_UP', code: 106 },
    { id: 'buff', label: 'Buff', hwKey: 'DOWN', x: 5, y: 55, mappedKey: 'DPAD_DOWN', code: 107 },
  ], { x: 14, y: 78, radius: 170 }, { x: 82, y: 30, radius: 120 }, false);

const EFOOTBALL = buildProfile('efootball', 'eFootball 2024', 'com.konami.pesam',
  'Konami eFootball — soccer simulator (tablet 12.2")',
  [
    { id: 'l_stick', label: 'Move', hwKey: 'L_STICK', x: 14, y: 80, width: 140, height: 140, type: 'analog_stick', mappedKey: 'L_STICK', opacity: 0.5 },
    { id: 'r_stick', label: 'Skill', hwKey: 'R_STICK', x: 82, y: 80, width: 120, height: 120, type: 'analog_stick', mappedKey: 'R_STICK', opacity: 0.5 },
    { id: 'pass', label: 'Pass', hwKey: 'A', x: 88, y: 72, mappedKey: 'BUTTON_A', code: 96, opacity: 0.85 },
    { id: 'shoot', label: 'Shoot', hwKey: 'B', x: 84, y: 62, mappedKey: 'BUTTON_B', code: 97, opacity: 0.85 },
    { id: 'through', label: 'Through', hwKey: 'Y', x: 76, y: 68, mappedKey: 'BUTTON_Y', code: 100, opacity: 0.85 },
    { id: 'sprint', label: 'Sprint', hwKey: 'RB', x: 90, y: 55, mappedKey: 'BUTTON_R1', code: 102, opacity: 0.8 },
    { id: 'skill1', label: 'Skill', hwKey: 'X', x: 72, y: 76, mappedKey: 'BUTTON_X', code: 99, opacity: 0.8 },
    { id: 'tackle', label: 'Tackle', hwKey: 'LB', x: 8, y: 70, mappedKey: 'BUTTON_L1', code: 101, opacity: 0.8 },
    { id: 'slide', label: 'Slide', hwKey: 'L2', x: 8, y: 60, mappedKey: 'BUTTON_L2', code: 104, opacity: 0.8 },
    { id: 'press', label: 'Press', hwKey: 'R2', x: 90, y: 65, mappedKey: 'BUTTON_R2', code: 105, opacity: 0.7 },
    { id: 'switch', label: 'Switch', hwKey: 'R3', x: 86, y: 50, mappedKey: 'BUTTON_R3', code: 106 },
    { id: 'keeper', label: 'Keeper', hwKey: 'Y', x: 50, y: 88, mappedKey: 'BUTTON_Y', code: 100 },
    { id: 'strategy', label: 'Strategy', hwKey: 'SELECT', x: 4, y: 8, mappedKey: 'BUTTON_SELECT', code: 109, width: 45, height: 24 },
    { id: 'pause', label: 'Pause', hwKey: 'START', x: 96, y: 8, mappedKey: 'BUTTON_START', code: 108, width: 45, height: 24 },
  ], { x: 14, y: 80, radius: 170 }, { x: 82, y: 80, radius: 150 }, false);

const GENSHIN = buildProfile('genshin', 'Genshin Impact', 'com.miHoYo.GenshinImpact',
  'Genshin Impact — open world ARPG (tablet 12.2")',
  [
    { id: 'l_stick', label: 'Move', hwKey: 'L_STICK', x: 15, y: 70, width: 140, height: 140, type: 'analog_stick', mappedKey: 'L_STICK', opacity: 0.5 },
    { id: 'r_stick', label: 'Camera', hwKey: 'R_STICK', x: 80, y: 40, width: 120, height: 120, type: 'analog_stick', mappedKey: 'R_STICK', opacity: 0.5 },
    { id: 'attack', label: 'Attack', hwKey: 'A', x: 85, y: 75, mappedKey: 'BUTTON_A', code: 96, opacity: 0.85 },
    { id: 'skill', label: 'Skill', hwKey: 'B', x: 90, y: 65, mappedKey: 'BUTTON_B', code: 97, opacity: 0.85 },
    { id: 'burst', label: 'Burst', hwKey: 'Y', x: 80, y: 62, mappedKey: 'BUTTON_Y', code: 100, opacity: 0.85 },
    { id: 'jump', label: 'Jump', hwKey: 'X', x: 78, y: 80, mappedKey: 'BUTTON_X', code: 99, opacity: 0.8 },
    { id: 'sprint', label: 'Sprint', hwKey: 'RB', x: 88, y: 55, mappedKey: 'BUTTON_R1', code: 102, opacity: 0.75 },
    { id: 'aim', label: 'Aim', hwKey: 'L2', x: 70, y: 78, mappedKey: 'BUTTON_L2', code: 104, opacity: 0.8 },
    { id: 'ultimate', label: 'Q Burst', hwKey: 'R2', x: 75, y: 70, mappedKey: 'BUTTON_R2', code: 105, opacity: 0.8 },
    { id: 'interact', label: 'Interact', hwKey: 'LB', x: 10, y: 55, mappedKey: 'BUTTON_L1', code: 101, opacity: 0.75 },
    { id: 'swap1', label: 'Char 1', hwKey: 'UP', x: 50, y: 12, mappedKey: 'DPAD_UP', code: 106, width: 45, height: 24 },
    { id: 'swap2', label: 'Char 2', hwKey: 'RIGHT', x: 58, y: 12, mappedKey: 'DPAD_RIGHT', code: 109, width: 45, height: 24 },
    { id: 'swap3', label: 'Char 3', hwKey: 'DOWN', x: 66, y: 12, mappedKey: 'DPAD_DOWN', code: 107, width: 45, height: 24 },
    { id: 'swap4', label: 'Char 4', hwKey: 'LEFT', x: 42, y: 12, mappedKey: 'DPAD_LEFT', code: 108, width: 45, height: 24 },
    { id: 'map', label: 'Map', hwKey: 'SELECT', x: 4, y: 8, mappedKey: 'BUTTON_SELECT', code: 109, width: 45, height: 24 },
    { id: 'inventory', label: 'Bag', hwKey: 'START', x: 96, y: 8, mappedKey: 'BUTTON_START', code: 108, width: 45, height: 24 },
    { id: 'heal', label: 'Heal', hwKey: 'L3', x: 22, y: 75, mappedKey: 'BUTTON_L3', code: 103 },
  ], { x: 15, y: 70, radius: 170 }, { x: 80, y: 40, radius: 150 }, true);

const HONOR_OF_KINGS = buildProfile('hok', 'Honor of Kings', 'com.tencent.tmgp.hok',
  'Tencent Honor of Kings — MOBA 5v5 (tablet 12.2")',
  [
    { id: 'l_stick', label: 'Move', hwKey: 'L_STICK', x: 14, y: 78, width: 140, height: 140, type: 'analog_stick', mappedKey: 'L_STICK', opacity: 0.5 },
    { id: 'r_stick', label: 'Camera', hwKey: 'R_STICK', x: 82, y: 30, width: 100, height: 100, type: 'analog_stick', mappedKey: 'R_STICK', opacity: 0.4 },
    { id: 'attack', label: 'Attack', hwKey: 'A', x: 88, y: 78, mappedKey: 'BUTTON_A', code: 96, opacity: 0.85 },
    { id: 'skill1', label: 'Skill 1', hwKey: 'X', x: 78, y: 82, mappedKey: 'BUTTON_X', code: 99, opacity: 0.85 },
    { id: 'skill2', label: 'Skill 2', hwKey: 'B', x: 84, y: 66, mappedKey: 'BUTTON_B', code: 97, opacity: 0.85 },
    { id: 'skill3', label: 'Ultimate', hwKey: 'Y', x: 72, y: 72, mappedKey: 'BUTTON_Y', code: 100, opacity: 0.85 },
    { id: 'skill4', label: 'Special', hwKey: 'RB', x: 90, y: 55, mappedKey: 'BUTTON_R1', code: 102, opacity: 0.8 },
    { id: 'recall', label: 'Recall', hwKey: 'LB', x: 8, y: 60, mappedKey: 'BUTTON_L1', code: 101, opacity: 0.7 },
    { id: 'shop', label: 'Shop', hwKey: 'SELECT', x: 4, y: 8, mappedKey: 'BUTTON_SELECT', code: 109, width: 45, height: 24 },
    { id: 'map', label: 'Map', hwKey: 'START', x: 96, y: 8, mappedKey: 'BUTTON_START', code: 108, width: 45, height: 24 },
    { id: 'target', label: 'Target', hwKey: 'L2', x: 70, y: 80, mappedKey: 'BUTTON_L2', code: 104 },
    { id: 'emote', label: 'Emote', hwKey: 'UP', x: 5, y: 50, mappedKey: 'DPAD_UP', code: 106 },
  ], { x: 14, y: 78, radius: 170 }, { x: 82, y: 30, radius: 120 }, false);

export const PRESET_PROFILES: GamepadProfile[] = [
  GENSHIN, PUBGM, FREEFIRE, CODM, MOBILE_LEGENDS, EFOOTBALL, HONOR_OF_KINGS,
];
