<template>
  <div class="order-page">
    <!-- 页面标题 -->
    <div class="page-header">
      <h1 class="page-title">订单管理</h1>
      <p class="page-desc">查看和管理您的支付订单</p>
    </div>

    <!-- 筛选条件 -->
    <div class="dev-card filter-card">
      <!--
        手机上筛选条默认收起来：一屏只有 812px 高，六个筛选框摊开就把订单列表整个挤出屏幕，
        商户打开页面第一眼看到的会是一排空输入框而不是自己的订单。
        电脑上这个按钮根本不渲染，筛选条照旧一直摊开。
      -->
      <button
        v-if="isMobile"
        type="button"
        class="filter-toggle"
        :aria-expanded="filterOpen"
        @click="filterOpen = !filterOpen"
      >
        <span class="filter-toggle-title">筛选条件</span>
        <span v-if="activeFilterCount > 0" class="filter-toggle-count">已选 {{ activeFilterCount }} 项</span>
        <span class="filter-toggle-action">{{ filterOpen ? '收起' : '展开' }}</span>
        <el-icon class="filter-toggle-caret" :class="{ 'is-open': filterOpen }"><ArrowDown /></el-icon>
      </button>

      <div v-show="!isMobile || filterOpen" class="card-body">
        <el-form :inline="true" :model="queryParams" class="filter-form">
          <!--
            xs / sm / md 三档：手机一行一个（标签在上、输入框占满），平板一行两个，
            电脑（≥992px）还是一行四个 —— 和改之前一模一样。
          -->
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="平台订单号">
                <el-input v-model="queryParams.orderNo" placeholder="请输入平台订单号" clearable @keyup.enter="handleSearch" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="商户订单号">
                <el-input v-model="queryParams.outTradeNo" placeholder="请输入商户订单号" clearable @keyup.enter="handleSearch" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="商品名称">
                <el-input v-model="queryParams.subject" placeholder="请输入商品名称" clearable @keyup.enter="handleSearch" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="订单状态">
                <el-select v-model="queryParams.status" placeholder="全部状态" clearable style="width: 100%">
                  <el-option label="待支付" :value="0" />
                  <el-option label="已支付" :value="1" />
                  <el-option label="已过期" :value="2" />
                  <el-option label="已关闭" :value="3" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="支付类型">
                <el-select v-model="queryParams.payType" placeholder="全部类型" clearable style="width: 100%">
                  <el-option label="微信支付" value="wxpay" />
                  <el-option label="支付宝" value="alipay" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="12">
              <el-form-item class="filter-buttons">
                <el-button type="primary" @click="handleSearch">
                  <el-icon><Search /></el-icon>
                  搜索
                </el-button>
                <el-button @click="resetQuery">
                  <el-icon><Refresh /></el-icon>
                  重置
                </el-button>
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
    </div>

    <!-- 订单列表 -->
    <div class="dev-card">
      <div class="card-header">
        <span class="card-title">订单列表</span>
        <span class="card-extra">共 {{ total }} 条记录</span>
      </div>
      <div class="card-body" style="padding-top: 0;">
        <!-- 手机上换成卡片：一条订单一张卡，竖着排，不用左右拖 -->
        <OrderCardList
          v-if="isMobile"
          :orders="orderList"
          :loading="loading"
          @view="handleView"
          @confirm="handleConfirm"
          @close="handleClose"
          @resend="handleResendNotify"
        />

        <el-table v-else :data="orderList" v-loading="loading" stripe scrollbar-always-on class="order-table">
          <!-- 两个订单号并成一栏、上下两行：原来各占一列（180 + 140）还都被截断，
               合成一栏之后平台订单号一行完整显示，商户单号跟在下面，总宽反而省出 130 -->
          <el-table-column label="订单号" :min-width="COL.orderNo">
            <template #default="{ row }">
              <div class="order-no" :title="row.orderNo">{{ row.orderNo }}</div>
              <div class="order-no-sub" :title="row.outTradeNo">
                商户单号 {{ row.outTradeNo || '-' }}
              </div>
            </template>
          </el-table-column>
          <!-- 商品名称一行、店铺一行。店铺原来单独占 100，"token.copliot.cloud" 只看得到前几个字 -->
          <el-table-column label="商品 / 店铺" :min-width="COL.subject">
            <template #default="{ row }">
              <div class="cell-main" :title="row.subject">{{ row.subject || '-' }}</div>
              <div class="cell-sub" :title="row.shopName">{{ row.shopName || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="金额" :width="COL.amount" align="right">
            <template #default="{ row }">
              <span class="amount-text">¥{{ row.amount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支付类型" :width="COL.payType" align="center">
            <template #default="{ row }">
              <div class="pay-type-cell">
                <el-icon v-if="row.payType === 'wxpay'" class="pay-icon wxpay"><ChatDotRound /></el-icon>
                <el-icon v-else class="pay-icon alipay"><Wallet /></el-icon>
                <span>{{ row.payType === 'wxpay' ? '微信' : '支付宝' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" :width="COL.status" align="center">
            <template #default="{ row }">
              <el-tag :type="getStatusType(row.status)" size="small" effect="light">
                {{ getStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="回调状态" :width="COL.notify" align="center">
            <template #default="{ row }">
              <template v-if="row.status === 1">
                <el-tag :type="getNotifyStatusType(row.notifyStatus)" size="small">
                  {{ getNotifyStatusText(row.notifyStatus) }}
                </el-tag>
                <span v-if="row.notifyCount > 0" class="notify-count">({{ row.notifyCount }})</span>
              </template>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="跳转地址" :min-width="COL.returnUrl" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.returnUrl" class="return-url">{{ row.returnUrl }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <!-- 时间列拆成"日期 / 时刻"上下两行：一行放不下会被右侧固定的操作列截掉，
               拆两行后完整时间戳始终看得全，列宽还从 160 降到 100 -->
          <el-table-column label="创建时间" :width="COL.createTime">
            <template #default="{ row }">
              <template v-if="row.createTime">
                <div class="time-date">{{ splitTime(row.createTime).date }}</div>
                <div class="time-clock">{{ splitTime(row.createTime).time }}</div>
              </template>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" :width="COL.action" :fixed="actionFixed" align="center" class-name="action-cell">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="handleView(row)">详情</el-button>
              <el-button v-if="row.status === 0" type="success" link size="small" @click="handleConfirm(row)">确认</el-button>
              <el-button v-if="row.status === 0" type="danger" link size="small" @click="handleClose(row)">关闭</el-button>
              <el-button v-if="row.status === 1 && row.notifyStatus !== 1" type="warning" link size="small" @click="handleResendNotify(row)">重发</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrapper">
          <!--
            手机上收掉「每页几条」和「前往 __ 页」：这两块加起来比屏幕还宽，
            页面往右溢出 27px、要左右拖，就是它们撑的。翻页用左右箭头就够了。
          -->
          <el-pagination
            v-model:current-page="queryParams.current"
            v-model:page-size="queryParams.size"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            :pager-count="isMobile ? 5 : 7"
            :layout="isMobile ? 'total, prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
            @size-change="loadData"
            @current-change="loadData"
          />
        </div>
      </div>
    </div>

    <!-- 订单详情弹窗 -->
    <OrderDetailDialog
      v-model="showDetail"
      :row="currentOrder"
      :loader="getOrderByNo"
    />
  </div>
</template>

<script setup>
/**
 * Fast 易支付 - 订单管理
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { getOrderPage, getOrderByNo, confirmOrder, closeOrder, resendNotify } from '@/api'
import OrderDetailDialog from './OrderDetailDialog.vue'
import OrderCardList from './OrderCardList.vue'
import { useIsMobile } from '@/composables/useIsMobile'
import { ORDER_COLUMN_WIDTHS as COL, shouldFixActionColumn } from './columns'
import {
  getStatusText,
  getStatusType,
  getNotifyStatusText,
  getNotifyStatusType,
  splitDateTime as splitTime
} from '@/utils/orderDetail'

const queryParams = reactive({
  current: 1,
  size: 10,
  orderNo: '',
  outTradeNo: '',
  subject: '',
  status: undefined,
  payType: undefined
})

const orderList = ref([])
const loading = ref(false)
const total = ref(0)

const showDetail = ref(false)
const currentOrder = ref({})

// 手机上换一套结构：筛选条可收起、订单列表用卡片。电脑上 isMobile 恒为 false，
// 这些分支一个都不走，页面和改之前完全一样。
const { isMobile } = useIsMobile()
const filterOpen = ref(false)

// 收起状态下也要让商户知道「现在的列表是筛过的」，否则会以为订单丢了
const activeFilterCount = computed(() => {
  const filled = [
    queryParams.orderNo,
    queryParams.outTradeNo,
    queryParams.subject,
    queryParams.status,
    queryParams.payType
  ]
  return filled.filter(v => v !== '' && v !== undefined && v !== null).length
})

// 窗口够宽（整张表放得下、不会横向滚动）时才固定「操作」列，
// 窄窗口下取消固定，避免固定列浮上来盖住「跳转地址」「创建时间」
const actionFixed = ref(false)
const syncActionFixed = () => {
  const pageWidth = typeof document === 'undefined' ? 0 : document.documentElement.clientWidth
  actionFixed.value = shouldFixActionColumn(pageWidth) ? 'right' : false
}

// 状态文案、时间格式化统一放在 @/utils/orderDetail，列表和详情弹窗共用一套口径

const loadData = async () => {
  loading.value = true
  try {
    const res = await getOrderPage(queryParams)
    orderList.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (error) {
    console.error('加载订单列表失败:', error)
  }
  loading.value = false
}

// 点「搜索」永远从第 1 页开始查：翻到第 3 页再改条件，拿回来的还是第 3 页，
// 商户看到的是一片空白，会以为没查到
const handleSearch = () => {
  queryParams.current = 1
  loadData()
  // 手机上查完就把筛选条收起来，腾出屏幕给结果
  if (isMobile.value) filterOpen.value = false
}

const resetQuery = () => {
  queryParams.orderNo = ''
  queryParams.outTradeNo = ''
  queryParams.subject = ''
  queryParams.status = undefined
  queryParams.payType = undefined
  queryParams.current = 1
  loadData()
}

const handleView = (order) => {
  currentOrder.value = { ...order }
  showDetail.value = true
}

const handleConfirm = (order) => {
  ElMessageBox.confirm(
    '确认后将触发回调通知',
    '确定该订单已收到付款吗？',
    { type: 'warning' }
  ).then(async () => {
    await confirmOrder(order.orderNo)
    ElMessage.success('确认成功')
    loadData()
  }).catch(() => {})
}

const handleClose = (order) => {
  ElMessageBox.confirm(
    '关闭后订单将无法支付',
    '确定要关闭该订单吗？',
    { type: 'warning' }
  ).then(async () => {
    await closeOrder(order.orderNo)
    ElMessage.success('关闭成功')
    loadData()
  }).catch(() => {})
}

// 重发通知
const handleResendNotify = async (order) => {
  try {
    await resendNotify(order.orderNo)
    ElMessage.success('通知已发送')
    loadData()
  } catch (error) {
    ElMessage.error('发送失败')
  }
}

onMounted(() => {
  syncActionFixed()
  window.addEventListener('resize', syncActionFixed)
  loadData()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncActionFixed)
})
</script>

<style scoped>
.order-page {
  padding: 0;
}

/* 筛选卡片 */
.filter-card {
  .card-body {
    padding-bottom: 8px;
  }
}

.filter-form {
  :deep(.el-form-item) {
    margin-bottom: 12px;
    width: 100%;
    
    .el-form-item__label {
      font-size: 13px;
      color: #606266;
    }
    
    .el-input, .el-select {
      width: 100%;
    }
  }
  
  .filter-buttons {
    padding-top: 22px;
  }
}

/* 单元格左右内边距从 12px 收到 10px：9 列一共省出 36px，
   刚好够整张表在内容区里放下，不用横向滚动 */
.order-table :deep(.el-table .cell) {
  padding-left: 10px;
  padding-right: 10px;
  line-height: 1.4;
}

/* 操作列按钮间距收到 8px，「详情 / 确认 / 关闭」三个按钮才能排成一行不折行 */
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

/* 订单号样式 */
.order-no {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 12px;
  color: #409eff;
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

/* 支付类型单元格 */
.pay-type-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  font-size: 12px;
  
  .pay-icon {
    font-size: 14px;
    
    &.wxpay {
      color: #07c160;
    }
    
    &.alipay {
      color: #1677ff;
    }
  }
}

/* 卡片额外信息 */
.card-extra {
  font-size: 13px;
  color: #909399;
}

/* 回调次数 */
.notify-count {
  font-size: 12px;
  color: #909399;
  margin-left: 4px;
}

/* 文本灰色 */
.text-muted {
  color: #909399;
}

/* 跳转地址 */
.return-url {
  font-size: 12px;
  color: #606266;
}

/* ============================================================
   手机端（≤767px）
   原来的毛病：筛选条用的是固定 25% 的栅格，375px 屏上每格只有 80px，
   标签就占掉 78px —— 输入框只剩十几像素打不进字，标签自己也被截成
   「户订单号」「品名称」；分页的「前往 __ 页」再把页面顶出去 27px。
   电脑端（≥992px）走的是 :md="6"，一行四个，和改之前完全一样。
   ============================================================ */

/* 收起 / 展开筛选条的那一条 */
.filter-toggle {
  display: none;
}

@media (max-width: 767px) {
  .filter-toggle {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 14px 16px;
    border: 0;
    background: transparent;
    font-family: inherit;
    font-size: 15px;
    color: #303133;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }

  .filter-toggle-title {
    font-weight: 500;
  }

  /* 收起来的时候也要看得见「现在是筛过的」，否则会以为订单丢了 */
  .filter-toggle-count {
    padding: 1px 8px;
    border-radius: 10px;
    background: #f0f5ff;
    color: #1677ff;
    font-size: 12px;
  }

  .filter-toggle-action {
    margin-left: auto;
    color: #909399;
    font-size: 13px;
  }

  .filter-toggle-caret {
    color: #c0c4cc;
    transition: transform 0.2s ease;
  }

  .filter-toggle-caret.is-open {
    transform: rotate(180deg);
  }

  /* 展开时上面已经有那条标题了，正文不用再留一份上内边距 */
  .filter-card .card-body {
    padding-top: 0;
  }

  /* 标签挪到输入框上面：横着放的话，光标签就吃掉一半宽度 */
  .filter-form :deep(.el-form-item) {
    display: block;
    margin-bottom: 14px;
  }

  .filter-form :deep(.el-form-item__label) {
    width: auto !important;
    justify-content: flex-start;
    padding: 0 0 6px;
    line-height: 1.5;
  }

  .filter-form :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }

  /* 「搜索」「重置」各占一半，手指点得着 */
  .filter-form .filter-buttons {
    padding-top: 0;
    margin-bottom: 0;
  }

  .filter-form .filter-buttons :deep(.el-form-item__content) {
    display: flex;
    gap: 10px;
  }

  .filter-form .filter-buttons :deep(.el-button) {
    flex: 1;
    height: 40px;
    margin-left: 0;
  }
}
</style>
