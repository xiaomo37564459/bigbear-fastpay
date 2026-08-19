<template>
  <div class="unmatched-page">
    <!-- 页面说明：这个列表不常有人来，进来的人得先知道自己在看什么 -->
    <el-alert
      class="page-intro"
      type="warning"
      :closable="false"
      show-icon
      title="这里是「钱到账了，但没找到对应订单」的记录"
    >
      <template #default>
        用户多付少付了几分钱，或者付款时订单已经超时关掉了，这笔钱就会落到这里。
        核对清楚之后，先去「订单管理」里对这张单点「确认支付」，再回来把这条标成已处理。
      </template>
    </el-alert>

    <!-- 筛选区域 -->
    <div class="page-card" style="margin-top: 16px">
      <div class="card-body">
        <div class="table-toolbar">
          <div class="toolbar-left">
            <el-select
              v-model="queryParams.handleStatus"
              data-test="filter-status"
              placeholder="全部状态"
              clearable
              style="width: 140px"
            >
              <el-option label="待处理" :value="0" />
              <el-option label="已处理" :value="1" />
              <el-option label="已忽略" :value="2" />
            </el-select>
            <el-select
              v-model="queryParams.merchantId"
              data-test="filter-merchant"
              placeholder="所属商户"
              clearable
              style="width: 180px"
            >
              <el-option v-for="m in merchants" :key="m.id" :label="m.merchantName" :value="m.id" />
            </el-select>
            <el-button type="primary" data-test="do-search" @click="handleSearch">
              <el-icon><Search /></el-icon>
              搜索
            </el-button>
            <el-button data-test="do-reset" @click="resetQuery">
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
        <el-alert
          v-if="loadError"
          class="load-error"
          data-test="unmatched-error"
          type="error"
          :closable="false"
          show-icon
          title="列表没查出来"
        >
          <template #default>
            <span class="load-error-text">{{ loadError }}</span>
            <el-button size="small" @click="loadData">重新加载</el-button>
          </template>
        </el-alert>

        <el-table :data="tableData" v-loading="loading" stripe class="unmatched-table">
          <!-- 「没查出来」和「真的一笔都没有」是两回事，说反了会让运营以为钱都对上了 -->
          <template #empty>
            <div v-if="loadError" class="empty-hint" data-test="unmatched-empty-failed">
              <p class="empty-title">这一页没能加载出来</p>
              <p class="empty-desc">先别当成「没有」—— 可能正有几笔钱悬在这儿，点上面的「重新加载」再看一次</p>
            </div>
            <div v-else class="empty-hint" data-test="unmatched-empty">
              <p class="empty-title">这段时间没有认不到订单的收款</p>
              <p class="empty-desc">说明每一笔进来的钱都对上了订单，不用管这个页面</p>
            </div>
          </template>

          <el-table-column label="收到金额" width="120" align="right">
            <template #default="{ row }">
              <span class="amount-text" data-test="notify-amount">{{ formatAmount(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支付方式" width="100">
            <template #default="{ row }">
              <el-tag :type="row.payType === 'wxpay' ? 'success' : 'primary'" size="small">
                {{ formatPayType(row.payType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="所属商户" min-width="140">
            <template #default="{ row }">
              <div class="cell-main">{{ merchantNameOf(row.merchantId) }}</div>
              <div class="cell-sub">通道 {{ row.channelId ?? '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="到账时间" width="110">
            <template #default="{ row }">
              <div class="time-date">{{ splitDateTime(row.notifyTime).date || '-' }}</div>
              <div class="time-clock">{{ splitDateTime(row.notifyTime).time }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTagOf(row.handleStatus)" size="small">
                {{ statusTextOf(row.handleStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理结果" min-width="200">
            <template #default="{ row }">
              <template v-if="row.handleStatus === PENDING">
                <span class="text-muted">还没人处理</span>
              </template>
              <template v-else>
                <div v-if="row.handledOrderNo" class="cell-main handled-order">
                  认到订单 {{ row.handledOrderNo }}
                </div>
                <div class="cell-sub">{{ row.handleRemark || '没写备注' }}</div>
                <div class="cell-sub">{{ formatDateTimeOrDash(row.handleTime) }}</div>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="原始通知" min-width="200">
            <template #default="{ row }">
              <div class="raw-message" :title="row.rawMessage">{{ row.rawMessage || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right" class-name="action-cell">
            <template #default="{ row }">
              <template v-if="row.handleStatus === PENDING">
                <el-button
                  type="success"
                  link
                  size="small"
                  data-test="action-handle"
                  @click="openHandle(row)"
                >标记已处理</el-button>
                <el-button
                  type="info"
                  link
                  size="small"
                  data-test="action-ignore"
                  @click="openIgnore(row)"
                >忽略</el-button>
              </template>
              <span v-else class="text-muted">已经处理过</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 没查出来时「共 0 条」也是同一句反话的小字版，一起藏掉 -->
        <div v-if="!loadError" class="pagination-wrapper" data-test="unmatched-pagination">
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

    <!-- 标记已处理 -->
    <el-dialog v-model="handleVisible" title="标记为已处理" width="520px">
      <p class="dialog-tip">
        这笔 <strong>{{ formatAmount(current.amount) }}</strong> 的收款，认到哪张订单上了？
        订单号在「订单管理」里能查到，形如 FP2026...
      </p>
      <el-form label-width="90px">
        <el-form-item label="订单号">
          <el-input
            v-model="handleForm.handledOrderNo"
            data-test="handle-order-no"
            placeholder="这笔钱对应的平台订单号"
            clearable
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="handleForm.remark"
            data-test="handle-remark"
            type="textarea"
            :rows="3"
            placeholder="怎么核对的、跟谁确认的，写一句方便以后查"
          />
        </el-form-item>
      </el-form>
      <p v-if="handleError" class="form-error" data-test="handle-error">{{ handleError }}</p>
      <template #footer>
        <el-button data-test="handle-cancel" @click="handleVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-test="handle-submit"
          :loading="submitting"
          @click="submitHandle"
        >确定</el-button>
      </template>
    </el-dialog>

    <!-- 忽略 -->
    <el-dialog v-model="ignoreVisible" title="忽略这条记录" width="520px">
      <p class="dialog-tip">
        忽略之后这笔 <strong>{{ formatAmount(current.amount) }}</strong> 就不再出现在待处理里了。
        请写清为什么不用管，别让下一个人看不懂。
      </p>
      <el-form label-width="90px">
        <el-form-item label="忽略原因">
          <el-input
            v-model="ignoreForm.remark"
            data-test="ignore-remark"
            type="textarea"
            :rows="3"
            placeholder="例如：这是别人转账，不是平台收的钱"
          />
        </el-form-item>
      </el-form>
      <p v-if="ignoreError" class="form-error" data-test="ignore-error">{{ ignoreError }}</p>
      <template #footer>
        <el-button data-test="ignore-cancel" @click="ignoreVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-test="ignore-submit"
          :loading="submitting"
          @click="submitIgnore"
        >确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * Fast 易支付 - 未匹配收款通知
 *
 * 钱到账了但按「商户 + 支付方式 + 实付金额」找不到待支付订单时，后端会把这笔落到
 * fp_unmatched_notify 表。以前这种情况只写一行日志，没人翻日志就等于收了钱没认单。
 * 这个页面让运营看得见这些钱，并且能人工兜住：认到哪张单上，或者确认这笔不用管。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getUnmatchedNotifyPage,
  handleUnmatchedNotify,
  ignoreUnmatchedNotify,
  getMerchantList
} from '@/api'
import {
  formatAmount,
  formatPayType,
  formatDateTimeOrDash,
  splitDateTime
} from '@/utils/orderDetail'
import { describeRequestError } from '@/utils/request'

/** 处理状态：跟后端 UnmatchedNotifyServiceImpl 里的常量一一对应 */
const PENDING = 0
const HANDLE_STATUS_TEXT = { 0: '待处理', 1: '已处理', 2: '已忽略' }
const HANDLE_STATUS_TAG = { 0: 'warning', 1: 'success', 2: 'info' }

const statusTextOf = (status) => HANDLE_STATUS_TEXT[status] || '未知'
const statusTagOf = (status) => HANDLE_STATUS_TAG[status] || 'info'

/**
 * 把请求失败翻译成一句人话。
 * 后端返回业务错误（比如「该通知已经处理过」）时，axios 拦截器抛的是带中文提示的
 * Error，直接用它；网络层的错误只有英文，交给 describeRequestError 换成中文。
 */
const explainError = (error) => {
  if (error && !error.response && !error.code && error.message) return error.message
  return describeRequestError(error)
}

// 默认只看待处理的：进来的人要的就是「有哪些钱还没人管」
const queryParams = reactive({
  current: 1,
  size: 10,
  handleStatus: PENDING,
  merchantId: undefined
})

const loading = ref(false)
const loadError = ref('')
const tableData = ref([])
const total = ref(0)
const merchants = ref([])

const current = ref({})
const submitting = ref(false)

const handleVisible = ref(false)
const handleError = ref('')
const handleForm = reactive({ handledOrderNo: '', remark: '' })

const ignoreVisible = ref(false)
const ignoreError = ref('')
const ignoreForm = reactive({ remark: '' })

// 记录里只有 merchantId，直接摆一个数字运营看不懂，用商户列表换成名字
const merchantNameOf = (merchantId) => {
  if (merchantId === null || merchantId === undefined) return '未知商户'
  const hit = merchants.value.find(m => m.id === merchantId)
  return hit ? hit.merchantName : `商户#${merchantId}`
}

const loadMerchants = async () => {
  try {
    const res = await getMerchantList()
    merchants.value = res.data || []
  } catch (error) {
    // 商户名只是锦上添花，查不到就退回「商户#id」，不挡列表
    console.error('加载商户列表失败:', error)
  }
}

const loadData = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const res = await getUnmatchedNotifyPage({ ...queryParams })
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (error) {
    // 查不出来就说查不出来，别让空表格冒充「一笔都没有」—— 那正是这个功能要防的事
    loadError.value = explainError(error)
    tableData.value = []
    total.value = 0
  }
  loading.value = false
}

const handleSearch = () => {
  queryParams.current = 1
  loadData()
}

const resetQuery = () => {
  queryParams.current = 1
  queryParams.handleStatus = PENDING
  queryParams.merchantId = undefined
  loadData()
}

const openHandle = (row) => {
  current.value = { ...row }
  handleForm.handledOrderNo = ''
  handleForm.remark = ''
  handleError.value = ''
  handleVisible.value = true
}

const submitHandle = async () => {
  const orderNo = (handleForm.handledOrderNo || '').trim()
  if (!orderNo) {
    handleError.value = '请填写这笔钱对应的平台订单号，不填的话以后没人知道认到哪去了'
    return
  }
  handleError.value = ''
  submitting.value = true
  try {
    await handleUnmatchedNotify(current.value.id, {
      handledOrderNo: orderNo,
      remark: (handleForm.remark || '').trim()
    })
    ElMessage.success('已标记为已处理')
    handleVisible.value = false
    loadData()
  } catch (error) {
    // 失败就把弹窗留着、填过的内容留着，改完能直接再交一次
    handleError.value = explainError(error)
  }
  submitting.value = false
}

const openIgnore = (row) => {
  current.value = { ...row }
  ignoreForm.remark = ''
  ignoreError.value = ''
  ignoreVisible.value = true
}

const submitIgnore = async () => {
  const remark = (ignoreForm.remark || '').trim()
  if (!remark) {
    ignoreError.value = '请写一句忽略的原因，方便以后回头查'
    return
  }
  ignoreError.value = ''
  submitting.value = true
  try {
    await ignoreUnmatchedNotify(current.value.id, { remark })
    ElMessage.success('已忽略')
    ignoreVisible.value = false
    loadData()
  } catch (error) {
    ignoreError.value = explainError(error)
  }
  submitting.value = false
}

onMounted(() => {
  loadMerchants()
  loadData()
})
</script>

<style scoped>
.unmatched-page {
  padding: 0;
}

.page-intro :deep(.el-alert__content) {
  line-height: 1.7;
}

.load-error {
  margin-bottom: 12px;
}

.load-error-text {
  margin-right: 12px;
}

.amount-text {
  color: #e6a23c;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.text-muted {
  color: #909399;
}

.cell-main {
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.handled-order {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

/* 原始通知内容通常是一长串 JSON，列表里只给一行，鼠标停上去看全文 */
.raw-message {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

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

.empty-hint {
  padding: 24px 0;
}

.empty-title {
  color: #606266;
  font-size: 14px;
  margin: 0;
}

.empty-desc {
  color: #909399;
  font-size: 12px;
  margin: 6px 0 0;
}

.dialog-tip {
  margin: 0 0 16px;
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}

.form-error {
  margin: 0;
  color: #f56c6c;
  font-size: 13px;
  line-height: 1.6;
}

.unmatched-table :deep(.action-cell .el-button + .el-button) {
  margin-left: 8px;
}
</style>
