import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined
          }
          if (id.includes('reka-ui') || id.includes('lucide-vue-next')) {
            return 'ui'
          }
          if (id.includes('vue') || id.includes('vue-router') || id.includes('pinia')) {
            return 'vue'
          }
          return undefined
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
        cookiePathRewrite: '/',
      },
      // 后端提供的静态资产：头像、助手附件、知识库插图与 PDF 原件。
      // 不代理的话它们会落到 SPA fallback 上，返回 index.html——
      // 表现为图片裂开、PDF 预览空白，且状态码是 200，很难一眼看出问题。
      '/uploads': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
