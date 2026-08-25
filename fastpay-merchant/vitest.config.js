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
    include: ['test/**/*.spec.js'],

    // 单条用例的超时时间。默认只有 5 秒，而挂载一次带表格的页面、
    // 或者真去读一遍 vite 配置，本来就要好几秒；这台机器同时在跑后端测试、
    // CPU 被抢的时候就会越过 5 秒这条线，于是同样的代码这次红下次绿。
    // 超时是用来兜住「卡死不动」的，不是用来卡性能的，所以放宽到 30 秒，
    // 准备阶段（挂载组件、预热配置加载器）放宽到 60 秒。
    testTimeout: 30000,
    hookTimeout: 60000

    // ⚠️ 这里**故意没有**开 deps.optimizer.web（Element Plus 预打包）。
    //
    // 隔壁 fastpay-admin/vitest.config.js 里有这么一段，看着照抄过来就行 ——
    // 别抄。这里试过，CI 上当场就红了（MTM-251，PR #82 连红两次）：
    //
    // 预打包的产物会写进 node_modules/.vite/vitest/deps/。本地这份缓存早就在了，
    // 所以怎么跑都是绿的；但 CI 每次都是全新环境，10 个测试文件的进程同时开跑、
    // 同时发现缓存不在、就同时去建。一个进程还在读，另一个把整个目录重建了，
    // 读的那个当场报「找不到 element-plus.js」或者「找不到 .vite/vitest/deps」。
    // 特征很好认：**每次挂的文件都不一样**，而且是整个文件一条测试都没跑起来。
    //
    // 那段预打包本来是为了让「CPU 被占满的本地机器」跑快点，但 CI 上整套测试
    // 只要 12 秒，离 30 秒的线远得很，压根不需要它。为了省几十秒本地时间，
    // 换来一个每次 PR 都可能随机红的门禁，不划算。
    //
    // 真要治本地慢，去掉全局注册整个 Element Plus 才是正路（见 test/setup.js），
    // 不是在这里加预打包。
  }
})
