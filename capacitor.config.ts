import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.nanomindexplorer.gamemappermind',
  appName: 'GameMapperMind',
  webDir: 'dist/client',
  server: {
    androidScheme: 'capacitor',
    // BUG-CAPS-CLR FIX: cleartext: true is REQUIRED to allow HTTP requests to localhost / 127.0.0.1
    // on Android 9+ (network security policy blocks cleartext by default). Without this, dev server
    // and any HTTP-only asset requests fail. Previously this was only set in the generated
    // capacitor.config.json, but `npx cap sync android` overwrites that file from this source —
    // so the setting was silently lost on every CI/local sync.
    cleartext: true,
    allowNavigation: [
      "localhost",
      "127.0.0.1",
      "appassets.androidplatform.net"
    ]
  }
};

export default config;
