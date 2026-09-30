import { useEffect, useRef } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';
import { ProfileDebouncer } from '../utils/profileDebouncer';

export function useGamepadLoop(mapProfile: GamepadProfile | null, connected: boolean, injectActive: boolean) {
  // BUG-N2/N3 FIX: Use refs for all values accessed inside closures (listeners, callbacks).
  // React state captured in closures is stale after the first render because effect with []
  // deps only runs once. Without refs, listeners always see initial values.
  const mapProfileRef = useRef(mapProfile);
  const injectActiveRef = useRef(injectActive);
  const debouncerRef = useRef<ProfileDebouncer | null>(null);

  useEffect(() => { mapProfileRef.current = mapProfile; }, [mapProfile]);
  useEffect(() => { injectActiveRef.current = injectActive; }, [injectActive]);

  // Effect 1: Set up listeners ONCE (no re-run on toggle).
  useEffect(() => {
    let isCleanedUp = false;
    let btnListener: any = null;
    let axisListener: any = null;
    let feedbackListener: any = null;
    let diagnosticLogListener: any = null;

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

        // FIX: relay native-side detection diagnostics (e.g. which raw axis/button names a
        // connected controller actually reports) to the app's on-screen log — previously
        // only visible via `adb logcat`, which most users testing on-device can't access.
        diagnosticLogListener = await TouchInjection.addListener('onDiagnosticLog', (data: any) => {
          if (!isCleanedUp) {
            window.dispatchEvent(new CustomEvent('native-diagnostic-log', { detail: data }));
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
      if (diagnosticLogListener && diagnosticLogListener.remove) diagnosticLogListener.remove();
    };
  }, []);

  // Effect 2: Manage Shizuku service binding & listener (isolated from rapid profile drag updates).
  // Decoupled so that dragging a button doesn't redundantly call bindService/startGamepadListener.
  useEffect(() => {
    let isCleanedUp = false;
    const initService = async () => {
      if (connected) {
        try {
          await TouchInjection.bindService().catch(() => {});
          if (isCleanedUp) return;
          await TouchInjection.startGamepadListener().catch(() => {});
        } catch (err) {
          console.error("Failed to connect Shizuku service", err);
        }
      }
    };
    initService();
    return () => {
      isCleanedUp = true;
    };
  }, [connected]);

  // Effect 3: Instantiate ProfileDebouncer instance with trailing-edge guarantee.
  useEffect(() => {
    const debouncer = new ProfileDebouncer({
      debounceDelayMs: 250,
      maxWaitMs: 600,
      onSync: async (profileJson: string) => {
        await TouchInjection.updateActiveProfile({ profileJson });
      }
    });
    debouncerRef.current = debouncer;

    return () => {
      debouncer.dispose();
      debouncerRef.current = null;
    };
  }, []);

  // Effect 4: Ingest profile changes into Trailing-Edge Debouncer.
  // Throttles rapid drag events to avoid IPC Binder churn while GUARANTEEING that the
  // final resting coordinate/state is delivered to the native layer when dragging finishes.
  useEffect(() => {
    if (!mapProfile || !debouncerRef.current) return;
    debouncerRef.current.update(mapProfile);
  }, [mapProfile]);

  // Effect 5: When connected state transitions to true, force immediate sync of active profile.
  useEffect(() => {
    if (connected && mapProfile && debouncerRef.current) {
      debouncerRef.current.update(mapProfile, { forceImmediate: true });
    }
  }, [connected]);
}
