import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

/**
 * Fast 易支付 - 商户中心单元测试配置
 * 只跑 test/ 目录下的测试，不走 vite.config.js 里的自动按需引入插件，
 * Element Plus 在 test/setup.js 里整体注册，避免测试环境和构建环境互相牵扯。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./test/setup.js'],
    include: ['test/**/*.spec.js']
  }
})
