import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

/**
 * Fast 易支付 - 管理后台单元测试配置
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
    include: ['test/**/*.spec.js'],

    // 单条用例的超时时间。默认只有 5 秒，而挂载一次带表格的页面、
    // 或者真去读一遍 vite 配置，本来就要好几秒；这台机器同时在跑后端测试、
    // CPU 被抢的时候就会越过 5 秒这条线，于是同样的代码这次红下次绿。
    // 超时是用来兜住「卡死不动」的，不是用来卡性能的，所以放宽到 30 秒，
    // 准备阶段（挂载组件、预热配置加载器）放宽到 60 秒。
    testTimeout: 30000,
    hookTimeout: 60000,

    // Element Plus 有一千多个小模块，逐个现场转换要花掉一分多钟，
    // 每挂载一次组件都要重走一遍。先用 esbuild 把它打成一个包再用，
    // 准备时间从 90 多秒降到 5 秒以内，整套测试也就离超时线远得多。
    deps: {
      optimizer: {
        web: {
          enabled: true,
          include: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  }
})
