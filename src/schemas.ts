/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 *
 * Schema validation untuk semua tipe data aplikasi GameMapperMind.
 *
 * Fix untuk BUG-N06 (regression dari fix BUG-C02):
 * - Sebelumnya OverlayApp.handleMessage menerima MessageEvent tanpa validasi shape data.
 * - Jika e.data bukan GamepadProfile, setProfile akan set object salah, React crash.
 * - Fix: tambah zod schema validation untuk GamepadProfile dan tipe terkait.
 *
 * Invariant:
 * - Setiap data yang masuk dari external source (MessageEvent, profile import, dll.)
 *   wajib divalidasi dengan schema di file ini sebelum digunakan.
 * - Jika validasi gagal, data di-reject dengan error message yang jelas.
 *
 * Kompleksitas:
 * - Validasi zod: O(n) di mana n = jumlah field, untuk schema sederhana ini ~10 field.
 * - Acceptable untuk operasi yang jarang (saat profile di-set, bukan per gamepad event).
 */

import { z } from 'zod';

/**
 * Schema untuk VirtualButton.
 * Field wajib: id, label, type, x, y, width, height, mappedKey, androidEventCode, opacity.
 * Field opsional: macroId, deadzone, sensitivity, swipeDirection, swipeDuration.
 */
export const VirtualButtonSchema = z.object({
  id: z.string().min(1),
  label: z.string(),
  type: z.enum(['button', 'analog_stick', 'dpad', 'gyro_area', 'macro', 'swipe']),
  x: z.number().min(0).max(100),
  y: z.number().min(0).max(100),
  width: z.number().positive(),
  height: z.number().positive(),
  mappedKey: z.string().min(1),
  androidEventCode: z.number().int(),
  opacity: z.number().min(0).max(1),
  macroId: z.string().optional(),
  deadzone: z.number().min(0).max(1).optional(),
  sensitivity: z.number().min(0).max(10).optional(),
  swipeDirection: z.enum(['UP', 'DOWN', 'LEFT', 'RIGHT']).optional(),
  swipeDuration: z.number().positive().optional(),
});

/**
 * Schema untuk GamepadProfile.
 * Field wajib: id, name, packageName, description, buttons, gyroSensitivity, deadzone, smoothing, isCustom.
 * Field opsional: icon, globalOpacity, antiBanEnabled, screenshotMode, customScreenshotUrl.
 */
export const GamepadProfileSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  packageName: z.string().min(1),
  icon: z.string().optional(),
  description: z.string(),
  buttons: z.array(VirtualButtonSchema),
  gyroSensitivity: z.number().min(0).max(10),
  deadzone: z.number().min(0).max(0.5),
  smoothing: z.number().min(0).max(1),
  isCustom: z.boolean(),
  globalOpacity: z.number().min(0).max(1).optional(),
  antiBanEnabled: z.boolean().optional(),
  screenshotMode: z.string().optional(),
  customScreenshotUrl: z.string().url().optional().or(z.literal('')).optional(),
});

/**
 * Schema untuk MacroAction.
 */
export const MacroActionSchema = z.object({
  id: z.string().min(1),
  type: z.enum(['touch_down', 'touch_move', 'touch_up', 'delay']),
  x: z.number().optional(),
  y: z.number().optional(),
  delayMs: z.number().positive().optional(),
  pointerId: z.number().int().min(0).max(20),
});

/**
 * Schema untuk GamepadMacro.
 */
export const GamepadMacroSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  actions: z.array(MacroActionSchema),
  triggerKey: z.string().min(1),
  playbackSpeed: z.number().positive().max(5),
});

/**
 * Helper function untuk validasi GamepadProfile dengan safe parsing.
 * @param data - data yang akan divalidasi (bisa dari JSON.parse, MessageEvent, dll.)
 * @returns object dengan success flag dan data atau error.
 *
 * Kompleksitas: O(n) di mana n = jumlah field.
 */
export function validateGamepadProfile(data: unknown): {
  success: boolean;
  data?: import('./types').GamepadProfile;
  error?: string;
} {
  const result = GamepadProfileSchema.safeParse(data);
  if (result.success) {
    return { success: true, data: result.data as import('./types').GamepadProfile };
  }
  const errorMessage = result.error.issues
    .map((issue) => `${issue.path.join('.')}: ${issue.message}`)
    .join('; ');
  return { success: false, error: errorMessage };
}

/**
 * Helper function untuk validasi GamepadMacro dengan safe parsing.
 * @param data - data yang akan divalidasi.
 * @returns object dengan success flag dan data atau error.
 */
export function validateGamepadMacro(data: unknown): {
  success: boolean;
  data?: import('./types').GamepadMacro;
  error?: string;
} {
  const result = GamepadMacroSchema.safeParse(data);
  if (result.success) {
    return { success: true, data: result.data as import('./types').GamepadMacro };
  }
  const errorMessage = result.error.issues
    .map((issue) => `${issue.path.join('.')}: ${issue.message}`)
    .join('; ');
  return { success: false, error: errorMessage };
}
