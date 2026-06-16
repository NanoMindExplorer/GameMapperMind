import { registerPlugin } from '@capacitor/core';

export interface ShizukuBridgePlugin {
  checkStatus(): Promise<{ running: boolean; authorized: boolean }>;
  startDaemon(): Promise<{ success: boolean }>;
  stopDaemon(): Promise<{ success: boolean }>;
  injectTap(options: { x: number; y: number }): Promise<{ success: boolean }>;
  injectSwipe(options: { x1: number; y1: number; x2: number; y2: number; duration: number }): Promise<{ success: boolean }>;
  touchDown(options: { x: number; y: number; pointerId: number }): Promise<{ success: boolean }>;
  touchMove(options: { x: number; y: number; pointerId: number }): Promise<{ success: boolean }>;
  touchUp(options: { pointerId: number }): Promise<{ success: boolean }>;
}

const ShizukuBridge = registerPlugin<ShizukuBridgePlugin>('ShizukuBridge');

export default ShizukuBridge;
