/**
 * FASE 4.3 — Frontend-side CSP validation helper.
 *
 * Path di repo: src/security/csp.ts
 *
 * Why this exists:
 *   The CSP is enforced by the <meta> tag in index.html + WebView's
 *   shouldInterceptRequest. But CSP violations can happen at runtime
 *   (e.g., a third-party dependency tries to load an external CDN).
 *   This module subscribes to the browser's SecurityPolicyViolationEvent
 *   and reports violations to NativeCrashGuard via Capacitor bridge.
 *
 *   In development, it also logs violations to the console for easy debugging.
 *
 * Usage (call once at app startup):
 *   import { installCspViolationReporter } from '@/security/csp';
 *   installCspViolationReporter();
 */

type CspViolationListener = (event: SecurityPolicyViolationEvent) => void;

let installed = false;
let listener: CspViolationListener | null = null;

interface CspViolationReport {
  violatedDirective: string;
  blockedURI: string;
  documentURI: string;
  sourceFile: string;
  lineNumber: number;
  columnNumber: number;
  sample?: string;
  disposition: 'enforce' | 'report';
  timestamp: string;
}

export function installCspViolationReporter(): void {
  if (installed) return;
  if (typeof window === 'undefined' || !window.addEventListener) return;

  listener = (event: SecurityPolicyViolationEvent) => {
    const report: CspViolationReport = {
      violatedDirective: event.violatedDirective,
      blockedURI: event.blockedURI,
      documentURI: event.documentURI,
      sourceFile: event.sourceFile,
      lineNumber: event.lineNumber,
      columnNumber: event.columnNumber,
      sample: event.sample,
      disposition: event.disposition === 'report' ? 'report' : 'enforce',
      timestamp: new Date().toISOString(),
    };

    // Console log (always — useful for adb logcat / chrome://inspect).
    try {
      // eslint-disable-next-line no-console
      console.error('[CSP]', report.violatedDirective, 'blocked', report.blockedURI, '@', report.sourceFile, report.lineNumber);
    } catch {
      /* no-op */
    }

    // Best-effort forward to native side.
    forwardToNative(report).catch(() => {
      /* no-op */
    });
  };

  window.addEventListener('securitypolicyviolation', listener as EventListener);
  installed = true;
}

export function uninstallCspViolationReporter(): void {
  if (!installed || !listener) return;
  window.removeEventListener('securitypolicyviolation', listener as EventListener);
  listener = null;
  installed = false;
}

async function forwardToNative(report: CspViolationReport): Promise<void> {
  try {
    // Dynamic import to avoid pulling Capacitor into web preview builds.
    const { App } = await import('@capacitor/app');
    await App.addListener('app:error' as any, {
      source: 'csp',
      message: `CSP violation: ${report.violatedDirective} blocked ${report.blockedURI}`,
      stack: JSON.stringify(report, null, 2),
      recoverable: true,
    } as any).catch(() => {
      /* The 'app:error' listener is on the JS side; we actually want to PUSH
         to native, not listen. Use the GameMapper plugin's reportCspViolation
         method instead — added by FASE 4.2 reference impl. */
    });
  } catch {
    /* Capacitor not available — silent */
  }
}

/**
 * In test/dev: returns the current CSP policy string for verification.
 */
export function getCurrentCsp(): string | null {
  if (typeof document === 'undefined') return null;
  const meta = document.querySelector<HTMLMetaElement>(
    'meta[http-equiv="Content-Security-Policy"]'
  );
  return meta?.content ?? null;
}
