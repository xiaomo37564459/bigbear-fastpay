import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderList from '@/views/order/index.vue'
import { isMobileWidth, MOBILE_MAX_WIDTH } from '@/composables/useIsMobile'

/**
 * 手机上打开「订单管理」，顶上那排筛选框挤成一团（MTM-240）
 *
 * 线上反馈：375px 的手机上打开订单管理，
 *   - 筛选的输入框被压得只剩十几像素宽，打不进字
 *   - 标签被截成「户订单号」「品名称」这种半截字
 *   - 页面往右溢出 27px，要左右拖
 *
 * 根因有两个：
 *   1. 筛选条用的是固定 25% 的栅格（:span="6"），375px 屏上每格只有 80px，
 *      而「平台订单号」这个标签横着放就要 78px，输入框自然只剩十几像素
 *   2. 分页里的「前往 __ 页」比屏幕还宽，把整个页面顶出去
 * 再往下，订单表本身 9 列最少 1050px，手机上得左右拖着看。
 *
 * 修完之后应该是：
 *   1. 手机上筛选条默认收起，展开后一行一个、标签在输入框上面
 *   2. 手机上订单列表换成卡片，不再渲染那张表
 *   3. 电脑上一切照旧：还是那张表、还是一行四个筛选框
 *
 * jsdom 不跑 @media，也不做真正的排版，所以这里只验「结构对不对」——
 * 视觉效果靠真机截图。
 */

const getOrderPage = vi.fn()

vi.mock('@/api', () => ({
  getOrderPage: (...args) => getOrderPage(...args),
  getOrderByNo: vi.fn(),
  confirmOrder: vi.fn(),
  closeOrder: vi.fn(),
  resendNotify: vi.fn()
}))

const paidOrder = {
  orderNo: 'FP20260819093038176306',
  outTradeNo: 'sub2_20260819Igx7cQc8',
  shopName: 'token.copliot.cloud',
  subject: 'Sub2API 1.00 CNY',
  amount: '1.00',
  payType: 'wxpay',
  status: 1,
  notifyStatus: 1,
  notifyCount: 1,
  returnUrl: 'https://token.copliot.cloud/pay/return',
  createTime: '2026-08-19T09:30:38'
}

const unpaidOrder = {
  orderNo: 'FP20260819091230235581',
  outTradeNo: 'sub2_20260819SsBh1msp',
  shopName: 'token.copliot.cloud',
  subject: 'Sub2API 10.00 CNY',
  amount: '10.00',
  payType: 'alipay',
  status: 0,
  notifyStatus: 0,
  notifyCount: 0,
  returnUrl: '',
  createTime: '2026-08-19T09:12:30'
}

const realInnerWidth = window.innerWidth

/** jsdom 不做排版，documentElement.clientWidth 永远是 0，只能改 innerWidth 来假装屏幕宽度 */
function setViewportWidth(width) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true })
}

function mountList() {
  return mount(OrderList, {
    global: {
      stubs: { Search: true, Refresh: true, ChatDotRound: true, Wallet: true }
    }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  getOrderPage.mockResolvedValue({ data: { records: [paidOrder, unpaidOrder], total: 2 } })
})

afterEach(() => {
  setViewportWidth(realInnerWidth)
})

describe('手机断点判定', () => {
  it('767 及以下算手机，768 起算电脑', () => {
    expect(MOBILE_MAX_WIDTH).toBe(767)
    expect(isMobileWidth(375)).toBe(true)
    expect(isMobileWidth(767)).toBe(true)
    expect(isMobileWidth(768)).toBe(false)
    expect(isMobileWidth(1440)).toBe(false)
  })

  it('拿不到宽度时按电脑兜底，不能因为读不到就把电脑端变成卡片', () => {
    expect(isMobileWidth(0)).toBe(false)
    expect(isMobileWidth(undefined)).toBe(false)
    expect(isMobileWidth(null)).toBe(false)
    expect(isMobileWidth(-1)).toBe(false)
  })
})

describe('订单管理 - 手机上（375px）', () => {
  beforeEach(() => setViewportWidth(375))

  it('订单列表换成卡片，那张 9 列的表不再渲染', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.order-card')).toHaveLength(2)
    expect(wrapper.find('.order-table').exists()).toBe(false)
  })

  it('卡片上订单号、商户单号、商品、店铺、金额、时间一个都不少', async () => {
    const wrapper = mountList()
    await flushPromises()

    const first = wrapper.findAll('.order-card')[0]
    const text = first.text()
    expect(text).toContain(paidOrder.orderNo)
    expect(text).toContain(paidOrder.outTradeNo)
    expect(text).toContain(paidOrder.subject)
    expect(text).toContain(paidOrder.shopName)
    expect(text).toContain('¥1.00')
    // 时间要完整，秒不能丢
    expect(text).toContain('2026-08-19')
    expect(text).toContain('09:30:38')
    // 跳转地址整条都在，不再只剩 "http"
    expect(text).toContain(paidOrder.returnUrl)
  })

  it('待支付的订单才给「确认 / 关闭」，已支付的给「重发」', async () => {
    const wrapper = mountList()
    await flushPromises()

    const [paidCard, unpaidCard] = wrapper.findAll('.order-card')
    const paidActions = paidCard.findAll('.rc-actions .el-button').map(b => b.text())
    const unpaidActions = unpaidCard.findAll('.rc-actions .el-button').map(b => b.text())

    // 这条已支付、回调也成功了，只剩「详情」
    expect(paidActions).toEqual(['详情'])
    expect(unpaidActions).toEqual(['详情', '确认', '关闭'])
  })

  it('点卡片上的「详情」能打开订单详情弹窗', async () => {
    const wrapper = mountList()
    await flushPromises()

    await wrapper.findAll('.order-card')[0].find('.rc-actions .el-button').trigger('click')
    await flushPromises()

    expect(wrapper.vm.showDetail).toBe(true)
    expect(wrapper.vm.currentOrder.orderNo).toBe(paidOrder.orderNo)
  })

  it('筛选条默认收起，点一下才展开 —— 一进来先看到订单，不是一排空输入框', async () => {
    const wrapper = mountList()
    await flushPromises()

    const toggle = wrapper.find('.filter-toggle')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toContain('展开')
    // v-show 收起时是 display:none
    expect(wrapper.find('.filter-card .card-body').attributes('style')).toContain('display: none')

    await toggle.trigger('click')
    expect(wrapper.find('.filter-toggle').text()).toContain('收起')
    expect(wrapper.find('.filter-card .card-body').attributes('style') || '').not.toContain('display: none')
  })

  it('收起状态下要告诉商户「现在是筛过的」，否则会以为订单丢了', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('.filter-toggle-count').exists()).toBe(false)

    wrapper.vm.queryParams.orderNo = 'FP2026'
    wrapper.vm.queryParams.status = 1
    await flushPromises()

    expect(wrapper.find('.filter-toggle-count').text()).toBe('已选 2 项')
  })

  it('「订单状态 = 待支付」是 0，不能被当成没填', async () => {
    const wrapper = mountList()
    await flushPromises()

    wrapper.vm.queryParams.status = 0
    await flushPromises()

    expect(wrapper.find('.filter-toggle-count').text()).toBe('已选 1 项')
  })

  it('分页收掉「每页几条」和「前往 __ 页」—— 页面往右溢出就是它们撑的', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('.el-pagination__jump').exists()).toBe(false)
    expect(wrapper.find('.el-pagination__sizes').exists()).toBe(false)
    // 上一页 / 下一页还得留着，不然翻不了页
    expect(wrapper.find('.btn-prev').exists()).toBe(true)
    expect(wrapper.find('.btn-next').exists()).toBe(true)
  })
})

describe('订单管理 - 电脑上（1440px）不许被改坏', () => {
  beforeEach(() => setViewportWidth(1440))

  it('还是原来那张表，不出现卡片，也不出现收起筛选的那一条', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('.order-table').exists()).toBe(true)
    expect(wrapper.findAll('.order-card')).toHaveLength(0)
    expect(wrapper.find('.filter-toggle').exists()).toBe(false)
  })

  it('筛选条一直是摊开的，不会被 v-show 藏起来', async () => {
    const wrapper = mountList()
    await flushPromises()

    const style = wrapper.find('.filter-card .card-body').attributes('style') || ''
    expect(style).not.toContain('display: none')
    expect(wrapper.findAll('.filter-form .el-form-item').length).toBeGreaterThanOrEqual(6)
  })

  it('分页里的「每页几条」和「前往 __ 页」照旧还在', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('.el-pagination__sizes').exists()).toBe(true)
    expect(wrapper.find('.el-pagination__jump').exists()).toBe(true)
  })
})

describe('点「搜索」要从第 1 页开始查', () => {
  it('翻到第 3 页再改条件，不能还停在第 3 页 —— 那样商户看到的是一片空白', async () => {
    setViewportWidth(1440)
    const wrapper = mountList()
    await flushPromises()

    wrapper.vm.queryParams.current = 3
    wrapper.vm.queryParams.orderNo = 'FP2026'
    await wrapper.vm.handleSearch()
    await flushPromises()

    expect(wrapper.vm.queryParams.current).toBe(1)
    expect(getOrderPage).toHaveBeenLastCalledWith(expect.objectContaining({ current: 1, orderNo: 'FP2026' }))
  })

  it('手机上查完顺手把筛选条收起来，腾出屏幕给结果', async () => {
    setViewportWidth(375)
    const wrapper = mountList()
    await flushPromises()

    await wrapper.find('.filter-toggle').trigger('click')
    expect(wrapper.vm.filterOpen).toBe(true)

    await wrapper.vm.handleSearch()
    await flushPromises()

    expect(wrapper.vm.filterOpen).toBe(false)
  })
})
