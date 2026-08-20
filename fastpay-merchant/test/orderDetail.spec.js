import { describe, it, expect } from 'vitest'
import {
  formatDateTime,
  splitDateTime,
  formatAmount,
  formatPayType,
  formatPayMethod,
  formatOrderSource,
  formatExtParam,
  textOrDash,
  getStatusText,
  getStatusType,
  getNotifyStatusText,
  getNotifyStatusType,
  resolveNotifyFailure,
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
  merchantName: '示例测试商户',
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
  // 回调失败：后端只写 notifyError，notifyResult 会被置空（两者互斥）
  notifyResult: null,
  notifyError: 'SocketTimeoutException: Read timed out at java.net.SocketInputStream.socketRead0(Native Method) after 5000ms',
  lastNotifyTime: '2026-08-19T10:35:12',
  payTime: '2026-08-19T10:31:08',
  expireTime: '2026-08-19T10:45:00',
  clientIp: '203.0.113.42',
  orderSource: 'epay',
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

  it('订单来源显示成人话，未知来源不会显示成空白', () => {
    expect(formatOrderSource('native')).toBe('平台接口')
    expect(formatOrderSource('epay')).toBe('易支付协议')
    // 存量订单没这个字段时显示占位符，不是空白
    expect(formatOrderSource(null)).toBe(EMPTY_TEXT)
    expect(formatOrderSource(undefined)).toBe(EMPTY_TEXT)
    expect(formatOrderSource('')).toBe(EMPTY_TEXT)
    // 以后后端加了新来源，前端没跟上也要原样显示，不能吞掉
    expect(formatOrderSource('wechat_mini')).toBe('wechat_mini')
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

describe('订单详情 - 回调失败原因', () => {
  it('回调成功的订单不给失败原因，页面据此不占位置', () => {
    expect(resolveNotifyFailure({ notifyStatus: 1, notifyResult: 'success' })).toBeNull()
  })

  it('还没通知过的订单也不给失败原因', () => {
    expect(resolveNotifyFailure({ notifyStatus: 0 })).toBeNull()
    expect(resolveNotifyFailure({})).toBeNull()
    expect(resolveNotifyFailure(null)).toBeNull()
    expect(resolveNotifyFailure(undefined)).toBeNull()
  })

  it('请求根本没发成功时，显示报错信息并说明这是我们这边报的错', () => {
    const failure = resolveNotifyFailure({
      notifyStatus: 2,
      notifyResult: null,
      notifyError: 'ConnectException: Connection refused'
    })
    expect(failure.text).toBe('ConnectException: Connection refused')
    expect(failure.source).toBe('error')
    // 排查方向不一样，要让运营看得出这段文字是哪来的
    expect(failure.hint).toBe('发通知时报的错')
  })

  it('请求发出去了但对方没回 success 时，显示对方返回的原文', () => {
    const failure = resolveNotifyFailure({
      notifyStatus: 2,
      notifyResult: '{"code":500,"msg":"内部错误"}',
      notifyError: null
    })
    expect(failure.text).toBe('{"code":500,"msg":"内部错误"}')
    expect(failure.source).toBe('result')
    expect(failure.hint).toBe('对方返回的内容')
  })

  it('老订单失败了但没记原因时说清楚为什么是空的，不是甩一个横杠', () => {
    const failure = resolveNotifyFailure({ notifyStatus: 2 })
    expect(failure.text).toBe(EMPTY_TEXT)
    expect(failure.source).toBe('none')
    expect(failure.hint).toBeTruthy()
  })

  it('两个字段都有值时以报错为准（后端保证互斥，这里只是兜底不崩）', () => {
    const failure = resolveNotifyFailure({
      notifyStatus: 2,
      notifyResult: '对方返回的',
      notifyError: '报的错'
    })
    expect(failure.text).toBe('报的错')
    expect(failure.source).toBe('error')
  })
})

describe('订单详情 - 列表行和详情接口的数据合并', () => {
  it('详情接口没返回的字段（商户名、店铺名）保留列表行里的值', () => {
    const row = { orderNo: 'FP1', merchantName: '示例测试商户', shopName: '总部旗舰店', status: 0 }
    const detail = { orderNo: 'FP1', merchantName: null, shopName: '', status: 1, clientIp: '1.2.3.4' }
    expect(mergeOrderDetail(row, detail)).toEqual({
      orderNo: 'FP1',
      merchantName: '示例测试商户',
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
      'notifyFailReason',
      'notifyStatus',
      'notifyUrl',
      'outTradeNo',
      'orderNo',
      'orderSource',
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

  it('订单来源放在「时间与来源」那一组', () => {
    const trace = sections.find(s => s.key === 'trace')
    expect(trace.items.map(i => i.key)).toContain('orderSource')
  })

  it('失败原因放在「状态与回调」那一组，紧跟在回调状态后面', () => {
    const notify = sections.find(s => s.key === 'notify')
    const keys = notify.items.map(i => i.key)
    expect(keys).toContain('notifyFailReason')
    expect(keys.indexOf('notifyFailReason')).toBe(keys.indexOf('notifyStatus') + 1)
  })

  it('回调成功的订单里根本不出现失败原因这一项，不占位置', () => {
    const ok = buildOrderDetailSections(
      { ...fullOrder, notifyStatus: 1, notifyResult: 'success', notifyError: null },
      { showMerchant: false }
    )
    expect(collectDetailKeys(ok)).not.toContain('notifyFailReason')
    // 订单来源跟回调成不成功没关系，任何订单都要有
    expect(collectDetailKeys(ok)).toContain('orderSource')
  })

  it('商户中心不展示"所属商户"，其余字段和管理后台一致', () => {
    const merchantSections = buildOrderDetailSections(fullOrder, { showMerchant: false })
    const adminKeys = collectDetailKeys(sections)
    const merchantKeys = collectDetailKeys(merchantSections)
    expect(merchantKeys).not.toContain('merchantName')
    expect(adminKeys.filter(k => k !== 'merchantName')).toEqual(merchantKeys)
  })

  it('长内容（订单号、商户单号、回调地址、跳转地址、扩展参数、失败原因）标记为可复制', () => {
    const copyable = collectDetailKeys(sections.map(s => ({
      ...s,
      items: s.items.filter(i => i.copyable)
    })))
    expect(copyable.sort()).toEqual(
      ['orderNo', 'outTradeNo', 'notifyUrl', 'returnUrl', 'extParam', 'notifyFailReason'].sort()
    )
  })

  it('没记到原因的失败订单不给复制按钮，免得复制出一个横杠', () => {
    const legacy = buildOrderDetailSections(
      { ...fullOrder, notifyResult: null, notifyError: null },
      { showMerchant: false }
    )
    const item = legacy.flatMap(s => s.items).find(i => i.key === 'notifyFailReason')
    expect(item.value).toBe(EMPTY_TEXT)
    expect(item.copyable).toBe(false)
  })

  it('长内容占满整行，避免被挤成一小格', () => {
    const items = sections.flatMap(s => s.items)
    const fullWidth = items.filter(i => i.span === 2).map(i => i.key)
    expect(fullWidth.sort()).toEqual(
      ['subject', 'notifyUrl', 'returnUrl', 'extParam', 'notifyFailReason', 'orderSource'].sort()
    )
  })

  it('订单来源占整行，扩展参数才不会被挤成半格（长 JSON 要有地方展开）', () => {
    const trace = sections.find(s => s.key === 'trace')
    // 这一组里"占半格"的字段必须是偶数个，否则最后会剩半行把扩展参数挤扁
    const halfWidth = trace.items.filter(i => (i.span || 1) === 1)
    expect(halfWidth.length % 2).toBe(0)
    const byKey = Object.fromEntries(trace.items.map(i => [i.key, i]))
    expect(byKey.orderSource.span).toBe(2)
    expect(byKey.extParam.span).toBe(2)
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
    expect(byKey.orderSource.value).toBe('易支付协议')
    // 失败原因原文一个字都不能少，长内容也不许截断
    expect(byKey.notifyFailReason.value).toBe(fullOrder.notifyError)
    expect(byKey.notifyFailReason.copyValue).toBe(fullOrder.notifyError)
    expect(byKey.notifyFailReason.suffix).toBe('发通知时报的错')
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
