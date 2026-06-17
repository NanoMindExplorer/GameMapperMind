import React from 'react';

interface ErrorBoundaryProps {
  children: React.ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  declare state: ErrorBoundaryState;

  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    console.error('App crashed:', error, errorInfo);
  }

  render(): React.ReactNode {
    if (this.state.hasError) {
      return React.createElement('div', {
        style: { padding: '20px', color: '#fff', background: '#1a1a2e', minHeight: '100vh', fontFamily: 'monospace' }
      },
        React.createElement('h2', { style: { color: '#ef4444' } }, 'App Crashed'),
        React.createElement('p', { style: { color: '#94a3b8', fontSize: '14px' } }, 'GameMapperMind encountered an error:'),
        React.createElement('pre', { style: { color: '#f87171', fontSize: '12px', overflow: 'auto', whiteSpace: 'pre-wrap' } }, this.state.error?.message || 'Unknown error'),
        React.createElement('pre', { style: { color: '#64748b', fontSize: '10px', overflow: 'auto', whiteSpace: 'pre-wrap', marginTop: '10px' } }, this.state.error?.stack || ''),
        React.createElement('button', {
          onClick: () => window.location.reload(),
          style: { marginTop: '20px', padding: '10px 20px', background: '#6366f1', color: '#fff', border: 'none', borderRadius: '8px', cursor: 'pointer' }
        }, 'Reload App')
      );
    }
    return (this as any).props.children;
  }
}

export default ErrorBoundary;
