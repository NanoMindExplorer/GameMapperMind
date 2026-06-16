import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.nanomindexplorer.gamemappermind',
  appName: 'GameMapperMind',
  webDir: 'dist',
  server: {
    // FloatingOverlayService loads overlay via https://appassets.androidplatform.net/public/index.html
    androidScheme: 'https',
    allowNavigation: ['appassets.androidplatform.net'],
  },
};

export default config;
