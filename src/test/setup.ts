// FASE 5.5 — Vitest setup file (runs before every test file).
//
// Path di repo: src/test/setup.ts
//
// Global mocks + polyfills needed by the test environment:
//   - matchMedia (not in jsdom)
//   - ResizeObserver (not in jsdom)
//   - IntersectionObserver (not in jsdom)
//   - localStorage (jsdom provides it, but we add a safe wrapper)
//   - window.matchMedia stub for theme detection
//   - Capacitor bridge mock (no-op by default; tests can override)

import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

// ─────────────────────────────────────────────────────────────────────────────
// jsdom polyfills
// ─────────────────────────────────────────────────────────────────────────────

// matchMedia — needed by some React UI components.
if (!window.matchMedia) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
}

// ResizeObserver — needed by useResizeObserver hooks.
if (!('ResizeObserver' in window)) {
  class MockResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  (window as any).ResizeObserver = MockResizeObserver;
  (global as any).ResizeObserver = MockResizeObserver;
}

// IntersectionObserver — needed by virtualization libs.
if (!('IntersectionObserver' in window)) {
  class MockIntersectionObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() { return []; }
  }
  (window as any).IntersectionObserver = MockIntersectionObserver;
  (global as any).IntersectionObserver = MockIntersectionObserver;
}

// ─────────────────────────────────────────────────────────────────────────────
// Capacitor bridge mock
// ─────────────────────────────────────────────────────────────────────────────
//
// @capacitor/core's registerPlugin() returns an object whose methods are
// proxied through a native bridge. In jsdom there's no native bridge, so
// we provide a no-op mock. Tests that need to verify plugin calls should
// override these mocks with vi.mock() in their test file.

vi.mock('@capacitor/core', () => ({
  registerPlugin: (name: string) => {
    return new Proxy({}, {
      get: (_target, prop: string) => {
        // Return a no-op async function for any method call.
        return vi.fn().mockResolvedValue(undefined);
      },
    });
  },
}));

vi.mock('@capacitor/app', () => ({
  App: {
    addListener: vi.fn().mockResolvedValue({
      remove: vi.fn().mockResolvedValue(undefined),
    }),
    removeAllListeners: vi.fn().mockResolvedValue(undefined),
  },
}));

// ─────────────────────────────────────────────────────────────────────────────
// LocalStorage safety — prevent tests from polluting each other
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  localStorage.clear();
});

// ─────────────────────────────────────────────────────────────────────────────
// Suppress noisy console.error during expected-failure tests
// ─────────────────────────────────────────────────────────────────────────────
//
// ErrorBoundary intentionally logs to console.error when it catches an error.
// This is correct production behavior, but it makes test output noisy.
// Tests that EXPECT an error can wrap their expect() in a try/catch and
// suppress the console.error. Here we just ensure the global handler doesn't
// re-throw (which would crash jsdom).

if (typeof window !== 'undefined') {
  window.addEventListener('error', (e) => {
    // Don't let uncaught test errors silently pass — re-log with stack.
    // eslint-disable-next-line no-console
    console.error('[window error in test]', e.error?.stack || e.message);
  });
  window.addEventListener('unhandledrejection', (e) => {
    // eslint-disable-next-line no-console
    console.error('[unhandled rejection in test]', e.reason);
  });
}
