import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 9888,
    proxy: {
      // 后端默认 8080（application-dev.yml server.port）
      '/api': 'http://localhost:8080',
      '/charts': 'http://localhost:8080',
      '/icons': 'http://localhost:8080'
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets'
  },
  test: {
    environment: 'jsdom'
  }
})
