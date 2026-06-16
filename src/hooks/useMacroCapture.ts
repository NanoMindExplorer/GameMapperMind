import { useEffect, useRef, useCallback } from 'react';
import { Capacitor } from '@capacitor/core';
import TouchInjection from '../plugins/TouchInjection';
import type { MacroAction, MacroCaptureEvent } from '../types';

interface UseMacroCaptureOptions {
  onCapture: (event: MacroCaptureEvent) => void;
}

// ============================================================
// useMacroCapture — controls the AccessibilityService-based real
// macro recorder. When startCapture() is called, all touch events
// on screen are forwarded to JS as MacroCaptureEvent objects.
// ============================================================
export function useMacroCapture({ onCapture }: UseMacroCaptureOptions) {
  const listenerRef = useRef<any>(null);
  const onCaptureRef = useRef(onCapture);
  const activeRef = useRef(false);

  useEffect(() => {
    onCaptureRef.current = onCapture;
  }, [onCapture]);

  useEffect(() => {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return;

    let cancelled = false;

    const setup = async () => {
      listenerRef.current = await TouchInjection.addListener('onMacroCapture', (data) => {
        if (cancelled) return;
        const event: MacroCaptureEvent = {
          action: data.action,
          pointerId: data.pointerId,
          x: data.x,
          y: data.y,
          pressure: data.pressure,
          size: data.size,
          timestamp: data.timestamp,
        };
        onCaptureRef.current(event);
      });
    };

    setup().catch(e => console.warn('useMacroCapture setup failed', e));

    return () => {
      cancelled = true;
      listenerRef.current?.remove();
      listenerRef.current = null;
    };
  }, []);

  const startCapture = useCallback(async () => {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') {
      console.warn('Macro capture only supported on native Android');
      return false;
    }
    try {
      await TouchInjection.startMacroCapture();
      activeRef.current = true;
      return true;
    } catch (e) {
      console.error('startMacroCapture failed', e);
      return false;
    }
  }, []);

  const stopCapture = useCallback(async () => {
    try {
      await TouchInjection.stopMacroCapture();
    } catch (e) {
      console.error('stopMacroCapture failed', e);
    } finally {
      activeRef.current = false;
    }
  }, []);

  // Convert raw capture events to MacroAction array (with delayMs between events).
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
        delayMs,
        pointerId: ev.pointerId,
        timestamp: ev.timestamp,
        pressure: ev.pressure,
        size: ev.size,
      });
      lastTs = ev.timestamp;
    }

    return actions;
  }, []);

  return {
    startCapture,
    stopCapture,
    eventsToActions,
    isCapturing: () => activeRef.current,
  };
}
