import { registerPlugin } from '@capacitor/core';
import { GamepadProfile } from '../types';

const OverlayPlugin = registerPlugin('Overlay');

export function useOverlay() {
  const startOverlay = async (config: GamepadProfile) => {
    try {
      await (OverlayPlugin as any).startOverlay({
        config: JSON.stringify(config)
      });
      return true;
    } catch (e) {
      console.error("Native overlay error", e);
      return false;
    }
  };

  const stopOverlay = async () => {
    try {
      await (OverlayPlugin as any).stopOverlay();
      return true;
    } catch (e) {
      console.error("Native overlay error", e);
      return false;
    }
  };

  return { startOverlay, stopOverlay };
}
