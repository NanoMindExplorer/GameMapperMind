import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor Configuration
 * 
 * FIX: webDir diubah menjadi 'dist/client' untuk mencocokkan 
 * konfigurasi `build.outDir` di vite.config.ts.
 * Tanpa ini, Capacitor akan mencari index.html di folder 'www' 
 * dan menyebabkan error build.
 */
const config: CapacitorConfig = {
  appId: 'com.nanomind.gamemapper',
  appName: 'GameMapperMind',
  
  // PENTING: Harus sama dengan outDir di vite.config.ts
  webDir: 'dist/client', 
  
  bundledWebRuntime: false,
  
  server: {
    androidScheme: 'https',
  },
};

export default config;
