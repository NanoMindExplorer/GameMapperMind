import { useEffect } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

export function useGamepadLoop(mapProfile: GamepadProfile | null, connected: boolean, injectActive: boolean) {
  useEffect(() => {
    if (!connected || !mapProfile) return;

    let isCleanedUp = false;

    const setupNative = async () => {
      try {
        await TouchInjection.bindService().catch(() => {});
        await TouchInjection.startGamepadListener().catch(() => {});
        
        if (isCleanedUp) return;
        
        const profileStr = injectActive ? JSON.stringify(mapProfile) : "{}";
        await TouchInjection.updateActiveProfile({ profileJson: profileStr });
      } catch (err) {
        console.error("Failed to setup native gamepad listener", err);
      }
    };

    setupNative();

    const btnListener = TouchInjection.addListener('onGamepadButton', (data: any) => {
        window.dispatchEvent(new CustomEvent('native-gamepad-button', { detail: data }));
    });
    const axisListener = TouchInjection.addListener('onGamepadAxis', (data: any) => {
        window.dispatchEvent(new CustomEvent('native-gamepad-axis', { detail: data }));
    });

    return () => {
      isCleanedUp = true;
      btnListener.then(l => l.remove());
      axisListener.then(l => l.remove());
      TouchInjection.updateActiveProfile({ profileJson: "{}" }).catch(() => {});
    };
  }, [mapProfile, connected, injectActive]);
}
