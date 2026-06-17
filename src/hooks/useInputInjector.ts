import { Capacitor } from '@capacitor/core';
import GameMapper from '../plugins/GameMapper';
import { GamepadProfile } from '../types';

export function useInputInjector() {
  const startOverlay = async (_config: GamepadProfile) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        await GameMapper.startOverlay();
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
        await GameMapper.stopOverlay();
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
