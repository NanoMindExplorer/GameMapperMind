// ============================================================
// Community Profiles — User-contributed game profiles
// ============================================================
// Users can add their own game profiles here by creating a new
// .ts file in this directory. The profile will be automatically
// loaded and merged with the official preset profiles.
//
// HOW TO ADD YOUR PROFILE:
// 1. Fork this repository
// 2. Copy _template.ts to a new file (e.g., myGame.ts)
// 3. Edit the profile data (game name, button positions, etc.)
// 4. Submit a Pull Request
//
// RULES:
// - Only files in src/communityProfiles/ can be modified in PR
// - Profile must follow the GamepadProfile type schema
// - Profile packageName must match the actual Android app
// - Button coordinates should be percentages (0-100) so they
//   auto-scale to any screen size
// ============================================================

import { GamepadProfile } from '../types';
import { DEFAULT_ANTI_BAN, DEFAULT_GYRO_MAPPING } from '../defaults';

// Import all community profiles (add your import here)
// Example: import { MY_GAME } from './myGame';

// Auto-collect all exported profiles from this directory
// New files will be automatically picked up by the bundler
const communityProfileImports: GamepadProfile[] = [
  // Add your imported profile here
  // Example: MY_GAME,
];

export const COMMUNITY_PROFILES: GamepadProfile[] = communityProfileImports;
