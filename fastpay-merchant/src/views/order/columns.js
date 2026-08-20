/**
 * Fast 易支付 - 商户中心订单列表的列宽方案（单位 px）
 *
 * 这几个数字不是随手填的。整张表所有列加起来必须放得进商户中心内容区能给的宽度，
 * 否则表格会横向滚动，而右侧固定的「操作」列会浮在内容上方，把它左边的列盖掉半截
 * （线上就是这么坏的：「跳转地址」只看得见 "http"，「创建时间」整列看不到）。
 *
 * 商户中心内容区是定宽的，窗口再宽表格也不会变宽：
 *   .developer-content  max-width 1200，左右各 24 内边距  → 1152
 *   .dev-card .card-body            左右各 24 内边距      → 1104
 * 所以只要窗口不小于 1248，表格永远只有 1104 可用。这就是下面这些宽度的上限。
 *
 * orderNo / subject / returnUrl 用的是"最小宽度"：宽度有富余时由这三列吃掉，
 * 订单号和跳转地址能多显示几个字符。
 * 改列宽之前先看一眼 test/OrderList.spec.js 里那条总宽度约束。
 */
export const ORDER_COLUMN_WIDTHS = {
  // 平台订单号是 FP + 20 位数字，一行必须完整显示（商户要拿它去对账）；
  // 商户自己的单号放在同一格的第二行，两个单号都不再被截断
  orderNo: 190,
  // 商品名称一行、店铺一行。合并前店铺只有 100，"token.copliot.cloud" 只看得到 "token.cop…"
  subject: 150,
  amount: 84,
  // 下面三列装的是短标签（微信 / 已支付 / 成功）。宽度下限不是标签本身，
  // 是四个字的表头「支付类型」「回调状态」——再窄表头就折行了，比内容更早出问题。
  payType: 80,
  status: 76,
  notify: 90,
  // 跳转地址是商户自己填的回跳链接，长短不一，给到最小 150 再超出就省略并挂 title
  returnUrl: 150,
  createTime: 100,
  // 待支付的订单有「详情 / 确认 / 关闭」三个按钮，实测一行放下要 110，留一点余量免得折行
  action: 130
}

/** 内容区左右内边距 + 卡片左右内边距，页面宽度要先扣掉这些才轮到表格 */
const PAGE_CHROME_WIDTH = 24 * 2 + 24 * 2

/** 商户中心内容区定宽，表格实际能用的宽度 */
export const TABLE_WIDTH = 1200 - PAGE_CHROME_WIDTH

/** 所有列的最小总宽 */
export function totalColumnWidth(widths = ORDER_COLUMN_WIDTHS) {
  return Object.values(widths).reduce((sum, width) => sum + width, 0)
}

/**
 * 「操作」列要不要固定在右边。
 *
 * 固定列的做法是浮在表格内容上方，只要表格一横向滚动，它就会盖住左边的列
 * —— 这正是这个页面原来的毛病。窗口够宽、整张表放得下时不会滚动，固定列
 * 纯粹是个方便（按钮永远在同一个位置），没有副作用；窗口窄到放不下时就
 * 取消固定，让整张表老老实实一起横向滚动，宁可多拖一下也不要有列被盖住。
 *
 * @param {number} pageWidth 页面可用宽度（document.documentElement.clientWidth）
 */
export function shouldFixActionColumn(pageWidth) {
  const tableWidth = Math.min(pageWidth || 0, 1200) - PAGE_CHROME_WIDTH
  return tableWidth >= totalColumnWidth()
}
