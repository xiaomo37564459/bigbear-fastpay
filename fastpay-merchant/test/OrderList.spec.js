import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderList from '@/views/order/index.vue'
import {
  ORDER_COLUMN_WIDTHS,
  TABLE_WIDTH,
  totalColumnWidth,
  shouldFixActionColumn
} from '@/views/order/columns'

/**
 * 商户中心订单列表 - 「跳转地址」这一列要看得全
 *
 * 线上反馈：「跳转地址」只看得到 "http"，「创建时间」整列看不见，都被右侧固定的
 * 「操作」列盖住了。根因是整张表 1380 宽，内容区只给得起 1104，表格一横向滚动，
 * 固定列就浮上来盖住了它左边的内容。
 * 现在两个订单号并成一栏、商品和店铺并成一栏、时间拆成两行，整表降到 1050；
 * 窗口窄到还是放不下时，「操作」列自动取消固定，让整张表一起滚，谁也不盖谁。
 * 这组测试盯的是：总宽度不许再涨回去，跳转地址和完整时间戳必须都渲染出来。
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

describe('商户中心订单列表 - 跳转地址和创建时间要看得全', () => {
  it('整张表的列宽加起来不超过内容区给的宽度，跳转地址才不会被操作列盖住', () => {
    // 这是"跳转地址不再被挡住"的硬约束：总宽度一旦超了，表格就横向滚动，
    // 右侧固定的操作列又会浮上来盖住它左边的列，线上那个毛病就回来了。
    expect(totalColumnWidth()).toBeLessThanOrEqual(TABLE_WIDTH)

    // 跳转地址至少要留得下 "https://" 加一小段域名，不能再退回只看得见 "http"
    expect(ORDER_COLUMN_WIDTHS.returnUrl).toBeGreaterThanOrEqual(150)
    // 时间列要留得下完整的 "2026-08-19" / "09:30:38" 两行
    expect(ORDER_COLUMN_WIDTHS.createTime).toBeGreaterThanOrEqual(100)
  })

  it('窗口放得下就固定操作列，放不下就取消固定，避免固定列盖住别的列', () => {
    // 1440 / 1280 的窗口，内容区定宽 1200，表格拿到 1104，放得下 → 固定
    expect(shouldFixActionColumn(1440)).toBe(true)
    expect(shouldFixActionColumn(1280)).toBe(true)
    // 窄到表格放不下整张表时不固定，让它整体横向滚动
    expect(shouldFixActionColumn(1100)).toBe(false)
    expect(shouldFixActionColumn(1024)).toBe(false)
    // 服务端渲染或拿不到宽度时按"不固定"兜底，宁可多滚一下也不要盖住内容
    expect(shouldFixActionColumn(0)).toBe(false)
    expect(shouldFixActionColumn(undefined)).toBe(false)
  })

  it('跳转地址整条渲染出来，没有跳转地址的订单显示占位符', async () => {
    const wrapper = mountList()
    await flushPromises()

    const urls = wrapper.findAll('.return-url').map(node => node.text())
    expect(urls).toEqual([paidOrder.returnUrl])
    expect(wrapper.findAll('.text-muted').length).toBeGreaterThan(0)
  })

  it('创建时间拆成日期一行、时刻一行，秒不能丢', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.time-date').map(n => n.text())).toEqual(['2026-08-19', '2026-08-19'])
    expect(wrapper.findAll('.time-clock').map(n => n.text())).toEqual(['09:30:38', '09:12:30'])
  })

  it('平台订单号和商户订单号都还在，只是并成一栏上下两行', async () => {
    const wrapper = mountList()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain(paidOrder.orderNo)
    expect(text).toContain(paidOrder.outTradeNo)
  })

  it('商品名称和店铺都还在，并成一栏上下两行', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.cell-main').map(n => n.text())).toEqual([paidOrder.subject, unpaidOrder.subject])
    expect(wrapper.findAll('.cell-sub').map(n => n.text())).toEqual([paidOrder.shopName, unpaidOrder.shopName])
  })

  it('没数据时表格是空的，不报错', async () => {
    getOrderPage.mockResolvedValue({ data: { records: [], total: 0 } })
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.el-table__row').length).toBe(0)
    expect(wrapper.text()).toContain('共 0 条记录')
  })

  it('接口出错时不会把页面搞崩，loading 也会收掉', async () => {
    getOrderPage.mockRejectedValue(new Error('网络异常'))
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.el-table__row').length).toBe(0)
    expect(wrapper.vm.loading).toBe(false)
    consoleError.mockRestore()
  })
})
