/**
 * Fast 易支付 - 订单列表的列宽方案（单位 px）
 *
 * 这几个数字不是随手填的。整张表所有列加起来必须放得进 1280 宽窗口里的表格区域，
 * 否则表格会横向滚动，而右侧固定的「操作」列会浮在内容上方，把「创建时间」盖掉半截
 * （线上就是这么坏的：只看得见 "2026-08…"）。
 *
 * 1280 窗口下表格能用的宽度：
 *   1280 - 220(侧边栏) - 40(内容区左右内边距) - 40(卡片左右内边距) - 6(纵向滚动条) = 974
 *
 * orderNo / merchant 用的是"最小宽度"：窗口更宽的时候，多出来的空间由这两列吃掉。
 * 改列宽之前先看一眼 test/OrderList.spec.js 里那条总宽度约束。
 */
export const ORDER_COLUMN_WIDTHS = {
  // 平台订单号是 FP + 20 位数字，一行必须完整显示（管理员要拿它去对账），所以给的宽度最足
  orderNo: 178,
  merchant: 104,
  amount: 86,
  // 下面三列装的是短标签（微信 / 已支付 / 成功）。宽度下限不是标签本身，
  // 是四个字的表头「支付类型」「回调状态」——再窄表头就折行了，比内容更早出问题。
  payType: 80,
  status: 76,
  notify: 82,
  createTime: 100,
  payTime: 100,
  // 待支付的订单有「详情 / 确认支付 / 关闭」三个按钮，实测一行放下要 150，留一点余量免得折行
  action: 160
}

/** 1280 宽窗口下，表格实际能用的宽度 */
export const TABLE_WIDTH_AT_1280 = 1280 - 220 - 40 - 40 - 6

/** 所有列的最小总宽 */
export function totalColumnWidth(widths = ORDER_COLUMN_WIDTHS) {
  return Object.values(widths).reduce((sum, width) => sum + width, 0)
}
