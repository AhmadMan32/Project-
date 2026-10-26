import path from 'path';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

/**
 * Offline demo build: no API server, synthetic terrain, in-browser simulation.
 * Relative asset paths and stable file names so the output can be hosted as static files anywhere.
 *   pnpm --filter @workspace/firebreak-lab run build:demo
 */
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  define: { 'import.meta.env.VITE_DEMO': JSON.stringify('1') },
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
    dedupe: ['react', 'react-dom'],
  },
  root: path.resolve(import.meta.dirname),
  // demo-public holds the bundled real data (WorldCover crops, Terrarium tiles); only this build ships it.
  publicDir: path.resolve(import.meta.dirname, 'demo-public'),
  build: {
    outDir: path.resolve(import.meta.dirname, 'dist/demo'),
    emptyOutDir: true,
    assetsInlineLimit: 0,
    rollupOptions: {
      output: { entryFileNames: 'assets/app.js', chunkFileNames: 'assets/[name].js', assetFileNames: 'assets/[name][extname]' },
    },
  },
});
