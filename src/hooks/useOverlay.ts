import { registerPlugin, Capacitor } from '@capacitor/core';
import { GamepadProfile } from '../types';

const OverlayPlugin = registerPlugin('Overlay');

export function useOverlay() {
  const startOverlay = async (config: GamepadProfile) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        await (OverlayPlugin as any).startOverlay({
          config: JSON.stringify(config)
        });
        return true;
      } catch (e) {
        console.error("Native overlay error", e);
        return false;
      }
    }
    console.warn("Overlay is only supported on native Android");
    return false;
  };

  const stopOverlay = async () => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        await (OverlayPlugin as any).stopOverlay();
        return true;
      } catch (e) {
        console.error("Native overlay error", e);
        return false;
      }
    }
    return false;
  };

  return { startOverlay, stopOverlay };
}
