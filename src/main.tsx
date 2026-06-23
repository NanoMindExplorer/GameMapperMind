import React, { StrictMode, Component, ReactNode, ErrorInfo } from 'react';
import {createRoot} from 'react-dom/client';
import App from './App.tsx';
import OverlayApp from './OverlayApp.tsx';
import './index.css';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends React.Component<Props, State> {
  state: State = { hasError: false, error: null };
  declare props: Props;
  constructor(props: Props) {
    super(props);
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidMount() {
    this.sendPendingLogs();
  }

  sendPendingLogs = () => {
    try {
      const existingLogs = JSON.parse(localStorage.getItem('pending_error_logs') || '[]');
      if (existingLogs.length > 0) {
        existingLogs.forEach((log: any) => {
          fetch('/api/log', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: "REACT_RENDER_ERROR_RETRY: " + log.message + "\n" + (log.componentStack || "") })
          }).catch(() => { /* ignore */ });
        });
        localStorage.removeItem('pending_error_logs');
      }
    } catch (e) { /* ignore */ }
  };

  saveToLocalStorage = (error: Error, errorInfo: ErrorInfo) => {
    try {
      const existingLogs = JSON.parse(localStorage.getItem('pending_error_logs') || '[]');
      existingLogs.push({
        message: error.message,
        stack: error.stack,
        componentStack: errorInfo.componentStack,
        timestamp: Date.now()
      });
      localStorage.setItem('pending_error_logs', JSON.stringify(existingLogs));
    } catch (e) { /* ignore */ }
  };

  reportBug = () => {
    const error = this.state.error;
    if (!error) return;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 3000);
    try {
      fetch('/api/log', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: "USER_REPORT_BUG: " + error.message + "\n" + error.stack
        }),
        signal: controller.signal
      }).then(() => alert('Bug reported!'))
        .catch(() => alert('Failed to report bug, try again later.'));
    } catch (e) { /* ignore */ } finally {
      clearTimeout(timeoutId);
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("ErrorBoundary caught an error:", error, errorInfo);
    
    if (error.message.includes('network') || error.message.includes('fetch')) {
      this.saveToLocalStorage(error, errorInfo);
      return;
    }
    
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 3000);
    
    try {
      fetch('/api/log', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: "REACT_RENDER_ERROR: " + error.message + "\n" + errorInfo.componentStack
        }),
        signal: controller.signal
      }).catch(() => {
        this.saveToLocalStorage(error, errorInfo);
      });
    } catch (e) {
      this.saveToLocalStorage(error, errorInfo);
    } finally {
      clearTimeout(timeoutId);
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ backgroundColor: '#1e293b', color: 'white', padding: '20px', textAlign: 'center', minHeight: '100vh', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center' }}>
          <h2 style={{ fontSize: '24px', marginBottom: '16px' }}>Terjadi Kesalahan</h2>
          <p style={{ marginBottom: '24px', opacity: 0.8 }}>Aplikasi mengalami error. Tap untuk restart.</p>
          <div style={{ display: 'flex', gap: '12px' }}>
            <button onClick={() => window.location.reload()} style={{ padding: '12px 24px', backgroundColor: '#7c3aed', color: 'white', border: 'none', borderRadius: '8px', cursor: 'pointer' }}>Restart</button>
            <button onClick={() => this.reportBug()} style={{ padding: '12px 24px', backgroundColor: '#475569', color: 'white', border: 'none', borderRadius: '8px', cursor: 'pointer' }}>Laporkan Bug</button>
          </div>
          <details style={{ marginTop: '24px', textAlign: 'left', maxWidth: '600px', width: '100%' }}>
            <summary style={{ cursor: 'pointer', opacity: 0.6 }}>Mode Developer (detail error)</summary>
            <pre style={{ marginTop: '12px', padding: '12px', backgroundColor: '#0f172a', borderRadius: '4px', overflow: 'auto', fontSize: '12px' }}>{this.state.error?.stack}</pre>
          </details>
        </div>
      );
    }
    return this.props.children;
  }
}

const isOverlay = window.location.search.includes('overlay=true');

if (isOverlay) {
  document.documentElement.classList.add('is-overlay');
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      {isOverlay ? <OverlayApp /> : <App />}
    </ErrorBoundary>
  </StrictMode>
);
