import { z } from 'zod';

export const VirtualButtonSchema = z.object({
  id: z.string(),
  label: z.string(),
  type: z.enum(['button', 'analog_stick', 'dpad', 'gyro_area', 'macro', 'swipe']),
  x: z.number(),
  y: z.number(),
  width: z.number(),
  height: z.number(),
  mappedKey: z.string(),
  androidEventCode: z.number(),
  opacity: z.number(),
  macroId: z.string().optional(),
  deadzone: z.number().optional(),
  sensitivity: z.number().optional(),
  swipeDirection: z.enum(['UP', 'DOWN', 'LEFT', 'RIGHT']).optional(),
  swipeDuration: z.number().optional(),
});

export const GamepadProfileSchema = z.object({
  id: z.string(),
  name: z.string(),
  packageName: z.string(),
  icon: z.string().optional(),
  description: z.string(),
  buttons: z.array(VirtualButtonSchema),
  gyroSensitivity: z.number(),
  deadzone: z.number(),
  smoothing: z.number(),
  isCustom: z.boolean(),
  globalOpacity: z.number().optional(),
  antiBanEnabled: z.boolean().optional(),
  screenshotMode: z.string().optional(),
  customScreenshotUrl: z.string().optional(),
});
