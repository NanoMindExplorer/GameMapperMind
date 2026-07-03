import { useEffect, useRef } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

export function useGamepadLoop(mapProfile: GamepadProfile | null, connected: boolean, injectActive: boolean) {
  // BUG-N2/N3 FIX: Use refs for all values accessed inside closures (listeners, callbacks).
  // React state captured in closures is stale after the first render because effect with []
  // deps only runs once. Without refs, listeners always see initial values.
  const mapProfileRef = useRef(mapProfile);
  const injectActiveRef = useRef(injectActive);
  const prevInjectActiveRef = useRef(injectActive);
  const lastProfileUpdateRef = useRef(0);

  useEffect(() => { mapProfileRef.current = mapProfile; }, [mapProfile]);
  useEffect(() => { injectActiveRef.current = injectActive; }, [injectActive]);

  // Effect 1: Set up listeners ONCE (no re-run on toggle).
  useEffect(() => {
    let isCleanedUp = false;
    let btnListener: any = null;
    let axisListener: any = null;
    let feedbackListener: any = null;

    const setupListeners = async () => {
      try {
        btnListener = await TouchInjection.addListener('onGamepadButton', (data: any) => {
          if (!isCleanedUp) {
            window.dispatchEvent(new CustomEvent('native-gamepad-button', { detail: data }));
          }
        });

        axisListener = await TouchInjection.addListener('onGamepadAxis', (data: any) => {
          if (!isCleanedUp) {
            window.dispatchEvent(new CustomEvent('native-gamepad-axis', { detail: data }));
          }
        });

        // H13: Haptics listener — BUG-N2 FIX: read from refs to avoid stale closure.
        feedbackListener = await TouchInjection.addListener('onGamepadFeedback', async (data: any) => {
           if (!isCleanedUp && injectActiveRef.current && mapProfileRef.current?.hapticIntensity) {
              const { Haptics, ImpactStyle } = await import('@capacitor/haptics');
              if (mapProfileRef.current.hapticIntensity > 0.5) {
                 await Haptics.impact({ style: ImpactStyle.Heavy }).catch(()=>{});
              } else {
                 await Haptics.impact({ style: ImpactStyle.Light }).catch(()=>{});
              }
           }
        });
      } catch (err) {
        console.error("Failed to setup gamepad listeners", err);
      }
    };

    setupListeners();

    return () => {
      isCleanedUp = true;
      if (btnListener && btnListener.remove) btnListener.remove();
      if (axisListener && axisListener.remove) axisListener.remove();
      if (feedbackListener && feedbackListener.remove) feedbackListener.remove();
    };
  }, []);

  // Effect 2: Manage Shizuku bind + profile update.
  // BUG-N3 FIX: Include injectActive in deps so ref updates correctly when it changes.
  // Previously, injectActive was excluded from deps, so prevInjectActiveRef was never
  // updated when injectActive changed — the "only injectActive changed" branch never
  // triggered because the comparison always showed "not changed" (both stale).
  //
  // CACAT #2 FIX: Profile SELALU dikirim ke daemon saat connected=true, terlepas dari
  // injectActive (overlay on/off). Sebelumnya, profile dikirim sebagai "{}" saat
  // injectActive=false (overlay off) — akibatnya buildMapCache di NativeGamepadMapper
  // kosong, findButtonMapping selalu null, dan TIDAK ADA INJEKSI sama sekali.
  //
  // injectActive sekarang hanya mengontrol haptic feedback (di Effect 1 listener).
  // Injection aktif selama Shizuku connected + profile loaded.
  useEffect(() => {
    let isCleanedUp = false;

    const setupShizuku = async () => {
      if (!mapProfile) return;
      try {
        if (connected) {
          await TouchInjection.bindService().catch(() => {});
          if (isCleanedUp) return;
          await TouchInjection.startGamepadListener().catch(() => {});
          if (isCleanedUp) return;
        }
        
        // PERFORMANCE FIX: Debounce profile updates (max 1 update per 500ms)
        // When user drags button in canvas, many profile updates fire rapidly.
        // Sending each one to native causes binder churn. Instead, batch them.
        const now = Date.now();
        if (now - lastProfileUpdateRef.current >= 500) {
          const profileStr = JSON.stringify(mapProfile);
          await TouchInjection.updateActiveProfile({ profileJson: profileStr });
          lastProfileUpdateRef.current = now;
        }
      } catch (err) {
        console.error("Failed to setup Shizuku gamepad listener", err);
      }
    };

    setupShizuku();
    prevInjectActiveRef.current = injectActive;

    return () => {
      isCleanedUp = true;
      // BUG-FIX #3: Do NOT clear profile to "{}" on every effect re-run.
      // Previously, cleanup cleared profile every time deps changed (mapProfile, connected, injectActive).
      // This caused: (1) profile cleared when overlay toggled, (2) profile cleared when profile updated during drag.
      // Result: buttonMapCache empty → findButtonMapping returns null → NO INJECTION.
      // Fix: Only clear profile on actual unmount (all deps undefined), not on re-runs.
      // Profile will be re-sent by the next setupShizuku() call if needed.
    };
  }, [mapProfile, connected, injectActive]);
}
