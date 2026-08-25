import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { resolve, dirname } from 'path'
import PayPage from '@/views/pay/index.vue'

/**
 * 收款页 - 必须按「实付金额」付款
 *
 * 背景（MTM-170）：两个人同时买同价商品会认错人，没付钱的白拿、付了钱的挂着。
 * 后端的修法是给每笔订单单独算一个不重复的实付金额（payAmount），在订单金额
 * （amount）上下微调几分钱，收到付款后按这个金额认单。
 *
 * 所以收款页上写的数字必须是 payAmount。要是还显示 amount，用户照着页面付款，
 * 付出去的钱又会撞到别人那笔订单上 —— 那个会赔钱的 bug 换个方式原样复发。
 * 这组测试盯的就是这件事，外加：金额永远两位小数、提示必须写清楚、能一键复制。
 */

const getPayPageData = vi.fn()
const getPayStatus = vi.fn()
const showToast = vi.fn()
const toDataURL = vi.fn()

vi.mock('@/api', () => ({
  getPayPageData: (...args) => getPayPageData(...args),
  getPayStatus: (...args) => getPayStatus(...args)
}))

vi.mock('vant', () => ({
  showToast: (...args) => showToast(...args)
}))

vi.mock('qrcode', () => ({
  default: { toDataURL: (...args) => toDataURL(...args) }
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { orderNo: 'FP20260819110000000001' } }),
  useRouter: () => ({ push: vi.fn(), back: vi.fn() })
}))

/** 待支付订单：商户报价 10 元，系统给这一单定的实付金额是 10.01 元 */
const unpaidOrder = {
  merchantNo: 'M20260819',
  orderNo: 'FP20260819110000000001',
  outTradeNo: 'SHOP-ORDER-0001',
  amount: 10,
  payAmount: 10.01,
  subject: 'sub2api 充值 10 元套餐',
  status: 0,
  payType: 'wxpay',
  shopName: '大熊测试店铺',
  qrcodeUrl: 'wxp://f2f0abc',
  returnUrl: ''
}

function mockPage(order) {
  getPayPageData.mockResolvedValue({ code: 200, data: { ...order } })
}

async function mountPage() {
  const wrapper = mount(PayPage)
  await flushPromises()
  return wrapper
}

let wrapper = null

beforeEach(() => {
  vi.clearAllMocks()
  toDataURL.mockResolvedValue('data:image/png;base64,QRCODE')
  mockPage(unpaidOrder)
  // 收款页会开长连接实时等支付结果，测试环境用一个空壳顶掉，别真去连服务器
  global.WebSocket = class {
    constructor() { this.readyState = 0 }
    send() {}
    close() {}
  }
})

afterEach(() => {
  if (wrapper) {
    wrapper.unmount()
    wrapper = null
  }
})

describe('收款页 - 显示的金额必须是实付金额', () => {
  it('页面正中的大金额显示 payAmount（10.01），不是订单金额 10.00', async () => {
    wrapper = await mountPage()

    expect(wrapper.find('[data-test="pay-amount"]').text()).toBe('10.01')
  })

  it('金额永远保留两位小数：后端传来 10 也要显示 10.00，不能显示成 10', async () => {
    // 接口回来的 JSON 数字 10.00 到了浏览器就是 10，直接渲染会变成「¥10」，
    // 用户不知道该付 10 块还是 10 块几毛，这一单就可能付错。
    mockPage({ ...unpaidOrder, amount: 10, payAmount: 10 })
    wrapper = await mountPage()

    expect(wrapper.find('[data-test="pay-amount"]').text()).toBe('10.00')
  })

  it('有一条醒目提示，把该付多少、付错了会怎样都说清楚', async () => {
    wrapper = await mountPage()

    const alert = wrapper.find('[data-test="amount-alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('请务必按 10.01 元付款')
    expect(alert.text()).toContain('多付或少付都不会自动到账')
    expect(alert.text()).toContain('联系客服')
  })

  it('实付金额和订单金额不一样时，把订单原价也写出来，用户看得明白差在哪', async () => {
    wrapper = await mountPage()

    const origin = wrapper.find('[data-test="amount-origin"]')
    expect(origin.exists()).toBe(true)
    expect(origin.text()).toContain('10.00')
  })

  it('两个金额本来就一样时，不再画蛇添足解释差额', async () => {
    mockPage({ ...unpaidOrder, amount: 10, payAmount: 10 })
    wrapper = await mountPage()

    expect(wrapper.find('[data-test="amount-origin"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="amount-alert"]').text()).toContain('请务必按 10.00 元付款')
  })

  it('后端老版本没返回 payAmount 时退回订单金额，不能显示空白或 undefined', async () => {
    const { payAmount, ...legacyOrder } = unpaidOrder
    mockPage(legacyOrder)
    wrapper = await mountPage()

    expect(wrapper.find('[data-test="pay-amount"]').text()).toBe('10.00')
    expect(wrapper.find('[data-test="amount-alert"]').text()).toContain('请务必按 10.00 元付款')
    expect(wrapper.text()).not.toContain('undefined')
    expect(wrapper.text()).not.toContain('NaN')
  })

  it('页面底部的付款须知也按这一单的实付金额写，不再是一句笼统的空话', async () => {
    wrapper = await mountPage()

    expect(wrapper.find('.pay-notice').text()).toContain('10.01')
  })

  it('二维码旁边说清楚：扫码带不进金额，得自己输 10.01', async () => {
    // 个人收款码协议不支持把金额编进二维码（issue 里需求方已确认做不到），
    // 用户扫完还得手输，所以必须在二维码边上再说一次要输多少。
    wrapper = await mountPage()

    const tip = wrapper.find('[data-test="scan-tip"]')
    expect(tip.exists()).toBe(true)
    expect(tip.text()).toContain('自己填')
    expect(tip.text()).toContain('10.01')
  })
})

describe('收款页 - 一键复制金额', () => {
  it('点「复制」复制的是实付金额 10.01，并给出复制成功的提示', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(global.navigator, 'clipboard', {
      value: { writeText },
      configurable: true
    })

    wrapper = await mountPage()
    await wrapper.find('[data-test="copy-amount"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('10.01')
    expect(showToast).toHaveBeenCalled()
    expect(JSON.stringify(showToast.mock.calls)).toContain('10.01')
  })

  it('浏览器不支持剪贴板时不报错、也不假装成功，提示用户自己照着输', async () => {
    Object.defineProperty(global.navigator, 'clipboard', {
      value: undefined,
      configurable: true
    })
    document.execCommand = vi.fn().mockReturnValue(false)

    wrapper = await mountPage()
    await wrapper.find('[data-test="copy-amount"]').trigger('click')
    await flushPromises()

    expect(JSON.stringify(showToast.mock.calls)).toContain('手动')
  })
})

describe('收款页 - 长连接地址不许写死某台机器', () => {
  // 背景（MTM-235）：这个文件里原来留着一行被注释掉的旧写法，把长连接地址写死成
  // 一台早就不用的机器。它虽然不执行，但下一个人看到很容易以为要改它、或者顺手
  // 把注释去掉，结果收款页就连到那台废机器上，扫码付完钱页面永远不跳转。
  // 这两条测试连注释一起扫，防止这种东西再被留下来。
  const PAY_PAGE_SOURCE = readFileSync(
    resolve(dirname(fileURLToPath(import.meta.url)), '../src/views/pay/index.vue'),
    'utf8'
  )
  // 匹配任意写死的 IPv4 地址（比如 192.0.2.1 这种）
  const HARDCODED_IP = /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/

  it('整个文件里连注释都不许出现写死的机器地址', () => {
    expect(PAY_PAGE_SOURCE).not.toMatch(HARDCODED_IP)
  })

  it('长连接地址取用户当前访问的域名，部署到哪台机器都能连上', () => {
    expect(PAY_PAGE_SOURCE).toContain('const host = window.location.host')
    expect(PAY_PAGE_SOURCE).toContain('`${protocol}//${host}/fastpay-server/ws/pay/')
  })
})

describe('收款页 - 支付成功后展示的金额', () => {
  it('成功页「支付金额」显示用户实际付的 10.01，同时保留订单金额 10.00', async () => {
    mockPage({ ...unpaidOrder, status: 1 })
    wrapper = await mountPage()

    expect(wrapper.find('[data-test="paid-amount"]').text()).toContain('10.01')
    expect(wrapper.find('[data-test="paid-origin-amount"]').text()).toContain('10.00')
  })

  it('点「我已支付」查到付款成功后，成功页显示的是后端回的实付金额', async () => {
    getPayStatus.mockResolvedValue({ code: 200, data: { status: 1, payAmount: 10.01 } })

    wrapper = await mountPage()
    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="paid-amount"]').text()).toContain('10.01')
  })
})
