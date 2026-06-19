import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.nanomind.gamemappermind',
  appName: 'GameMapperMind',
  webDir: 'dist',
  server: {
    androidScheme: 'https'
  },
  android: {
    captureInput: true,
    webContentsDebuggingEnabled: false
  }
};

export default config;
