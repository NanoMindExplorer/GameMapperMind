import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {defineConfig} from 'vite';

export default defineConfig(() => {
  return {
    base: '/',
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    build: {
      outDir: 'dist/client',
      target: 'es2020',
      chunkSizeWarningLimit: 1000,
      rollupOptions: {
        output: {
          // FIX: vite 8 / Rollup 4+ requires manualChunks as a function, not an object.
          // The function receives the module ID and returns the chunk name (or null to
          // leave it in the default chunk). This is backward-compatible with vite 6.
          manualChunks(id) {
            if (id.includes('node_modules/react/') || id.includes('node_modules/react-dom/')) {
              return 'vendor-react';
            }
            if (id.includes('node_modules/lucide-react/')) {
              return 'vendor-icons';
            }
            if (id.includes('node_modules/@capacitor/core/')) {
              return 'vendor-capacitor';
            }
            return null;
          }
        }
      }
    },
    server: {
      port: 5173,
      proxy: {
        '/api': 'http://localhost:3000'
      },
      hmr: process.env.DISABLE_HMR !== 'true',
      watch: process.env.DISABLE_HMR === 'true' ? false : {},
    },
  };
});
