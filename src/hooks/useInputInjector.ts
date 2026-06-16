import { Capacitor } from '@capacitor/core';
import TouchInjection from '../plugins/TouchInjection';
import { GamepadProfile } from '../types';

export function useInputInjector() {
  const startOverlay = async (config: GamepadProfile) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        await TouchInjection.startOverlay({
          profile: config
        });
        return true;
      } catch (e) {
        console.error("Native overlay error", e);
        throw e;
      }
    }
    console.warn("Overlay is only supported on native Android");
    return false;
  };

  const stopOverlay = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        await TouchInjection.stopOverlay();
        return true;
      } catch (e) {
        console.error("Native overlay error", e);
        throw e;
      }
    }
    return false;
  };

  return { startOverlay, stopOverlay };
}
