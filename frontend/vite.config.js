import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 9888,
    proxy: {
      '/api': 'http://localhost:9898',
      '/charts': 'http://localhost:9898',
      '/icons': 'http://localhost:9898'
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
