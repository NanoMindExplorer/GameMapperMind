import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.nanomindexplorer.gamemappermind',
  appName: 'GameMapperMind',
  webDir: 'dist/client',
  server: {
    androidScheme: 'capacitor',
    cleartext: true,
    allowNavigation: [
      "localhost",
      "127.0.0.1",
      "appassets.androidplatform.net"
    ]
  }
};

export default config;
