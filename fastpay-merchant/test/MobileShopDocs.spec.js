import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import postcss from 'postcss'

/**
 * 手机上「店铺管理」挤成竖条、「开发文档」能横着滚（MTM-216）
 *
 * 修之前商户用手机打开这两页看到的是：
 *   店铺管理 —— 「二维码」竖排三行、「启用」被劈成两行、第二张店铺卡片被拦腰截断
 *   开发文档 —— 390px 的屏幕上页面实际有 518px 宽，手指能把整页横着推走
 *
 * 这里分两种测法：
 *   1. 结构测试：真的把组件挂起来，确认页面结构没漂
 *      （店铺卡片还是那几块、文档页两栏在窄屏配了 xs=24）
 *   2. 样式测试：jsdom 不会按屏幕宽度去算布局，所以没法直接量「宽度是多少」。
 *      改成把组件的 <style> 交给浏览器的样式表解析器，确认手机断点里
 *      确实有那几条关键规则 —— 谁把它们删了，这里就会红。
 *      真实观感由 issue 里的真机截图和横向溢出实测保证。
 */

vi.mock('qrcode', () => ({ default: { toDataURL: async () => 'data:image/png;base64,AAAA' } }))
vi.mock('jsqr', () => ({ default: () => null }))

const page = (records) => Promise.resolve({ data: { records, total: records.length } })

vi.mock('@/api', () => ({
  getShopPage: () => page([
    { id: 1, shopName: '大熊百货旗舰店', shopNo: 'S2026081900001', status: 1, qrcodeCount: 3, totalAmount: '12580.00' },
    { id: 2, shopName: '大熊数码专营店', shopNo: 'S2026081900002', status: 0, qrcodeCount: 1, totalAmount: '860.50' }
  ]),
  createShop: vi.fn(),
  updateShop: vi.fn(),
  updateShopStatus: vi.fn(),
  getQrcodePage: () => page([]),
  addQrcode: vi.fn(),
  updateQrcodeStatus: vi.fn(),
  deleteQrcode: vi.fn(),
  getOrderPage: () => page([]),
  confirmOrder: vi.fn(),
  getChannelList: () => Promise.resolve({ data: [] })
}))

const SRC = resolve(__dirname, '../src')
const readStyle = (relPath) => {
  const file = readFileSync(resolve(SRC, relPath), 'utf8')
  // 只取第一个 <style scoped> 里的内容
  const m = file.match(/<style scoped>([\s\S]*?)<\/style>/)
  if (!m) throw new Error(`${relPath} 里没找到 <style scoped>`)
  return m[1]
}

/**
 * 用真正的 CSS 解析器（postcss，vite 自己就在用它）把样式解析成结构，
 * 再取出「某个媒体查询里，某条选择器」的那几行声明。
 *
 * 用解析器而不是正则去匹配源码，是为了让「换个写法但意思一样」也能通过：
 * 关心的是最终有没有这条规则，而不是它被写成了什么样子。
 *
 * 返回一个 { 属性名: 值 } 的对象；没找到这条规则时返回 null。
 */
const ruleIn = (css, mediaContains, selectorContains) => {
  const root = postcss.parse(css)
  let hit = null
  root.walkAtRules('media', (atRule) => {
    if (hit || !atRule.params.includes(mediaContains)) return
    atRule.walkRules((rule) => {
      if (hit || !rule.selector.includes(selectorContains)) return
      const decls = {}
      rule.walkDecls((d) => { decls[d.prop] = d.value })
      hit = decls
    })
  })
  return hit
}

const noop = { template: '<div />' }
const makeRouter = () => createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: noop },
    { path: '/login', component: noop },
    { path: '/console/channel', component: noop },
    { path: '/console/shop', component: noop },
    { path: '/docs', component: noop }
  ]
})

beforeEach(() => {
  localStorage.setItem('merchant_token', 'test-token')
})

describe('店铺管理：手机上店铺卡片的结构', () => {
  const mountShop = async () => {
    const Shop = (await import('@/views/shop/index.vue')).default
    const router = makeRouter()
    await router.push('/console/shop')
    await router.isReady()
    const wrapper = mount(Shop, { global: { plugins: [router] } })
    await flushPromises()
    return wrapper
  }

  it('每张店铺卡片都由「信息 / 两个数 / 操作」三块组成', async () => {
    const wrapper = await mountShop()
    const items = wrapper.findAll('.shop-item')
    expect(items).toHaveLength(2)
    for (const item of items) {
      expect(item.find('.shop-info .shop-name').exists()).toBe(true)
      expect(item.find('.shop-info .shop-meta .status-tag').exists()).toBe(true)
      expect(item.findAll('.shop-stats .stat-item')).toHaveLength(2)
      expect(item.find('.shop-actions').exists()).toBe(true)
    }
  })

  it('两个数的说明文字还是「二维码」和「累计交易」', async () => {
    const wrapper = await mountShop()
    const labels = wrapper.findAll('.shop-item')[0].findAll('.stat-label').map(n => n.text())
    expect(labels).toEqual(['二维码', '累计交易'])
  })

  it('状态标签显示「启用 / 禁用」，不是空的', async () => {
    const wrapper = await mountShop()
    const tags = wrapper.findAll('.shop-item .status-tag').map(n => n.text())
    expect(tags).toEqual(['启用', '禁用'])
  })
})

describe('店铺管理：手机断点里的关键样式（≤767px）', () => {
  const css = readStyle('views/shop/index.vue')

  it('店铺卡片改成上下排，三块不再横着抢宽度', () => {
    const rule = ruleIn(css, '767px', '.shop-item')
    expect(rule, '手机断点里没找到 .shop-item 的规则').not.toBeNull()
    expect(rule['flex-direction']).toBe('column')
  })

  it('「二维码」「累计交易」不许再被压成竖排', () => {
    const rule = ruleIn(css, '767px', '.stat-label')
    expect(rule, '手机断点里没找到 .stat-label 的规则').not.toBeNull()
    expect(rule['white-space']).toBe('nowrap')
  })

  it('「启用 / 禁用」这个小标签不许被劈成两行', () => {
    const rule = ruleIn(css, '767px', '.status-tag')
    expect(rule, '手机断点里没找到 .status-tag 的规则').not.toBeNull()
    expect(rule['white-space']).toBe('nowrap')
  })

  it('统计块自己带内边距，不再吃全局 .stat-item 那份大内边距', () => {
    const rule = ruleIn(css, '767px', '.shop-stats .stat-item')
    expect(rule, '手机断点里没找到 .shop-stats .stat-item 的规则').not.toBeNull()
    expect(rule.padding).toBeTruthy()
    expect(rule['min-width']).toBe('0')
  })

  it('店铺列表的高度放开了，第二张卡片不会再被拦腰截断', () => {
    const rule = ruleIn(css, '767px', '.shop-list-wrapper')
    expect(rule, '手机断点里没找到 .shop-list-wrapper 的规则').not.toBeNull()
    // 原来写死 240px，只够放一张半，第二张正好被切在中间
    expect(rule['max-height']).toBeTruthy()
    expect(rule['max-height']).not.toBe('240px')
  })
})

describe('开发文档（公开页）：手机上不许左右横着推', () => {
  it('左右两栏在手机上都占满整屏（xs=24），不再是写死的 5 : 19', async () => {
    const PublicDocs = (await import('@/views/docs/public.vue')).default
    const router = makeRouter()
    await router.push('/docs')
    await router.isReady()
    const wrapper = mount(PublicDocs, { global: { plugins: [router] } })
    await flushPromises()

    const cols = wrapper.findAll('.docs-container > .el-row > .el-col')
    expect(cols.length).toBe(2)
    for (const col of cols) {
      expect(col.classes()).toContain('el-col-xs-24')
    }
    // 电脑端仍旧是 5 : 19
    expect(cols[0].classes()).toContain('el-col-md-5')
    expect(cols[1].classes()).toContain('el-col-md-19')
  })

  it('窄屏下目录改成横着滑的一条，不再竖着占掉一整屏', () => {
    const css = readStyle('views/docs/public.vue')
    const nav = ruleIn(css, '991px', '.docs-nav')
    expect(nav, '窄屏断点里没找到 .docs-nav 的规则').not.toBeNull()
    expect(nav.position).toBe('static')

    const list = ruleIn(css, '991px', '.el-menu')
    expect(list, '窄屏断点里没找到目录列表的规则').not.toBeNull()
    expect(list.display).toBe('flex')
    expect(list['overflow-x']).toBe('auto')
  })

  it('接口地址这类长字符串该折行就折行，不许把页面拽宽', () => {
    const css = readStyle('views/docs/public.vue')
    const rule = ruleIn(css, '767px', '.api-endpoint code')
    expect(rule, '手机断点里没找到接口地址的规则').not.toBeNull()
    expect(rule['overflow-wrap']).toBe('anywhere')
  })
})

describe('开发文档（后台内）：手机上不许左右横着推', () => {
  const css = readStyle('views/docs/index.vue')

  it('一栏排布时把 el-row 的负外边距归零，不留多顶出来的那一截', () => {
    const row = ruleIn(css, '767px', '.el-row')
    expect(row, '手机断点里没找到 el-row 的规则').not.toBeNull()
    expect(row['margin-left']).toBe('0')
    expect(row['margin-right']).toBe('0')
  })

  it('接口地址该折行就折行', () => {
    const rule = ruleIn(css, '767px', '.api-info code')
    expect(rule, '手机断点里没找到接口地址的规则').not.toBeNull()
    expect(rule['overflow-wrap']).toBe('anywhere')
  })
})
