import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createHash } from 'node:crypto'
import DocsPage from '@/views/docs/index.vue'
import {
  EPAY_BASE_URL,
  epayEndpoints,
  epayConfigFields,
  epayRequestParams,
  epaySignSteps,
  epaySignExample,
  epayNotifySignExample,
  epaySignDiff,
  epayNotifyParams,
  epayFaq,
  epayPhpCode,
  epayJavaCode,
  epayPythonCode
} from '@/views/docs/epayDocData.js'

/**
 * 商户开发文档页 —— 彩虹易支付章节
 *
 * 这一页是给商户自己看的：他要照着这一页，把 sub2api、发卡站、商城系统的
 * 支付配置填对。所以测试盯的不是「好不好看」，而是：
 *
 * 1. 该说的关键信息一条都不能少（接口地址、后台三个配置项、参数、回调、常见问题）
 * 2. 文档里给的签名示例必须是真的 —— 直接算一遍 MD5 对答案，
 *    防止以后有人改了示例串却忘了改哈希值，商户照抄反而对不上
 * 3. 两套签名的差异必须写得够醒目（混用是最容易踩的坑）
 * 4. 原有的原生接口文档一个字都不能被动到
 * 5. 手机上打开排版不能垮（商户经常拿手机看）
 */

const md5 = (str) => createHash('md5').update(str, 'utf8').digest('hex')

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
  return mount(DocsPage, { attachTo: document.body })
}

describe('易支付章节的数据：必须和后端实现对得上', () => {
  it('接口根地址填到 /fastpay-server 为止，不带具体的 .php 文件名', () => {
    expect(EPAY_BASE_URL).toBe('https://pay.copliot.cloud/fastpay-server')
    expect(EPAY_BASE_URL).not.toMatch(/\.php/)
    expect(EPAY_BASE_URL.endsWith('/')).toBe(false)
  })

  it('三个接口入口齐全，方法和后端一致', () => {
    const paths = epayEndpoints.map((e) => e.path)
    expect(paths).toContain('/submit.php')
    expect(paths).toContain('/mapi.php')
    expect(paths).toContain('/api.php?act=order')

    const byPath = Object.fromEntries(epayEndpoints.map((e) => [e.path, e]))
    // 后端 EpayGatewayController：submit.php 收 GET/POST，mapi.php 只收 POST
    expect(byPath['/submit.php'].method).toMatch(/GET/)
    expect(byPath['/submit.php'].method).toMatch(/POST/)
    expect(byPath['/mapi.php'].method).toBe('POST')
    expect(byPath['/api.php?act=order'].method).toMatch(/GET/)
  })

  it('第三方后台的三个配置项都说清楚了填什么', () => {
    expect(epayConfigFields).toHaveLength(3)
    const all = JSON.stringify(epayConfigFields)
    // 商户号填商户编号、密钥填 API Secret、地址填根地址
    expect(all).toMatch(/PID/)
    expect(all).toMatch(/商户编号/)
    expect(all).toMatch(/API Secret/)
    expect(all).toContain(EPAY_BASE_URL)
  })

  it('接口地址那一条必须写明「后面的 submit.php 不用自己填」', () => {
    const urlField = epayConfigFields.find((f) => f.value.includes(EPAY_BASE_URL))
    expect(urlField).toBeTruthy()
    expect(urlField.tip).toMatch(/submit\.php/)
    expect(urlField.tip).toMatch(/不用|不要|别/)
  })

  it('下单参数一个都不少，必填选填标清楚', () => {
    const names = epayRequestParams.map((p) => p.name)
    for (const key of [
      'pid', 'type', 'out_trade_no', 'name', 'money',
      'notify_url', 'return_url', 'sign', 'sign_type'
    ]) {
      expect(names).toContain(key)
    }

    const byName = Object.fromEntries(epayRequestParams.map((p) => [p.name, p]))
    // 后端 verifyAndBuildDto 里硬校验的五个：缺一个就直接报错
    for (const key of ['pid', 'type', 'out_trade_no', 'name', 'money']) {
      expect(byName[key].required).toBe(true)
    }
    // 这四个后端没强制要
    for (const key of ['notify_url', 'return_url', 'sign_type']) {
      expect(byName[key].required).toBe(false)
    }
    expect(byName.sign.required).toBe(true)
    // type 的取值必须写出来，不然商户不知道填什么
    expect(byName.type.desc).toMatch(/alipay/)
    expect(byName.type.desc).toMatch(/wxpay/)
  })

  it('签名步骤是易支付那一套：去掉 sign/sign_type 和空值、末尾直接拼密钥、转小写', () => {
    expect(epaySignSteps).toHaveLength(5)
    const all = epaySignSteps.map((s) => `${s.title}${s.desc}`).join('')
    expect(all).toMatch(/sign_type/)
    expect(all).toMatch(/ASCII/)
    expect(all).toMatch(/小写/)
    // 关键差异：不是 &key=
    expect(all).toMatch(/不是.*&key=|&key=.*不是/)
  })

  it('下单签名示例是真的：照着串算一遍 MD5，结果要和文档上写的一致', () => {
    expect(md5(epaySignExample.signStr)).toBe(epaySignExample.sign)
    // 结果必须是小写 32 位
    expect(epaySignExample.sign).toMatch(/^[0-9a-f]{32}$/)
    // 示例串末尾直接跟着密钥，中间没有 &key=
    expect(epaySignExample.signStr.endsWith(epaySignExample.apiSecret)).toBe(true)
    expect(epaySignExample.signStr).not.toContain('&key=')
    // 参数确实按 ASCII 升序排过
    const keys = epaySignExample.signStr
      .slice(0, -epaySignExample.apiSecret.length)
      .split('&')
      .map((kv) => kv.split('=')[0])
    expect(keys).toEqual([...keys].sort())
    // sign / sign_type 不参与计算
    expect(keys).not.toContain('sign')
    expect(keys).not.toContain('sign_type')
  })

  it('回调签名示例也是真的，能照着自己验一遍', () => {
    expect(md5(epayNotifySignExample.signStr)).toBe(epayNotifySignExample.sign)
    expect(epayNotifySignExample.signStr).toContain('trade_status=TRADE_SUCCESS')
  })

  it('两套签名的差异逐条列出来了', () => {
    expect(epaySignDiff.length).toBeGreaterThanOrEqual(3)
    const all = JSON.stringify(epaySignDiff)
    expect(all).toMatch(/大写/)
    expect(all).toMatch(/小写/)
    expect(all).toMatch(/&key=/)
  })

  it('回调参数齐全，trade_status 的固定值写出来了', () => {
    const names = epayNotifyParams.map((p) => p.name)
    for (const key of [
      'pid', 'trade_no', 'out_trade_no', 'type',
      'name', 'money', 'trade_status', 'sign', 'sign_type'
    ]) {
      expect(names).toContain(key)
    }
    const status = epayNotifyParams.find((p) => p.name === 'trade_status')
    expect(status.desc).toContain('TRADE_SUCCESS')

    // money 的口径要说清楚：回传的是下单时提交的订单金额
    const money = epayNotifyParams.find((p) => p.name === 'money')
    expect(money.desc).toMatch(/下单|订单金额/)
  })

  it('常见问题覆盖了四个最容易踩的坑', () => {
    const all = epayFaq.map((f) => `${f.q}${f.a}`).join('')
    expect(epayFaq.length).toBeGreaterThanOrEqual(4)
    expect(all).toMatch(/签名/)          // 签名报错
    expect(all).toMatch(/submit\.php/)   // 接口地址填错
    expect(all).toMatch(/金额/)          // 付款金额对不上
    expect(all).toMatch(/退款/)          // 不支持退款
  })

  it('三种语言的示例代码都在，而且都是易支付那一套签名', () => {
    for (const code of [epayPhpCode, epayJavaCode, epayPythonCode]) {
      expect(code.length).toBeGreaterThan(200)
      // 易支付是转小写，绝不能出现原生那套的 &key= 或转大写
      expect(code).not.toContain('&key=')
      expect(code).not.toMatch(/strtoupper|toUpperCase\(\)|\.upper\(\)/)
    }
    expect(epayPhpCode).toMatch(/ksort/)
    expect(epayPhpCode).toMatch(/md5/)
    expect(epayJavaCode).toMatch(/TreeMap/)
    expect(epayPythonCode).toMatch(/hashlib/)
    // 三段代码都要排除 sign / sign_type
    for (const code of [epayPhpCode, epayJavaCode, epayPythonCode]) {
      expect(code).toMatch(/sign_type/)
    }
  })
})

describe('易支付章节渲染到页面上', () => {
  it('左侧导航多了一个易支付入口，原有 8 个入口一个没少', () => {
    const wrapper = mountDocs()
    const indexes = wrapper.findAll('.doc-nav .el-menu-item').map((el) => el.text())

    // 原生接口的 8 个入口原样保留
    for (const label of [
      '快速开始', '页面跳转支付', 'API接口支付', '查询订单',
      '回调通知', 'WebSocket监听', '签名算法', '错误码'
    ]) {
      expect(indexes.some((t) => t.includes(label))).toBe(true)
    }
    // 新增易支付入口
    expect(indexes.some((t) => t.includes('易支付协议'))).toBe(true)
    wrapper.unmount()
  })

  it('页面上有 id="epay" 的章节，能被导航滚动定位到', () => {
    const wrapper = mountDocs()
    expect(wrapper.find('#epay').exists()).toBe(true)

    wrapper.vm.scrollToSection('epay')
    expect(wrapper.vm.activeSection).toBe('epay')
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('五张表都挂上了对应的数据，一行不多一行不少', () => {
    const wrapper = mountDocs()
    const section = wrapper.find('#epay')

    // el-table 在 jsdom 里算不出列宽，只渲染得出行、渲染不出单元格文字，
    // 所以这里对着数据源核行数；单元格里写了什么由上面「数据」那一组测试盯着
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

  it('正文里（不靠表格）也能读到接口根地址和最容易填错的那一条', () => {
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
    // 用 el-alert 承载，且是 error / warning 级别，不能是不起眼的 info
    const alerts = section.findAll('.el-alert')
    expect(alerts.length).toBeGreaterThan(0)
    const loud = alerts.filter(
      (a) => a.classes().some((c) => /el-alert--(error|warning)/.test(c))
    )
    expect(loud.length).toBeGreaterThan(0)
    expect(loud.map((a) => a.text()).join('')).toMatch(/签名/)
    wrapper.unmount()
  })

  it('章节里带着可验证的签名示例和常见问题', () => {
    const wrapper = mountDocs()
    const text = wrapper.find('#epay').text()

    expect(text).toContain(epaySignExample.sign)
    expect(text).toContain('success')
    for (const item of epayFaq) {
      expect(text).toContain(item.q)
    }
    wrapper.unmount()
  })

  it('原生接口那几段文档一个字没改', () => {
    const wrapper = mountDocs()

    // 原生签名章节还在，还写着 &key= 后缀 + 转大写
    const nativeSign = wrapper.find('#sign')
    expect(nativeSign.exists()).toBe(true)
    expect(nativeSign.text()).toContain('&key=API_SECRET')
    expect(nativeSign.text()).toContain('转为大写')

    // 原生的几个章节都还在
    for (const id of [
      '#quick-start', '#page-pay', '#api-pay',
      '#query-order', '#callback', '#websocket', '#error-code'
    ]) {
      expect(wrapper.find(id).exists()).toBe(true)
    }
    expect(wrapper.find('#page-pay').text()).toContain('/api/pay/submit')
    expect(wrapper.find('#api-pay').text()).toContain('/api/pay/create')
    wrapper.unmount()
  })

  it('手机上打开不垮：左右两栏在窄屏各占满一行', () => {
    const wrapper = mountDocs()
    const cols = wrapper.findAll('.docs-page > .el-row > .el-col')
    expect(cols.length).toBe(2)
    // Element Plus 的 xs 断点（<768px）下两栏都占满 24 格
    for (const col of cols) {
      expect(col.classes()).toContain('el-col-xs-24')
    }
    wrapper.unmount()
  })

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
