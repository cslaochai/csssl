import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  // 本地开发保持根路径 base='/'；仅门户部署构建时用 VITE_BASE=/ssl/ 注入
  base: process.env.VITE_BASE || '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(import.meta.dirname, 'src'),
    },
  },
  server: {
    port: 5175,
    proxy: {
      // 本地默认接口前缀 /ssl；门户部署时前端走 /ssl-api，再改写到 context-path /ssl
      '/ssl': { target: 'http://localhost:8092', changeOrigin: true },
      '/ssl-api': {
        target: 'http://localhost:8092',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ssl-api/, '/ssl'),
      }
    }
  }
})
