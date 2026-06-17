// constants.ts — Shared constants extracted from defaults.ts
// This file breaks the circular import:
//   defaults.ts → gameProfiles.ts → defaults.ts
// Now: gameProfiles.ts → constants.ts (no cycle)

import { AntiBanConfig, GyroMapping } from './types';

export const DEFAULT_ANTI_BAN: AntiBanConfig = {
  enabled: true,
  coordinateJitter: 4,
  timingJitter: 3,
  pressureVariance: 0.15,
  sizeVariance: 0.10,
  strokeDurationJitter: 12,
  microPauseProbability: 0.02,
  microPauseMaxMs: 45,
};

export const DEFAULT_GYRO_MAPPING: GyroMapping = {
  enabled: false,
  mode: 'camera',
  sensitivityX: 800,
  sensitivityY: 600,
  invertX: false,
  invertY: false,
  deadzone: 0.05,
  smoothing: 0.3,
  targetX: 1530,
  targetY: 540,
  targetRadius: 150,
};
