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
        <el-table :data="tableData" v-loading="loading" stripe class="order-table">
          <el-table-column label="订单号" :min-width="COL.orderNo">
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
                <div v-if="row.notifyCount > 0" class="notify-count">已通知 {{ row.notifyCount }} 次</div>
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
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMerchantList, getOrderPage, getOrderByNo, confirmOrder, closeOrder, resendNotify } from '@/api'
import OrderDetailDialog from './OrderDetailDialog.vue'
import { ORDER_COLUMN_WIDTHS as COL } from './columns'
import {
  getStatusText,
  getStatusType,
  getNotifyStatusText,
  getNotifyStatusType,
  splitDateTime as splitTime
} from '@/utils/orderDetail'

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

.notify-count {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  line-height: 1.3;
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
