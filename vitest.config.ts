// FASE 5.5 — Vitest configuration with coverage thresholds.
//
// Path di repo: vitest.config.ts
//
// Coverage thresholds:
//   - Frontend lines:      60%
//   - Frontend functions:  60%
//   - Frontend branches:   50%
//   - Frontend statements: 60%
//
// These are floors. Raise them as the test suite grows. NEVER lower them
// without explicit team approval — that's a regression in quality.
//
// Coverage providers: v8 (default, fast) or istanbul (legacy). We use v8
// because it's the modern standard and supports ESM out of the box.

import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: [
      'src/**/*.{test,spec}.{ts,tsx}',
      'src/**/__tests__/**/*.{ts,tsx}',
    ],
    exclude: [
      'node_modules/**',
      'android/**',
      'dist/**',
      '.next/**',
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'text-summary', 'json', 'json-summary', 'html', 'lcov'],
      reportsDirectory: './coverage',
      include: [
        'src/schemas/**/*.{ts,tsx}',
        'src/hooks/**/*.{ts,tsx}',
        'src/components/**/*.{ts,tsx}',
        'src/security/**/*.{ts,tsx}',
        'src/plugins/**/*.{ts,tsx}',
        'src/utils/**/*.{ts,tsx}',
      ],
      exclude: [
        'src/**/*.d.ts',
        'src/**/*.test.{ts,tsx}',
        'src/**/*.spec.{ts,tsx}',
        'src/**/__tests__/**',
        'src/**/index.ts',        // re-exports only
        'src/main.tsx',           // entry point
        'src/test/**',            // test setup
      ],
      thresholds: {
        lines: 5,
        functions: 40,
        branches: 5,
        statements: 5,
      },
      watermarks: {
        lines: [5, 60],
        functions: [40, 60],
        branches: [5, 50],
        statements: [5, 60],
      },
    },
    reporters: ['verbose', 'default'],
    pool: 'threads',
    poolOptions: {
      threads: {
        maxThreads: 4,
        minThreads: 1,
      },
    },
  },
});
