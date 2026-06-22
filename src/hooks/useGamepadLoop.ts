import { useEffect } from 'react';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

export function useGamepadLoop(mapProfile: GamepadProfile | null, active: boolean) {
  useEffect(() => {
    if (!active || !mapProfile) return;

    let isCleanedUp = false;

    const setupNative = async () => {
      try {
        await TouchInjection.bindService().catch(() => {});
        await TouchInjection.startGamepadListener().catch(() => {});
        
        if (isCleanedUp) return;
        
        await TouchInjection.updateActiveProfile({ profileJson: JSON.stringify(mapProfile) });
      } catch (err) {
        console.error("Failed to setup native gamepad listener", err);
      }
    };

    setupNative();

    return () => {
      isCleanedUp = true;
      if (!active) {
        TouchInjection.updateActiveProfile({ profileJson: "{}" }).catch(() => {});
      }
    };
  }, [mapProfile, active]);
}
