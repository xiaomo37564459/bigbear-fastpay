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
 * 下面每个基准宽都是拿 Chromium 实测的"内容天然要多宽"定出来的，
 * 换算关系是：列宽 = 文字实际宽度 + 24（左右各 10px 单元格内边距 + 表格自身占的 4px）。
 * 改列宽之前先看一眼 test/OrderList.spec.js 里那几条约束。
 */
export const ORDER_COLUMN_WIDTHS = {
  // 平台订单号是 FP + 20 位数字，一行必须完整显示（管理员要拿它去对账）。
  // 实测这串字在 12px 等宽字体下要 163px，所以基准宽是 163 + 24 = 187，一格都不能再少。
  orderNo: 187,
  // 商户名长度没有上限，1280 下怎么都放不全，只能截断 + 悬停看全名。
  // 这里给的是"最低保障"，窗口一宽就会自动变宽，见 resolveColumnWidths()。
  merchant: 103,
  // 「¥12800.00」实测要 63px。原来给 86（文字只有 62px）差 1px，
  // 万元级的金额会折成 "¥12800.0" / "0" 两行，整行跟着变高。88 能放下万元级。
  // 再大的金额（七位数以上）仍然会折行——这是故意的：宁可换行也不能把金额截掉半截。
  amount: 88,
  // 下面三列装的是短标签（微信 / 已支付 / 成功）。宽度下限不是标签本身，
  // 是四个字的表头「支付类型」「回调状态」——实测 56px，再窄表头就折行了，比内容更早出问题。
  payType: 80,
  status: 76,
  notify: 80,
  createTime: 100,
  payTime: 100,
  // 待支付的订单有「详情 / 确认支付 / 关闭」三个按钮，实测一行排下来 138px，160 留了余量免得折行
  action: 160
}

/**
 * 两列"越宽越好"的列各自的封顶宽度。到了这个宽度，这一列的内容就全能一行显示完了，
 * 再宽只是浪费——原来的毛病就出在这儿：订单号列吃掉了大部分富余宽度，
 * 右边空一大片，商户名却还在截断。
 */
export const ORDER_COLUMN_CAPS = {
  // 副行「商户单号 SHOP-ORDER-20260819-000001」实测 231px，231 + 24 = 255
  orderNo: 255,
  // 16 个汉字的公司全名（如「广州天河智汇网络科技有限责任公司」）实测 224px，224 + 24 = 248
  merchant: 248
}

/**
 * 富余宽度的发放顺序。谁排前面谁先吃饱，每一档都写清楚吃饱了能看到什么。
 *
 * 为什么商户名排在订单号前面：基准宽 187 已经保证了平台订单号（管理员对账用的那串）
 * 一行显示完整，这条硬要求在最窄的 1280 下就已经满足了；订单号列再宽只是让副行的
 * 「商户单号」也能一行放下——那是次要信息，而且商户单号长度没有上限，本来就得靠截断
 * 加悬停。所以先给商户名补到"常见公司全名能看全"，再回头喂订单号的副行。
 */
const SPARE_WIDTH_PLAN = [
  // 「深圳大冰熊科技有限公司」这类 11 个字的公司名：11 × 14 + 24 = 178
  { key: 'merchant', upTo: 178 },
  // 订单号列副行的「商户单号 SHOP-ORDER-20260819-000001」完整显示
  { key: 'orderNo', upTo: ORDER_COLUMN_CAPS.orderNo },
  // 还有富余，商户名继续放宽到 16 个字的公司全名
  { key: 'merchant', upTo: ORDER_COLUMN_CAPS.merchant }
]

/** 1280 宽窗口下，表格实际能用的宽度 */
export const TABLE_WIDTH_AT_1280 = 1280 - 220 - 40 - 40 - 6

/** 所有列的最小总宽 */
export function totalColumnWidth(widths = ORDER_COLUMN_WIDTHS) {
  return Object.values(widths).reduce((sum, width) => sum + width, 0)
}

/**
 * 按表格实际可用宽度分配列宽。
 *
 * 每列先拿基准宽（总和刚好等于 1280 能给的 974，这时候订单号完整、商户名截断），
 * 窗口再宽出来的部分按 SPARE_WIDTH_PLAN 一档一档发放，两列都吃饱之后剩下的对半分，
 * 免得表格右边留一条空白。
 *
 * 之前是把富余宽度按 min-width 比例分给这两列（Element Plus 的默认行为），
 * 结果 1440 下订单号列拿到 288px、右边空出五十多像素没人用，
 * 商户名却只有 168px，「深圳大冰熊科技有限公司」还是被截成「深圳大冰熊科技有…」。
 *
 * @param {number} tableWidth 表格容器的实际像素宽度
 * @returns {typeof ORDER_COLUMN_WIDTHS} 这个宽度下每一列应该多宽
 */
export function resolveColumnWidths(tableWidth) {
  const widths = { ...ORDER_COLUMN_WIDTHS }
  let spare = Math.max(0, Math.floor(tableWidth || 0) - totalColumnWidth())

  for (const { key, upTo } of SPARE_WIDTH_PLAN) {
    if (spare <= 0) break
    const got = Math.min(spare, Math.max(0, upTo - widths[key]))
    widths[key] += got
    spare -= got
  }

  if (spare > 0) {
    const half = Math.floor(spare / 2)
    widths.orderNo += half
    widths.merchant += spare - half
  }

  return widths
}
