<template>
  <div class="order-page">
    <!-- 搜索区域 -->
    <div class="page-card">
      <div class="card-body">
        <div class="table-toolbar">
          <div class="toolbar-left">
            <el-select v-model="queryParams.merchantId" placeholder="所属商户" clearable style="width: 180px">
              <el-option v-for="m in merchants" :key="m.id" :label="m.merchantName" :value="m.id" />
            </el-select>
            <el-input
              v-model="queryParams.orderNo"
              placeholder="平台/商户订单号"
              clearable
              style="width: 180px"
              @keyup.enter="loadData"
            />
            <el-select v-model="queryParams.status" placeholder="订单状态" clearable style="width: 120px">
              <el-option label="待支付" :value="0" />
              <el-option label="已支付" :value="1" />
              <el-option label="已过期" :value="2" />
              <el-option label="已关闭" :value="3" />
            </el-select>
            <el-button type="primary" @click="loadData">
              <el-icon><Search /></el-icon>
              搜索
            </el-button>
            <el-button @click="resetQuery">
              <el-icon><Refresh /></el-icon>
              重置
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="page-card" style="margin-top: 16px">
      <div class="card-body">
        <el-table ref="tableRef" :data="tableData" v-loading="loading" stripe class="order-table">
          <!-- 订单号列给的是固定宽度：它需要多宽是算得出来的，喂饱就够了，
               多出来的宽度留给下面的商户列（那一列是全表唯一的弹性列，吃掉所有富余） -->
          <el-table-column label="订单号" :width="COL.orderNo">
            <template #default="{ row }">
              <div class="order-no" :title="row.orderNo">{{ row.orderNo }}</div>
              <div class="order-no-sub" :title="row.outTradeNo">
                商户单号 {{ row.outTradeNo || '-' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="商户/店铺" :min-width="COL.merchant">
            <template #default="{ row }">
              <div class="cell-main" :title="row.merchantName">{{ row.merchantName || '-' }}</div>
              <div class="cell-sub" :title="row.shopName">{{ row.shopName || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="金额" :width="COL.amount" align="right">
            <template #default="{ row }">
              <span class="amount-text">¥{{ row.amount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支付类型" :width="COL.payType">
            <template #default="{ row }">
              <el-tag :type="row.payType === 'wxpay' ? 'success' : 'primary'" size="small">
                {{ row.payType === 'wxpay' ? '微信' : '支付宝' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" :width="COL.status">
            <template #default="{ row }">
              <el-tag :type="getStatusType(row.status)" size="small">
                {{ getStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="回调状态" :width="COL.notify">
            <template #default="{ row }">
              <template v-if="row.status === 1">
                <el-tag :type="getNotifyStatusType(row.notifyStatus)" size="small">
                  {{ getNotifyStatusText(row.notifyStatus) }}
                </el-tag>
                <div v-if="row.notifyCount > 0" class="notify-count">{{ formatNotifyCount(row.notifyCount) }}</div>
              </template>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <!-- 时间列拆成"日期 / 时刻"上下两行：一行放不下会被右侧固定的操作列截掉，
               拆两行后完整时间戳始终看得全，列宽还从 160 降到 100 -->
          <el-table-column label="创建时间" :width="COL.createTime">
            <template #default="{ row }">
              <div class="time-date">{{ splitTime(row.createTime).date }}</div>
              <div class="time-clock">{{ splitTime(row.createTime).time }}</div>
            </template>
          </el-table-column>
          <el-table-column label="支付时间" :width="COL.payTime">
            <template #default="{ row }">
              <template v-if="row.payTime">
                <div class="time-date">{{ splitTime(row.payTime).date }}</div>
                <div class="time-clock">{{ splitTime(row.payTime).time }}</div>
              </template>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" :width="COL.action" fixed="right" class-name="action-cell">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="handleView(row)">详情</el-button>
              <el-button v-if="row.status === 0" type="success" link size="small" @click="handleConfirm(row)">确认支付</el-button>
              <el-button v-if="row.status === 0" type="danger" link size="small" @click="handleClose(row)">关闭</el-button>
              <el-button v-if="row.status === 1 && row.notifyStatus !== 1" type="warning" link size="small" @click="handleResendNotify(row)">重发通知</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrapper">
          <el-pagination
            v-model:current-page="queryParams.current"
            v-model:page-size="queryParams.size"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="loadData"
            @current-change="loadData"
          />
        </div>
      </div>
    </div>

    <!-- 订单详情弹窗 -->
    <OrderDetailDialog
      v-model="detailVisible"
      :row="currentOrder"
      :loader="getOrderByNo"
      show-merchant
    />
  </div>
</template>

<script setup>
/**
 * Fast 易支付 - 订单管理页面
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMerchantList, getOrderPage, getOrderByNo, confirmOrder, closeOrder, resendNotify } from '@/api'
import OrderDetailDialog from './OrderDetailDialog.vue'
import { resolveColumnWidths, formatNotifyCount, TABLE_WIDTH_AT_1280 } from './columns'
import {
  getStatusText,
  getStatusType,
  getNotifyStatusText,
  getNotifyStatusType,
  splitDateTime as splitTime
} from '@/utils/orderDetail'

// 列宽：跟着表格实际能用的宽度走。窗口拖宽了、侧边栏收起来了，多出来的宽度
// 按 columns.js 里定好的顺序分配（先补商户名、再补订单号副行），而不是让
// Element Plus 按 min-width 比例平摊——平摊的结果是订单号列右边空一片、商户名还在截断。
// 表格根元素永远是容器的 100% 宽（横向溢出是它内部滚的），所以量它不会和列宽互相牵扯。
const tableRef = ref()
const tableWidth = ref(TABLE_WIDTH_AT_1280)
const COL = computed(() => resolveColumnWidths(tableWidth.value))

let tableResizeObserver = null

// 商户列表
const merchants = ref([])

// 查询参数
const queryParams = reactive({
  current: 1,
  size: 10,
  merchantId: undefined,
  orderNo: '',
  status: undefined
})

// 表格数据
const loading = ref(false)
const tableData = ref([])
const total = ref(0)

// 详情弹窗
const detailVisible = ref(false)
const currentOrder = ref({})

// 状态文案、时间格式化统一放在 @/utils/orderDetail，列表和详情弹窗共用一套口径

// 加载商户列表
const loadMerchants = async () => {
  try {
    const res = await getMerchantList()
    merchants.value = res.data || []
  } catch (error) {
    console.error('加载商户列表失败:', error)
  }
}

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    const res = await getOrderPage(queryParams)
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (error) {
    console.error('加载订单列表失败:', error)
  }
  loading.value = false
}

// 重置查询
const resetQuery = () => {
  queryParams.merchantId = undefined
  queryParams.orderNo = ''
  queryParams.status = undefined
  queryParams.current = 1
  loadData()
}

// 查看详情
const handleView = (record) => {
  currentOrder.value = { ...record }
  detailVisible.value = true
}

// 确认支付
const handleConfirm = (record) => {
  ElMessageBox.confirm(
    '确认后将触发回调通知',
    '确定要确认该订单已支付吗？',
    { type: 'warning' }
  ).then(async () => {
    await confirmOrder(record.orderNo)
    ElMessage.success('确认成功')
    loadData()
  }).catch(() => {})
}

// 关闭订单
const handleClose = (record) => {
  ElMessageBox.confirm(
    '关闭后订单将无法支付',
    '确定要关闭该订单吗？',
    { type: 'warning' }
  ).then(async () => {
    await closeOrder(record.orderNo)
    ElMessage.success('关闭成功')
    loadData()
  }).catch(() => {})
}

// 重发通知
const handleResendNotify = async (record) => {
  await resendNotify(record.orderNo)
  ElMessage.success('通知已发送')
  loadData()
}

onMounted(() => {
  loadMerchants()
  loadData()

  // jsdom（单元测试环境）里的 ResizeObserver 是个空壳，量不到宽度，
  // 这时候就一直用 1280 的基准宽，不影响其它断言
  const tableEl = tableRef.value?.$el
  if (tableEl && typeof ResizeObserver !== 'undefined') {
    tableResizeObserver = new ResizeObserver(([entry]) => {
      const width = entry?.contentRect?.width
      if (width > 0) tableWidth.value = width
    })
    tableResizeObserver.observe(tableEl)
  }
})

onBeforeUnmount(() => {
  tableResizeObserver?.disconnect()
  tableResizeObserver = null
})
</script>

<style scoped>
.order-page {
  padding: 0;
}

.amount-text {
  color: #e6a23c;
  font-weight: 500;
}

.text-muted {
  color: #909399;
}

/* 回调状态列的副行。这一列只有 80px（下限是四个字的表头「回调状态」，压不下去），
   文案必须写成最短的「通知12次」——「已通知 12 次」实测要 86px，两位数就折成两行，
   整行跟着变高 65→71px，一列订单忽高忽低没法扫读（MTM-193）。
   nowrap 是兜底：万一以后文案变长或次数上四位数，宁可溢出一点也不再折行。
   这里故意不加 text-overflow: ellipsis —— 截断的是数字，「通知9999次」截成
   「通知999…」会被看成 999 次，比折行更误导人。 */
.notify-count {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  line-height: 1.3;
  white-space: nowrap;
}

/* 单元格左右内边距从 12px 收到 10px：9 列一共省出 36px，
   刚好够整张表在 1280 宽的窗口里放下，不用横向滚动 */
.order-table :deep(.el-table .cell) {
  padding-left: 10px;
  padding-right: 10px;
  line-height: 1.4;
}

/* 操作列按钮间距收到 8px，「详情 / 确认支付 / 关闭」三个按钮才能排成一行不折行 */
.order-table :deep(.action-cell .el-button + .el-button) {
  margin-left: 8px;
}

/* 主次两行：主信息正常色，次信息灰色小字，都不换行、超长省略并挂 title 提示 */
.order-no,
.order-no-sub,
.cell-main,
.cell-sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-no {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: #303133;
}

.order-no-sub,
.cell-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.cell-main {
  color: #303133;
}

/* 时间：日期一行、时刻一行，数字用等宽字形，两行左边对得齐 */
.time-date,
.time-clock {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.time-date {
  color: #303133;
}

.time-clock {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
</style>
