/**
 * FASE 4.1 — App-level ErrorBoundary with crash reporting + restart button.
 *
 * Path di repo: src/components/ErrorBoundary.tsx
 *
 * Responsibilities:
 *   - Catch React render errors anywhere in the tree.
 *   - Catch unhandled promise rejections at the window level.
 *   - Catch global error events (script load failures, etc.).
 *   - Display a recovery UI with three actions:
 *       1) "Reload App"        → window.location.reload()
 *       2) "Reset Overlay"     → clears window.injectConfig + localStorage
 *       3) "Copy Error"        → copies stack trace for bug report
 *   - Persist the last 5 crash reports to localStorage for post-mortem.
 *   - NEVER crash the error UI itself — all rendering is wrapped in try/catch.
 *
 * Usage:
 *   <ErrorBoundary>
 *     <App />
 *   </ErrorBoundary>
 */

import React, { Component, ErrorInfo, ReactNode } from 'react';

// ─────────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────────

interface CrashReport {
  id: string;
  timestamp: string;
  message: string;
  stack?: string;
  componentStack?: string;
  source: 'render' | 'window' | 'unhandledrejection';
  url: string;
  userAgent: string;
}

interface ErrorBoundaryState {
  hasError: boolean;
  currentError: CrashReport | null;
  recentCrashes: CrashReport[];
}

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const STORAGE_KEY = 'gmm:crash-reports';
const MAX_REPORTS = 5;
const RELOAD_COOLDOWN_MS = 3000;

// ─────────────────────────────────────────────────────────────────────────────
// Crash persistence
// ─────────────────────────────────────────────────────────────────────────────

function loadRecentCrashes(): CrashReport[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.slice(0, MAX_REPORTS);
  } catch {
    return [];
  }
}

function persistCrash(report: CrashReport): CrashReport[] {
  try {
    const prev = loadRecentCrashes();
    const next = [report, ...prev].slice(0, MAX_REPORTS);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    return next;
  } catch {
    // localStorage might be full or disabled — swallow silently.
    return [report];
  }
}

function clearCrashHistory(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* no-op */
  }
}

function safeResetOverlayState(): void {
  try {
    // Clear window.injectConfig (set by OverlayApp in FASE 1).
    if (typeof window !== 'undefined' && 'injectConfig' in window) {
      try {
        delete (window as any).injectConfig;
      } catch {
        (window as any).injectConfig = undefined;
      }
    }
    // Clear profile cache + pipeline state keys (without touching Shizuku permission).
    const keysToKeep = new Set(['gmm:shizuku-permission', 'gmm:shizuku-bound']);
    const toRemove: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith('gmm:') && !keysToKeep.has(key)) {
        toRemove.push(key);
      }
    }
    toRemove.forEach((k) => localStorage.removeItem(k));
  } catch {
    /* no-op */
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// ErrorBoundary component
// ─────────────────────────────────────────────────────────────────────────────

interface ErrorBoundaryProps {
  children: ReactNode;
  fallback?: (error: CrashReport, actions: ErrorBoundaryActions) => ReactNode;
}

export interface ErrorBoundaryActions {
  reloadApp: () => void;
  resetOverlay: () => void;
  copyError: () => Promise<void>;
  dismiss: () => void;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  private lastReloadAt = 0;
  private windowErrorHandler: ((event: ErrorEvent) => void) | null = null;
  private unhandledRejectionHandler: ((event: PromiseRejectionEvent) => void) | null = null;

  state: ErrorBoundaryState = {
    hasError: false,
    currentError: null,
    recentCrashes: loadRecentCrashes(),
  };

  // ───── Lifecycle ─────

  componentDidMount(): void {
    // Window-level error handler — catches script load failures, etc.
    this.windowErrorHandler = (event: ErrorEvent) => {
      if (!event.error && !event.message) return;
      this.captureError({
        source: 'window',
        message: event.message || 'Unknown window error',
        stack: event.error?.stack,
        url: event.filename || window.location.href,
      });
    };

    // Unhandled promise rejections.
    this.unhandledRejectionHandler = (event: PromiseRejectionEvent) => {
      const reason = event.reason;
      const message =
        reason instanceof Error ? reason.message : String(reason ?? 'Unknown rejection');
      this.captureError({
        source: 'unhandledrejection',
        message,
        stack: reason instanceof Error ? reason.stack : undefined,
        url: window.location.href,
      });
    };

    window.addEventListener('error', this.windowErrorHandler);
    window.addEventListener('unhandledrejection', this.unhandledRejectionHandler);
  }

  componentWillUnmount(): void {
    if (this.windowErrorHandler) {
      window.removeEventListener('error', this.windowErrorHandler);
    }
    if (this.unhandledRejectionHandler) {
      window.removeEventListener('unhandledrejection', this.unhandledRejectionHandler);
    }
  }

  // React render error — called by React automatically.
  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return {
      hasError: true,
      currentError: {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        timestamp: new Date().toISOString(),
        message: error.message || 'Render error',
        stack: error.stack,
        source: 'render',
        url: window.location.href,
        userAgent: navigator.userAgent,
      },
    };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    if (this.state.currentError) {
      this.state.currentError.componentStack = info.componentStack ?? undefined;
      this.captureError(this.state.currentError);
    }
  }

  // ───── Internal ─────

  private captureError(partial: Omit<CrashReport, 'id' | 'timestamp' | 'userAgent'>): void {
    const report: CrashReport = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      timestamp: new Date().toISOString(),
      userAgent: navigator.userAgent,
      ...partial,
    };
    const recent = persistCrash(report);
    // Only flip to error UI if we're not already showing one.
    this.setState((prev) => ({
      recentCrashes: recent,
      hasError: prev.hasError || partial.source === 'render',
      currentError: prev.hasError ? prev.currentError : (partial.source === 'render' ? report : null),
    }));
    // Best-effort log to console for adb logcat / chrome://inspect.
    try {
      // eslint-disable-next-line no-console
      console.error('[ErrorBoundary]', report.source, report.message, report.stack);
    } catch {
      /* no-op */
    }
  }

  // ───── Actions exposed to fallback UI ─────

  private reloadApp = (): void => {
    const now = Date.now();
    if (now - this.lastReloadAt < RELOAD_COOLDOWN_MS) return;
    this.lastReloadAt = now;
    try {
      window.location.reload();
    } catch {
      /* no-op */
    }
  };

  private resetOverlay = (): void => {
    safeResetOverlayState();
    this.setState({ hasError: false, currentError: null });
  };

  private copyError = async (): Promise<void> => {
    if (!this.state.currentError) return;
    const text = formatCrashForClipboard(this.state.currentError);
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        // Fallback: use a hidden textarea + execCommand('copy').
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
    } catch {
      /* clipboard may be blocked — silently ignore */
    }
  };

  private dismiss = (): void => {
    this.setState({ hasError: false, currentError: null });
  };

  // ───── Render ─────

  render(): ReactNode {
    if (!this.state.hasError || !this.state.currentError) {
      return this.props.children;
    }

    const actions: ErrorBoundaryActions = {
      reloadApp: this.reloadApp,
      resetOverlay: this.resetOverlay,
      copyError: this.copyError,
      dismiss: this.dismiss,
    };

    if (this.props.fallback) {
      try {
        return this.props.fallback(this.state.currentError, actions);
      } catch {
        // User-provided fallback crashed — fall through to default.
      }
    }

    try {
      return <DefaultFallback error={this.state.currentError} actions={actions} />;
    } catch {
      // Absolute last resort — plain HTML string, no React involved.
      return (
        <div style={{ padding: 16, fontFamily: 'monospace', color: '#d32f2f' }}>
          <h2>App crashed</h2>
          <p>{this.state.currentError.message}</p>
          <button onClick={() => window.location.reload()}>Reload</button>
        </div>
      );
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Default fallback UI
// ─────────────────────────────────────────────────────────────────────────────

function DefaultFallback({
  error,
  actions,
}: {
  error: CrashReport;
  actions: ErrorBoundaryActions;
}): ReactNode {
  const [showDetails, setShowDetails] = React.useState(false);

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: '#0d1117',
        color: '#e6edf3',
        fontFamily:
          'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans SC", sans-serif',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        zIndex: 2147483647,
      }}
    >
      <div style={{ maxWidth: 560, textAlign: 'center' }}>
        <div style={{ fontSize: 56, marginBottom: 16 }} aria-hidden>
          ⚠️
        </div>
        <h1 style={{ fontSize: 24, margin: '0 0 8px' }}>GameMapperMind crashed</h1>
        <p style={{ color: '#8b949e', margin: '0 0 24px' }}>
          The overlay state has been preserved. You can reload the app, reset the overlay
          configuration, or copy the error for a bug report.
        </p>

        <div
          style={{
            background: '#161b22',
            border: '1px solid #30363d',
            borderRadius: 8,
            padding: 12,
            marginBottom: 16,
            fontSize: 13,
            textAlign: 'left',
            fontFamily: 'ui-monospace, "SF Mono", Menlo, monospace',
          }}
        >
          <div style={{ color: '#8b949e' }}>Error message</div>
          <div style={{ marginTop: 4, color: '#ff7b72' }}>{error.message}</div>
          <div style={{ marginTop: 8, color: '#8b949e' }}>
            {error.source} · {error.timestamp}
          </div>
        </div>

        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'center' }}>
          <button
            onClick={actions.reloadApp}
            style={btnStyle('#238636', '#2ea043')}
          >
            Reload App
          </button>
          <button
            onClick={actions.resetOverlay}
            style={btnStyle('#1f6feb', '#388bfd')}
          >
            Reset Overlay
          </button>
          <button
            onClick={actions.copyError}
            style={btnStyle('#6e7681', '#848d97')}
          >
            Copy Error
          </button>
          <button
            onClick={() => setShowDetails((v) => !v)}
            style={btnStyle('transparent', '#30363d')}
          >
            {showDetails ? 'Hide Details' : 'Show Details'}
          </button>
        </div>

        {showDetails && (
          <pre
            style={{
              marginTop: 16,
              background: '#010409',
              border: '1px solid #30363d',
              borderRadius: 8,
              padding: 12,
              fontSize: 11,
              textAlign: 'left',
              overflow: 'auto',
              maxHeight: 240,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {error.stack || '(no stack trace)'}
            {error.componentStack ? `\n\nComponent stack:${error.componentStack}` : ''}
          </pre>
        )}
      </div>
    </div>
  );
}

function btnStyle(bg: string, hover: string): React.CSSProperties {
  return {
    background: bg,
    color: '#fff',
    border: '1px solid transparent',
    borderRadius: 6,
    padding: '8px 16px',
    fontSize: 14,
    fontWeight: 500,
    cursor: 'pointer',
    transition: 'background 0.15s',
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function formatCrashForClipboard(report: CrashReport): string {
  const lines = [
    'GameMapperMind crash report',
    '===========================',
    `Timestamp: ${report.timestamp}`,
    `Source:    ${report.source}`,
    `URL:       ${report.url}`,
    `UA:        ${report.userAgent}`,
    '',
    `Message: ${report.message}`,
    '',
    'Stack:',
    report.stack || '(none)',
    '',
    'Component stack:',
    report.componentStack || '(none)',
  ];
  return lines.join('\n');
}

// ─────────────────────────────────────────────────────────────────────────────
// Hook: useRecentCrashes — for showing a "previous crashes" banner
// ─────────────────────────────────────────────────────────────────────────────

export function useRecentCrashes(): CrashReport[] {
  const [crashes, setCrashes] = React.useState<CrashReport[]>(() => loadRecentCrashes());
  const refresh = React.useCallback(() => {
    setCrashes(loadRecentCrashes());
  }, []);
  React.useEffect(() => {
    const handler = () => refresh();
    window.addEventListener('storage', handler);
    return () => window.removeEventListener('storage', handler);
  }, [refresh]);
  return crashes;
}

export function clearAllCrashes(): void {
  clearCrashHistory();
}
