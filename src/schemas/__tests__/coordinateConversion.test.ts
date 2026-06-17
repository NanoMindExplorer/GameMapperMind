/**
 * FASE 5.1 — Unit tests for percentToAbsolutePixels coordinate conversion.
 *
 * Path di repo: src/schemas/__tests__/coordinateConversion.test.ts
 *
 * Run: `npm test -- coordinateConversion`
 *
 * Coverage:
 *   - Various screen resolutions (FHD, QHD, 4K, 8K)
 *   - Landscape vs portrait orientation
 *   - Safe-area inset compensation
 *   - Clamping to [0, 100] for input percentages
 *   - Origin offset (rect.left + rect.top) applied correctly
 *   - Edge cases: 0%, 50%, 100%, negative, >100
 *
 * Strategy:
 *   Mock window.screen with various sizes + orientation.
 *   Mock CSS env(safe-area-inset-*) via getComputedStyle stub.
 *   Verify percentToAbsolutePixels returns correct absolute pixel coords.
 *
 * Note: percentToAbsolutePixels lives inside OverlayWysiwyg.tsx (not exported).
 * To test it without refactoring, we extract the algorithm into a standalone
 * helper module: src/utils/coordinateConversion.ts. This is a pure function
 * with no React dependencies, so it's trivially testable.
 */

import { describe, expect, it, beforeEach, vi } from 'vitest';
import {
  getEffectiveScreenRect,
  percentToAbsolutePixels,
  pixelsToPercent,
} from '../../utils/coordinateConversion';

// ─────────────────────────────────────────────────────────────────────────────
// Test fixtures — various screen sizes
// ─────────────────────────────────────────────────────────────────────────────

interface ScreenMock {
  availWidth: number;
  availHeight: number;
  orientation: { type: string };
}

function mockScreen(s: ScreenMock) {
  Object.defineProperty(window, 'screen', {
    value: {
      availWidth: s.availWidth,
      availHeight: s.availHeight,
      width: s.availWidth,
      height: s.availHeight,
      orientation: { type: s.orientation.type },
    },
    configurable: true,
    writable: true,
  });
}

function mockSafeArea(left: number, top: number, right: number, bottom: number) {
  // getComputedStyle returns a CSSStyleDeclaration. We stub it to return
  // the safe-area inset values for left/top/right/bottom properties.
  const original = window.getComputedStyle;
  window.getComputedStyle = vi.fn(((_elt: Element): CSSStyleDeclaration => {
    return {
      left: left ? `${left}px` : '0px',
      top: top ? `${top}px` : '0px',
      right: right ? `${right}px` : '0px',
      bottom: bottom ? `${bottom}px` : '0px',
    } as unknown as CSSStyleDeclaration;
  }) as typeof window.getComputedStyle);
  return () => { window.getComputedStyle = original; };
}

beforeEach(() => {
  // Reset to default mocks before each test.
  mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
  mockSafeArea(0, 0, 0, 0);
});

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

describe('getEffectiveScreenRect', () => {
  it('returns landscape-normalized dimensions for landscape orientation', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    const rect = getEffectiveScreenRect();
    expect(rect.width).toBe(1920);
    expect(rect.height).toBe(1080);
    expect(rect.left).toBe(0);
    expect(rect.top).toBe(0);
  });

  it('returns landscape-normalized dimensions for portrait (swaps)', () => {
    mockScreen({ availWidth: 1080, availHeight: 1920, orientation: { type: 'portrait-primary' } });
    const rect = getEffectiveScreenRect();
    // In portrait, larger dimension becomes width (landscape normalization)
    expect(rect.width).toBe(1920);
    expect(rect.height).toBe(1080);
  });

  it('applies safe-area insets to dimensions and origin', () => {
    mockScreen({ availWidth: 2800, availHeight: 1840, orientation: { type: 'landscape-primary' } });
    mockSafeArea(40, 30, 40, 30);
    const rect = getEffectiveScreenRect();
    expect(rect.left).toBe(40);
    expect(rect.top).toBe(30);
    expect(rect.width).toBe(2800 - 40 - 40); // 2720
    expect(rect.height).toBe(1840 - 30 - 30); // 1780
  });

  it('handles zero safe-area insets (no notch)', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const rect = getEffectiveScreenRect();
    expect(rect.left).toBe(0);
    expect(rect.top).toBe(0);
    expect(rect.width).toBe(1920);
    expect(rect.height).toBe(1080);
  });

  it('uses dimension comparison fallback when orientation.type is empty', () => {
    mockScreen({ availWidth: 2560, availHeight: 1440, orientation: { type: '' } });
    const rect = getEffectiveScreenRect();
    expect(rect.width).toBe(2560);
    expect(rect.height).toBe(1440);
  });
});

describe('percentToAbsolutePixels', () => {
  it('returns center pixel for 50%, 50% on 1920x1080', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(50, 50);
    expect(x).toBe(960);
    expect(y).toBe(540);
  });

  it('returns top-left pixel for 0%, 0%', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(0, 0);
    expect(x).toBe(0);
    expect(y).toBe(0);
  });

  it('returns bottom-right pixel for 100%, 100%', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(100, 100);
    expect(x).toBe(1920);
    expect(y).toBe(1080);
  });

  it('handles QHD resolution (2560x1440)', () => {
    mockScreen({ availWidth: 2560, availHeight: 1440, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(25, 75);
    expect(x).toBe(640);   // 0.25 * 2560
    expect(y).toBe(1080);  // 0.75 * 1440
  });

  it('handles 4K resolution (3840x2160)', () => {
    mockScreen({ availWidth: 3840, availHeight: 2160, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(50, 50);
    expect(x).toBe(1920);
    expect(y).toBe(1080);
  });

  it('handles 8K resolution (7680x4320)', () => {
    mockScreen({ availWidth: 7680, availHeight: 4320, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(10, 90);
    expect(x).toBe(768);   // 0.10 * 7680
    expect(y).toBe(3888);  // 0.90 * 4320
  });

  it('handles Huawei MatePad Pro 12.2 (2800x1840)', () => {
    mockScreen({ availWidth: 2800, availHeight: 1840, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(50, 50);
    expect(x).toBe(1400);
    expect(y).toBe(920);
  });

  it('applies safe-area origin offset (notch compensation)', () => {
    mockScreen({ availWidth: 2800, availHeight: 1840, orientation: { type: 'landscape-primary' } });
    mockSafeArea(40, 30, 40, 30);
    // Effective drawable area: 2720 x 1780, origin at (40, 30)
    const { x, y } = percentToAbsolutePixels(50, 50);
    // x = 40 (left) + 0.5 * 2720 = 40 + 1360 = 1400
    // y = 30 (top) + 0.5 * 1780 = 30 + 890 = 920
    expect(x).toBe(1400);
    expect(y).toBe(920);
  });

  it('clamps negative percentages to 0', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(-10, -20);
    expect(x).toBe(0);
    expect(y).toBe(0);
  });

  it('clamps percentages > 100 to 100', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(150, 200);
    expect(x).toBe(1920);
    expect(y).toBe(1080);
  });

  it('clamps both negative and >100 simultaneously', () => {
    mockScreen({ availWidth: 1000, availHeight: 500, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(-50, 250);
    expect(x).toBe(0);
    expect(y).toBe(500);
  });

  it('returns integer pixel coordinates (rounded)', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    // 33.33% of 1920 = 639.936 → should round to 640
    const { x } = percentToAbsolutePixels(33.33, 0);
    expect(Number.isInteger(x)).toBe(true);
    expect(x).toBe(640);
  });

  it('handles portrait orientation with landscape normalization', () => {
    // Portrait 1080x1920 → normalized to landscape 1920x1080
    mockScreen({ availWidth: 1080, availHeight: 1920, orientation: { type: 'portrait-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(50, 50);
    expect(x).toBe(960);   // 0.5 * 1920
    expect(y).toBe(540);   // 0.5 * 1080
  });

  it('handles very small screen (320x240)', () => {
    mockScreen({ availWidth: 320, availHeight: 240, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = percentToAbsolutePixels(50, 50);
    expect(x).toBe(160);
    expect(y).toBe(120);
  });
});

describe('pixelsToPercent (inverse function)', () => {
  it('converts center pixel back to 50%, 50%', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = pixelsToPercent(960, 540);
    expect(x).toBeCloseTo(50, 1);
    expect(y).toBeCloseTo(50, 1);
  });

  it('converts top-left pixel back to 0%, 0%', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = pixelsToPercent(0, 0);
    expect(x).toBe(0);
    expect(y).toBe(0);
  });

  it('converts bottom-right pixel back to 100%, 100%', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const { x, y } = pixelsToPercent(1920, 1080);
    expect(x).toBeCloseTo(100, 1);
    expect(y).toBeCloseTo(100, 1);
  });

  it('undoes safe-area origin offset', () => {
    mockScreen({ availWidth: 2800, availHeight: 1840, orientation: { type: 'landscape-primary' } });
    mockSafeArea(40, 30, 40, 30);
    // Forward: 50%,50% → (1400, 920)
    // Inverse: (1400, 920) → 50%,50%
    const { x, y } = pixelsToPercent(1400, 920);
    expect(x).toBeCloseTo(50, 1);
    expect(y).toBeCloseTo(50, 1);
  });

  it('clamps result to [0, 100]', () => {
    mockScreen({ availWidth: 1920, availHeight: 1080, orientation: { type: 'landscape-primary' } });
    mockSafeArea(0, 0, 0, 0);
    const neg = pixelsToPercent(-100, -100);
    expect(neg.x).toBe(0);
    expect(neg.y).toBe(0);
    const over = pixelsToPercent(99999, 99999);
    expect(over.x).toBe(100);
    expect(over.y).toBe(100);
  });
});

describe('Round-trip consistency', () => {
  it('percentToAbsolutePixels ∘ pixelsToPercent = identity', () => {
    mockScreen({ availWidth: 2800, availHeight: 1840, orientation: { type: 'landscape-primary' } });
    mockSafeArea(40, 30, 40, 30);
    // Forward then inverse should return the original percentages
    const testCases = [
      { x: 0, y: 0 },
      { x: 25, y: 75 },
      { x: 50, y: 50 },
      { x: 75, y: 25 },
      { x: 100, y: 100 },
    ];
    for (const tc of testCases) {
      const px = percentToAbsolutePixels(tc.x, tc.y);
      const pct = pixelsToPercent(px.x, px.y);
      expect(pct.x).toBeCloseTo(tc.x, 1);
      expect(pct.y).toBeCloseTo(tc.y, 1);
    }
  });
});
