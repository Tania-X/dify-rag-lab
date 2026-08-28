import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发时把 /api 代理到 Java 网关（backend，默认 8081）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
});
