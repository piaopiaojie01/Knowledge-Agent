import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 9888,
    // 内网穿透演示：允许 Cloudflare Quick Tunnel 域名访问
    allowedHosts: ['.trycloudflare.com'],
    // 后端地址可用环境变量覆盖：VITE_API_PROXY=http://localhost:8082 npm run dev
    proxy: {
      '/api': process.env.VITE_API_PROXY || 'http://localhost:8080',
      '/charts': process.env.VITE_API_PROXY || 'http://localhost:8080',
      '/icons': process.env.VITE_API_PROXY || 'http://localhost:8080'
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
