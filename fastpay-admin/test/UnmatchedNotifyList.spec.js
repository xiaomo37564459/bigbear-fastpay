import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import UnmatchedNotify from '@/views/unmatched/index.vue'

/**
 * 未匹配收款列表页
 *
 * 背景（MTM-170）：钱到账了、但按金额找不到对应的待支付订单（用户多付少付了一分钱、
 * 或者付款时订单已经超时关掉了），后端会把这笔记到 fp_unmatched_notify 表里。
 * 以前这种情况只写一行日志，没人翻日志就等于「收了钱没认单」，用户来投诉才发现。
 *
 * 这个页面就是让运营看见这些钱，并且能人工兜住：认到哪张单上、或者确认这笔不用管。
 * 这组测试盯的是：默认要先看待处理的、每条要看得出是谁的钱、两个动作按钮真的会调对接口。
 */

const getUnmatchedNotifyPage = vi.fn()
const handleUnmatchedNotify = vi.fn()
const ignoreUnmatchedNotify = vi.fn()
const getMerchantList = vi.fn()

vi.mock('@/api', () => ({
  getUnmatchedNotifyPage: (...args) => getUnmatchedNotifyPage(...args),
  handleUnmatchedNotify: (...args) => handleUnmatchedNotify(...args),
  ignoreUnmatchedNotify: (...args) => ignoreUnmatchedNotify(...args),
  getMerchantList: (...args) => getMerchantList(...args)
}))

/** 待处理：收到 99.9 元，没认到任何订单 */
const pendingRow = {
  id: 101,
  amount: 99.9,
  payType: 'wxpay',
  merchantId: 3,
  channelId: 7,
  rawMessage: '{"amount":"99.90","payType":"wxpay","channelId":7,"time":"2026-08-19 11:05:23"}',
  notifyTime: '2026-08-19T11:05:23',
  handleStatus: 0,
  handleRemark: null,
  handledOrderNo: null,
  handleTime: null,
  createTime: '2026-08-19T11:05:24'
}

/** 已经人工认到订单上的那条 */
const handledRow = {
  id: 100,
  amount: 10.01,
  payType: 'alipay',
  merchantId: 3,
  channelId: 8,
  rawMessage: '{"amount":"10.01","payType":"alipay","channelId":8}',
  notifyTime: '2026-08-19T10:02:00',
  handleStatus: 1,
  handleRemark: '客服核对后认到这一单',
  handledOrderNo: 'FP20260819100000000009',
  handleTime: '2026-08-19T10:20:00',
  createTime: '2026-08-19T10:02:01'
}

const merchants = [{ id: 3, merchantName: '大熊测试商户' }]

function mockPage(records, total = records.length) {
  getUnmatchedNotifyPage.mockResolvedValue({ code: 200, data: { records, total } })
}

function mountPage() {
  return mount(UnmatchedNotify, {
    global: {
      stubs: { Search: true, Refresh: true, Warning: true }
    }
  })
}

/** 按 data-test 找按钮（el-button 会把这个属性透传到真正的 button 上） */
function buttons(wrapper, key) {
  return wrapper.findAll(`button[data-test="${key}"]`)
}

/** 下拉框只能通过组件事件改值：真实交互要展开挂在 body 上的浮层，测试里够不着 */
function selectAt(wrapper, index) {
  return wrapper.findAllComponents({ name: 'ElSelect' })[index]
}

let wrapper = null

beforeEach(() => {
  vi.clearAllMocks()
  getMerchantList.mockResolvedValue({ code: 200, data: merchants })
  mockPage([pendingRow, handledRow])
})

afterEach(() => {
  if (wrapper) {
    wrapper.unmount()
    wrapper = null
  }
})

describe('未匹配收款列表 - 看得见这笔钱', () => {
  it('一进页面默认只看「待处理」的，运营不用自己挑', async () => {
    wrapper = mountPage()
    await flushPromises()

    expect(getUnmatchedNotifyPage).toHaveBeenCalledTimes(1)
    expect(getUnmatchedNotifyPage.mock.calls[0][0]).toMatchObject({
      current: 1,
      size: 10,
      handleStatus: 0
    })
  })

  it('每条都看得出：收了多少钱、走的哪个支付方式、哪个商户、什么时候到的', async () => {
    wrapper = mountPage()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('99.90')
    expect(text).toContain('微信')
    expect(text).toContain('大熊测试商户')
    expect(text).toContain('2026-08-19')
    expect(text).toContain('11:05:23')
    expect(text).toContain('待处理')
  })

  it('金额补齐两位小数：后端回来的 99.9 要显示成 99.90，别让人看岔', async () => {
    mockPage([{ ...pendingRow, amount: 100 }])
    wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('[data-test="notify-amount"]').text()).toContain('100.00')
  })

  it('商户还没加载出来或查不到时，写「商户#3」，不显示一个光秃秃的数字', async () => {
    getMerchantList.mockResolvedValue({ code: 200, data: [] })
    wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('商户#3')
  })

  it('已经处理过的那条显示处理结果，不再给动作按钮', async () => {
    mockPage([handledRow])
    wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('已处理')
    expect(wrapper.text()).toContain('FP20260819100000000009')
    expect(wrapper.text()).toContain('客服核对后认到这一单')
    expect(buttons(wrapper, 'action-handle')).toHaveLength(0)
    expect(buttons(wrapper, 'action-ignore')).toHaveLength(0)
  })

  it('一条都没有时说一句人话，而不是空白表格', async () => {
    mockPage([], 0)
    wrapper = mountPage()
    await flushPromises()

    const empty = wrapper.find('[data-test="unmatched-empty"]')
    expect(empty.exists()).toBe(true)
    expect(empty.text()).toContain('没有')
  })

  it('接口挂了要说清楚是没查出来，并给一个重试入口，不是假装没有数据', async () => {
    getUnmatchedNotifyPage.mockRejectedValue(new Error('服务 500'))
    wrapper = mountPage()
    await flushPromises()

    const error = wrapper.find('[data-test="unmatched-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('没查出来')

    mockPage([pendingRow])
    await error.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="unmatched-error"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('99.90')
  })
})

describe('未匹配收款列表 - 按处理状态筛选', () => {
  it('切成「已忽略」再点搜索，会带上新的状态从第一页重新查', async () => {
    wrapper = mountPage()
    await flushPromises()

    selectAt(wrapper, 0).vm.$emit('update:modelValue', 2)
    await flushPromises()

    await wrapper.find('button[data-test="do-search"]').trigger('click')
    await flushPromises()

    expect(getUnmatchedNotifyPage.mock.calls.at(-1)[0]).toMatchObject({
      current: 1,
      handleStatus: 2
    })
  })

  it('点重置回到默认的「待处理」，不会把筛选清成"全部"', async () => {
    wrapper = mountPage()
    await flushPromises()

    selectAt(wrapper, 0).vm.$emit('update:modelValue', 1)
    await flushPromises()

    await wrapper.find('button[data-test="do-reset"]').trigger('click')
    await flushPromises()

    expect(getUnmatchedNotifyPage.mock.calls.at(-1)[0]).toMatchObject({ handleStatus: 0 })
  })
})

describe('未匹配收款列表 - 人工兜底的两个动作', () => {
  it('「标记已处理」必须填订单号，不填就不发请求，并且当场说明白要填什么', async () => {
    wrapper = mountPage()
    await flushPromises()

    await buttons(wrapper, 'action-handle')[0].trigger('click')
    await flushPromises()

    await wrapper.find('button[data-test="handle-submit"]').trigger('click')
    await flushPromises()

    expect(handleUnmatchedNotify).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="handle-error"]').text()).toContain('订单号')
  })

  it('填好订单号和备注后，按后端约定的字段名提交，并刷新列表', async () => {
    handleUnmatchedNotify.mockResolvedValue({ code: 200 })
    wrapper = mountPage()
    await flushPromises()

    await buttons(wrapper, 'action-handle')[0].trigger('click')
    await flushPromises()

    await wrapper.find('input[data-test="handle-order-no"]').setValue('FP20260819110000000123')
    await wrapper.find('textarea[data-test="handle-remark"]').setValue('已跟用户核对')
    await wrapper.find('button[data-test="handle-submit"]').trigger('click')
    await flushPromises()

    expect(handleUnmatchedNotify).toHaveBeenCalledWith(101, {
      handledOrderNo: 'FP20260819110000000123',
      remark: '已跟用户核对'
    })
    // 处理完要重新拉一次列表，不然页面上还挂着那条已经处理掉的
    expect(getUnmatchedNotifyPage).toHaveBeenCalledTimes(2)
  })

  it('「忽略」必须写清为什么忽略，写了才发请求', async () => {
    ignoreUnmatchedNotify.mockResolvedValue({ code: 200 })
    wrapper = mountPage()
    await flushPromises()

    await buttons(wrapper, 'action-ignore')[0].trigger('click')
    await flushPromises()

    await wrapper.find('button[data-test="ignore-submit"]').trigger('click')
    await flushPromises()
    expect(ignoreUnmatchedNotify).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="ignore-error"]').text()).toContain('原因')

    await wrapper.find('textarea[data-test="ignore-remark"]').setValue('这是别人转账，不是平台的钱')
    await wrapper.find('button[data-test="ignore-submit"]').trigger('click')
    await flushPromises()

    expect(ignoreUnmatchedNotify).toHaveBeenCalledWith(101, { remark: '这是别人转账，不是平台的钱' })
    expect(getUnmatchedNotifyPage).toHaveBeenCalledTimes(2)
  })

  it('重新打开弹窗不会带着上一条填过的内容和报错', async () => {
    wrapper = mountPage()
    await flushPromises()

    await buttons(wrapper, 'action-handle')[0].trigger('click')
    await flushPromises()
    await wrapper.find('input[data-test="handle-order-no"]').setValue('FP-OLD')
    await wrapper.find('button[data-test="handle-cancel"]').trigger('click')
    await flushPromises()

    await buttons(wrapper, 'action-handle')[0].trigger('click')
    await flushPromises()

    expect(wrapper.find('input[data-test="handle-order-no"]').element.value).toBe('')
    expect(wrapper.find('[data-test="handle-error"]').exists()).toBe(false)
  })

  it('提交失败时弹窗不关，填过的内容还在，可以改完再交一次', async () => {
    handleUnmatchedNotify.mockRejectedValue(new Error('该通知已经处理过'))
    wrapper = mountPage()
    await flushPromises()

    await buttons(wrapper, 'action-handle')[0].trigger('click')
    await flushPromises()
    await wrapper.find('input[data-test="handle-order-no"]').setValue('FP-RETRY')
    await wrapper.find('button[data-test="handle-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('input[data-test="handle-order-no"]').element.value).toBe('FP-RETRY')
    // 失败了就不该刷新列表假装成功
    expect(getUnmatchedNotifyPage).toHaveBeenCalledTimes(1)
  })
})
