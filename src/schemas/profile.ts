import { z } from 'zod';

// INTERACTION-EXPANSION: Zod schemas for flexible trigger + interaction types
export const TriggerSchema = z.object({
  type: z.enum(['button', 'chord', 'axis']),
  inputs: z.array(z.string()),
  axisThreshold: z.number().min(0).max(1).optional(),
});

export const GesturePointSchema = z.object({
  x: z.number(),
  y: z.number(),
  delayMs: z.number(),
});

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
  radius: z.number().optional(),
  swipeDirection: z.enum(['UP', 'DOWN', 'LEFT', 'RIGHT']).optional(),
  swipeDuration: z.number().optional(),
  inputSource: z.enum(['TOUCHSCREEN', 'MOUSE', 'STYLUS', 'GAMEPAD']).optional(),
  toolType: z.enum(['FINGER', 'STYLUS']).optional(),
  tapDuration: z.number().optional(),
  player: z.union([z.literal(1), z.literal(2), z.literal(3), z.literal(4)]).optional(),
  sensitivityCurve: z.enum(['linear', 'exponential', 'parabolic', 'concave', 'custom']).optional(),
  curvePoints: z.array(z.array(z.number())).optional(),

  // INTERACTION-EXPANSION fields
  trigger: TriggerSchema.optional(),
  interactionType: z.enum(['tap', 'hold', 'swipe', 'turbo', 'toggle', 'charge', 'gesture', 'macro']).optional(),
  repeatIntervalMs: z.number().min(10).max(1000).optional(),
  chargeThresholdMs: z.number().min(50).max(5000).optional(),
  gesturePoints: z.array(GesturePointSchema).optional(),
  stickMode: z.enum(['joystick', 'drag']).optional(),
  swipeEndX: z.number().optional(),
  swipeEndY: z.number().optional(),
  swipeReturn: z.boolean().optional(),
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
  orientation: z.enum(['landscape', 'portrait', 'auto']).optional(),
  portraitButtons: z.array(VirtualButtonSchema).optional(),
  hapticIntensity: z.number().optional(),
});
