/**
 * 单元测试公共初始化
 * 1. 全局注册 Element Plus（测试里不走 unplugin 的按需引入）
 * 2. 补 jsdom 缺失的浏览器能力
 *
 * 注意这两件事都只有跑在浏览器模拟环境（jsdom）里的测试才需要。
 * 少数测试（比如读 vite 配置的那个）跑在 node 环境里，压根没有 window，
 * 把整个 Element Plus 载进来纯属白等几十秒，还会把那些用例一起拖慢到超时线上，
 * 所以这里先看有没有 window，没有就整段跳过。
 */
if (typeof window !== 'undefined') {
  const [{ default: ElementPlus }, { config }] = await Promise.all([
    import('element-plus'),
    import('@vue/test-utils')
  ])

  config.global.plugins = [ElementPlus]

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
