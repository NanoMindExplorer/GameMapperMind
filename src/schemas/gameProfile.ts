/**
 * FASE 3.3 — Canonical TypeScript schema for GameMapper game profiles.
 *
 * Path di repo: src/schemas/gameProfile.ts
 *
 * This Zod schema is the TypeScript-side mirror of
 *   schemas/game_profile.schema.json
 * Both MUST stay in sync. The `ProfileSchema` below is the source of truth
 * for runtime validation in the React UI; the JSON Schema is the source of
 * truth for cross-language tooling (CI lint, IDE intellisense).
 *
 * Usage:
 *   import { ProfileSchema, validateProfile, validateProfileOrThrow } from '@/schemas/gameProfile';
 *   const result = validateProfile(json);
 *   if (!result.success) console.warn(result.errors);
 *   else const profile = result.data;
 */

import { z } from 'zod';

// ─────────────────────────────────────────────────────────────────────────────
// Primitive ranges (mirror JSON Schema constraints exactly)
// ─────────────────────────────────────────────────────────────────────────────

const schemaVersionSchema = z
  .string()
  .regex(/^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$/, 'Invalid semver')
  .describe('SemVer string. Current contract: 1.0.0');

const profileIdSchema = z
  .string()
  .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'Must be lowercase kebab-case')
  .max(64)
  .describe('Stable identifier, used as filename stem');

const gameNameSchema = z.string().min(1).max(128);

const packageNameSchema = z
  .string()
  .regex(/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/, 'Invalid Android package name')
  .describe('Target game package, e.g. com.tencent.ig');

const orientationSchema = z.enum(['landscape', 'portrait', 'auto']);

const percentSchema = z
  .number()
  .min(0.0)
  .max(1.0)
  .describe('Coordinate as fraction [0..1]');

const buttonCodeSchema = z
  .number()
  .int()
  .min(0)
  .max(1023)
  .describe('Linux evdev button code (BTN_SOUTH=304, BTN_EAST=305, …)');

const mappingIdSchema = z
  .number()
  .int()
  .min(0)
  .max(89)
  .describe('Pointer pool slot offset (actual slot = id + 10)');

const durationMsSchema = z.number().int().min(16).max(5000);

// ─────────────────────────────────────────────────────────────────────────────
// Anti-ban config
// ─────────────────────────────────────────────────────────────────────────────

export const AntiBanSchema = z
  .object({
    jitterPx: z.number().min(0.0).max(8.0).default(1.0),
    timingJitterMs: z.number().min(0.0).max(32.0).default(4.0),
    pressureVar: z.number().min(0.0).max(0.3).default(0.05),
  })
  .strict()
  .default({});

export type AntiBan = z.infer<typeof AntiBanSchema>;

// ─────────────────────────────────────────────────────────────────────────────
// Mapping
// ─────────────────────────────────────────────────────────────────────────────

const actionSchema = z.enum(['tap', 'swipe', 'hold']);

export const MappingSchema = z
  .object({
    id: mappingIdSchema,
    buttonCode: buttonCodeSchema,
    buttonName: z.string().max(32).optional(),
    action: actionSchema,
    xPercent: percentSchema,
    yPercent: percentSchema,
    endXPercent: percentSchema.default(0.0),
    endYPercent: percentSchema.default(0.0),
    durationMs: durationMsSchema.default(80),
    pressure: z.number().min(0.0).max(1.0).default(1.0),
    antiBan: AntiBanSchema,
  })
  .strict()
  .superRefine((m, ctx) => {
    if (m.action === 'swipe') {
      if (m.endXPercent === 0.0 && m.endYPercent === 0.0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['endXPercent'],
          message: 'swipe action requires endXPercent/endYPercent different from start',
        });
      }
    }
  });

export type Mapping = z.infer<typeof MappingSchema>;

// ─────────────────────────────────────────────────────────────────────────────
// Swipe trigger
// ─────────────────────────────────────────────────────────────────────────────

export const SwipeTriggerSchema = z
  .object({
    buttonCode: buttonCodeSchema,
    direction: z.enum(['up', 'down', 'left', 'right']),
    durationMs: z.number().int().min(16).max(2000).default(120),
    span: z.number().min(0.1).max(1.0).default(0.33),
  })
  .strict();

export type SwipeTrigger = z.infer<typeof SwipeTriggerSchema>;

// ─────────────────────────────────────────────────────────────────────────────
// Profile root
// ─────────────────────────────────────────────────────────────────────────────

export const ProfileSchema = z
  .object({
    schemaVersion: schemaVersionSchema,
    profileId: profileIdSchema,
    gameName: gameNameSchema,
    packageName: packageNameSchema,
    screenSize: z
      .object({
        width: z.number().int().min(1).max(7680),
        height: z.number().int().min(1).max(4320),
      })
      .strict(),
    orientation: orientationSchema,
    deadzone: z
      .object({
        leftStick: z.number().min(0.0).max(0.5).default(0.10),
        rightStick: z.number().min(0.0).max(0.5).default(0.10),
      })
      .strict()
      .default({ leftStick: 0.10, rightStick: 0.10 }),
    sensitivity: z
      .object({
        leftStick: z.number().min(0.1).max(5.0).default(1.0),
        rightStick: z.number().min(0.1).max(5.0).default(1.0),
      })
      .strict()
      .default({ leftStick: 1.0, rightStick: 1.0 }),
    mappings: z.array(MappingSchema).min(0).max(50),
    swipeTriggers: z.array(SwipeTriggerSchema).min(0).max(20).default([]),
    metadata: z
      .object({
        author: z.string().min(1).max(64),
        version: z.string().regex(/^\d+\.\d+\.\d+$/),
        createdAt: z.string().datetime(),
        updatedAt: z.string().datetime(),
        notes: z.string().max(512).default(''),
        tags: z.array(z.string().max(32)).max(10).default([]),
      })
      .strict(),
  })
  .strict()
  .superRefine((p, ctx) => {
    // Cross-field invariants that Zod primitives can't express.

    // 1) Unique mapping IDs (pointer pool slot collision = bug).
    const ids = p.mappings.map((m) => m.id);
    const dup = ids.filter((id, i) => ids.indexOf(id) !== i);
    if (dup.length > 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['mappings'],
        message: `Duplicate mapping id(s): ${[...new Set(dup)].join(', ')}`,
      });
    }

    // 2) Unique buttonCodes within mappings (one button → one action per profile).
    const btns = p.mappings.map((m) => m.buttonCode);
    const dupBtn = btns.filter((b, i) => btns.indexOf(b) !== i);
    if (dupBtn.length > 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['mappings'],
        message: `Duplicate buttonCode(s): ${[...new Set(dupBtn)].join(', ')}`,
      });
    }

    // 3) Swipe trigger buttonCodes must not collide with mapping buttonCodes
    //    (otherwise one physical button triggers both tap AND swipe).
    const mappingBtns = new Set(btns);
    const triggerBtns = p.swipeTriggers.map((s) => s.buttonCode);
    const colliding = triggerBtns.filter((b) => mappingBtns.has(b));
    if (colliding.length > 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['swipeTriggers'],
        message: `Swipe trigger buttonCode(s) collide with mappings: ${[...new Set(colliding)].join(', ')}`,
      });
    }

    // 4) Schema version must match what the runtime supports.
    const supported = ['1.0.0'];
    if (!supported.includes(p.schemaVersion)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['schemaVersion'],
        message: `Unsupported schemaVersion. Supported: ${supported.join(', ')}. Got: ${p.schemaVersion}`,
      });
    }
  });

export type Profile = z.infer<typeof ProfileSchema>;

// ─────────────────────────────────────────────────────────────────────────────
// Public API: validation helpers
// ─────────────────────────────────────────────────────────────────────────────

export type ProfileValidationResult =
  | { success: true; data: Profile }
  | { success: false; errors: string[] };

/**
 * Validate an unknown JSON payload as a Profile. Returns a discriminated union
 * so callers can use `result.success` for branching without try/catch.
 */
export function validateProfile(input: unknown): ProfileValidationResult {
  const parsed = ProfileSchema.safeParse(input);
  if (parsed.success) {
    return { success: true, data: parsed.data };
  }
  const errors = parsed.error.issues.map((issue) => {
    const path = issue.path.length > 0 ? issue.path.join('.') : '<root>';
    return `${path}: ${issue.message}`;
  });
  return { success: false, errors };
}

/**
 * Strict variant — throws on invalid input. Use when the caller has a recovery
 * path that requires the data to be valid (e.g., loading a bundled profile).
 */
export function validateProfileOrThrow(input: unknown): Profile {
  return ProfileSchema.parse(input);
}

/**
 * Serialize a Profile back to a plain JSON-compatible object. Use before
 * JSON.stringify() to ensure field ordering matches the canonical schema.
 */
export function serializeProfile(p: Profile): unknown {
  // Zod's .parse round-trips defaults, so re-parsing cleans up any undefined fields.
  return ProfileSchema.parse(p);
}
