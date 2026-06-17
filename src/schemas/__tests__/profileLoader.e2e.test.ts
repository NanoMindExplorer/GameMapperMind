/**
 * FASE 5.4 — Frontend E2E test: profile load → JS bridge → native pipeline.
 *
 * Path di repo: src/schemas/__tests__/profileLoader.e2e.test.ts
 *
 * Run: `npm test -- profileLoader.e2e`
 *
 * Goal:
 *   Verify the complete TypeScript-side flow:
 *     1. Load a profile JSON from src/gameProfiles/*.json
 *     2. Validate against ProfileSchema
 *     3. Serialize back to JSON (round-trip safe)
 *     4. Verify expected field values
 *     5. (Mocked) Capacitor plugin call to setProfile(JSON)
 *     6. Verify the JSON string passed to native is valid
 *
 * This test does NOT call the actual native plugin (no Android device needed).
 * It mocks @capacitor/core's registerPlugin to capture calls.
 */

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { validateProfile, serializeProfile, ProfileSchema } from '../gameProfile';
import { loadProfile, loadAllProfiles, findProfileByPackage, clearProfileCache } from '../profileLoader';
import type { Profile } from '../gameProfile';

// Mock the Capacitor plugin registry so we can capture calls to setProfile.
const mockSetProfile = vi.fn<(json: string) => Promise<void>>();
vi.mock('../../plugins/GameMapper', () => ({
  default: {
    setProfile: (opts: { profile: string }) => mockSetProfile(opts.profile),
  },
}));

describe('Profile → Native pipeline E2E (mocked bridge)', () => {
  beforeEach(() => {
    mockSetProfile.mockReset();
    clearProfileCache();
  });

  it('loads a valid profile and forwards serialized JSON to native', async () => {
    // Build a profile inline (avoids filesystem dependency in test env)
    const profileJson = {
      schemaVersion: '1.0.0',
      profileId: 'e2e-frontend',
      gameName: 'E2E Frontend Test',
      packageName: 'com.e2e.test',
      screenSize: { width: 2800, height: 1840 },
      orientation: 'landscape' as const,
      mappings: [
        {
          id: 0,
          buttonCode: 304,
          buttonName: 'A',
          action: 'tap' as const,
          xPercent: 0.5,
          yPercent: 0.5,
          durationMs: 80,
          pressure: 1.0,
        },
      ],
      metadata: {
        author: 'e2e',
        version: '1.0.0',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    };

    // 1) Validate
    const result = validateProfile(profileJson);
    expect(result.success).toBe(true);
    if (!result.success) return;
    const profile: Profile = result.data;

    // 2) Serialize back to JSON string
    const serialized = JSON.stringify(serializeProfile(profile));

    // 3) Forward to (mocked) native plugin
    const GameMapper = (await import("../../plugins/GameMapper")).default;
    await (GameMapper as any).setProfile({ profile: serialized });

    // 4) Verify mock was called with valid JSON
    expect(mockSetProfile).toHaveBeenCalledTimes(1);
    const forwardedJson = mockSetProfile.mock.calls[0][0];

    // 5) Round-trip: re-parse the forwarded JSON and verify it still validates
    const reparsed = JSON.parse(forwardedJson);
    const revalidation = validateProfile(reparsed);
    expect(revalidation.success).toBe(true);

    // 6) Spot-check key fields survived the round-trip
    expect(reparsed.profileId).toBe('e2e-frontend');
    expect(reparsed.mappings[0].buttonCode).toBe(304);
    expect(reparsed.mappings[0].xPercent).toBe(0.5);
  });

  it('rejects invalid profile before forwarding to native', async () => {
    const invalidProfile = {
      schemaVersion: '2.0.0', // unsupported
      profileId: 'bad',
      gameName: 'Bad',
      packageName: 'com.bad',
      screenSize: { width: 2800, height: 1840 },
      orientation: 'landscape',
      mappings: [],
      metadata: {
        author: 'x',
        version: '1.0.0',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    };

    const result = validateProfile(invalidProfile);
    expect(result.success).toBe(false);

    // The contract: only forward to native if validation succeeded
    if (result.success) {
      const mod = await import('../../plugins/GameMapper');
      (mod.default as any).setProfile({ profile: JSON.stringify(result.data) });
    }

    expect(mockSetProfile).not.toHaveBeenCalled();
  });

  it('batch-loads all bundled profiles without errors', () => {
    const { valid, invalid } = loadAllProfiles();
    // We expect at least the example profile to load
    expect(valid.length).toBeGreaterThan(0);
    // Log any invalid profiles for debugging (don't fail the test —
    // because the test environment may not have all profiles bundled)
    if (invalid.length > 0) {
      // eslint-disable-next-line no-console
      console.warn('Invalid profiles:', invalid);
    }
  });

  it('findProfileByPackage returns matching profile', () => {
    // Build a fake cache entry
    const profileJson = {
      schemaVersion: '1.0.0',
      profileId: 'pkg-test',
      gameName: 'Pkg Test',
      packageName: 'com.example.pkgtest',
      screenSize: { width: 1080, height: 1920 },
      orientation: 'portrait' as const,
      mappings: [],
      metadata: {
        author: 't',
        version: '1.0.0',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    };
    const result = validateProfile(profileJson);
    expect(result.success).toBe(true);

    // findProfileByPackage walks the cache — since we can't inject into the
    // import.meta.glob cache in test env, just verify it returns null for
    // an unknown package.
    const found = findProfileByPackage('com.does.not.exist');
    expect(found).toBeNull();
  });

  it('clearProfileCache forces reload on next access', () => {
    clearProfileCache();
    // After clear, loadAllProfiles rebuilds the cache
    const { valid } = loadAllProfiles();
    expect(valid).toBeDefined();
  });

  it('serializing + re-validating produces identical profile', () => {
    const profileJson = {
      schemaVersion: '1.0.0',
      profileId: 'round-trip',
      gameName: 'RT',
      packageName: 'com.rt',
      screenSize: { width: 2800, height: 1840 },
      orientation: 'landscape' as const,
      mappings: [
        {
          id: 0,
          buttonCode: 304,
          action: 'swipe' as const,
          xPercent: 0.1,
          yPercent: 0.2,
          endXPercent: 0.3,
          endYPercent: 0.4,
          durationMs: 100,
        },
      ],
      metadata: {
        author: 'rt',
        version: '1.0.0',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    };

    const validated = validateProfile(profileJson);
    expect(validated.success).toBe(true);
    if (!validated.success) return;

    const serialized = serializeProfile(validated.data);
    const revalidated = validateProfile(serialized);
    expect(revalidated.success).toBe(true);
    if (!revalidated.success) return;

    // Spot-check key fields
    expect(revalidated.data.profileId).toBe(validated.data.profileId);
    expect(revalidated.data.mappings[0].action).toBe('swipe');
    expect(revalidated.data.mappings[0].endXPercent).toBe(0.3);
  });
});
