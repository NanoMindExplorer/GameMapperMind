/**
 * FASE 3.3 — Test suite for the canonical Zod schema.
 *
 * Path di repo: src/schemas/__tests__/gameProfile.test.ts
 *
 * Run with: `npm test` or `vitest run`
 *
 * These tests are the regression contract: every behavior described in
 * schemas/game_profile.schema.json MUST have at least one test here.
 * If you change the schema, update both the JSON Schema AND these tests.
 */

import { describe, expect, it } from 'vitest';
import { ProfileSchema, validateProfile, validateProfileOrThrow } from '../gameProfile';
import type { Profile } from '../gameProfile';

const baseProfile: Profile = {
  schemaVersion: '1.0.0',
  profileId: 'test-profile',
  gameName: 'Test Game',
  packageName: 'com.example.test',
  screenSize: { width: 2800, height: 1840 },
  orientation: 'landscape',
  deadzone: { leftStick: 0.10, rightStick: 0.10 },
  sensitivity: { leftStick: 1.0, rightStick: 1.0 },
  mappings: [
    {
      id: 0,
      buttonCode: 304,
      buttonName: 'A',
      action: 'tap',
      xPercent: 0.5,
      yPercent: 0.5,
      endXPercent: 0.0,
      endYPercent: 0.0,
      durationMs: 80,
      pressure: 1.0,
      antiBan: { jitterPx: 1.0, timingJitterMs: 4.0, pressureVar: 0.05 },
    },
  ],
  swipeTriggers: [
    { buttonCode: 314, direction: 'left', durationMs: 120, span: 0.33 },
  ],
  metadata: {
    author: 'tester',
    version: '1.0.0',
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-17T00:00:00Z',
    notes: '',
    tags: [],
  },
};

function clone<T>(o: T): T {
  return JSON.parse(JSON.stringify(o));
}

describe('ProfileSchema — happy path', () => {
  it('accepts a minimal valid profile', () => {
    const result = validateProfile(baseProfile);
    expect(result.success).toBe(true);
  });

  it('applies defaults for optional fields', () => {
    const minimal = {
      schemaVersion: '1.0.0',
      profileId: 'minimal',
      gameName: 'Min',
      packageName: 'com.example.min',
      screenSize: { width: 1080, height: 1920 },
      orientation: 'portrait',
      mappings: [],
      metadata: {
        author: 'a',
        version: '1.0.0',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    };
    const result = validateProfileOrThrow(minimal);
    expect(result.deadzone.leftStick).toBe(0.10);
    expect(result.sensitivity.rightStick).toBe(1.0);
    expect(result.swipeTriggers).toEqual([]);
    expect(result.metadata.notes).toBe('');
  });
});

describe('ProfileSchema — field-level validation', () => {
  it('rejects non-semver schemaVersion', () => {
    const p = clone(baseProfile);
    (p as any).schemaVersion = 'v1.0';
    const r = validateProfile(p);
    expect(r.success).toBe(false);
    if (!r.success) expect((r as { errors: string[] }).errors.join(' ')).toMatch(/semver/i);
  });

  it('rejects uppercase profileId', () => {
    const p = clone(baseProfile);
    (p as any).profileId = 'PUBGM';
    const r = validateProfile(p);
    expect(r.success).toBe(false);
  });

  it('rejects invalid packageName', () => {
    const p = clone(baseProfile);
    (p as any).packageName = 'not-a-package';
    expect(validateProfile(p).success).toBe(false);
  });

  it('rejects xPercent > 1.0', () => {
    const p = clone(baseProfile);
    p.mappings[0].xPercent = 1.5;
    expect(validateProfile(p).success).toBe(false);
  });

  it('rejects mapping id > 89', () => {
    const p = clone(baseProfile);
    p.mappings[0].id = 90;
    expect(validateProfile(p).success).toBe(false);
  });

  it('rejects durationMs < 16', () => {
    const p = clone(baseProfile);
    p.mappings[0].durationMs = 10;
    expect(validateProfile(p).success).toBe(false);
  });

  it('rejects unknown orientation value', () => {
    const p = clone(baseProfile);
    (p as any).orientation = 'sideways';
    expect(validateProfile(p).success).toBe(false);
  });

  it('rejects unknown action value', () => {
    const p = clone(baseProfile);
    (p.mappings[0] as any).action = 'double-tap';
    expect(validateProfile(p).success).toBe(false);
  });
});

describe('ProfileSchema — swipe invariants', () => {
  it('rejects swipe with endPercent both 0', () => {
    const p = clone(baseProfile);
    p.mappings[0].action = 'swipe';
    p.mappings[0].endXPercent = 0.0;
    p.mappings[0].endYPercent = 0.0;
    const r = validateProfile(p);
    expect(r.success).toBe(false);
    if (!r.success) expect((r as { errors: string[] }).errors.join(' ')).toMatch(/swipe/i);
  });

  it('accepts swipe with valid endPercent', () => {
    const p = clone(baseProfile);
    p.mappings[0].action = 'swipe';
    p.mappings[0].endXPercent = 0.7;
    p.mappings[0].endYPercent = 0.5;
    expect(validateProfile(p).success).toBe(true);
  });
});

describe('ProfileSchema — cross-field invariants', () => {
  it('rejects duplicate mapping ids', () => {
    const p = clone(baseProfile);
    p.mappings.push({ ...p.mappings[0], buttonCode: 305 });
    const r = validateProfile(p);
    expect(r.success).toBe(false);
    if (!r.success) expect((r as { errors: string[] }).errors.join(' ')).toMatch(/Duplicate mapping id/i);
  });

  it('rejects duplicate mapping buttonCodes', () => {
    const p = clone(baseProfile);
    p.mappings.push({ ...p.mappings[0], id: 1 });
    const r = validateProfile(p);
    expect(r.success).toBe(false);
    if (!r.success) expect((r as { errors: string[] }).errors.join(' ')).toMatch(/Duplicate buttonCode/i);
  });

  it('rejects swipe trigger buttonCode colliding with mapping', () => {
    const p = clone(baseProfile);
    p.swipeTriggers = [
      { buttonCode: 304, direction: 'left', durationMs: 120, span: 0.33 },
    ];
    const r = validateProfile(p);
    expect(r.success).toBe(false);
    if (!r.success) expect((r as { errors: string[] }).errors.join(' ')).toMatch(/collide/i);
  });

  it('rejects unsupported schemaVersion', () => {
    const p = clone(baseProfile);
    (p as any).schemaVersion = '2.0.0';
    const r = validateProfile(p);
    expect(r.success).toBe(false);
    if (!r.success) expect((r as { errors: string[] }).errors.join(' ')).toMatch(/Unsupported/i);
  });
});

describe('ProfileSchema — additional properties', () => {
  it('rejects unknown top-level field', () => {
    const p = clone(baseProfile);
    (p as any).unknownField = 'oops';
    expect(validateProfile(p).success).toBe(false);
  });

  it('rejects unknown field inside mapping', () => {
    const p = clone(baseProfile);
    (p.mappings[0] as any).extra = 'oops';
    expect(validateProfile(p).success).toBe(false);
  });
});

describe('ProfileSchema — capacity limits', () => {
  it('rejects more than 50 mappings', () => {
    const p = clone(baseProfile);
    p.mappings = [];
    for (let i = 0; i < 51; i++) {
      p.mappings.push({
        id: i,
        buttonCode: 304 + i,
        action: 'tap',
        xPercent: 0.5,
        yPercent: 0.5,
        durationMs: 80,
      });
    }
    expect(validateProfile(p).success).toBe(false);
  });

  it('rejects more than 20 swipeTriggers', () => {
    const p = clone(baseProfile);
    p.swipeTriggers = [];
    for (let i = 0; i < 21; i++) {
      p.swipeTriggers.push({
        buttonCode: 400 + i,
        direction: 'up',
        durationMs: 120,
        span: 0.33,
      });
    }
    expect(validateProfile(p).success).toBe(false);
  });
});
