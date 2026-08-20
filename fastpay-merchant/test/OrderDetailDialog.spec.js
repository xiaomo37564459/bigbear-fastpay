import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderDetailDialog from '@/views/order/OrderDetailDialog.vue'

const row = {
  orderNo: 'FP20260819103000123456789012',
  outTradeNo: 'SHOP-ORDER-20260819-000000000001',
  merchantName: '示例测试商户',
  shopName: '总部旗舰店',
  subject: '年度会员套餐',
  amount: '199.00',
  payType: 'wxpay',
  status: 1,
  notifyStatus: 2,
  notifyCount: 3,
  createTime: '2026-08-19T10:30:00'
}

const detail = {
  orderNo: row.orderNo,
  merchantName: null,
  shopName: null,
  payAmount: '199.00',
  payMethod: 'api',
  notifyUrl: 'https://shop.example.com/api/payment/fastpay/notify?traceId=abcdef0123456789',
  returnUrl: 'https://shop.example.com/pay/result?orderNo=SHOP-ORDER-20260819-000000000001',
  clientIp: '203.0.113.42',
  extParam: '{"uid":8899}',
  payTime: '2026-08-19T10:31:08',
  expireTime: '2026-08-19T10:45:00',
  lastNotifyTime: '2026-08-19T10:35:12',
  updateTime: '2026-08-19T10:35:12'
}

function mountDialog(props = {}) {
  return mount(OrderDetailDialog, {
    props: {
      modelValue: true,
      row,
      showMerchant: false,
      ...props
    }
  })
}

const originalWidth = window.innerWidth

function setWindowWidth(width) {
  Object.defineProperty(window, 'innerWidth', { value: width, writable: true, configurable: true })
  window.dispatchEvent(new Event('resize'))
}

beforeEach(() => {
  setWindowWidth(1440)
})

afterEach(() => {
  setWindowWidth(originalWidth)
  vi.restoreAllMocks()
})

describe('订单详情弹窗', () => {
  it('打开时先用列表数据兜底，再用详情接口补齐缺的字段', async () => {
    const loader = vi.fn().mockResolvedValue({ code: 200, data: detail })
    const wrapper = mountDialog({ loader })
    await flushPromises()

    expect(loader).toHaveBeenCalledWith(row.orderNo)
    const text = wrapper.text()
    // 列表行独有的字段没被详情接口的 null 冲掉
    expect(text).toContain('总部旗舰店')
    // 商户中心只看自己的订单，不展示"所属商户"
    expect(text).not.toContain('所属商户')
    // 详情接口补上来的字段显示出来了
    expect(text).toContain('203.0.113.42')
    expect(text).toContain('接口调用')
    expect(text).toContain(detail.notifyUrl)
    wrapper.unmount()
  })

  it('详情接口出错时给出提示，同时保留列表里已有的信息，不白屏', async () => {
    const loader = vi.fn().mockRejectedValue(new Error('网络错误'))
    const wrapper = mountDialog({ loader })
    await flushPromises()

    expect(wrapper.find('.fp-order-detail-error').exists()).toBe(true)
    expect(wrapper.text()).toContain(row.orderNo)
    wrapper.unmount()
  })

  it('加载中有明确的加载提示，不是一片空白', async () => {
    let resolveLoader
    const loader = vi.fn(() => new Promise(resolve => { resolveLoader = resolve }))
    const wrapper = mountDialog({ loader, row: { orderNo: row.orderNo } })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.fp-order-detail-loading').exists()).toBe(true)

    resolveLoader({ code: 200, data: detail })
    await flushPromises()
    expect(wrapper.find('.fp-order-detail-loading').exists()).toBe(false)
    wrapper.unmount()
  })

  it('没有订单数据时显示"没查到"，不是空弹窗', async () => {
    const wrapper = mountDialog({ row: null, loader: null })
    await flushPromises()

    expect(wrapper.find('.fp-order-detail-empty').exists()).toBe(true)
    wrapper.unmount()
  })

  it('没传 loader 时直接用列表行数据渲染', async () => {
    const wrapper = mountDialog({ loader: null })
    await flushPromises()

    expect(wrapper.text()).toContain(row.orderNo)
    expect(wrapper.find('.fp-order-detail-empty').exists()).toBe(false)
    wrapper.unmount()
  })

  it('四组信息都渲染出来了', async () => {
    const wrapper = mountDialog({ loader: null })
    await flushPromises()

    expect(wrapper.findAll('.fp-order-detail-section')).toHaveLength(4)
    wrapper.unmount()
  })

  it('长内容有复制按钮，点一下能复制全文', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true
    })

    const wrapper = mountDialog({ loader: null })
    await flushPromises()

    const copyBtn = wrapper.find('[data-copy-key="orderNo"]')
    expect(copyBtn.exists()).toBe(true)
    await copyBtn.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(row.orderNo)
    wrapper.unmount()
  })

  it('窗口变窄时自动改成一列上下排布，内容不会被挤没', async () => {
    const wrapper = mountDialog({ loader: null })
    await flushPromises()
    expect(wrapper.vm.layout.column).toBe(2)

    setWindowWidth(420)
    await wrapper.vm.$nextTick()
    expect(wrapper.vm.layout.column).toBe(1)
    expect(wrapper.vm.layout.direction).toBe('vertical')
    wrapper.unmount()
  })

  it('关掉弹窗会通知父组件', async () => {
    const wrapper = mountDialog({ loader: null })
    await flushPromises()

    await wrapper.find('.fp-order-detail-close').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('每次重新打开都会重新拉一次详情，不会显示上一笔订单的残留', async () => {
    const loader = vi.fn().mockResolvedValue({ code: 200, data: detail })
    const wrapper = mountDialog({ loader, modelValue: false })
    await flushPromises()
    expect(loader).not.toHaveBeenCalled()

    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(loader).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true, row: { ...row, orderNo: 'FP-OTHER' } })
    await flushPromises()
    expect(loader).toHaveBeenCalledTimes(2)
    expect(loader).toHaveBeenLastCalledWith('FP-OTHER')
    wrapper.unmount()
  })
})

describe('订单详情弹窗 - 订单来源与回调失败原因', () => {
  /** 一笔易支付进来、回调超时失败的订单 */
  const epayTimeout = {
    ...row,
    orderSource: 'epay',
    notifyStatus: 2,
    notifyResult: null,
    notifyError: 'SocketTimeoutException: Read timed out'
  }

  it('两项都带标签渲染出来，标签不能是空白格', async () => {
    const wrapper = mountDialog({ loader: null, row: epayTimeout })
    await flushPromises()

    const labels = wrapper.findAll('.el-descriptions__label').map(n => n.text())
    // 标签必须真的画出来，否则页面上是一个没有名字的格子
    expect(labels).toContain('订单来源')
    expect(labels).toContain('失败原因')
    // 顺带守住原有的长内容字段，别被新字段的排版挤掉标签
    expect(labels).toContain('扩展参数')

    const text = wrapper.text()
    expect(text).toContain('易支付协议')
    expect(text).toContain('SocketTimeoutException: Read timed out')
    expect(text).toContain('发通知时报的错')
    wrapper.unmount()
  })

  it('原生接口进来、对方回了非 success 时，显示对方返回的原文', async () => {
    const wrapper = mountDialog({
      loader: null,
      row: { ...row, orderSource: 'native', notifyStatus: 2, notifyResult: '{"code":500}', notifyError: null }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('平台接口')
    expect(wrapper.text()).toContain('{"code":500}')
    expect(wrapper.text()).toContain('对方返回的内容')
    wrapper.unmount()
  })

  it('回调成功的订单不显示失败原因这一行，但订单来源照常显示', async () => {
    const wrapper = mountDialog({
      loader: null,
      row: { ...row, orderSource: 'native', notifyStatus: 1, notifyResult: 'success', notifyError: null }
    })
    await flushPromises()

    const labels = wrapper.findAll('.el-descriptions__label').map(n => n.text())
    expect(labels).not.toContain('失败原因')
    expect(labels).toContain('订单来源')
    wrapper.unmount()
  })

  it('失败原因很长时能整段看到，不被截断成省略号', async () => {
    const longError = 'ConnectException: ' + 'x'.repeat(400)
    const wrapper = mountDialog({
      loader: null,
      row: { ...epayTimeout, notifyError: longError }
    })
    await flushPromises()

    expect(wrapper.text()).toContain(longError)
    wrapper.unmount()
  })
})
