/**
 * FASE 3.3 — Loader for GameMapper profiles.
 *
 * Path di repo: src/schemas/profileLoader.ts
 *
 * Responsibilities:
 *   - Load profile JSON from bundled assets at runtime.
 *   - Validate against ProfileSchema (Zod).
 *   - Cache parsed profiles in-memory (keyed by profileId).
 *   - Expose `loadProfile(id)` and `loadAllProfiles()` to React hooks.
 *
 * Failure modes:
 *   - File not found          → return { success: false, errors: ['not found'] }
 *   - JSON parse error        → return { success: false, errors: [parse msg] }
 *   - Schema validation error → return { success: false, errors: [...] }
 *   - Any other error         → return { success: false, errors: [msg] }
 *
 * The loader NEVER throws — callers can use the discriminated union.
 */

import type { Profile, ProfileValidationResult } from './gameProfile';
import { validateProfile } from './gameProfile';

// In a Vite/Capacitor setup, profile JSONs live alongside the bundled JS.
// Adjust the import.meta.glob pattern if your project structure differs.
const profileModules = import.meta.glob<{ default: unknown }>(
  '../gameProfiles/*.json',
  { eager: true, import: 'default' }
);

const cache = new Map<string, Profile>();

function keyFromPath(p: string): string {
  // '../gameProfiles/pubgm-classic.json' → 'pubgm-classic'
  const m = p.match(/\/([^/]+)\.json$/);
  return m ? m[1] : p;
}

export function loadProfile(profileId: string): ProfileValidationResult {
  // 1) Cache hit.
  const cached = cache.get(profileId);
  if (cached) return { success: true, data: cached };

  // 2) Find module by id.
  const path = Object.keys(profileModules).find(
    (p) => keyFromPath(p) === profileId
  );
  if (!path) {
    return {
      success: false,
      errors: [`Profile not found: ${profileId}`],
    };
  }

  // 3) Validate.
  const raw = profileModules[path];
  const result = validateProfile(raw);
  if (result.success) {
    cache.set(profileId, result.data);
  }
  return result;
}

export function loadAllProfiles(): {
  valid: Profile[];
  invalid: { profileId: string; errors: string[] }[];
} {
  const valid: Profile[] = [];
  const invalid: { profileId: string; errors: string[] }[] = [];

  for (const [path, raw] of Object.entries(profileModules)) {
    const id = keyFromPath(path);
    const result = validateProfile(raw);
    if (result.success) {
      cache.set(id, result.data);
      valid.push(result.data);
    } else {
      invalid.push({ profileId: id, errors: (result as { errors: string[] }).errors });
    }
  }

  return { valid, invalid };
}

/**
 * Find a profile by Android package name. Used for auto-switching when the
 * foreground app changes. Returns the first match (profiles are expected to
 * have unique packageNames, but this is not enforced at schema level).
 */
export function findProfileByPackage(pkg: string): Profile | null {
  // Ensure cache is warm.
  if (cache.size === 0) loadAllProfiles();
  for (const p of cache.values()) {
    if (p.packageName === pkg) return p;
  }
  return null;
}

/**
 * Clear the in-memory cache. Useful in tests or when hot-reloading profiles
 * during development.
 */
export function clearProfileCache(): void {
  cache.clear();
}
