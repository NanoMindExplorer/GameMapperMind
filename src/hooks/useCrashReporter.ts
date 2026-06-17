/**
 * FASE 4.1 — Global error reporter that bridges native crashes back to JS.
 *
 * Path di repo: src/hooks/useCrashReporter.ts
 *
 * Why this exists:
 *   The Kotlin side (FASE 4.2) wraps every @PluginMethod in try/catch and
 *   emits an "app:error" Capacitor event with structured payload. This hook
 *   subscribes to those events, persists them, and exposes them to React for:
 *     - Toast notifications
 *     - The ErrorBoundary's "recent crashes" list
 *     - Bug-report payload assembly
 *
 *   This hook also bridges window-level errors INTO the same persistence
 *   pipeline (so native + JS crashes share one crash log).
 *
 * Usage:
 *   function App() {
 *     const crashes = useCrashReporter();
 *     ...
 *   }
 *
 *   // Access latest:
 *   crashes.latest  // CrashReport | null
 *   crashes.all     // CrashReport[]
 *   crashes.clear() // void
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { App } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';

// ─────────────────────────────────────────────────────────────────────────────
// Types (must match Kotlin-side NativeCrashGuard.kt payload shape)
// ─────────────────────────────────────────────────────────────────────────────

export interface NativeCrashReport {
  id: string;
  timestamp: string;
  source: 'native' | 'plugin' | 'pipeline' | 'shizuku';
  plugin?: string;
  method?: string;
  message: string;
  stack?: string;
  recoverable: boolean;
}

// ─────────────────────────────────────────────────────────────────────────────
// Persistence
// ─────────────────────────────────────────────────────────────────────────────

const NATIVE_CRASH_KEY = 'gmm:native-crashes';
const MAX_NATIVE_REPORTS = 20;

function loadNativeCrashes(): NativeCrashReport[] {
  try {
    const raw = localStorage.getItem(NATIVE_CRASH_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.slice(0, MAX_NATIVE_REPORTS) : [];
  } catch {
    return [];
  }
}

function persistNativeCrash(report: NativeCrashReport): NativeCrashReport[] {
  try {
    const next = [report, ...loadNativeCrashes()].slice(0, MAX_NATIVE_REPORTS);
    localStorage.setItem(NATIVE_CRASH_KEY, JSON.stringify(next));
    return next;
  } catch {
    return [report];
  }
}

function clearNativeCrashes(): void {
  try {
    localStorage.removeItem(NATIVE_CRASH_KEY);
  } catch {
    /* no-op */
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Public hook
// ─────────────────────────────────────────────────────────────────────────────

export interface CrashReporterApi {
  latest: NativeCrashReport | null;
  all: NativeCrashReport[];
  clear: () => void;
  /** Manually push a JS-side error into the same crash pipeline. */
  reportJsError(error: Error, context?: { source?: string; method?: string }): void;
}

export function useCrashReporter(): CrashReporterApi {
  const [all, setAll] = useState<NativeCrashReport[]>(() => loadNativeCrashes());
  const listenerRef = useRef<PluginListenerHandle | null>(null);

  // Subscribe to native "app:error" events.
  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const handle = await App.addListener('app:error', (payload: unknown) => {
          if (cancelled) return;
          const report = sanitizeNativePayload(payload);
          if (!report) return;
          setAll((prev) => {
            const next = [report, ...prev].slice(0, MAX_NATIVE_REPORTS);
            try {
              localStorage.setItem(NATIVE_CRASH_KEY, JSON.stringify(next));
            } catch {
              /* no-op */
            }
            return next;
          });
          // Best-effort console log for adb logcat.
          try {
            // eslint-disable-next-line no-console
            console.error('[NativeCrash]', report.source, report.message, report.stack);
          } catch {
            /* no-op */
          }
        });
        if (!cancelled) listenerRef.current = handle;
      } catch {
        // App plugin might not be available in web preview — silently ignore.
      }
    })();

    return () => {
      cancelled = true;
      listenerRef.current?.remove().catch(() => {
        /* no-op */
      });
      listenerRef.current = null;
    };
  }, []);

  const clear = useCallback(() => {
    clearNativeCrashes();
    setAll([]);
  }, []);

  const reportJsError = useCallback(
    (error: Error, context?: { source?: string; method?: string }) => {
      const report: NativeCrashReport = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        timestamp: new Date().toISOString(),
        source: 'plugin',
        plugin: context?.source,
        method: context?.method,
        message: error.message || 'Unknown JS error',
        stack: error.stack,
        recoverable: true,
      };
      setAll((prev) => {
        const next = persistNativeCrash(report);
        return next;
      });
    },
    []
  );

  return useMemo(
    () => ({
      latest: all.length > 0 ? all[0] : null,
      all,
      clear,
      reportJsError,
    }),
    [all, clear, reportJsError]
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Sanitization
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Validate the payload coming from native side. Never trust shape — even if
 * our own Kotlin code produced it, a third-party Shizuku injection could
 * spoof events. Strip unknown fields, enforce types, cap string lengths.
 */
function sanitizeNativePayload(payload: unknown): NativeCrashReport | null {
  if (typeof payload !== 'object' || payload === null) return null;
  const p = payload as Record<string, unknown>;

  const source = String(p.source ?? 'native');
  if (!['native', 'plugin', 'pipeline', 'shizuku'].includes(source)) return null;

  const message = String(p.message ?? '').slice(0, 2048);
  if (!message) return null;

  const id =
    typeof p.id === 'string' && p.id.length > 0
      ? p.id.slice(0, 128)
      : `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

  return {
    id,
    timestamp:
      typeof p.timestamp === 'string'
        ? p.timestamp.slice(0, 64)
        : new Date().toISOString(),
    source: source as NativeCrashReport['source'],
    plugin: typeof p.plugin === 'string' ? p.plugin.slice(0, 64) : undefined,
    method: typeof p.method === 'string' ? p.method.slice(0, 128) : undefined,
    message,
    stack: typeof p.stack === 'string' ? p.stack.slice(0, 8192) : undefined,
    recoverable: Boolean(p.recoverable ?? true),
  };
}
