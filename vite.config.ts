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
          manualChunks: {
            'vendor-react': ['react', 'react-dom'],
            'vendor-recharts': ['recharts'],
            'vendor-icons': ['lucide-react'],
            'vendor-capacitor': ['@capacitor/core']
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
