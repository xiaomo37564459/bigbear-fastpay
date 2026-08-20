<template>
  <el-dialog
    :model-value="modelValue"
    title="订单详情"
    :width="layout.dialogWidth"
    top="5vh"
    class="fp-order-detail-dialog"
    @update:model-value="handleVisibleChange"
  >
    <div
      class="fp-order-detail-body"
      :class="{ 'fp-order-detail-loading': loading }"
      v-loading="loading && hasOrder"
      element-loading-text="正在加载最新数据"
    >
      <el-skeleton v-if="loading && !hasOrder" :rows="8" animated />

      <template v-else>
        <el-alert
          v-if="error"
          class="fp-order-detail-error"
          :title="error"
          type="warning"
          show-icon
          :closable="false"
        />

        <el-empty
          v-if="!hasOrder"
          class="fp-order-detail-empty"
          description="没查到这笔订单的信息，请返回列表重新点开"
        />

        <template v-else>
          <section
            v-for="section in sections"
            :key="section.key"
            class="fp-order-detail-section"
          >
            <h4 class="fp-section-title">{{ section.title }}</h4>
            <el-descriptions
              :column="layout.column"
              :direction="layout.direction"
              border
            >
              <el-descriptions-item
                v-for="item in section.items"
                :key="item.key"
                :label="item.label"
                :span="resolveSpan(item)"
              >
                <div class="fp-detail-value" :data-field="item.key">
                  <el-tag v-if="item.type === 'tag'" :type="item.tagType" size="small">
                    {{ item.value }}
                  </el-tag>

                  <template v-else-if="item.type === 'notify'">
                    <el-tag :type="item.tagType" size="small">{{ item.value }}</el-tag>
                    <span v-if="item.suffix" class="fp-detail-suffix">{{ item.suffix }}</span>
                  </template>

                  <span v-else-if="item.type === 'amount'" class="fp-detail-amount">
                    {{ item.value }}
                  </span>

                  <template v-else-if="item.type === 'code'">
                    <span class="fp-detail-code">{{ item.value }}</span>
                    <el-button
                      v-if="item.copyable && item.copyValue"
                      class="fp-copy-btn"
                      :data-copy-key="item.key"
                      type="primary"
                      link
                      size="small"
                      @click="handleCopy(item)"
                    >
                      <el-icon><DocumentCopy /></el-icon>
                      复制
                    </el-button>
                    <!-- 说明这段内容是哪来的，例如失败原因是"对方返回的"还是"我们报的错" -->
                    <span v-if="item.suffix" class="fp-detail-hint">{{ item.suffix }}</span>
                  </template>

                  <span v-else class="fp-detail-text">{{ item.value }}</span>
                </div>
              </el-descriptions-item>
            </el-descriptions>
          </section>
        </template>
      </template>
    </div>

    <template #footer>
      <el-button class="fp-order-detail-close" @click="handleClose">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 订单详情弹窗
 *
 * 解决的问题：
 * 1. 详情按分组展示，后端返回的业务字段一个不漏
 * 2. 长内容（订单号、回调地址、扩展参数）自动换行 + 一键复制，不会被截断成 "…"
 * 3. 内容超高时弹窗内部滚动，不会有信息被切在窗口外面
 * 4. 窗口变窄自动改成一列上下排布，小屏也能看
 * 5. 加载中 / 没数据 / 加载失败都有对应提示，不会白屏
 */
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
import {
  buildOrderDetailSections,
  copyToClipboard,
  mergeOrderDetail,
  resolveDetailLayout
} from '@/utils/orderDetail'

const props = defineProps({
  /** 弹窗是否可见 */
  modelValue: { type: Boolean, default: false },
  /** 列表里那一行的订单数据，作为兜底先显示 */
  row: { type: Object, default: null },
  /** 拉取订单详情的方法，传订单号返回接口结果；不传就只用列表行数据 */
  loader: { type: Function, default: null },
  /** 是否展示"所属商户"，商户中心不需要 */
  showMerchant: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const order = ref(null)
const loading = ref(false)
const error = ref('')

// 防止连着点两笔订单时，先发的请求后回来把后点的那笔覆盖掉
let requestToken = 0

const hasOrder = computed(() => !!order.value && Object.keys(order.value).length > 0)

const sections = computed(() =>
  buildOrderDetailSections(order.value, { showMerchant: props.showMerchant })
)

// 窗口宽度变化时重新算排布
const windowWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth)
const layout = computed(() => resolveDetailLayout(windowWidth.value))

const handleWindowResize = () => {
  windowWidth.value = window.innerWidth
}

onMounted(() => window.addEventListener('resize', handleWindowResize))
onBeforeUnmount(() => window.removeEventListener('resize', handleWindowResize))

/** 一列排布时，占两格的字段要退回一格，否则表格会画错 */
const resolveSpan = (item) => Math.min(item.span || 1, layout.value.column)

const loadDetail = async () => {
  error.value = ''
  order.value = props.row ? { ...props.row } : null

  const orderNo = props.row && props.row.orderNo
  if (!props.loader || !orderNo) return

  const token = ++requestToken
  loading.value = true
  try {
    const res = await props.loader(orderNo)
    if (token !== requestToken) return
    const detail = res && res.data !== undefined ? res.data : res
    order.value = mergeOrderDetail(props.row, detail)
  } catch (err) {
    if (token !== requestToken) return
    error.value = '订单详情没加载出来，下面显示的是列表里已有的信息，可以关掉重新点开试试'
  } finally {
    if (token === requestToken) loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) loadDetail()
  },
  { immediate: true }
)

const handleVisibleChange = (visible) => emit('update:modelValue', visible)

const handleClose = () => emit('update:modelValue', false)

const handleCopy = async (item) => {
  const ok = await copyToClipboard(item.copyValue)
  if (ok) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

defineExpose({ layout, order, loading, error })
</script>

<style>
/*
 * 这里不能用 scoped：el-dialog 会把内容挂到 body 上，scoped 样式管不到。
 * 所以统一挂在 .fp-order-detail-dialog 这个前缀下，不会影响其他页面。
 */
.fp-order-detail-dialog .el-dialog__body {
  /* 内容多的时候在弹窗里面滚动，不会有东西被切在窗口外面 */
  max-height: 76vh;
  overflow-y: auto;
  padding-top: 8px;
}

.fp-order-detail-body {
  min-height: 120px;
}

.fp-order-detail-error {
  margin-bottom: 12px;
}

.fp-order-detail-section + .fp-order-detail-section {
  margin-top: 16px;
}

.fp-order-detail-dialog .fp-section-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  padding-left: 8px;
  border-left: 3px solid #409eff;
  line-height: 1.4;
}

/* 标签列固定宽度且不换行，标签自己不会被截断，内容区也不会被挤掉 */
.fp-order-detail-dialog .el-descriptions__label {
  width: 122px;
  min-width: 122px;
  white-space: nowrap;
  color: #606266;
}

/* 关键：长地址、长订单号自动断行，不再撑破弹窗 */
.fp-order-detail-dialog .el-descriptions__content {
  word-break: break-all;
  white-space: pre-wrap;
  vertical-align: top;
}

.fp-detail-value {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 4px 8px;
  min-width: 0;
}

.fp-detail-code {
  font-family: 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
  flex: 1 1 auto;
  min-width: 0;
}

.fp-detail-amount {
  color: #e6a23c;
  font-weight: 600;
}

.fp-detail-suffix {
  font-size: 12px;
  color: #909399;
}

/* 长内容下面的说明文字：独占一行，不跟正文抢位置 */
.fp-detail-hint {
  flex: 0 0 100%;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

.fp-order-detail-dialog .fp-copy-btn {
  flex: 0 0 auto;
  height: 20px;
  padding: 0;
}

/* 窄屏：标签在上、内容在下，长内容有整行可用 */
@media (max-width: 768px) {
  .fp-order-detail-dialog .el-descriptions__label {
    width: auto;
    min-width: 0;
    white-space: normal;
  }

  .fp-order-detail-dialog .el-dialog__body {
    max-height: 72vh;
  }
}
</style>
