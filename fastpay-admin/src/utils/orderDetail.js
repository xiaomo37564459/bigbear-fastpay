/**
 * Fast 易支付 - 订单展示相关的纯函数
 *
 * 这里只放"把订单数据算成可以直接渲染的文本"的逻辑，不依赖 Vue、不依赖 DOM，
 * 方便单元测试覆盖，也让管理后台和商户中心共用同一套口径。
 */

/** 空值统一显示成这个 */
export const EMPTY_TEXT = '-'

/** 订单状态文案 */
export const ORDER_STATUS_TEXT = { 0: '待支付', 1: '已支付', 2: '已过期', 3: '已关闭' }

/** 订单状态对应的标签颜色 */
export const ORDER_STATUS_TAG = { 0: 'warning', 1: 'success', 2: 'info', 3: 'danger' }

/** 回调通知状态文案 */
export const NOTIFY_STATUS_TEXT = { 0: '未通知', 1: '成功', 2: '失败' }

/** 回调通知状态对应的标签颜色 */
export const NOTIFY_STATUS_TAG = { 0: 'info', 1: 'success', 2: 'danger' }

/** 支付类型文案 */
export const PAY_TYPE_TEXT = { wxpay: '微信支付', alipay: '支付宝' }

/** 支付方式文案 */
export const PAY_METHOD_TEXT = { page: '页面跳转', api: '接口调用' }

/** 订单来源文案：这笔订单是打哪个入口进来的 */
export const ORDER_SOURCE_TEXT = { native: '平台接口', epay: '易支付协议' }

/** 回调通知状态里代表"失败"的那个值 */
export const NOTIFY_STATUS_FAILED = 2

export function getStatusText(status) {
  return ORDER_STATUS_TEXT[status] || '未知'
}

export function getStatusType(status) {
  return ORDER_STATUS_TAG[status] || 'info'
}

export function getNotifyStatusText(status) {
  return NOTIFY_STATUS_TEXT[status] || '未通知'
}

export function getNotifyStatusType(status) {
  return NOTIFY_STATUS_TAG[status] || 'info'
}

/** 判断是不是"没有值"，注意 0 和 false 都是有值的 */
function isBlank(value) {
  return value === null || value === undefined || value === ''
}

/** 空值显示占位符，其余转成字符串 */
export function textOrDash(value) {
  return isBlank(value) ? EMPTY_TEXT : String(value)
}

/**
 * 时间统一格式化成 yyyy-MM-dd HH:mm:ss
 * 没有时间时返回空串（由调用方决定显示占位符还是别的）
 */
export function formatDateTime(time) {
  if (isBlank(time)) return ''
  if (typeof time === 'string' && time.includes('-')) {
    return time.replace('T', ' ').substring(0, 19)
  }
  return String(time)
}

/** 时间为空时显示占位符 */
export function formatDateTimeOrDash(time) {
  const text = formatDateTime(time)
  return text === '' ? EMPTY_TEXT : text
}

/**
 * 把 yyyy-MM-dd HH:mm:ss 拆成日期和时刻两段。
 *
 * 列表里时间列只有 100px 宽，一行放不下完整时间戳会被截断成 "2026-08…"；
 * 拆成上下两行后，年月日时分秒一个不少，列宽还能省出一半留给别的列。
 * 没有时间时两段都返回空串，由调用方决定显示占位符。
 */
export function splitDateTime(value) {
  const text = formatDateTime(value)
  if (!text) return { date: '', time: '' }
  const [date, time = ''] = text.split(' ')
  return { date, time }
}

/**
 * 金额格式化：没有金额时显示占位符，而不是显示成 "¥-"
 * 0 元是合法金额，要正常显示
 */
export function formatAmount(amount) {
  if (isBlank(amount)) return EMPTY_TEXT
  const num = Number(amount)
  if (Number.isNaN(num)) return String(amount)
  return `¥${num.toFixed(2)}`
}

export function formatPayType(payType) {
  if (isBlank(payType)) return EMPTY_TEXT
  return PAY_TYPE_TEXT[payType] || String(payType)
}

export function formatPayMethod(payMethod) {
  if (isBlank(payMethod)) return EMPTY_TEXT
  return PAY_METHOD_TEXT[payMethod] || String(payMethod)
}

/**
 * 订单来源转成人话。
 * 存量订单可能没有这个字段，显示占位符；以后后端加了新来源前端没跟上，原样显示不吞掉。
 */
export function formatOrderSource(orderSource) {
  if (isBlank(orderSource)) return EMPTY_TEXT
  return ORDER_SOURCE_TEXT[orderSource] || String(orderSource)
}

/**
 * 回调失败时，把"为什么失败"算成一段能直接显示的文字。
 *
 * 后端在两种失败情形下写的字段不一样，而且互斥（写一个就把另一个置空）：
 * - 请求根本没发出去（超时、连不上）→ notifyError 记异常信息，notifyResult 为空
 * - 请求发出去了，但对方没回 success → notifyResult 记对方返回的原文，notifyError 为空
 * 这两种情况排查方向完全不同（一个找网络、一个找商户），所以除了内容本身，
 * 还要用 hint 告诉运营这段文字是哪来的。
 *
 * 回调没失败（成功 / 还没通知）时返回 null，调用方据此决定这一项根本不展示。
 */
export function resolveNotifyFailure(order) {
  const o = order || {}
  if (o.notifyStatus !== NOTIFY_STATUS_FAILED) return null
  if (!isBlank(o.notifyError)) {
    return { text: String(o.notifyError), source: 'error', hint: '发通知时报的错' }
  }
  if (!isBlank(o.notifyResult)) {
    return { text: String(o.notifyResult), source: 'result', hint: '对方返回的内容' }
  }
  // 老订单：失败发生在"记录失败原因"这个功能上线之前，没得可显示
  return { text: EMPTY_TEXT, source: 'none', hint: '这笔单失败的时候还没开始记原因' }
}

/** 扩展参数是 JSON 就展开成多行，方便看；不是 JSON 就原样显示 */
export function formatExtParam(extParam) {
  if (isBlank(extParam)) return EMPTY_TEXT
  if (typeof extParam !== 'string') {
    try {
      return JSON.stringify(extParam, null, 2)
    } catch (error) {
      return String(extParam)
    }
  }
  try {
    const parsed = JSON.parse(extParam)
    if (parsed && typeof parsed === 'object') {
      return JSON.stringify(parsed, null, 2)
    }
  } catch (error) {
    // 不是 JSON，原样显示
  }
  return extParam
}

/**
 * 把详情接口返回的数据盖到列表行数据上。
 *
 * 详情接口 /order/{orderNo} 直接返回订单实体，商户名、店铺名这两个字段是空的
 * （它们只在列表接口里被补上），所以这里只用详情里"有值"的字段覆盖，避免把
 * 列表里已经拿到的信息冲掉。详情接口失败时退回列表行数据，界面不会变空。
 */
export function mergeOrderDetail(row, detail) {
  const base = { ...(row || {}) }
  if (!detail || typeof detail !== 'object') return base
  Object.keys(detail).forEach(key => {
    if (!isBlank(detail[key])) {
      base[key] = detail[key]
    }
  })
  return base
}

/**
 * 根据窗口宽度决定弹窗宽度和描述列表的排布方式。
 * 窄窗口改成一列 + 标签在上、内容在下，长地址才有地方展开。
 */
export function resolveDetailLayout(width) {
  if (width < 768) {
    return { column: 1, direction: 'vertical', dialogWidth: '94%' }
  }
  if (width < 1280) {
    return { column: 2, direction: 'horizontal', dialogWidth: '88%' }
  }
  return { column: 2, direction: 'horizontal', dialogWidth: '880px' }
}

/** 把分组里所有字段的 key 拉平成数组，测试和调试用 */
export function collectDetailKeys(sections) {
  return (sections || []).flatMap(section => (section.items || []).map(item => item.key))
}

/**
 * 生成订单详情的分组字段清单。
 *
 * 每个字段都算好了可以直接渲染的 value，组件只负责按 type 选择展示样式：
 * - text   普通文本
 * - amount 金额（带强调色）
 * - tag    彩色标签（订单状态、支付类型）
 * - notify 回调状态（标签 + 通知次数）
 * - code   长文本（等宽字体、自动换行、可复制，suffix 会作为说明显示在下面一行）
 *
 * @param {object} order      订单数据
 * @param {object} options    showMerchant - 是否展示"所属商户"（商户中心不需要）
 */
export function buildOrderDetailSections(order, options = {}) {
  const { showMerchant = false } = options
  const o = order || {}

  const baseItems = [
    { key: 'orderNo', label: '平台订单号', type: 'code', span: 1, value: textOrDash(o.orderNo), copyable: true, copyValue: o.orderNo || '' },
    { key: 'outTradeNo', label: '商户订单号', type: 'code', span: 1, value: textOrDash(o.outTradeNo), copyable: true, copyValue: o.outTradeNo || '' }
  ]
  if (showMerchant) {
    baseItems.push({ key: 'merchantName', label: '所属商户', type: 'text', span: 1, value: textOrDash(o.merchantName) })
  }
  baseItems.push({ key: 'shopName', label: '所属店铺', type: 'text', span: 1, value: textOrDash(o.shopName) })
  baseItems.push({ key: 'subject', label: '商品名称', type: 'text', span: 2, value: textOrDash(o.subject) })

  const notifyItems = [
    { key: 'status', label: '订单状态', type: 'tag', span: 1, value: getStatusText(o.status), tagType: getStatusType(o.status) },
    {
      key: 'notifyStatus',
      label: '回调状态',
      type: 'notify',
      span: 1,
      value: getNotifyStatusText(o.notifyStatus),
      tagType: getNotifyStatusType(o.notifyStatus),
      suffix: o.notifyCount > 0 ? `已通知 ${o.notifyCount} 次` : ''
    }
  ]
  // 失败原因紧跟在回调状态后面；回调成功或还没通知的订单不占这一行
  const failure = resolveNotifyFailure(o)
  if (failure) {
    notifyItems.push({
      key: 'notifyFailReason',
      label: '失败原因',
      type: 'code',
      span: 2,
      value: failure.text,
      suffix: failure.hint,
      // 没记到原因时别给复制按钮，免得复制出一个横杠
      copyable: failure.source !== 'none',
      copyValue: failure.source === 'none' ? '' : failure.text
    })
  }
  notifyItems.push(
    { key: 'notifyUrl', label: '回调地址', type: 'code', span: 2, value: textOrDash(o.notifyUrl), copyable: true, copyValue: o.notifyUrl || '' },
    { key: 'returnUrl', label: '跳转地址', type: 'code', span: 2, value: textOrDash(o.returnUrl), copyable: true, copyValue: o.returnUrl || '' }
  )

  return [
    {
      key: 'base',
      title: '基本信息',
      items: baseItems
    },
    {
      key: 'payment',
      title: '金额与支付',
      items: [
        { key: 'amount', label: '订单金额', type: 'amount', span: 1, value: formatAmount(o.amount) },
        { key: 'payAmount', label: '实付金额', type: 'amount', span: 1, value: formatAmount(o.payAmount) },
        { key: 'payType', label: '支付类型', type: 'tag', span: 1, value: formatPayType(o.payType), tagType: o.payType === 'wxpay' ? 'success' : 'primary' },
        { key: 'payMethod', label: '支付方式', type: 'text', span: 1, value: formatPayMethod(o.payMethod) }
      ]
    },
    {
      key: 'notify',
      title: '状态与回调',
      items: notifyItems
    },
    {
      key: 'trace',
      title: '时间与来源',
      items: [
        { key: 'createTime', label: '创建时间', type: 'text', span: 1, value: formatDateTimeOrDash(o.createTime) },
        { key: 'payTime', label: '支付时间', type: 'text', span: 1, value: formatDateTimeOrDash(o.payTime) },
        { key: 'expireTime', label: '过期时间', type: 'text', span: 1, value: formatDateTimeOrDash(o.expireTime) },
        { key: 'lastNotifyTime', label: '最后通知时间', type: 'text', span: 1, value: formatDateTimeOrDash(o.lastNotifyTime) },
        { key: 'updateTime', label: '更新时间', type: 'text', span: 1, value: formatDateTimeOrDash(o.updateTime) },
        { key: 'clientIp', label: '客户端IP', type: 'text', span: 1, value: textOrDash(o.clientIp) },
        // 占整行：这一组前面是 6 个半格字段（3 整行），订单来源若只占半格，
        // 后面的扩展参数就会被挤到同一行、被 el-descriptions 压成半格，长 JSON 没地方展开
        { key: 'orderSource', label: '订单来源', type: 'text', span: 2, value: formatOrderSource(o.orderSource) },
        { key: 'extParam', label: '扩展参数', type: 'code', span: 2, value: formatExtParam(o.extParam), copyable: true, copyValue: o.extParam || '' }
      ]
    }
  ]
}

/**
 * 复制文本到剪贴板。
 * 优先用浏览器原生剪贴板 API，非 https 环境下退回旧的 execCommand 方案。
 */
export async function copyToClipboard(text) {
  const value = text === null || text === undefined ? '' : String(text)
  if (!value) return false
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch (error) {
    // 继续走下面的兜底方案
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = value
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch (error) {
    return false
  }
}
