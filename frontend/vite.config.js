import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  // loadEnv 读取 .env 里的 VITE_* 变量（process.env 不会自动带 .env 的值）
  const env = loadEnv(mode, process.cwd(), '')
  const backend = env.VITE_API_PROXY || process.env.VITE_API_PROXY || 'http://localhost:8080'
  return {
    plugins: [vue()],
    server: {
      port: 9888,
      // 内网穿透演示：允许 Cloudflare Quick Tunnel 域名访问
      allowedHosts: ['.trycloudflare.com'],
      proxy: {
        '/api': backend,
        '/charts': backend,
        '/icons': backend
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
  }
})
