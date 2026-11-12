import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Development: forward API paths to locally running services. They answer 401 with a
    // Basic challenge, so the browser asks for the user name and password.
    proxy: {
      '/query': { target: 'http://localhost:8083', rewrite: (path) => path.replace(/^\/query/, '') },
      '/simulator': { target: 'http://localhost:8084', rewrite: (path) => path.replace(/^\/simulator/, '') },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.ts',
  },
});
