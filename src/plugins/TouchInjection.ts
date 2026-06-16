import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface TouchInjectionPluginType {
  bindService(): Promise<void>;
  unbindService(): Promise<void>;
  startGamepadListener(): Promise<void>;
  stopGamepadListener(): Promise<void>;
  startOverlay(options: { profile: any }): Promise<void>;
  stopOverlay(): Promise<void>;
  checkPermission(): Promise<{ granted: boolean }>;
  
  touchDown(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchMove(options: { pointerId: number; x: number; y: number }): Promise<void>;
  touchUp(options: { pointerId: number }): Promise<void>;
  injectTap(options: { x: number; y: number }): Promise<void>;

  addListener(
    eventName: 'onGamepadButton', 
    listenerFunc: (data: { buttonName: string; value: number; pressure: number }) => void
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'onGamepadAxis', 
    listenerFunc: (data: { axes: number[] }) => void
  ): Promise<PluginListenerHandle>;
}

const TouchInjection = registerPlugin<TouchInjectionPluginType>('TouchInjection');

export default TouchInjection;
