import { registerPlugin, Capacitor } from '@capacitor/core';
import { GamepadProfile } from '../types';

export interface GameOverlayPluginInterface {
  startOverlay(options: { config: string }): Promise<void>;
  stopOverlay(): Promise<void>;
  checkPermission(): Promise<{ hasPermission: boolean }>;
}

const GameOverlayPlugin = registerPlugin<GameOverlayPluginInterface>('Overlay');

export function useInputInjector() {
  const startOverlay = async (config: GamepadProfile) => {
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try {
        await GameOverlayPlugin.startOverlay({
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
        await GameOverlayPlugin.stopOverlay();
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
