import { useEffect } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

export function useGamepadLoop(mapProfile: GamepadProfile | null, connected: boolean, injectActive: boolean) {
  useEffect(() => {
    if (!connected || !mapProfile) return;

    let isCleanedUp = false;
    let btnListener: any = null;
    let axisListener: any = null;
    let feedbackListener: any = null;

    const setupNative = async () => {
      try {
        await TouchInjection.bindService().catch(() => {});
        if (isCleanedUp) return;
        await TouchInjection.startGamepadListener().catch(() => {});
        if (isCleanedUp) return;
        
        const profileStr = injectActive ? JSON.stringify(mapProfile) : "{}";
        await TouchInjection.updateActiveProfile({ profileJson: profileStr });
        if (isCleanedUp) return;

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
        
        // H13: Haptics listener — save handle for cleanup
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
        console.error("Failed to setup native gamepad listener", err);
      }
    };

    setupNative();

    return () => {
      isCleanedUp = true;
      if (btnListener && btnListener.remove) btnListener.remove();
      if (axisListener && axisListener.remove) axisListener.remove();
      if (feedbackListener && feedbackListener.remove) feedbackListener.remove();
      TouchInjection.updateActiveProfile({ profileJson: "{}" }).catch(() => {});
    };
  }, [mapProfile, connected, injectActive]);
}
