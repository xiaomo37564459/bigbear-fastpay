import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import Layout from '@/layout/index.vue'

/**
 * 商户后台顶部导航栏在手机上被挤乱（MTM-201）
 *
 * 原来 fastpay-merchant/src/layout/index.vue 里，「FAST 易支付」和四个主菜单
 * （控制台/店铺管理/通道管理/开发配置）全挤在顶栏一行里，
 * 屏幕一窄就被压成一个字一行，「开发配置」还会掉到屏幕外。
 *
 * 修完之后应该是：
 *   1. 顶栏里的四个主菜单在手机上不出现（它们改到底部标签栏了）
 *   2. 底部标签栏应该有一个 <nav.mobile-tabbar>，四个按钮，name 一一对应
 *   3. 帐号入口在手机上以头像形式出现，取商户名首字
 *   4. 电脑上顶栏还照旧显示四个菜单，底部标签栏不出现
 *
 * jsdom 不会自动读 @media 规则，我们只验 DOM 结构和类名 —— 视觉表现
 * 靠 shots/ 目录下的真机截图和 developer-header 的 CSS 覆盖率保证。
 */

const noop = { template: '<div />' }
const routes = [
  { path: '/', redirect: '/console/dashboard' },
  { path: '/console/dashboard', component: noop },
  { path: '/console/shop', component: noop },
  { path: '/console/channel', component: noop },
  { path: '/console/config', component: noop },
  { path: '/console/docs', component: noop },
  { path: '/login', component: noop }
]

async function mountLayout () {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/console/dashboard')
  await router.isReady()
  const wrapper = mount(Layout, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.setItem('merchant_info', JSON.stringify({
    merchantName: '大熊百货旗舰店',
    nickname: '大熊百货旗舰店',
    merchantNo: 'M2026081900001'
  }))
})

describe('商户后台顶栏 + 底部标签栏', () => {
  it('底部标签栏应该渲染出全部四个主菜单', async () => {
    const wrapper = await mountLayout()
    const tabs = wrapper.findAll('.mobile-tabbar .tabbar-item')
    expect(tabs).toHaveLength(4)
    const labels = tabs.map(t => t.find('.tabbar-label').text())
    expect(labels).toEqual(['控制台', '店铺管理', '通道管理', '开发配置'])
  })

  it('顶栏里的四个主菜单和底部标签栏文案完全一致，避免两处漂移', async () => {
    const wrapper = await mountLayout()
    const nav = wrapper.findAll('.nav-menu .nav-item').map(n => n.text())
    const tabs = wrapper.findAll('.mobile-tabbar .tabbar-label').map(n => n.text())
    expect(nav).toEqual(tabs)
  })

  it('当前路由对应的标签栏项应该带 active 类，并且 aria-current 是 page', async () => {
    const wrapper = await mountLayout()
    const active = wrapper.findAll('.mobile-tabbar .tabbar-item').filter(t => t.classes('active'))
    expect(active).toHaveLength(1)
    expect(active[0].find('.tabbar-label').text()).toBe('控制台')
    expect(active[0].attributes('aria-current')).toBe('page')
  })

  it('点底部标签栏应该跳到对应路由，并且新的标签栏项接管 active', async () => {
    const wrapper = await mountLayout()
    const shopTab = wrapper.findAll('.mobile-tabbar .tabbar-item')[1]
    await shopTab.trigger('click')
    await flushPromises()
    expect(wrapper.vm.$route.path).toBe('/console/shop')
    const active = wrapper.findAll('.mobile-tabbar .tabbar-item').filter(t => t.classes('active'))
    expect(active).toHaveLength(1)
    expect(active[0].find('.tabbar-label').text()).toBe('店铺管理')
  })

  it('用户入口在手机上留有头像挡位，取商户名首字', async () => {
    const wrapper = await mountLayout()
    const avatar = wrapper.find('.user-dropdown .user-avatar')
    expect(avatar.exists()).toBe(true)
    expect(avatar.text()).toBe('大')
  })

  it('商户信息缺失时头像用「商」兜底，不会出现空圈', async () => {
    localStorage.removeItem('merchant_info')
    const wrapper = await mountLayout()
    expect(wrapper.find('.user-dropdown .user-avatar').text()).toBe('商')
  })

  it('顶栏 logo 里的品牌名称加了 nowrap，防止再被压成三行', async () => {
    const wrapper = await mountLayout()
    // 我们不通过内联 style 校验（真正的 nowrap 写在 SCSS 里），
    // 只确认结构上「FAST 易支付」是完整一段文字，且 badge 单独一节
    const logoText = wrapper.find('.logo-text')
    expect(logoText.text()).toBe('FAST 易支付')
    expect(wrapper.find('.logo-badge').text()).toBe('商户中心')
  })
})
