import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { VantResolver } from '@vant/auto-import-resolver'

/**
 * Fast 易支付 - 商户平台 Vite 配置
 */

// 后端地址默认走线上域名：任何人拉下代码一条命令启动就能拿到数据，不用改配置。
// 这里不要写死 IP —— 换了机器或换了 IP，别人在自己电脑上就连不上了。
// 本机跑后端做自测时，设置环境变量 VITE_API_PROXY=http://localhost:7001 覆盖即可。
const apiTarget = process.env.VITE_API_PROXY || 'https://pay.copliot.cloud'
// 收款页面靠长连接（WebSocket）实时收支付结果，它和普通接口是同一个后端，
// 只是协议头要从 http/https 换成 ws/wss，这样改后端地址时两边不会走散。
const wsTarget = apiTarget.replace(/^http/, 'ws')

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()]
    }),
    Components({
      resolvers: [ElementPlusResolver(), VantResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  base: '/fastpay-merchant/',
  server: {
    port: 3002,
    strictPort: true,
    proxy: {
      '/fastpay-server': {
        target: apiTarget,
        changeOrigin: true
      },
      '/fastpay-server/ws': {
        target: wsTarget,
        ws: true,
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
