import { useEffect, useRef, useCallback } from 'react';
import { Capacitor } from '@capacitor/core';
import GameMapper from '../plugins/GameMapper';
import type { MacroAction, MacroCaptureEvent } from '../types';

interface UseMacroCaptureOptions {
  onCapture: (event: MacroCaptureEvent) => void;
}

export function useMacroCapture({ onCapture }: UseMacroCaptureOptions) {
  const listenerRef = useRef<any>(null);
  const onCaptureRef = useRef(onCapture);
  const activeRef = useRef(false);

  useEffect(() => { onCaptureRef.current = onCapture; }, [onCapture]);

  useEffect(() => {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return;
    let cancelled = false;
    const setup = async () => {
      // No onMacroCapture event in GameMapper plugin yet — use onGamepadButton as fallback
      // Real macro capture will be added when TouchAccessibilityService onTouchEvent is implemented
    };
    setup().catch(e => console.warn('useMacroCapture setup failed', e));
    return () => { cancelled = true; listenerRef.current?.remove(); listenerRef.current = null; };
  }, []);

  const startCapture = useCallback(async () => {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return false;
    // TODO: startMacroCapture not yet available in GameMapper plugin
    activeRef.current = true;
    return true;
  }, []);

  const stopCapture = useCallback(async () => {
    activeRef.current = false;
  }, []);

  const eventsToActions = useCallback((events: MacroCaptureEvent[]): MacroAction[] => {
    if (events.length === 0) return [];
    const actions: MacroAction[] = [];
    let lastTs = events[0].timestamp;
    for (const ev of events) {
      const delayMs = Math.max(1, ev.timestamp - lastTs);
      actions.push({
        id: `act_${ev.timestamp}_${ev.pointerId}_${Math.random().toString(36).slice(2, 6)}`,
        type: ev.action === 'down' ? 'touch_down' : ev.action === 'move' ? 'touch_move' : 'touch_up',
        x: ev.action === 'up' ? undefined : Math.round(ev.x),
        y: ev.action === 'up' ? undefined : Math.round(ev.y),
        delayMs, pointerId: ev.pointerId, timestamp: ev.timestamp, pressure: ev.pressure, size: ev.size,
      });
      lastTs = ev.timestamp;
    }
    return actions;
  }, []);

  return { startCapture, stopCapture, eventsToActions, isCapturing: () => activeRef.current };
}
