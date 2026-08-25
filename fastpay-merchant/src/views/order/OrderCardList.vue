<template>
  <div v-loading="loading" class="record-list order-card-list">
    <div v-for="row in orders" :key="row.orderNo" class="record-card order-card">
      <div class="rc-head">
        <div class="rc-title order-card-no">{{ row.orderNo }}</div>
        <el-tag class="rc-badge" :type="getStatusType(row.status)" size="small" effect="light">
          {{ getStatusText(row.status) }}
        </el-tag>
      </div>
      <div class="rc-sub">商户单号 {{ row.outTradeNo || '-' }}</div>

      <div class="rc-rows">
        <div class="rc-row">
          <span class="rc-label">商品</span>
          <span class="rc-value">{{ row.subject || '-' }}</span>
        </div>
        <div class="rc-row">
          <span class="rc-label">店铺</span>
          <span class="rc-value">{{ row.shopName || '-' }}</span>
        </div>
        <div class="rc-row">
          <span class="rc-label">金额</span>
          <span class="rc-value">
            <span class="amount-text">¥{{ row.amount }}</span>
            <span class="order-card-paytype">
              <el-icon v-if="row.payType === 'wxpay'" class="pay-icon wxpay"><ChatDotRound /></el-icon>
              <el-icon v-else class="pay-icon alipay"><Wallet /></el-icon>
              {{ row.payType === 'wxpay' ? '微信' : '支付宝' }}
            </span>
          </span>
        </div>
        <div class="rc-row">
          <span class="rc-label">创建时间</span>
          <span class="rc-value time-value">
            {{ splitDateTime(row.createTime).date }} {{ splitDateTime(row.createTime).time }}
          </span>
        </div>
        <!-- 只有已支付的订单才谈得上回调，其他状态显示出来只会让人以为出错了 -->
        <div v-if="row.status === 1" class="rc-row">
          <span class="rc-label">回调</span>
          <span class="rc-value">
            <el-tag :type="getNotifyStatusType(row.notifyStatus)" size="small">
              {{ getNotifyStatusText(row.notifyStatus) }}
            </el-tag>
            <span v-if="row.notifyCount > 0" class="notify-count">({{ row.notifyCount }})</span>
          </span>
        </div>
        <div v-if="row.returnUrl" class="rc-row">
          <span class="rc-label">跳转地址</span>
          <span class="rc-value return-url">{{ row.returnUrl }}</span>
        </div>
      </div>

      <div class="rc-actions">
        <el-button v-if="allow.includes('view')" type="primary" link size="small" @click="emit('view', row)">详情</el-button>
        <el-button v-if="allow.includes('confirm') && row.status === 0" type="success" link size="small" @click="emit('confirm', row)">确认</el-button>
        <el-button v-if="allow.includes('close') && row.status === 0" type="danger" link size="small" @click="emit('close', row)">关闭</el-button>
        <el-button v-if="allow.includes('resend') && row.status === 1 && row.notifyStatus !== 1" type="warning" link size="small" @click="emit('resend', row)">重发</el-button>
      </div>
    </div>

    <div v-if="!loading && orders.length === 0" class="empty-state order-card-empty">
      <div class="empty-text">还没有订单记录</div>
    </div>
  </div>
</template>

<script setup>
/**
 * Fast 易支付 - 手机上的订单列表（一条订单一张卡片）
 *
 * 电脑上订单是一张 9 列的表。那张表最少要 1050px，手机只有 375px，
 * 商户得左右拖着看，右侧固定的「操作」列还会浮上来盖住「跳转地址」和「创建时间」。
 * 所以手机换一套结构：竖着排、一屏看完一条，不用拖。
 *
 * 订单管理页和店铺管理里的「订单记录」共用这个组件，两处长得一模一样。
 * 店铺页那边只给「详情 / 确认」两个动作，靠 allow 控制。
 */
import { ChatDotRound, Wallet } from '@element-plus/icons-vue'
import {
  getStatusText,
  getStatusType,
  getNotifyStatusText,
  getNotifyStatusType,
  splitDateTime
} from '@/utils/orderDetail'

defineProps({
  orders: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  /** 这张卡片上允许出现哪些操作按钮 */
  allow: { type: Array, default: () => ['view', 'confirm', 'close', 'resend'] }
})

const emit = defineEmits(['view', 'confirm', 'close', 'resend'])
</script>

<style scoped>
.order-card-no {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  color: #409eff;
}

/* 金额和支付方式挤在同一行右侧：金额是重点，支付方式跟在后面做补充 */
.order-card-paytype {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}

.order-card-paytype .pay-icon {
  font-size: 13px;
  vertical-align: -2px;
}

.order-card-paytype .pay-icon.wxpay {
  color: #07c160;
}

.order-card-paytype .pay-icon.alipay {
  color: #1677ff;
}

.time-value {
  font-variant-numeric: tabular-nums;
}

.notify-count {
  margin-left: 4px;
  font-size: 12px;
  color: #909399;
}

.return-url {
  font-size: 12px;
  color: #606266;
}

.order-card-empty {
  padding: 32px 16px;
}
</style>
