import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderList from '@/views/order/index.vue'
import {
  ORDER_COLUMN_WIDTHS,
  TABLE_WIDTH_AT_1280,
  totalColumnWidth
} from '@/views/order/columns'

/**
 * 订单列表 - 时间列
 *
 * 线上反馈：「创建时间」只看得到 "2026-08…"，后半截被右侧固定的操作列盖住了。
 * 根因是整张表比窗口宽，时间又挤在一行里。现在时间拆成"日期 / 时刻"上下两行、
 * 列宽从 160 降到 100，整表宽度降到 1280 窗口也放得下。
 * 这组测试盯的是：完整的年月日时分秒必须都渲染出来，列宽不许再涨回去。
 */

const getOrderPage = vi.fn()
const getMerchantList = vi.fn()

vi.mock('@/api', () => ({
  getOrderPage: (...args) => getOrderPage(...args),
  getMerchantList: (...args) => getMerchantList(...args),
  getOrderByNo: vi.fn(),
  confirmOrder: vi.fn(),
  closeOrder: vi.fn(),
  resendNotify: vi.fn()
}))

const paidOrder = {
  orderNo: 'FP20260819103000123456',
  outTradeNo: 'SHOP-ORDER-20260819-000001',
  merchantName: '示例测试商户',
  shopName: '总部旗舰店',
  amount: '199.00',
  payType: 'wxpay',
  status: 1,
  notifyStatus: 2,
  notifyCount: 3,
  createTime: '2026-08-19T10:30:00',
  payTime: '2026-08-19T10:31:08'
}

const unpaidOrder = {
  orderNo: 'FP20260819104500654321',
  outTradeNo: 'SHOP-ORDER-20260819-000002',
  merchantName: '示例测试商户',
  shopName: '分店',
  amount: '9.90',
  payType: 'alipay',
  status: 0,
  notifyStatus: 0,
  notifyCount: 0,
  createTime: '2026-08-19T10:45:00',
  payTime: null
}

function mountList() {
  return mount(OrderList, {
    global: {
      stubs: { Search: true, Refresh: true }
    }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  getMerchantList.mockResolvedValue({ data: [] })
  getOrderPage.mockResolvedValue({ data: { records: [paidOrder, unpaidOrder], total: 2 } })
})

describe('订单列表 - 创建时间要看得全', () => {
  it('创建时间和支付时间都拆成日期一行、时刻一行，秒不能丢', async () => {
    const wrapper = mountList()
    await flushPromises()

    const dates = wrapper.findAll('.time-date').map(node => node.text())
    const clocks = wrapper.findAll('.time-clock').map(node => node.text())

    // 第一行订单：创建 10:30:00、支付 10:31:08；第二行订单没支付，只有创建时间
    expect(dates).toEqual(['2026-08-19', '2026-08-19', '2026-08-19'])
    expect(clocks).toEqual(['10:30:00', '10:31:08', '10:45:00'])
  })

  it('没支付的订单，支付时间显示占位符而不是空白', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.text-muted').length).toBeGreaterThan(0)
  })

  it('平台订单号和商户订单号都还在，只是并成一栏上下两行', async () => {
    const wrapper = mountList()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain(paidOrder.orderNo)
    expect(text).toContain(paidOrder.outTradeNo)
    expect(text).toContain(paidOrder.merchantName)
    expect(text).toContain(paidOrder.shopName)
  })

  it('整张表的列宽加起来不超过 1280 窗口能给的宽度，创建时间才不会被操作列盖住', () => {
    // 这是"创建时间不再被挡住"的硬约束：总宽度一旦超了，表格就横向滚动，
    // 右侧固定的操作列又会浮上来盖住时间列，线上那个毛病就回来了。
    expect(totalColumnWidth()).toBeLessThanOrEqual(TABLE_WIDTH_AT_1280)

    // 时间列要留得下完整的 "2026-08-19" / "10:30:00" 两行
    expect(ORDER_COLUMN_WIDTHS.createTime).toBeGreaterThanOrEqual(100)
    expect(ORDER_COLUMN_WIDTHS.payTime).toBeGreaterThanOrEqual(100)
  })
})
