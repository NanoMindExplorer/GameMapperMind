import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.nanomindexplorer.gamemappermind',
  appName: 'GameMapperMind',
  webDir: 'dist/client',
  server: {
    androidScheme: 'https',
    url: process.env.NODE_ENV === "development" ? "http://10.0.2.2:3000" : undefined,
    cleartext: true
  },
  android: {
    webContentsDebuggingEnabled: false
  }
};

export default config;
