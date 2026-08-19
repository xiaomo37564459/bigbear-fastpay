import { describe, it, expect } from 'vitest'
import {
  formatDateTime,
  splitDateTime,
  formatAmount,
  formatPayType,
  formatPayMethod,
  formatExtParam,
  textOrDash,
  getStatusText,
  getStatusType,
  getNotifyStatusText,
  getNotifyStatusType,
  mergeOrderDetail,
  resolveDetailLayout,
  buildOrderDetailSections,
  collectDetailKeys,
  EMPTY_TEXT
} from '@/utils/orderDetail'

/** 一笔字段全满的订单，模拟后端 /admin/order/page 返回的一行 */
const fullOrder = {
  id: 1024,
  orderNo: 'FP20260819103000123456789012',
  outTradeNo: 'SHOP-ORDER-20260819-000000000001',
  merchantId: 7,
  merchantName: '大熊测试商户',
  shopId: 12,
  shopName: '总部旗舰店',
  qrcodeId: 33,
  payType: 'wxpay',
  payMethod: 'api',
  amount: '199.00',
  payAmount: '199.00',
  subject: '年度会员套餐（含增值服务包）',
  status: 1,
  notifyUrl: 'https://shop.example.com/api/payment/fastpay/notify?channel=fastpay&source=web&traceId=abcdef0123456789',
  returnUrl: 'https://shop.example.com/pay/result?orderNo=SHOP-ORDER-20260819-000000000001&from=fastpay',
  notifyStatus: 2,
  notifyCount: 3,
  lastNotifyTime: '2026-08-19T10:35:12',
  payTime: '2026-08-19T10:31:08',
  expireTime: '2026-08-19T10:45:00',
  clientIp: '203.0.113.42',
  extParam: '{"uid":8899,"channel":"app"}',
  createTime: '2026-08-19T10:30:00',
  updateTime: '2026-08-19T10:35:12'
}

describe('订单详情 - 取值与格式化', () => {
  it('时间为空时返回空串，带 T 的时间统一成 yyyy-MM-dd HH:mm:ss', () => {
    expect(formatDateTime(null)).toBe('')
    expect(formatDateTime(undefined)).toBe('')
    expect(formatDateTime('')).toBe('')
    expect(formatDateTime('2026-08-19T10:30:00')).toBe('2026-08-19 10:30:00')
    expect(formatDateTime('2026-08-19 10:30:00')).toBe('2026-08-19 10:30:00')
    expect(formatDateTime('2026-08-19T10:30:00.123+08:00')).toBe('2026-08-19 10:30:00')
  })

  it('时间能拆成日期和时刻两段，列表里分两行显示，秒不会丢', () => {
    expect(splitDateTime('2026-08-19T09:30:38')).toEqual({ date: '2026-08-19', time: '09:30:38' })
    expect(splitDateTime('2026-08-19 09:30:38')).toEqual({ date: '2026-08-19', time: '09:30:38' })
    expect(splitDateTime(null)).toEqual({ date: '', time: '' })
    expect(splitDateTime('')).toEqual({ date: '', time: '' })
  })

  it('金额为空时显示占位符而不是 ¥-，0 元要正常显示', () => {
    expect(formatAmount(null)).toBe(EMPTY_TEXT)
    expect(formatAmount(undefined)).toBe(EMPTY_TEXT)
    expect(formatAmount('')).toBe(EMPTY_TEXT)
    expect(formatAmount(0)).toBe('¥0.00')
    expect(formatAmount('199.00')).toBe('¥199.00')
    expect(formatAmount(199)).toBe('¥199.00')
  })

  it('支付类型和支付方式有中文说法，未知值不会显示成空白', () => {
    expect(formatPayType('wxpay')).toBe('微信支付')
    expect(formatPayType('alipay')).toBe('支付宝')
    expect(formatPayType('')).toBe(EMPTY_TEXT)
    expect(formatPayMethod('page')).toBe('页面跳转')
    expect(formatPayMethod('api')).toBe('接口调用')
    expect(formatPayMethod(null)).toBe(EMPTY_TEXT)
  })

  it('扩展参数是 JSON 时格式化展开，不是 JSON 时原样显示', () => {
    expect(formatExtParam('{"uid":1}')).toBe('{\n  "uid": 1\n}')
    expect(formatExtParam('uid=1&from=app')).toBe('uid=1&from=app')
    expect(formatExtParam(null)).toBe(EMPTY_TEXT)
  })

  it('空值统一显示成占位符，0 和 false 不算空', () => {
    expect(textOrDash(null)).toBe(EMPTY_TEXT)
    expect(textOrDash('')).toBe(EMPTY_TEXT)
    expect(textOrDash(0)).toBe('0')
    expect(textOrDash('abc')).toBe('abc')
  })

  it('订单状态和回调状态的文案、颜色保持原有口径', () => {
    expect([0, 1, 2, 3].map(getStatusText)).toEqual(['待支付', '已支付', '已过期', '已关闭'])
    expect([0, 1, 2, 3].map(getStatusType)).toEqual(['warning', 'success', 'info', 'danger'])
    expect(getStatusText(9)).toBe('未知')
    expect([0, 1, 2].map(getNotifyStatusText)).toEqual(['未通知', '成功', '失败'])
    expect([0, 1, 2].map(getNotifyStatusType)).toEqual(['info', 'success', 'danger'])
    expect(getNotifyStatusText(undefined)).toBe('未通知')
  })
})

describe('订单详情 - 列表行和详情接口的数据合并', () => {
  it('详情接口没返回的字段（商户名、店铺名）保留列表行里的值', () => {
    const row = { orderNo: 'FP1', merchantName: '大熊测试商户', shopName: '总部旗舰店', status: 0 }
    const detail = { orderNo: 'FP1', merchantName: null, shopName: '', status: 1, clientIp: '1.2.3.4' }
    expect(mergeOrderDetail(row, detail)).toEqual({
      orderNo: 'FP1',
      merchantName: '大熊测试商户',
      shopName: '总部旗舰店',
      status: 1,
      clientIp: '1.2.3.4'
    })
  })

  it('详情接口挂了（返回 null）时退回列表行数据，不会把界面清空', () => {
    const row = { orderNo: 'FP1', amount: '9.90' }
    expect(mergeOrderDetail(row, null)).toEqual(row)
    expect(mergeOrderDetail(row, undefined)).toEqual(row)
    expect(mergeOrderDetail(null, null)).toEqual({})
  })

  it('状态 0 这类合法的零值要能覆盖旧值', () => {
    expect(mergeOrderDetail({ status: 1, notifyCount: 5 }, { status: 0, notifyCount: 0 }))
      .toEqual({ status: 0, notifyCount: 0 })
  })
})

describe('订单详情 - 窗口宽度自适应', () => {
  it('手机/窄窗口：一列 + 上下排布 + 百分比宽度，不会横向溢出', () => {
    const layout = resolveDetailLayout(375)
    expect(layout.column).toBe(1)
    expect(layout.direction).toBe('vertical')
    expect(layout.dialogWidth).toMatch(/%$/)
  })

  it('小笔记本：两列，弹窗宽度按百分比走', () => {
    const layout = resolveDetailLayout(1024)
    expect(layout.column).toBe(2)
    expect(layout.direction).toBe('horizontal')
    expect(layout.dialogWidth).toMatch(/%$/)
  })

  it('大屏：两列，弹窗固定宽度不会拉得过宽', () => {
    const layout = resolveDetailLayout(1920)
    expect(layout.column).toBe(2)
    expect(layout.dialogWidth).toBe('880px')
  })
})

describe('订单详情 - 字段清单（防止漏字段）', () => {
  const sections = buildOrderDetailSections(fullOrder, { showMerchant: true })

  it('分成四组展示，每组都有标题和内容', () => {
    expect(sections.map(s => s.key)).toEqual(['base', 'payment', 'notify', 'trace'])
    sections.forEach(section => {
      expect(section.title).toBeTruthy()
      expect(section.items.length).toBeGreaterThan(0)
    })
  })

  it('后端返回的业务字段一个都不能漏', () => {
    expect(collectDetailKeys(sections).sort()).toEqual([
      'amount',
      'clientIp',
      'createTime',
      'expireTime',
      'extParam',
      'lastNotifyTime',
      'merchantName',
      'notifyStatus',
      'notifyUrl',
      'outTradeNo',
      'orderNo',
      'payAmount',
      'payMethod',
      'payTime',
      'payType',
      'returnUrl',
      'shopName',
      'status',
      'subject',
      'updateTime'
    ].sort())
  })

  it('商户中心不展示"所属商户"，其余字段和管理后台一致', () => {
    const merchantSections = buildOrderDetailSections(fullOrder, { showMerchant: false })
    const adminKeys = collectDetailKeys(sections)
    const merchantKeys = collectDetailKeys(merchantSections)
    expect(merchantKeys).not.toContain('merchantName')
    expect(adminKeys.filter(k => k !== 'merchantName')).toEqual(merchantKeys)
  })

  it('长内容（订单号、商户单号、回调地址、跳转地址、扩展参数）标记为可复制', () => {
    const copyable = collectDetailKeys(sections.map(s => ({
      ...s,
      items: s.items.filter(i => i.copyable)
    })))
    expect(copyable.sort()).toEqual(
      ['orderNo', 'outTradeNo', 'notifyUrl', 'returnUrl', 'extParam'].sort()
    )
  })

  it('长内容占满整行，避免被挤成一小格', () => {
    const items = sections.flatMap(s => s.items)
    const fullWidth = items.filter(i => i.span === 2).map(i => i.key)
    expect(fullWidth.sort()).toEqual(['subject', 'notifyUrl', 'returnUrl', 'extParam'].sort())
  })

  it('值都算好成可以直接渲染的文本', () => {
    const items = sections.flatMap(s => s.items)
    const byKey = Object.fromEntries(items.map(i => [i.key, i]))
    expect(byKey.orderNo.value).toBe(fullOrder.orderNo)
    expect(byKey.orderNo.copyValue).toBe(fullOrder.orderNo)
    expect(byKey.amount.value).toBe('¥199.00')
    expect(byKey.payType.value).toBe('微信支付')
    expect(byKey.payMethod.value).toBe('接口调用')
    expect(byKey.status.value).toBe('已支付')
    expect(byKey.status.tagType).toBe('success')
    expect(byKey.notifyStatus.value).toBe('失败')
    expect(byKey.notifyStatus.tagType).toBe('danger')
    expect(byKey.notifyStatus.suffix).toBe('已通知 3 次')
    expect(byKey.createTime.value).toBe('2026-08-19 10:30:00')
    expect(byKey.notifyUrl.value).toBe(fullOrder.notifyUrl)
  })

  it('字段为空的订单不会出现空白格，一律显示占位符', () => {
    const empty = buildOrderDetailSections({ orderNo: 'FP-EMPTY' }, { showMerchant: true })
    const items = empty.flatMap(s => s.items)
    items
      .filter(i => i.key !== 'orderNo')
      .forEach(item => {
        expect(item.value, `字段 ${item.key} 不该是空白`).not.toBe('')
        expect(item.value).not.toBeNull()
        expect(item.value).not.toBeUndefined()
      })
    const byKey = Object.fromEntries(items.map(i => [i.key, i]))
    expect(byKey.payAmount.value).toBe(EMPTY_TEXT)
    expect(byKey.notifyUrl.value).toBe(EMPTY_TEXT)
    expect(byKey.payTime.value).toBe(EMPTY_TEXT)
    expect(byKey.notifyStatus.suffix).toBe('')
  })

  it('传空订单也不报错', () => {
    expect(() => buildOrderDetailSections(null, { showMerchant: true })).not.toThrow()
    expect(() => buildOrderDetailSections(undefined)).not.toThrow()
  })
})
