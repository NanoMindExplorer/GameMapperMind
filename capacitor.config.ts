import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.nanomindexplorer.gamemappermind',
  appName: 'GameMapperMind',
  webDir: 'dist/client',
  server: {
    androidScheme: 'https'
  },
  android: {
    webContentsDebuggingEnabled: false
  }
};

export default config;
