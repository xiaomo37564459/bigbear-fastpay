/**
 * 单元测试公共初始化
 * 1. 全局注册 Element Plus（测试里不走 unplugin 的按需引入）
 * 2. 补 jsdom 缺失的浏览器能力
 */
import ElementPlus from 'element-plus'
import { config } from '@vue/test-utils'

config.global.plugins = [ElementPlus]

// 少数测试（比如读 vite 配置的那个）跑在 node 环境里，压根没有 window，
// 下面这些浏览器补丁只在有 window 的时候才需要打。
if (typeof window !== 'undefined') {
  // jsdom 没有实现 matchMedia，Element Plus 内部会用到
  if (!window.matchMedia) {
    window.matchMedia = (query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false
    })
  }

  // jsdom 没有 ResizeObserver，Element Plus 的部分组件会用到
  if (!window.ResizeObserver) {
    window.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }
}
