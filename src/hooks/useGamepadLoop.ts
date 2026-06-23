import { useEffect } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

export function useGamepadLoop(mapProfile: GamepadProfile | null, connected: boolean, injectActive: boolean) {
  useEffect(() => {
    // BUG FIX: ALWAYS set up listeners — they receive events from BOTH:
    // 1. Shizuku getevent (via GamepadListenerService) when connected
    // 2. Android native input (via GamepadPlugin.dispatchKeyEvent) — works WITHOUT Shizuku
    // Previously, listeners were only set up when connected=true, causing gamepad
    // to be invisible in GamepadTester when Shizuku was not running.
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

    const setupShizuku = async () => {
      // Only bind Shizuku service when connected
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

    // Always set up listeners first (for native Android gamepad input)
    setupListeners();
    // Then set up Shizuku if connected (for getevent-based input + touch injection)
    setupShizuku();

    return () => {
      isCleanedUp = true;
      if (btnListener && btnListener.remove) btnListener.remove();
      if (axisListener && axisListener.remove) axisListener.remove();
      if (feedbackListener && feedbackListener.remove) feedbackListener.remove();
      if (connected) {
        TouchInjection.updateActiveProfile({ profileJson: "{}" }).catch(() => {});
      }
    };
  }, [mapProfile, connected, injectActive]);
}
