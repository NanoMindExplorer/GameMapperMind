import { useEffect, useRef } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

export function useGamepadLoop(mapProfile: GamepadProfile | null, connected: boolean, injectActive: boolean) {
  // BUG-H1 FIX: Track previous injectActive to detect toggle without re-running setup effect.
  const prevInjectActiveRef = useRef(injectActive);
  
  // Effect 1: Set up listeners ONCE (no re-run on toggle).
  // These listeners receive events from both Shizuku getevent (when connected)
  // and Android native input (via GamepadPlugin.dispatchKeyEvent) — works WITHOUT Shizuku.
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
        
        // H13: Haptics listener
        feedbackListener = await TouchInjection.addListener('onGamepadFeedback', async (data: any) => {
           if (!isCleanedUp && injectActive && mapProfile?.hapticIntensity) {
              const { Haptics, ImpactStyle } = await import('@capacitor/haptics');
              if (mapProfile.hapticIntensity > 0.5) {
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
  }, []); // BUG-H1 FIX: Empty dependency — listeners set up ONCE.

  // Effect 2: Manage Shizuku bind + profile update (re-runs only when connected/profile change).
  // injectActive changes only update the profile JSON, NOT re-bind the service.
  useEffect(() => {
    let isCleanedUp = false;

    const setupShizuku = async () => {
      if (!connected || !mapProfile) return;
      try {
        await TouchInjection.bindService().catch(() => {});
        if (isCleanedUp) return;
        await TouchInjection.startGamepadListener().catch(() => {});
        if (isCleanedUp) return;
        
        const profileStr = injectActive ? JSON.stringify(mapProfile) : "{}";
        await TouchInjection.updateActiveProfile({ profileJson: profileStr });
      } catch (err) {
        console.error("Failed to setup Shizuku gamepad listener", err);
      }
    };

    // BUG-H1 FIX: If only injectActive changed (not connected or mapProfile),
    // just update the profile JSON without re-binding the service.
    const onlyInjectActiveChanged = 
      prevInjectActiveRef.current !== injectActive;
    
    if (onlyInjectActiveChanged && connected && mapProfile) {
      // Just update profile, no re-bind
      const profileStr = injectActive ? JSON.stringify(mapProfile) : "{}";
      TouchInjection.updateActiveProfile({ profileJson: profileStr }).catch(() => {});
    } else {
      setupShizuku();
    }
    
    prevInjectActiveRef.current = injectActive;

    return () => {
      isCleanedUp = true;
      // BUG-H2 FIX: Always reset profile on cleanup, regardless of `connected` state.
      // Stale profile in GamepadListenerService.activeProfileJson can persist across reconnects.
      TouchInjection.updateActiveProfile({ profileJson: "{}" }).catch(() => {});
    };
  }, [mapProfile, connected]); // BUG-H1 FIX: injectActive removed from deps (handled via ref).
}
