import { registerPlugin } from "@capacitor/core";

export interface GamepadPluginProtocol {
  getStatus(): Promise<{ active: boolean }>;
  addListener(
    eventName: "gamepadEvent",
    listenerFunc: (event: { type: "BUTTON" | "AXIS"; action?: string; keyCode?: number; buttonName?: string; axisX?: number; axisY?: number; axisZ?: number; axisRZ?: number; hatX?: number; hatY?: number; timestamp: number }) => void
  ): import("@capacitor/core").PluginListenerHandle;
}

export const GamepadNative = registerPlugin<GamepadPluginProtocol>("GamepadPlugin");
