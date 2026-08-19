import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Fast 易支付 - 管理后台 Vite 配置
 */
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()]
    }),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  base: '/fastpay-admin/',
  server: {
    port: 3001,
    strictPort: true,
    proxy: {
      // 联调时用环境变量指定后端地址：VITE_API_PROXY=http://localhost:7001 或线上 https://pay.copliot.cloud
      // 默认指向本机，避免把请求打到不该打的地方
      '/fastpay-server': {
        target: process.env.VITE_API_PROXY || 'http://localhost:7001',
        changeOrigin: true
      }
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        additionalData: ``
      }
    }
  }
})
