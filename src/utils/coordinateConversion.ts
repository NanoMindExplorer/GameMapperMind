/**
 * FASE 5.1 — Pure coordinate conversion utilities.
 *
 * Path di repo: src/utils/coordinateConversion.ts
 *
 * These functions are extracted from OverlayWysiwyg.tsx so they can be
 * unit-tested in isolation. The OverlayWysiwyg component imports these
 * instead of defining them inline, keeping the React component focused
 * on rendering while the math lives here.
 *
 * Best logical algorithm (5 steps):
 *   1. Read availWidth/availHeight (falls back to width/height on browsers
 *      that don't support the avail* properties).
 *   2. Detect orientation via standard Orientation API; fall back to
 *      dimension comparison if the API is unavailable.
 *   3. Probe CSS env(safe-area-inset-*) by mounting a hidden element and
 *      reading its computed style. env() values are only resolvable in
 *      a layout context, so DOM insertion is required.
 *   4. Normalize raw dimensions to landscape orientation so the rect
 *      always represents the larger axis as width.
 *   5. Shrink the rect by safe-area insets and offset the origin so
 *      callers receive absolute screen coordinates.
 */

export interface ScreenRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * Get the effective screen rectangle accounting for:
 * - Available screen size (excludes system UI like status/nav bars)
 * - Orientation (landscape vs portrait) via window.screen.orientation.type
 * - Safe area insets (notch/cutout compensation) via CSS env() probe
 *
 * @returns ScreenRect with absolute pixel dimensions and origin offset
 */
export function getEffectiveScreenRect(): ScreenRect {
  // Step 1: Available dimensions (excludes system UI).
  const availW = window.screen.availWidth || window.screen.width;
  const availH = window.screen.availHeight || window.screen.height;

  // Step 2: Orientation detection with fallback.
  const orientationType =
    (typeof window.screen.orientation !== 'undefined' && window.screen.orientation && window.screen.orientation.type) || '';
  const isLandscape = orientationType.indexOf('landscape') !== -1 || availW > availH;

  // Step 3: Probe CSS env(safe-area-inset-*) values.
  let safeLeft = 0;
  let safeTop = 0;
  let safeRight = 0;
  let safeBottom = 0;
  try {
    const probe = document.createElement('div');
    probe.style.position = 'fixed';
    probe.style.left = 'env(safe-area-inset-left)';
    probe.style.top = 'env(safe-area-inset-top)';
    probe.style.right = 'env(safe-area-inset-right)';
    probe.style.bottom = 'env(safe-area-inset-bottom)';
    probe.style.visibility = 'hidden';
    probe.style.pointerEvents = 'none';
    probe.style.width = '0';
    probe.style.height = '0';
    document.body.appendChild(probe);
    const cs = window.getComputedStyle(probe);
    safeLeft = parseFloat(cs.left) || 0;
    safeTop = parseFloat(cs.top) || 0;
    safeRight = parseFloat(cs.right) || 0;
    safeBottom = parseFloat(cs.bottom) || 0;
    document.body.removeChild(probe);
  } catch (e) {
    // env() unsupported (older browsers, headless test env) — fall back to zero insets.
    safeLeft = 0;
    safeTop = 0;
    safeRight = 0;
    safeBottom = 0;
  }

  // Step 4: Normalize to landscape dimensions.
  const rawW = isLandscape ? Math.max(availW, availH) : Math.min(availW, availH);
  const rawH = isLandscape ? Math.min(availW, availH) : Math.max(availW, availH);

  // Step 5: Apply safe-area insets — shrink drawable area and offset origin.
  const width = Math.max(0, rawW - safeLeft - safeRight);
  const height = Math.max(0, rawH - safeTop - safeBottom);

  return { left: safeLeft, top: safeTop, width, height };
}

/**
 * Convert percentage coordinates (0-100) to absolute pixel coordinates.
 * Uses getEffectiveScreenRect() for accurate screen dimensions, including
 * safe-area origin offset so the returned coordinates are absolute
 * screen pixels (suitable for InputManager.injectInputEvent).
 *
 * @param xPct X percentage (0-100) — clamped to [0, 100]
 * @param yPct Y percentage (0-100) — clamped to [0, 100]
 * @returns Absolute pixel coordinates { x, y } in screen space
 */
export function percentToAbsolutePixels(xPct: number, yPct: number): { x: number; y: number } {
  // Step 1: Clamp inputs to 0-100 range (strict validation per contract).
  const clampedX = Math.max(0, Math.min(100, xPct));
  const clampedY = Math.max(0, Math.min(100, yPct));

  // Step 2: Get effective screen rect (includes safe-area origin offset).
  const rect = getEffectiveScreenRect();

  // Step 3: Convert percentage to local pixel offset within drawable area,
  // then add rect.left/rect.top to translate to absolute screen coordinates.
  return {
    x: Math.round(rect.left + (clampedX / 100) * rect.width),
    y: Math.round(rect.top + (clampedY / 100) * rect.height),
  };
}

/**
 * Convert absolute pixel coordinates back to percentages (0-100).
 * Inverse of percentToAbsolutePixels. Subtracts rect.left/rect.top
 * before dividing to undo the origin offset.
 *
 * @param x Absolute X pixel in screen space
 * @param y Absolute Y pixel in screen space
 * @returns Percentage coordinates { x, y } (0-100), clamped to valid range
 */
export function pixelsToPercent(x: number, y: number): { x: number; y: number } {
  const rect = getEffectiveScreenRect();
  // Subtract rect origin to get local-to-drawable coordinates, then
  // convert to percentage of drawable dimensions.
  const localX = x - rect.left;
  const localY = y - rect.top;
  return {
    x: rect.width > 0 ? Math.max(0, Math.min(100, (localX / rect.width) * 100)) : 0,
    y: rect.height > 0 ? Math.max(0, Math.min(100, (localY / rect.height) * 100)) : 0,
  };
}
