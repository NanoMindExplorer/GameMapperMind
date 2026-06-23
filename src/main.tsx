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

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("ErrorBoundary caught an error:", error, errorInfo);
    try {
      fetch('/api/log', {
         method: 'POST',
         headers: {'Content-Type': 'application/json'},
         body: JSON.stringify({message: "REACT_RENDER_ERROR: " + error.message + "\n" + errorInfo.componentStack})
      });
    } catch(e) {}
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ backgroundColor: 'red', color: 'white', padding: '20px' }}>
          <h2>React Render Crash</h2>
          <pre>{this.state.error?.message}</pre>
          <pre>{this.state.error?.stack}</pre>
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
