import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import App from './App.tsx';
import OverlayApp from './OverlayApp.tsx';
import {ErrorBoundary} from './components/ErrorBoundary.tsx';
import './index.css';

// Global error handler — catches errors that ErrorBoundary misses.
// These log to console.error so they appear in adb logcat / chrome://inspect.
// ErrorBoundary itself catches React render errors + window errors +
// unhandled rejections via its own listeners, but this is a safety net
// for any error that slips through (e.g., during ErrorBoundary's own
// initialization).
window.addEventListener('error', (e) => {
  console.error('[FATAL]', e.error || e.message);
});

window.addEventListener('unhandledrejection', (e) => {
  console.error('[PROMISE REJECT]', e.reason);
});

const isOverlay = window.location.search.includes('overlay=true');

if (isOverlay) {
  document.documentElement.classList.add('is-overlay');
}

const rootElement = document.getElementById('root');
if (!rootElement) {
  // Last-resort fallback if React root is missing — display a plain HTML
  // error message instead of crashing.
  document.body.innerHTML = '<div style="color:red;padding:20px;font-family:monospace">Fatal: root element not found. Please reload the app.</div>';
} else {
  createRoot(rootElement).render(
    <StrictMode>
      <ErrorBoundary>
        {isOverlay ? <OverlayApp /> : <App />}
      </ErrorBoundary>
    </StrictMode>,
  );
}
