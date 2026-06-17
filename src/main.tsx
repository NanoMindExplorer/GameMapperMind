import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import App from './App.tsx';
import OverlayApp from './OverlayApp.tsx';
import ErrorBoundary from './components/ErrorBoundary.tsx';
import './index.css';

// Global error handler — catches errors that ErrorBoundary misses
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
  document.body.innerHTML = '<div style="color:red;padding:20px">Fatal: root element not found</div>';
} else {
  createRoot(rootElement).render(
    <StrictMode>
      <ErrorBoundary>
        {isOverlay ? <OverlayApp /> : <App />}
      </ErrorBoundary>
    </StrictMode>,
  );
}
