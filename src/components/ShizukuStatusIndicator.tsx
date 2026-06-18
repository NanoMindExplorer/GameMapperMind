/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 *
 * GMM-AEC-002 §10.4: Realtime Shizuku status indicator
 *
 * Menampilkan status Shizuku secara realtime:
 *   ● Running (green)       — binder alive + permission granted + service connected
 *   ● Reconnecting (amber)  — binder alive but service disconnected, attempting rebind
 *   ● Disconnected (red)    — binder dead, Shizuku process not running
 */

import React, { useEffect, useState } from 'react';
import { Wifi, WifiOff, RefreshCw } from 'lucide-react';
import GameMapper from '../plugins/GameMapper';

type ShizukuWatcherState = 'IDLE' | 'RUNNING' | 'RECONNECTING' | 'PERMISSION_LOST' | 'BINDER_DEAD';

interface ShizukuStatusIndicatorProps {
  /** Compact mode — show only colored dot, no text */
  compact?: boolean;
  /** Show last check time */
  showLastCheck?: boolean;
  /** Callback when state changes */
  onStateChange?: (state: ShizukuWatcherState, statusString: string) => void;
}

const STATE_COLORS: Record<ShizukuWatcherState, string> = {
  IDLE: '#9CA3AF',          // gray
  RUNNING: '#10B981',        // green
  RECONNECTING: '#F59E0B',   // amber
  PERMISSION_LOST: '#F59E0B', // amber
  BINDER_DEAD: '#EF4444',    // red
};

const STATE_LABELS: Record<ShizukuWatcherState, string> = {
  IDLE: 'Idle',
  RUNNING: 'Running',
  RECONNECTING: 'Reconnecting',
  PERMISSION_LOST: 'Permission Lost',
  BINDER_DEAD: 'Disconnected',
};

const STATE_ICONS: Record<ShizukuWatcherState, React.ElementType> = {
  IDLE: WifiOff,
  RUNNING: Wifi,
  RECONNECTING: RefreshCw,
  PERMISSION_LOST: RefreshCw,
  BINDER_DEAD: WifiOff,
};

export default function ShizukuStatusIndicator({
  compact = false,
  showLastCheck = false,
  onStateChange,
}: ShizukuStatusIndicatorProps) {
  const [state, setState] = useState<ShizukuWatcherState>('IDLE');
  const [statusString, setStatusString] = useState('● Idle');
  const [statusColor, setStatusColor] = useState('#9CA3AF');
  const [lastCheckTime, setLastCheckTime] = useState<number>(0);
  const [isPolling, setIsPolling] = useState(false);

  // Listen for realtime state changes from native plugin
  useEffect(() => {
    let listener: any;

    const setupListener = async () => {
      try {
        listener = await GameMapper.addListener(
          'onShizukuWatcherStateChanged',
          (data: any) => {
            const newState = data.state as ShizukuWatcherState;
            setState(newState);
            setStatusString(data.statusString || '● Unknown');
            setStatusColor(data.statusColor || '#9CA3AF');
            setLastCheckTime(data.timestamp || Date.now());
            onStateChange?.(newState, data.statusString || '');
          }
        );
      } catch (e) {
        console.warn('[ShizukuStatusIndicator] Failed to add listener:', e);
      }
    };

    setupListener();

    return () => {
      if (listener?.remove) {
        listener.remove();
      }
    };
  }, [onStateChange]);

  // Poll status every 5 seconds (fallback jika listener tidak trigger)
  useEffect(() => {
    const pollStatus = async () => {
      setIsPolling(true);
      try {
        const result = await GameMapper.getShizukuWatcherStatus();
        if (result.available) {
          setState(result.state as ShizukuWatcherState);
          setStatusString(result.statusString || '● Unknown');
          setStatusColor(result.statusColor || '#9CA3AF');
          setLastCheckTime(result.lastCheckTime || Date.now());
        }
      } catch (e) {
        console.warn('[ShizukuStatusIndicator] Poll failed:', e);
      } finally {
        setIsPolling(false);
      }
    };

    // Initial poll
    pollStatus();

    // Poll every 5 seconds
    const interval = setInterval(pollStatus, 5000);

    return () => clearInterval(interval);
  }, []);

  const Icon = STATE_ICONS[state];
  const color = STATE_COLORS[state];

  // Format last check time
  const formatTime = (ts: number) => {
    if (!ts) return 'Never';
    const diff = Date.now() - ts;
    if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    return new Date(ts).toLocaleTimeString();
  };

  if (compact) {
    return (
      <div
        title={statusString}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: '4px',
          padding: '4px 8px',
          borderRadius: '99px',
          background: `${color}20`,
          border: `1px solid ${color}40`,
        }}
      >
        <div
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            background: color,
            boxShadow: `0 0 8px ${color}`,
          }}
        />
        <span style={{ fontSize: 10, color, fontWeight: 500 }}>
          {STATE_LABELS[state]}
        </span>
      </div>
    );
  }

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '8px 12px',
        borderRadius: 8,
        background: `${color}10`,
        border: `1px solid ${color}30`,
        fontFamily: 'monospace',
      }}
    >
      <Icon
        size={16}
        color={color}
        style={{
          animation: state === 'RECONNECTING' || isPolling ? 'spin 1s linear infinite' : 'none',
        }}
      />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontSize: 12, color, fontWeight: 600 }}>
          {statusString}
        </span>
        {showLastCheck && (
          <span style={{ fontSize: 10, color: '#6B7280' }}>
            Last check: {formatTime(lastCheckTime)}
          </span>
        )}
      </div>
      <style>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
}
