import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import PublicDocsPage from '@/views/docs/public.vue'
import {
  EPAY_BASE_URL,
  epayConfigFields,
  epayEndpoints,
  epayRequestParams,
  epaySignDiff,
  epayNotifyParams,
  epaySignExample,
  epayMapiResponse,
  epayNotifyRetry,
  epayIntro,
  epayFaq
} from '@/views/docs/epayDocData.js'

/**
 * 公开开发文档页（不用登录那个）—— 彩虹易支付章节
 *
 * 想接我们支付的人先看这一页再决定接不接，这一章不能藏着。
 * 测试盯的是：
 *
 * 1. 左侧目录多出「易支付协议对接」，位置和登录后那页一致（在「快速开始」后面）
 * 2. 章节内容是复用 epayDocData.js 的同一份数据，不是又复制了一份
 * 3. 原有九章内容一个字没动
 * 4. 手机上「对方后台填什么」那张表变成上下排的卡片，接口地址不被截断
 */

const originalWidth = window.innerWidth

function setWindowWidth(width) {
  Object.defineProperty(window, 'innerWidth', { value: width, writable: true, configurable: true })
  window.dispatchEvent(new Event('resize'))
}

// jsdom 没有实现 scrollIntoView，点导航时会炸，这里补一个空实现
beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
  setWindowWidth(1440)
})

afterEach(() => {
  setWindowWidth(originalWidth)
  vi.restoreAllMocks()
})

function mountDocs() {
  return mount(PublicDocsPage, { attachTo: document.body })
}

describe('公开文档页的易支付章节：目录入口', () => {
  it('左侧目录多出「易支付协议对接」，原有 9 个入口一个没少', () => {
    const wrapper = mountDocs()
    const items = wrapper.findAll('.docs-nav .el-menu-item')
    const labels = items.map((el) => el.text())

    // 原有九章原样保留
    for (const label of [
      '产品介绍', '快速开始', '页面跳转支付', 'API接口支付', '查询订单',
      '回调通知', 'WebSocket监听', '签名算法', '错误码'
    ]) {
      expect(labels.some((t) => t.includes(label))).toBe(true)
    }
    // 新增易支付入口
    expect(labels.some((t) => t.includes('易支付协议'))).toBe(true)
    wrapper.unmount()
  })

  it('目录里易支付排在「快速开始」后面，和登录后那页一致', () => {
    const wrapper = mountDocs()
    const labels = wrapper.findAll('.docs-nav .el-menu-item').map((el) => el.text())
    const quickStart = labels.findIndex((t) => t.includes('快速开始'))
    const epay = labels.findIndex((t) => t.includes('易支付协议'))
    expect(quickStart).toBeGreaterThanOrEqual(0)
    expect(epay).toBe(quickStart + 1)
    wrapper.unmount()
  })
})

describe('公开文档页的易支付章节：内容渲染', () => {
  it('页面上有 id="epay" 的章节，能被目录滚动定位到', () => {
    const wrapper = mountDocs()
    expect(wrapper.find('#epay').exists()).toBe(true)

    wrapper.vm.scrollToSection('epay')
    expect(wrapper.vm.activeSection).toBe('epay')
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('五张表都挂上了 epayDocData 里同一份数据，一行不多一行不少', () => {
    const wrapper = mountDocs()
    const section = wrapper.find('#epay')

    // el-table 在 jsdom 里算不出列宽，只渲染得出行、渲染不出单元格文字，
    // 所以对着数据源核行数；单元格里写了什么由 docsEpay.spec.js 盯着
    const rowCounts = section.findAll('.el-table').map(
      (t) => t.findAll('.el-table__row').length
    )
    expect(rowCounts).toEqual([
      epayConfigFields.length,   // 对方后台填什么
      epayEndpoints.length,      // 三个接口
      epayRequestParams.length,  // 下单参数
      epaySignDiff.length,       // 两套签名对比
      epayNotifyParams.length    // 回调参数
    ])
    wrapper.unmount()
  })

  it('正文里能读到接口根地址、submit.php 和「不用你填」的提醒', () => {
    const wrapper = mountDocs()
    const text = wrapper.find('#epay').text()

    expect(text).toContain(EPAY_BASE_URL)
    expect(text).toContain('/submit.php')
    expect(text).toContain('不用你填')
    // 下单返回示例里带着 code = 1 才算成功的提示
    expect(text).toContain('"code": 1')
    wrapper.unmount()
  })

  it('章节里把「两套签名不能混用」写成了醒目提示', () => {
    const wrapper = mountDocs()
    const section = wrapper.find('#epay')
    const alerts = section.findAll('.el-alert')
    expect(alerts.length).toBeGreaterThan(0)
    const loud = alerts.filter(
      (a) => a.classes().some((c) => /el-alert--(error|warning)/.test(c))
    )
    expect(loud.length).toBeGreaterThan(0)
    expect(loud.map((a) => a.text()).join('')).toMatch(/签名/)
    wrapper.unmount()
  })

  it('章节里带着可验证的签名示例和全部常见问题', () => {
    const wrapper = mountDocs()
    const text = wrapper.find('#epay').text()

    expect(text).toContain(epaySignExample.sign)
    expect(text).toContain('success')
    for (const item of epayFaq) {
      expect(text).toContain(item.q)
    }
    wrapper.unmount()
  })

  it('复用 epayDocData.js：公开页渲染出来就是同一份数据', () => {
    const wrapper = mountDocs()
    const text = wrapper.find('#epay').text()

    // 数据源里的关键内容一条条对得上，说明不是复制粘贴的第二份
    // （表格单元格在 jsdom 里渲染不出文字，所以挑代码块、提示框里的内容比）
    expect(text).toContain(epaySignExample.signStr)
    expect(text).toContain(epayMapiResponse)
    expect(text).toContain(epayNotifyRetry.text)
    expect(text).toContain(epayFaq[0].q)
    expect(text).toContain(epayIntro.note)
    wrapper.unmount()
  })
})

describe('公开文档页的易支付章节：原有九章一个字没动', () => {
  it('九个原生章节都还在，关键内容原样', () => {
    const wrapper = mountDocs()

    for (const id of [
      '#intro', '#quick-start', '#page-pay', '#api-pay',
      '#query-order', '#callback', '#websocket', '#sign', '#error-code'
    ]) {
      expect(wrapper.find(id).exists()).toBe(true)
    }

    // 原生签名章节还在，还写着 &key= 后缀 + 转大写
    const nativeSign = wrapper.find('#sign')
    expect(nativeSign.text()).toContain('&key=API_SECRET')
    expect(nativeSign.text()).toContain('转为大写')

    expect(wrapper.find('#page-pay').text()).toContain('/api/pay/submit')
    expect(wrapper.find('#api-pay').text()).toContain('/api/pay/create')
    wrapper.unmount()
  })
})

describe('公开文档页的易支付章节：手机宽度', () => {
  it('手机上「对方后台填什么」改成上下排的卡片，接口地址不会被截断', () => {
    setWindowWidth(390)
    const wrapper = mountDocs()
    const section = wrapper.find('#epay')

    // 窄屏不用表格 —— 表格得横着拖，商户抄地址容易抄漏
    expect(section.find('.epay-key-table').exists()).toBe(false)

    const cards = section.findAll('.epay-config-card')
    expect(cards.length).toBe(epayConfigFields.length)
    // 卡片里的内容和表格完全一样，一条都不少
    for (const item of epayConfigFields) {
      const card = cards.find((c) => c.text().includes(item.field))
      expect(card).toBeTruthy()
      expect(card.text()).toContain(item.value)
      expect(card.text()).toContain(item.tip)
    }
    // 最关键的接口根地址在窄屏下完整可见
    expect(section.text()).toContain(EPAY_BASE_URL)
    wrapper.unmount()
  })

  it('从手机宽度拉回大屏，表格会自己变回来', async () => {
    setWindowWidth(390)
    const wrapper = mountDocs()
    expect(wrapper.find('.epay-config-card').exists()).toBe(true)

    setWindowWidth(1440)
    await nextTick()
    expect(wrapper.find('.epay-config-card').exists()).toBe(false)
    expect(wrapper.find('.epay-key-table').exists()).toBe(true)
    wrapper.unmount()
  })
})
