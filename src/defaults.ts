import { GamepadProfile, GamepadMacro } from './types';

/**
 * Fix untuk BUG-L01: tambah 2 profile default tambahan agar Multi Profil
 * yang diiklankan README terwujud. Sebelumnya hanya 1 profile Genshin.
 */
export const INITIAL_PROFILES: GamepadProfile[] = [
  {
    id: 'genshin',
    name: 'Genshin Impact',
    packageName: 'com.miHoYo.GenshinImpact',
    description: 'Default action mapping untuk Genshin Impact',
    isCustom: false,
    gyroSensitivity: 1.0,
    deadzone: 0.15,
    smoothing: 0.5,
    buttons: [
      { id: '1', label: 'Attack', type: 'button', x: 85, y: 75, width: 40, height: 40, mappedKey: 'A', androidEventCode: 96, opacity: 0.8 },
      { id: '2', label: 'Movement', type: 'analog_stick', x: 15, y: 70, width: 140, height: 140, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.1 }
    ]
  },
  {
    id: 'pubgm',
    name: 'PUBG Mobile',
    packageName: 'com.tencent.ig',
    description: 'Mapping untuk PUBG Mobile (landscape, FPS control)',
    isCustom: false,
    gyroSensitivity: 1.5,
    deadzone: 0.12,
    smoothing: 0.3,
    buttons: [
      { id: '1', label: 'Move', type: 'analog_stick', x: 20, y: 70, width: 140, height: 140, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.1 },
      { id: '2', label: 'Look', type: 'analog_stick', x: 80, y: 50, width: 120, height: 120, mappedKey: 'R_STICK', androidEventCode: 0, opacity: 0.4, deadzone: 0.08 },
      { id: '3', label: 'Fire', type: 'button', x: 85, y: 75, width: 50, height: 50, mappedKey: 'RT', androidEventCode: 0, opacity: 0.8 },
      { id: '4', label: 'Aim', type: 'button', x: 80, y: 65, width: 40, height: 40, mappedKey: 'LT', androidEventCode: 0, opacity: 0.7 },
      { id: '5', label: 'Jump', type: 'button', x: 70, y: 80, width: 40, height: 40, mappedKey: 'B', androidEventCode: 0, opacity: 0.8 },
      { id: '6', label: 'Crouch', type: 'button', x: 60, y: 85, width: 40, height: 40, mappedKey: 'A', androidEventCode: 0, opacity: 0.8 }
    ]
  },
  {
    id: 'mlbb',
    name: 'Mobile Legends',
    packageName: 'com.mobile.legends',
    description: 'Mapping untuk Mobile Legends (portrait, MOBA control)',
    isCustom: false,
    gyroSensitivity: 0.0,
    deadzone: 0.18,
    smoothing: 0.4,
    buttons: [
      { id: '1', label: 'Move', type: 'analog_stick', x: 20, y: 70, width: 140, height: 140, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.12 },
      { id: '2', label: 'Skill 1', type: 'button', x: 75, y: 75, width: 45, height: 45, mappedKey: 'A', androidEventCode: 0, opacity: 0.8 },
      { id: '3', label: 'Skill 2', type: 'button', x: 85, y: 65, width: 45, height: 45, mappedKey: 'B', androidEventCode: 0, opacity: 0.8 },
      { id: '4', label: 'Skill 3', type: 'button', x: 80, y: 85, width: 45, height: 45, mappedKey: 'X', androidEventCode: 0, opacity: 0.8 },
      { id: '5', label: 'Ultimate', type: 'button', x: 90, y: 75, width: 50, height: 50, mappedKey: 'Y', androidEventCode: 0, opacity: 0.9 }
    ]
  }
];

export const INITIAL_MACROS: GamepadMacro[] = [];

/**
 * Fix untuk BUG-M03: hapus hardcode /dev/input/event1.
 * Path event device tidak deterministik, berbeda per boot dan per device.
 * GamepadTester import array ini tetapi hanya untuk display.
 * Biarkan kosong, akan di-populate saat runtime via InputManager API.
 */
export const DEVICE_RAW_NODES: { path: string; name: string; type: string }[] = [];
