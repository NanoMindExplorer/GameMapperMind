import { GamepadProfile, GamepadMacro } from './types';

export const INITIAL_PROFILES: GamepadProfile[] = [
  {
    id: 'genshin',
    name: 'Genshin Impact',
    packageName: 'com.miHoYo.GenshinImpact',
    description: 'Default action mapping untuk Genshin Impact',
    isCustom: false,
    gyroSensitivity: 1.0,
    deadzone: 0.15,
    smoothing: 0.3,
    buttons: [
      { id: 'g1', label: 'Attack', type: 'button', x: 85, y: 75, width: 56, height: 56, mappedKey: 'A', androidEventCode: 96, opacity: 0.8 },
      { id: 'g2', label: 'Skill', type: 'button', x: 78, y: 85, width: 56, height: 56, mappedKey: 'X', androidEventCode: 99, opacity: 0.8 },
      { id: 'g3', label: 'Burst', type: 'button', x: 90, y: 65, width: 56, height: 56, mappedKey: 'Y', androidEventCode: 100, opacity: 0.8 },
      { id: 'g4', label: 'Jump', type: 'button', x: 72, y: 78, width: 56, height: 56, mappedKey: 'B', androidEventCode: 97, opacity: 0.8 },
      { id: 'g5', label: 'Sprint', type: 'button', x: 60, y: 50, width: 56, height: 56, mappedKey: 'RT', androidEventCode: 111, opacity: 0.8 },
      { id: 'g6', label: 'Aim', type: 'button', x: 40, y: 50, width: 56, height: 56, mappedKey: 'LT', androidEventCode: 110, opacity: 0.8 },
      { id: 'g7', label: 'Menu', type: 'button', x: 50, y: 15, width: 48, height: 48, mappedKey: 'START', androidEventCode: 108, opacity: 0.7 },
      { id: 'g8', label: 'Map', type: 'button', x: 10, y: 15, width: 48, height: 48, mappedKey: 'SELECT', androidEventCode: 109, opacity: 0.7 },
      { id: 'g9', label: 'Movement', type: 'analog_stick', x: 20, y: 70, width: 120, height: 120, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 },
      { id: 'g10', label: 'Camera', type: 'analog_stick', x: 80, y: 40, width: 120, height: 120, mappedKey: 'R_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 }
    ]
  },
  {
    id: 'pubg',
    name: 'PUBG Mobile',
    packageName: 'com.tencent.ig',
    description: 'Default mapping untuk PUBG Mobile',
    isCustom: false,
    gyroSensitivity: 1.2,
    deadzone: 0.15,
    smoothing: 0.2,
    buttons: [
      { id: 'p1', label: 'Fire', type: 'button', x: 85, y: 75, width: 64, height: 64, mappedKey: 'RT', androidEventCode: 111, opacity: 0.8 },
      { id: 'p2', label: 'Aim', type: 'button', x: 20, y: 75, width: 64, height: 64, mappedKey: 'LT', androidEventCode: 110, opacity: 0.8 },
      { id: 'p3', label: 'Crouch', type: 'button', x: 75, y: 60, width: 48, height: 48, mappedKey: 'B', androidEventCode: 97, opacity: 0.7 },
      { id: 'p4', label: 'Prone', type: 'button', x: 80, y: 50, width: 48, height: 48, mappedKey: 'X', androidEventCode: 99, opacity: 0.7 },
      { id: 'p5', label: 'Jump', type: 'button', x: 70, y: 80, width: 48, height: 48, mappedKey: 'A', androidEventCode: 96, opacity: 0.7 },
      { id: 'p6', label: 'Reload', type: 'button', x: 60, y: 65, width: 48, height: 48, mappedKey: 'Y', androidEventCode: 100, opacity: 0.7 },
      { id: 'p7', label: 'Inventory', type: 'button', x: 50, y: 15, width: 48, height: 48, mappedKey: 'START', androidEventCode: 108, opacity: 0.7 },
      { id: 'p8', label: 'Map', type: 'button', x: 10, y: 15, width: 48, height: 48, mappedKey: 'SELECT', androidEventCode: 109, opacity: 0.7 },
      // BUG-DEF2 FIX: Added LB/RB for grenade and melee — these were missing in PUBG profile.
      { id: 'p11', label: 'Grenade', type: 'button', x: 55, y: 50, width: 48, height: 48, mappedKey: 'LB', androidEventCode: 102, opacity: 0.7 },
      { id: 'p12', label: 'Melee', type: 'button', x: 60, y: 55, width: 48, height: 48, mappedKey: 'RB', androidEventCode: 103, opacity: 0.7 },
      { id: 'p9', label: 'Move', type: 'analog_stick', x: 22, y: 70, width: 120, height: 120, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 },
      { id: 'p10', label: 'Look', type: 'analog_stick', x: 80, y: 40, width: 120, height: 120, mappedKey: 'R_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 }
    ]
  },
  {
    id: 'mlbb',
    name: 'Mobile Legends',
    packageName: 'com.mobile.legends',
    description: 'Default mapping untuk Mobile Legends Bang Bang',
    isCustom: false,
    gyroSensitivity: 0.8,
    deadzone: 0.15,
    smoothing: 0.3,
    buttons: [
      { id: 'm1', label: 'Attack', type: 'button', x: 85, y: 75, width: 56, height: 56, mappedKey: 'A', androidEventCode: 96, opacity: 0.8 },
      { id: 'm2', label: 'Skill 1', type: 'button', x: 75, y: 85, width: 56, height: 56, mappedKey: 'X', androidEventCode: 99, opacity: 0.8 },
      { id: 'm3', label: 'Skill 2', type: 'button', x: 90, y: 65, width: 56, height: 56, mappedKey: 'Y', androidEventCode: 100, opacity: 0.8 },
      { id: 'm4', label: 'Skill 3', type: 'button', x: 70, y: 70, width: 56, height: 56, mappedKey: 'B', androidEventCode: 97, opacity: 0.8 },
      { id: 'm5', label: 'Recall', type: 'button', x: 50, y: 15, width: 48, height: 48, mappedKey: 'START', androidEventCode: 108, opacity: 0.7 },
      { id: 'm6', label: 'Shop', type: 'button', x: 10, y: 15, width: 48, height: 48, mappedKey: 'SELECT', androidEventCode: 109, opacity: 0.7 },
      { id: 'm7', label: 'Move', type: 'analog_stick', x: 22, y: 70, width: 120, height: 120, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 }
    ]
  },
  {
    id: 'codm',
    name: 'Call of Duty Mobile',
    packageName: 'com.activision.callofduty.shooter',
    description: 'Default mapping untuk COD Mobile',
    isCustom: false,
    gyroSensitivity: 1.0,
    deadzone: 0.12,
    smoothing: 0.2,
    buttons: [
      { id: 'c1', label: 'Fire', type: 'button', x: 85, y: 75, width: 64, height: 64, mappedKey: 'RT', androidEventCode: 111, opacity: 0.8 },
      { id: 'c2', label: 'ADS', type: 'button', x: 20, y: 75, width: 64, height: 64, mappedKey: 'LT', androidEventCode: 110, opacity: 0.8 },
      { id: 'c3', label: 'Crouch', type: 'button', x: 75, y: 60, width: 48, height: 48, mappedKey: 'B', androidEventCode: 97, opacity: 0.7 },
      { id: 'c4', label: 'Slide', type: 'button', x: 80, y: 50, width: 48, height: 48, mappedKey: 'X', androidEventCode: 99, opacity: 0.7 },
      { id: 'c5', label: 'Jump', type: 'button', x: 70, y: 80, width: 48, height: 48, mappedKey: 'A', androidEventCode: 96, opacity: 0.7 },
      { id: 'c6', label: 'Reload', type: 'button', x: 60, y: 65, width: 48, height: 48, mappedKey: 'Y', androidEventCode: 100, opacity: 0.7 },
      { id: 'c7', label: 'Grenade', type: 'button', x: 50, y: 50, width: 48, height: 48, mappedKey: 'LB', androidEventCode: 102, opacity: 0.7 },
      { id: 'c8', label: 'Special', type: 'button', x: 55, y: 60, width: 48, height: 48, mappedKey: 'RB', androidEventCode: 103, opacity: 0.7 },
      { id: 'c9', label: 'Move', type: 'analog_stick', x: 22, y: 70, width: 120, height: 120, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.12 },
      { id: 'c10', label: 'Look', type: 'analog_stick', x: 80, y: 40, width: 120, height: 120, mappedKey: 'R_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.12 }
    ]
  },
  {
    id: 'freefire',
    name: 'Free Fire',
    packageName: 'com.dts.freefireth',
    description: 'Default mapping untuk Garena Free Fire',
    isCustom: false,
    gyroSensitivity: 1.0,
    deadzone: 0.15,
    smoothing: 0.2,
    buttons: [
      { id: 'f1', label: 'Fire', type: 'button', x: 85, y: 75, width: 64, height: 64, mappedKey: 'RT', androidEventCode: 111, opacity: 0.8 },
      { id: 'f2', label: 'Aim', type: 'button', x: 20, y: 75, width: 64, height: 64, mappedKey: 'LT', androidEventCode: 110, opacity: 0.8 },
      { id: 'f3', label: 'Crouch', type: 'button', x: 75, y: 60, width: 48, height: 48, mappedKey: 'B', androidEventCode: 97, opacity: 0.7 },
      { id: 'f4', label: 'Jump', type: 'button', x: 70, y: 80, width: 48, height: 48, mappedKey: 'A', androidEventCode: 96, opacity: 0.7 },
      { id: 'f5', label: 'Grenade', type: 'button', x: 60, y: 65, width: 48, height: 48, mappedKey: 'Y', androidEventCode: 100, opacity: 0.7 },
      { id: 'f6', label: 'Medkit', type: 'button', x: 55, y: 50, width: 48, height: 48, mappedKey: 'X', androidEventCode: 99, opacity: 0.7 },
      { id: 'f7', label: 'Move', type: 'analog_stick', x: 22, y: 70, width: 120, height: 120, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 },
      { id: 'f8', label: 'Look', type: 'analog_stick', x: 80, y: 40, width: 120, height: 120, mappedKey: 'R_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 }
    ]
  },
  {
    // BUG-FIX: Profil eFootball sebelumnya tidak ada di INITIAL_PROFILES.
    // User yang main eFootball pakai profil default (genshin) → koordinat salah total
    // → sentuhan masuk di tempat kosong → terasa "tidak ada injeksi".
    // eFootball adalah game landscape, kontrol pemain pakai L-stick, tombol aksi di kanan.
    // Layout: Pass/Shoot/Sprint/Through-ball + analog gerak + skill moves.
    id: 'efootball',
    name: 'eFootball',
    packageName: 'jp.konami.pesam',
    description: 'Default mapping untuk eFootball (PES mobile)',
    isCustom: false,
    gyroSensitivity: 1.0,
    deadzone: 0.15,
    smoothing: 0.3,
    orientation: 'landscape',
    screenshotMode: 'efootball',
    buttons: [
      // Action buttons (right side, bottom) — typical football game layout
      { id: 'e1', label: 'Pass', type: 'button', x: 85, y: 78, width: 56, height: 56, mappedKey: 'A', androidEventCode: 96, opacity: 0.8 },
      { id: 'e2', label: 'Shoot', type: 'button', x: 90, y: 68, width: 64, height: 64, mappedKey: 'B', androidEventCode: 97, opacity: 0.85 },
      { id: 'e3', label: 'Through', type: 'button', x: 78, y: 88, width: 52, height: 52, mappedKey: 'X', androidEventCode: 99, opacity: 0.8 },
      { id: 'e4', label: 'Lob/Cross', type: 'button', x: 72, y: 75, width: 52, height: 52, mappedKey: 'Y', androidEventCode: 100, opacity: 0.8 },
      // Sprint (RT) — hold to run faster
      { id: 'e5', label: 'Sprint', type: 'button', x: 60, y: 50, width: 56, height: 56, mappedKey: 'RT', androidEventCode: 105, opacity: 0.75 },
      // Skill move (LT) — for fancy footwork
      { id: 'e6', label: 'Skill', type: 'button', x: 40, y: 50, width: 56, height: 56, mappedKey: 'LT', androidEventCode: 104, opacity: 0.75 },
      // LB/RB — switch player / tactics
      { id: 'e7', label: 'Switch', type: 'button', x: 50, y: 30, width: 48, height: 48, mappedKey: 'LB', androidEventCode: 102, opacity: 0.7 },
      { id: 'e8', label: 'Tactics', type: 'button', x: 50, y: 18, width: 48, height: 48, mappedKey: 'RB', androidEventCode: 103, opacity: 0.7 },
      // Menu buttons
      { id: 'e9', label: 'Pause', type: 'button', x: 50, y: 5, width: 44, height: 44, mappedKey: 'START', androidEventCode: 108, opacity: 0.6 },
      // Movement stick (left side)
      { id: 'e10', label: 'Move', type: 'analog_stick', x: 22, y: 70, width: 120, height: 120, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.15 },
      // Camera/view stick (right side, upper) — for replays/camera angle
      { id: 'e11', label: 'Camera', type: 'analog_stick', x: 80, y: 35, width: 100, height: 100, mappedKey: 'R_STICK', androidEventCode: 0, opacity: 0.4, deadzone: 0.15 }
    ]
  }
];

export const INITIAL_MACROS: GamepadMacro[] = [];

export const DEVICE_RAW_NODES = [
  { path: '/dev/input/event1', name: 'Generic Gamepad', type: 'Gamepad' }
];
