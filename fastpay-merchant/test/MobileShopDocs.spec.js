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

/**
 * 假的店铺接口：真的按页码分页，一页 2 条。
 * `db.total` 决定这个商户名下一共有几家店，每个用例自己设。
 */
const db = vi.hoisted(() => ({
  total: 2,
  shops: [
    { id: 1, shopName: '大熊百货旗舰店', shopNo: 'S2026081900001', status: 1, qrcodeCount: 3, totalAmount: '12580.00' },
    { id: 2, shopName: '大熊数码专营店', shopNo: 'S2026081900002', status: 0, qrcodeCount: 1, totalAmount: '860.50' },
    { id: 3, shopName: '大熊生鲜社区店', shopNo: 'S2026081900003', status: 1, qrcodeCount: 2, totalAmount: '3420.00' },
    { id: 4, shopName: '大熊文具校园店', shopNo: 'S2026081900004', status: 1, qrcodeCount: 1, totalAmount: '178.00' },
    { id: 5, shopName: '蹦蹦超市', shopNo: 'S2026081900005', status: 1, qrcodeCount: 4, totalAmount: '9060.30' }
  ]
}))

vi.mock('@/api', () => ({
  getShopPage: (params = {}) => {
    const all = db.shops.slice(0, db.total)
    const current = params.current || 1
    const size = params.size || 2
    const start = (current - 1) * size
    return Promise.resolve({ data: { records: all.slice(start, start + size), total: all.length } })
  },
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

/**
 * 和 ruleIn 相反：取「不在任何媒体查询里」的那条基础规则（电脑端默认样式）。
 */
const plainRule = (css, selectorContains) => {
  const root = postcss.parse(css)
  let hit = null
  root.walkRules((rule) => {
    if (hit || rule.parent.type !== 'root' || !rule.selector.includes(selectorContains)) return
    const decls = {}
    rule.walkDecls((d) => { decls[d.prop] = d.value })
    hit = decls
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
  // 假数据默认 2 家店（正好一页装完）；要测「装不完」的用例自己改成 5
  db.total = 2
})

/** 把店铺管理页真的挂起来（各用例共用） */
const mountShop = async () => {
  const Shop = (await import('@/views/shop/index.vue')).default
  const router = makeRouter()
  await router.push('/console/shop')
  await router.isReady()
  const wrapper = mount(Shop, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('店铺管理：手机上店铺卡片的结构', () => {
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

  it('手机上列表不再是那个切一半的滚动框，第二张卡片完整显示', () => {
    const rule = ruleIn(css, '767px', '.shop-list-wrapper')
    expect(rule, '手机断点里没找到 .shop-list-wrapper 的规则').not.toBeNull()
    // 返工后：框子整个取消（max-height: none、overflow: visible），
    // 而不是第一版那种「放宽了但两张卡片正好装满」——装满就拉不动，
    // 靠「在框里往下拉」触发的加载下一批就永远不会发生。
    expect(rule['max-height']).toBe('none')
    expect(rule.overflow).toBe('visible')
  })

  it('手机上的「加载更多店铺」按钮是露出来的', () => {
    const rule = ruleIn(css, '767px', '.load-more-btn')
    expect(rule, '手机断点里没找到 .load-more-btn 的规则').not.toBeNull()
    expect(rule.display).toBe('flex')
  })

  it('电脑上这个按钮是藏起来的，电脑端的滚动加载一点不受影响', () => {
    const rule = plainRule(css, '.load-more-btn')
    expect(rule, '基础样式里没找到 .load-more-btn 的规则').not.toBeNull()
    expect(rule.display).toBe('none')
  })
})

describe('店铺管理：一页装不下时，手机上必须能看到全部店铺（MTM-216 返工）', () => {
  it('5 家店：点「加载更多店铺」两次，5 家全部到手，按钮退场换成「没有更多了」', async () => {
    db.total = 5
    const wrapper = await mountShop()

    // 第一页还是 2 家
    expect(wrapper.findAll('.shop-item')).toHaveLength(2)

    // 按钮在场、写着人话
    let btn = wrapper.find('.load-more-btn')
    expect(btn.exists()).toBe(true)
    expect(btn.text()).toContain('加载更多店铺')

    // 点第一次：到第 2 页，共 4 家
    await btn.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.shop-item')).toHaveLength(4)

    // 点第二次：最后一页只有 1 家，共 5 家
    btn = wrapper.find('.load-more-btn')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.shop-item')).toHaveLength(5)

    // 真的能看到第 3~5 家的名字，不是只数了数量
    const names = wrapper.findAll('.shop-name').map(n => n.text())
    expect(names).toEqual(expect.arrayContaining(['大熊生鲜社区店', '大熊文具校园店', '蹦蹦超市']))

    // 全部到手后：按钮退场，换成「没有更多了」
    expect(wrapper.find('.load-more-btn').exists()).toBe(false)
    const noMore = wrapper.find('.load-more-tip.no-more')
    expect(noMore.exists()).toBe(true)
    expect(noMore.text()).toContain('没有更多了')
  })

  it('只有 2 家店（一页就装完）时，不冒「加载更多」按钮，直接说「没有更多了」', async () => {
    db.total = 2
    const wrapper = await mountShop()
    expect(wrapper.findAll('.shop-item')).toHaveLength(2)
    expect(wrapper.find('.load-more-btn').exists()).toBe(false)
    expect(wrapper.find('.load-more-tip.no-more').text()).toContain('没有更多了')
  })

  it('电脑端的「在框里往下拉」加载方式没被顺手弄掉', async () => {
    db.total = 5
    const wrapper = await mountShop()
    expect(wrapper.findAll('.shop-item')).toHaveLength(2)

    // 模拟电脑上：框里内容装不下、被拉到离底部不足 20px
    const box = wrapper.find('.shop-list-wrapper').element
    Object.defineProperty(box, 'clientHeight', { value: 240, configurable: true })
    Object.defineProperty(box, 'scrollHeight', { value: 400, configurable: true })
    Object.defineProperty(box, 'scrollTop', { value: 380, writable: true, configurable: true })
    await wrapper.find('.shop-list-wrapper').trigger('scroll')
    await flushPromises()

    // 滚动触发了「要下一批」
    expect(wrapper.findAll('.shop-item')).toHaveLength(4)
  })
})

/**
 * 店铺管理：电脑上列表框装得下时，自动去要下一批（MTM-222）
 *
 * 电脑端这个框高 240px，两张横排卡片约 212px —— 装得下就不出滚动条，
 * 靠「拉到底」触发的加载永远不发生，第 3 家店起够不着。
 * 修法：每次列表刷新后量一下框，装得下（且框确实在裁内容）就主动补齐下一批。
 *
 * jsdom 不做布局：scrollHeight/clientHeight 永远是 0、样式表也不生效，
 * 所以这里把电脑端的真实几何和 overflow 都手动摆出来再测。
 */
describe('店铺管理：电脑上列表框装得下时，自动去要下一批（MTM-222）', () => {
  /**
   * 把电脑端的真实几何摆出来：框会裁内容（overflow-y: auto）、
   * 两张卡片 ≈ 212px 正好装进 240px 的框，3 张起就装不下了。
   *
   * 挂载那一刻列表还是空的（先走「暂无店铺」分支），拿不到框这个元素，
   * 所以只能在 Element.prototype 上按类名临时接管这两个尺寸，测完原样还回去。
   */
  const stubDesktopBox = () => {
    const proto = Element.prototype
    const origClient = Object.getOwnPropertyDescriptor(proto, 'clientHeight')
    const origScroll = Object.getOwnPropertyDescriptor(proto, 'scrollHeight')
    const isBox = (el) => Boolean(el && el.classList && el.classList.contains('shop-list-wrapper'))
    Object.defineProperty(proto, 'clientHeight', {
      configurable: true,
      get() { return isBox(this) ? 240 : origClient.get.call(this) }
    })
    Object.defineProperty(proto, 'scrollHeight', {
      configurable: true,
      get() {
        if (!isBox(this)) return origScroll.get.call(this)
        // 2 张卡片装得下；3 张起溢出
        return this.querySelectorAll('.shop-item').length >= 3 ? 436 : 212
      }
    })
    const real = window.getComputedStyle.bind(window)
    const gcsSpy = vi.spyOn(window, 'getComputedStyle').mockImplementation((el) => {
      const style = real(el)
      if (!isBox(el)) return style
      return new Proxy(style, {
        get: (target, prop) => (prop === 'overflowY' ? 'auto' : Reflect.get(target, prop))
      })
    })
    return () => {
      gcsSpy.mockRestore()
      Object.defineProperty(proto, 'clientHeight', origClient)
      Object.defineProperty(proto, 'scrollHeight', origScroll)
    }
  }

  it('5 家店：不用拉滚动条，第 3、4 家自己出现；拉到底第 5 家也够得着', async () => {
    db.total = 5
    const restore = stubDesktopBox()
    try {
      const wrapper = await mountShop()

      // 首页 2 家装得下 → 自动补到第 2 页共 4 家；这时框装不下了，停下等用户拉
      expect(wrapper.findAll('.shop-item')).toHaveLength(4)
      const names = wrapper.findAll('.shop-name').map(n => n.text())
      expect(names).toContain('大熊生鲜社区店')
      expect(names).toContain('大熊文具校园店')

      // 第 5 家：框已溢出，拉到底走原来的滚动加载 —— 全部 5 家可达
      const box = wrapper.find('.shop-list-wrapper').element
      Object.defineProperty(box, 'scrollTop', { value: 380, writable: true, configurable: true })
      await wrapper.find('.shop-list-wrapper').trigger('scroll')
      await flushPromises()
      expect(wrapper.findAll('.shop-item')).toHaveLength(5)
      expect(wrapper.findAll('.shop-name').map(n => n.text())).toContain('蹦蹦超市')
    } finally {
      restore()
    }
  })

  it('没有内层滚动框的布局（手机那套）不吃这个自动补齐，「加载更多」按钮照旧', async () => {
    db.total = 5
    // jsdom 里样式表不生效，overflow 是初始值 visible —— 正好等于手机上「没有框」的样子
    const wrapper = await mountShop()
    expect(wrapper.findAll('.shop-item')).toHaveLength(2)
    expect(wrapper.find('.load-more-btn').exists()).toBe(true)
    expect(wrapper.find('.load-more-btn').text()).toContain('加载更多店铺')
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
