import { registerPlugin } from "@capacitor/core";

export interface ShizukuPluginProtocol {
  checkStatus(): Promise<{ available: boolean }>;
}

export const ShizukuNative = registerPlugin<ShizukuPluginProtocol>("ShizukuPlugin");
