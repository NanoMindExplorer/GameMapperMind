import { GamepadProfile, GamepadMacro } from './types';

export const INITIAL_PROFILES: GamepadProfile[] = [
  {
    id: 'genshin',
    name: 'Genshin Impact',
    packageName: 'com.miHoYo.GenshinImpact',
    description: 'Default action mapping',
    isCustom: false,
    gyroSensitivity: 1.0,
    deadzone: 0.15,
    smoothing: 0.5,
    buttons: [
      { id: '1', label: 'Attack', type: 'button', x: 85, y: 75, width: 40, height: 40, mappedKey: 'BUTTON_A', androidEventCode: 96, opacity: 0.8 },
      { id: '2', label: 'Movement', type: 'analog_stick', x: 15, y: 70, width: 140, height: 140, mappedKey: 'L_STICK', androidEventCode: 0, opacity: 0.5, deadzone: 0.1 }
    ]
  }
];

export const INITIAL_MACROS: GamepadMacro[] = [];

export const DEVICE_RAW_NODES = [
  { path: '/dev/input/event1', name: 'Generic Gamepad', type: 'Gamepad' }
];
