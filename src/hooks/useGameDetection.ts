import { useEffect, useRef } from 'react';
import { Capacitor } from '@capacitor/core';
import GameMapper from '../plugins/GameMapper';
import type { GamepadProfile, GameDetectionEvent } from '../types';

interface UseGameDetectionOptions {
  profiles: GamepadProfile[];
  onGameDetected: (event: GameDetectionEvent) => void;
  onAutoStart: (profile: GamepadProfile) => void;
  enabled: boolean;
}

// ============================================================
// useGameDetection — listens to onForegroundAppChanged events from
// TouchAccessibilityService and auto-switches the active profile
// (and optionally auto-starts the overlay) when a matching game
// becomes the foreground app.
// ============================================================
export function useGameDetection({
  profiles,
  onGameDetected,
  onAutoStart,
  enabled,
}: UseGameDetectionOptions) {
  const listenerRef = useRef<any>(null);
  const lastSeenPackageRef = useRef<string>('');
  const lastAutoStartRef = useRef<number>(0);
  const profilesRef = useRef(profiles);
  const onGameDetectedRef = useRef(onGameDetected);
  const onAutoStartRef = useRef(onAutoStart);
  const enabledRef = useRef(enabled);

  useEffect(() => {
    profilesRef.current = profiles;
  }, [profiles]);

  useEffect(() => {
    onGameDetectedRef.current = onGameDetected;
    onAutoStartRef.current = onAutoStart;
  }, [onGameDetected, onAutoStart]);

  useEffect(() => {
    enabledRef.current = enabled;
  }, [enabled]);

  useEffect(() => {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return;

    let cancelled = false;

    const setup = async () => {
      listenerRef.current = await GameMapper.addListener('onForegroundAppChanged', (data) => {
        if (cancelled || !enabledRef.current) return;
        const pkg = data.packageName;
        if (!pkg || pkg === lastSeenPackageRef.current) return;
        lastSeenPackageRef.current = pkg;

        // Find matching profile by packageName
        const matched = profilesRef.current.find(p => p.packageName === pkg);
        const event: GameDetectionEvent = {
          packageName: pkg,
          matchedProfileId: matched?.id,
          timestamp: data.timestamp,
        };
        onGameDetectedRef.current(event);

        // Auto-start if profile has autoStartEnabled = true
        if (matched?.autoStartEnabled) {
          // Debounce: only fire auto-start once per 5 seconds per package
          const now = Date.now();
          if (now - lastAutoStartRef.current < 5000) return;
          lastAutoStartRef.current = now;
          onAutoStartRef.current(matched);
        }
      });
    };

    setup().catch(e => console.warn('useGameDetection setup failed', e));

    return () => {
      cancelled = true;
      listenerRef.current?.remove();
      listenerRef.current = null;
    };
  }, []);
}
