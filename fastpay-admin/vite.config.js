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
      // 后端地址默认走线上（可直接联调）；本机跑后端做自测时设置 VITE_API_PROXY=http://localhost:7001
      '/fastpay-server': {
        target: process.env.VITE_API_PROXY || 'http://121.4.28.146:80',
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
