import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Fast 易支付 - 管理后台 Vite 配置
 */

// 后端地址默认走线上域名：任何人拉下代码一条命令启动就能拿到数据，不用改配置。
// 这里不要写死 IP —— 换了机器或换了 IP，别人在自己电脑上就连不上了。
// 本机跑后端做自测时，设置环境变量 VITE_API_PROXY=http://localhost:7001 覆盖即可。
const apiTarget = process.env.VITE_API_PROXY || 'https://pay.copliot.cloud'

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
      '/fastpay-server': {
        target: apiTarget,
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
