import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderList from '@/views/order/index.vue'
import {
  ORDER_COLUMN_WIDTHS,
  ORDER_COLUMN_CAPS,
  MEASURED_MIN_WIDTHS,
  NOTIFY_COUNT_SAMPLE,
  TABLE_WIDTH_AT_1280,
  formatNotifyCount,
  totalColumnWidth,
  resolveColumnWidths
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

// 大额订单。原来的假数据只有 ¥199.00 和 ¥9.90 两种小额，
// 结果金额列宽给少了 10px 也没人发现（MTM-168 验收打回的就是这条）。
const bigAmountOrder = {
  orderNo: 'FP20260819110200778899',
  outTradeNo: 'SHOP-ORDER-20260819-000003',
  merchantName: '深圳大冰熊科技有限公司',
  shopName: '深圳南山旗舰店',
  amount: '888888.88',
  payType: 'wxpay',
  status: 1,
  notifyStatus: 1,
  notifyCount: 1,
  createTime: '2026-08-19T11:02:00',
  payTime: '2026-08-19T11:02:31'
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
  getOrderPage.mockResolvedValue({ data: { records: [paidOrder, unpaidOrder, bigAmountOrder], total: 3 } })
})

describe('订单列表 - 创建时间要看得全', () => {
  it('创建时间和支付时间都拆成日期一行、时刻一行，秒不能丢', async () => {
    const wrapper = mountList()
    await flushPromises()

    const dates = wrapper.findAll('.time-date').map(node => node.text())
    const clocks = wrapper.findAll('.time-clock').map(node => node.text())

    // 第一行：创建 10:30:00、支付 10:31:08；第二行没支付，只有创建时间；第三行两个都有
    expect(dates).toEqual(Array(5).fill('2026-08-19'))
    expect(clocks).toEqual(['10:30:00', '10:31:08', '10:45:00', '11:02:00', '11:02:31'])
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

/**
 * 订单列表 - 富余宽度怎么分（MTM-168）
 *
 * 1440 窗口下「深圳大冰熊科技有限公司」这种 11 个字的商户名会被截成「深圳大冰熊科技有…」，
 * 而同一行的订单号列右边空着五十多像素没人用——富余宽度被 Element Plus 按 min-width
 * 比例平摊掉了。现在改成按需分配：先把订单号列喂饱，剩下的全给商户名。
 *
 * 下面这些像素数都是拿 Chromium 实测出来的，不是估的：
 *   - 列宽 = 文字宽度 + 24（左右各 10px 单元格内边距 + 表格自身占的 4px）
 *   - 平台订单号 FP+20 位数字（12px 等宽）= 163px
 *   - 「商户单号 SHOP-ORDER-20260819-000001」= 231px
 *   - 14px 下一个汉字正好 14px，11 个字的商户名 = 154px
 */
describe('订单列表 - 窗口变宽时富余宽度怎么分', () => {
  /** 某个窗口宽度下，表格实际能用多少像素（跟 TABLE_WIDTH_AT_1280 同一套算法） */
  const tableWidthAt = (viewport) => viewport - 220 - 40 - 40 - 6

  const CELL_PADDING = 24
  const ORDER_NO_TEXT = 163
  const OUT_TRADE_NO_TEXT = 231
  const ELEVEN_CJK_CHARS = 11 * 14

  it('基准宽正好用满 1280 能给的宽度，一格不多一格不少', () => {
    // 少了浪费，多了横向滚动。基准值就是 1280 这一档的最优解。
    expect(totalColumnWidth()).toBe(TABLE_WIDTH_AT_1280)
  })

  it('最窄的 1280 下订单号也必须完整显示，这是不能让的一条', () => {
    const cols = resolveColumnWidths(tableWidthAt(1280))
    expect(cols.orderNo).toBeGreaterThanOrEqual(ORDER_NO_TEXT + CELL_PADDING)
  })

  it('1440 下订单号列吃饱：平台订单号和商户单号两行都完整显示', () => {
    const cols = resolveColumnWidths(tableWidthAt(1440))
    expect(cols.orderNo).toBe(ORDER_COLUMN_CAPS.orderNo)
    expect(cols.orderNo).toBeGreaterThanOrEqual(OUT_TRADE_NO_TEXT + CELL_PADDING)
  })

  it('1440 下 11 个字的商户名不再被截断', () => {
    const cols = resolveColumnWidths(tableWidthAt(1440))
    expect(cols.merchant - CELL_PADDING).toBeGreaterThanOrEqual(ELEVEN_CJK_CHARS)
  })

  it('富余宽度先补商户名、再补订单号副行，谁都不会超过自己吃得下的上限', () => {
    // 只多一点点的时候：全进商户名（订单号的硬要求在基准宽就已经满足了）
    const tiny = resolveColumnWidths(TABLE_WIDTH_AT_1280 + 20)
    expect(tiny.merchant).toBe(ORDER_COLUMN_WIDTHS.merchant + 20)
    expect(tiny.orderNo).toBe(ORDER_COLUMN_WIDTHS.orderNo)

    // 商户名补到"11 个字看得全"之后，接着补订单号的副行
    const roomy = resolveColumnWidths(TABLE_WIDTH_AT_1280 + 100)
    expect(roomy.merchant).toBe(ELEVEN_CJK_CHARS + CELL_PADDING)
    expect(roomy.orderNo).toBeGreaterThan(ORDER_COLUMN_WIDTHS.orderNo)
    expect(roomy.orderNo).toBeLessThanOrEqual(ORDER_COLUMN_CAPS.orderNo)

    // 谁都不会超过自己的上限，除非两边都吃饱了
    expect(roomy.merchant).toBeLessThanOrEqual(ORDER_COLUMN_CAPS.merchant)
  })

  it('超宽屏下两列都吃饱了就对半分，表格右边不留白', () => {
    const wide = tableWidthAt(1920)
    const cols = resolveColumnWidths(wide)
    expect(cols.orderNo).toBeGreaterThanOrEqual(ORDER_COLUMN_CAPS.orderNo)
    expect(cols.merchant).toBeGreaterThanOrEqual(ORDER_COLUMN_CAPS.merchant)
    expect(totalColumnWidth(cols)).toBe(wide)
  })

  it('任何宽度下所有列加起来都刚好铺满表格，既不横向滚动也不留白', () => {
    // 表格宽度是连续变化的（拖窗口、收侧边栏都会变），随便取一批值都得成立
    for (const viewport of [1280, 1366, 1440, 1536, 1600, 1680, 1920, 2560]) {
      const width = tableWidthAt(viewport)
      expect(totalColumnWidth(resolveColumnWidths(width))).toBe(width)
    }
  })

  it('比 1280 还窄的时候退回基准宽，不会把订单号压得更小', () => {
    // 再窄就只能横向滚动了，但基准宽是底线，订单号不许因此被截
    const cols = resolveColumnWidths(tableWidthAt(1024))
    expect(cols).toEqual(ORDER_COLUMN_WIDTHS)
    expect(totalColumnWidth(cols)).toBe(TABLE_WIDTH_AT_1280)
  })
})

/**
 * 订单列表 - 金额不许折行（MTM-168 验收打回）
 *
 * 之前金额列只给了 88px，是照着「¥12800.00」这一条测试数据定的。
 * 真实业务里管理员会看到十万、百万级订单，这些在 88px 下会折成
 * "¥888888." / "88" 两行，整行跟着变高，一列数字忽高忽低没法扫读。
 *
 * MEASURED_MIN_WIDTHS 里的数字全是在 Chromium 里把列宽逐档调窄、
 * 真渲染出来看折不折行量到的，不是拿文字宽度加内边距算的。
 */
describe('订单列表 - 金额列要放得下大额订单', () => {
  it('金额列放得下百万级金额（¥888888.88），任何窗口宽度下都不折行', () => {
    expect(ORDER_COLUMN_WIDTHS.amount).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.amountMillion)

    // 金额列是固定宽度，不吃富余宽度，所以窄窗口也是这个宽度，得逐档确认
    for (const viewport of [1024, 1280, 1366, 1440, 1920]) {
      const cols = resolveColumnWidths(viewport - 220 - 40 - 40 - 6)
      expect(cols.amount).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.amountMillion)
    }
  })

  it('大额订单的金额一位数字都不少地渲染出来', async () => {
    const wrapper = mountList()
    await flushPromises()

    const amounts = wrapper.findAll('.amount-text').map(node => node.text())
    expect(amounts).toContain('¥888888.88')
  })

  it('整张表加起来仍然放得进 1280，金额列变宽不能把表挤到横向滚动', () => {
    expect(totalColumnWidth()).toBe(TABLE_WIDTH_AT_1280)
  })

  it('其它几列也都还在各自的实测下限之上', () => {
    // 金额列的 10px 是从商户列挪过来的，别顺手把别的列削到不够用
    expect(ORDER_COLUMN_WIDTHS.orderNo).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.orderNo)
    expect(ORDER_COLUMN_WIDTHS.payType).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.fourCharHeader)
    expect(ORDER_COLUMN_WIDTHS.notify).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.fourCharHeader)
    expect(ORDER_COLUMN_WIDTHS.createTime).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.time)
    expect(ORDER_COLUMN_WIDTHS.payTime).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.time)
    expect(ORDER_COLUMN_WIDTHS.action).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.action)
  })
})

/**
 * 订单列表 - 「通知 N 次」不许折行（MTM-193）
 *
 * 老毛病：回调状态列的副行写的是「已通知 3 次」，实测要 92px，而这一列只有 80px。
 * 次数一上两位数就折成两行，整行跟着从 65px 长到 71px，一列订单忽高忽低没法扫读。
 *
 * 这一列压不下去也补不上去：80px 的下限是四个字的表头「回调状态」，跟副行无关；
 * 整张表在 1280 下又已经排满 974px，没有空位可以补。所以只能让**文案迁就列宽**——
 * 改成最短的「通知12次」，实测 75px，装得进 80px。
 *
 * 下面的像素数同样是在 Chromium 里真渲染量的（1440 / 1280 两档都量过）：
 *   「已通知 999 次」= 92px  ｜  「通知999次」= 75px  ｜  表头「回调状态」= 80px
 */
describe('订单列表 - 回调状态副行不许折行', () => {
  const withNotifyCount = (count) => ({
    ...paidOrder,
    orderNo: `FP2026081910300000${String(count).padStart(4, '0')}`,
    notifyCount: count
  })

  it('文案是最短的「通知N次」：「已」和数字两边的空格正好是折行的那几像素，别加回去', () => {
    expect(formatNotifyCount(1)).toBe('通知1次')
    expect(formatNotifyCount(12)).toBe('通知12次')
    expect(formatNotifyCount(999)).toBe('通知999次')

    // 实测基准就是照着这个样本量的（75px）。样本变了，MEASURED_MIN_WIDTHS.notifyCountLine
    // 必须重新拿浏览器量一遍，不能照着字数估。
    expect(NOTIFY_COUNT_SAMPLE).toBe('通知999次')
    expect(NOTIFY_COUNT_SAMPLE).not.toContain(' ')
    expect(NOTIFY_COUNT_SAMPLE).not.toContain('已')
  })

  it('1 次 / 12 次 / 999 次三档都按短文案渲染，列表里不再出现「已通知 N 次」', async () => {
    getOrderPage.mockResolvedValue({
      data: { records: [1, 12, 999].map(withNotifyCount), total: 3 }
    })
    const wrapper = mountList()
    await flushPromises()

    const lines = wrapper.findAll('.notify-count').map((node) => node.text())
    expect(lines).toEqual(['通知1次', '通知12次', '通知999次'])
    expect(lines.join(' ')).not.toContain('已通知')
  })

  it('没通知过的订单还是不显示这一行，别凭空冒出个「通知0次」', async () => {
    getOrderPage.mockResolvedValue({ data: { records: [unpaidOrder], total: 1 } })
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.notify-count')).toHaveLength(0)
  })

  it('回调状态列放得下三位数的副行 —— 这就是"不折行"的硬约束', () => {
    expect(ORDER_COLUMN_WIDTHS.notify).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.notifyCountLine)

    // 这一列是固定宽度，不吃富余宽度，所以窄窗口也是这个宽度，得逐档确认
    for (const viewport of [1024, 1280, 1366, 1440, 1920]) {
      const cols = resolveColumnWidths(viewport - 220 - 40 - 40 - 6)
      expect(cols.notify).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.notifyCountLine)
    }
  })

  it('这一列的下限是表头不是副行：压到副行那 75px，折行的就从副行换成表头', () => {
    // 记这一条是为了挡住下一个人"副行只要 75px，那匀 5px 给金额列吧"的念头。
    // 实测把 notify 调到 75：副行是不折了，表头「回调状态」当场折成两行，等于白改。
    expect(MEASURED_MIN_WIDTHS.notifyCountLine).toBeLessThan(MEASURED_MIN_WIDTHS.fourCharHeader)
    expect(ORDER_COLUMN_WIDTHS.notify).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.fourCharHeader)
  })

  it('改文案没把整张表挤宽，也没从别的列身上挖肉', () => {
    expect(totalColumnWidth()).toBe(TABLE_WIDTH_AT_1280)
    expect(ORDER_COLUMN_WIDTHS.amount).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.amountMillion)
    expect(ORDER_COLUMN_WIDTHS.orderNo).toBeGreaterThanOrEqual(MEASURED_MIN_WIDTHS.orderNo)
    expect(ORDER_COLUMN_WIDTHS.merchant).toBe(93)
  })
})
