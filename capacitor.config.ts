import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.nanomindexplorer.gamemappermind',
  appName: 'GameMapperMind',
  webDir: 'dist/client',
  server: {
    androidScheme: 'capacitor',
    // cleartext: true allows HTTP requests to localhost/127.0.0.1 for dev server.
    // AndroidManifest.xml and network_security_config.xml restrict cleartext to
    // localhost only — this flag enables the Capacitor WebView to load dev assets.
    cleartext: true,
    allowNavigation: [
      "localhost",
      "127.0.0.1",
      "appassets.androidplatform.net"
    ]
  }
};

export default config;
