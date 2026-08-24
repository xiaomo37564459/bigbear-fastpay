<template>
  <div class="pay-page">
    <!-- 加载中 -->
    <div v-if="loading" class="loading-container">
      <div class="loading-spinner"></div>
      <p class="loading-text">加载中...</p>
    </div>

    <!-- 错误提示 -->
    <div v-else-if="error" class="result-container error">
      <div class="result-icon">❌</div>
      <div class="result-title">{{ error }}</div>
      <button class="btn btn-primary" @click="$router.back()">返回</button>
    </div>

    <!-- 订单已支付 -->
    <div v-else-if="payData.status === 1" class="result-container success">
      <div class="result-icon">✅</div>
      <div class="result-title">支付成功</div>
      <div class="result-info">
        <p>订单号：{{ payData.orderNo }}</p>
        <p data-test="paid-amount">支付金额：<strong>¥{{ displayPayAmount }}</strong></p>
        <p v-if="amountAdjusted" class="result-origin" data-test="paid-origin-amount">
          订单金额 ¥{{ displayOrderAmount }}
        </p>
      </div>
      <button class="btn btn-primary" @click="handleReturn">完成</button>
    </div>

    <!-- 订单已过期或关闭 -->
    <div v-else-if="payData.status === 2 || payData.status === 3" class="result-container warning">
      <div class="result-icon">⚠️</div>
      <div class="result-title">{{ payData.status === 2 ? '订单已过期' : '订单已关闭' }}</div>
      <button class="btn btn-primary" @click="$router.back()">返回</button>
    </div>

    <!-- 待支付 -->
    <div v-else class="pay-container">
      <div class="pay-card">
        <!-- 头部信息 -->
        <div class="pay-header">
          <div class="shop-name">{{ payData.shopName || '收款方' }}</div>
          <div class="order-subject">{{ payData.subject }}</div>
        </div>

        <!-- 金额：显示的是这一单专属的实付金额，不是商户报的订单金额 -->
        <div class="pay-amount">
          <span class="currency">¥</span>
          <span class="amount" data-test="pay-amount">{{ displayPayAmount }}</span>
        </div>

        <!-- 必须按这个数付款的提示：金额付错了系统认不出是谁付的 -->
        <div class="amount-alert" data-test="amount-alert">
          <div class="amount-alert-main">
            请务必按 <strong>{{ displayPayAmount }}</strong> 元付款
            <button class="copy-btn" data-test="copy-amount" @click="copyPayAmount">复制金额</button>
          </div>
          <div class="amount-alert-desc">多付或少付都不会自动到账，需要联系客服处理。</div>
          <div v-if="amountAdjusted" class="amount-alert-origin" data-test="amount-origin">
            订单金额 ¥{{ displayOrderAmount }}；为了不和别人的订单混在一起，这一单要付 ¥{{ displayPayAmount }}。
          </div>
        </div>

        <!-- 二维码 -->
        <div class="qrcode-section">
          <div class="qrcode-box" :class="payData.payType">
            <img v-if="qrcodeImage" :src="qrcodeImage" class="qrcode-img" alt="收款码" />
            <div v-else class="qrcode-loading">
              <div class="loading-spinner small"></div>
            </div>
          </div>
          <div class="scan-hint">
            <span class="pay-icon" :class="payData.payType">
              {{ payData.payType === 'wxpay' ? '微信' : '支付宝' }}
            </span>
            扫码支付
          </div>
          <!-- 个人收款码带不进金额，用户扫完还得自己输，这句话必须说在前面 -->
          <div class="scan-tip" data-test="scan-tip">
            扫码后金额要自己填，请输入 {{ displayPayAmount }} 元
          </div>
        </div>

        <!-- 倒计时 -->
        <div class="countdown" v-if="countdown > 0">
          ⏱️ 请在 <strong>{{ formatCountdown }}</strong> 内完成支付
        </div>

        <!-- 订单号 -->
        <div class="order-no">订单号：{{ payData.outTradeNo }}</div>
      </div>

      <!-- 操作按钮 -->
      <div class="action-buttons">
        <button class="btn btn-outline" @click="goBack">取消</button>
        <button class="btn btn-primary" @click="checkPayStatus">我已支付</button>
      </div>

      <!-- 提示 -->
      <div class="pay-notice">
        <p class="warning"><strong>• 付款金额必须正好是 {{ displayPayAmount }} 元，多一分少一分都认不到这笔订单</strong></p>
        <p><strong>• 请使用{{ payData.payType === 'wxpay' ? '微信' : '支付宝' }}扫描二维码完成支付</strong></p>
        <p><strong>• 支付完成后系统会自动确认，请勿重复支付</strong></p>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Fast 易支付 - 支付页面
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import QRCode from 'qrcode'
import { getPayPageData, getPayStatus } from '@/api'
import { copyToClipboard } from '@/utils/orderDetail'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const payData = ref({})
const qrcodeImage = ref('')  // 生成的二维码图片
const countdown = ref(0)
let countdownTimer = null
let websocket = null

/**
 * 金额统一补成两位小数。
 * 接口回来的 JSON 数字 10.00 到了浏览器就是 10，直接显示会变成「¥10」，
 * 用户不知道该付 10 块还是 10 块几毛 —— 这一单就是靠金额认人的，写不清楚就会付错。
 */
const formatMoney = (value) => {
  if (value === null || value === undefined || value === '') return ''
  const num = Number(value)
  return Number.isNaN(num) ? String(value) : num.toFixed(2)
}

// 这一单真正要付的钱。后端会在订单金额上下微调几分钱，保证同时挂着的订单金额互不相同，
// 收到付款后才能认出是谁付的。老版本后端没返回 payAmount，退回订单金额，别显示空白。
const displayPayAmount = computed(() => formatMoney(payData.value.payAmount ?? payData.value.amount))

// 商户下单时报的原始金额
const displayOrderAmount = computed(() => formatMoney(payData.value.amount))

// 微调过才需要额外解释一句差额是哪来的，两个数一样就别啰嗦
const amountAdjusted = computed(() =>
  !!displayOrderAmount.value && displayOrderAmount.value !== displayPayAmount.value
)

// 复制实付金额，方便用户直接粘到付款页，少一次手输就少一次输错
const copyPayAmount = async () => {
  const text = displayPayAmount.value
  if (!text) return
  if (await copyToClipboard(text)) {
    showToast({ message: `已复制 ${text}`, type: 'success' })
  } else {
    showToast({ message: `复制失败，请手动输入 ${text} 元`, type: 'fail' })
  }
}

// 格式化倒计时
const formatCountdown = computed(() => {
  const minutes = Math.floor(countdown.value / 60)
  const seconds = countdown.value % 60
  return `${minutes}分${seconds.toString().padStart(2, '0')}秒`
})

// 获取支付页面数据
const loadPayData = async () => {
  const orderNo = route.params.orderNo
  if (!orderNo) {
    error.value = '订单号不能为空'
    loading.value = false
    return
  }

  try {
    const res = await getPayPageData(orderNo)
    if (res.code === 200) {
      payData.value = res.data
      
      // 生成二维码图片
      if (payData.value.qrcodeUrl) {
        qrcodeImage.value = await generateQrcodeImage(payData.value.qrcodeUrl)
      }
      
      // 计算倒计时
      if (payData.value.expireTime && payData.value.status === 0) {
        const expireTime = new Date(payData.value.expireTime).getTime()
        const now = Date.now()
        countdown.value = Math.max(0, Math.floor((expireTime - now) / 1000))
        startCountdown()
        
        // 启动 WebSocket 监听支付结果
        connectWebSocket(payData.value.merchantNo, payData.value.outTradeNo)
      }
    } else {
      error.value = res.message || '获取订单信息失败'
    }
  } catch (e) {
    error.value = '网络错误，请稍后重试'
  }
  loading.value = false
}

// 开始倒计时
const startCountdown = () => {
  countdownTimer = setInterval(() => {
    if (countdown.value > 0) {
      countdown.value--
    } else {
      clearInterval(countdownTimer)
      payData.value.status = 2 // 标记为过期
    }
  }, 1000)
}

// 建立 WebSocket 连接
const connectWebSocket = (merchantNo, outTradeNo) => {
  // 构建 WebSocket URL，使用商户订单号作为标识
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const wsUrl = `${protocol}//${host}/fastpay-server/ws/pay/${merchantNo}/${outTradeNo}`

  console.log('WebSocket URL:', wsUrl)
  
  try {
    websocket = new WebSocket(wsUrl)
    console.log('WebSocket 对象已创建, readyState:', websocket.readyState)
    
    websocket.onopen = () => {
      console.log('WebSocket 连接已建立, readyState:', websocket.readyState)
      // 发送心跳保持连接
      startHeartbeat()
    }
    
    websocket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        console.log('收到 WebSocket 消息:', data)
        
        if (data.type === 'PAY_SUCCESS') {
          // 收到支付成功通知
          handlePaySuccess(data.payAmount)
        } else if (data.type === 'pong') {
          // 心跳响应，忽略
        }
      } catch (e) {
        console.error('解析 WebSocket 消息失败:', e)
      }
    }
    
    websocket.onerror = (error) => {
      console.error('WebSocket 错误:', error)
      console.error('WebSocket readyState:', websocket.readyState)
    }
    
    websocket.onclose = (event) => {
      console.log('WebSocket 连接已关闭, code:', event.code, 'reason:', event.reason)
    }
  } catch (e) {
    console.error('WebSocket 连接失败:', e)
  }
}

// WebSocket 心跳
let heartbeatTimer = null
const startHeartbeat = () => {
  heartbeatTimer = setInterval(() => {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
      websocket.send('ping')
    }
  }, 30000) // 每30秒发送一次心跳
}

// 处理支付成功
const handlePaySuccess = (payAmount) => {
  // 停止倒计时
  if (countdownTimer) clearInterval(countdownTimer)
  if (heartbeatTimer) clearInterval(heartbeatTimer)
  
  // 关闭 WebSocket
  if (websocket) {
    websocket.close()
  }
  
  // 如果有 returnUrl，直接跳转到商户页面，不显示平台结果页
  if (payData.value.returnUrl) {
    showToast({ message: '支付成功，正在跳转...', type: 'success' })
    setTimeout(() => {
      redirectToReturnUrl()
    }, 1000)
  } else {
    // 没有 returnUrl，显示平台结果页
    payData.value.status = 1
    payData.value.payAmount = payAmount
    showToast({ message: '支付成功', type: 'success' })
  }
}

// 跳转到商户配置的 returnUrl
const redirectToReturnUrl = () => {
  if (payData.value.returnUrl) {
    // 拼接订单信息到 returnUrl
    let url = payData.value.returnUrl
    const separator = url.includes('?') ? '&' : '?'
    url += `${separator}order_no=${payData.value.orderNo}&out_trade_no=${payData.value.outTradeNo}&status=1`
    
    console.log('跳转到商户页面:', url)
    window.location.href = url
  } else {
    // 没有配置 returnUrl，跳转到平台支付结果页
    router.push(`/pay/result/${payData.value.orderNo}`)
  }
}

// 手动检查支付状态
const checkPayStatus = async () => {
  showToast({ message: '正在查询...', type: 'loading', duration: 0 })
  try {
    const res = await getPayStatus(payData.value.orderNo)
    if (res.code === 200) {
      if (res.data.status === 1) {
        // 使用统一的支付成功处理
        handlePaySuccess(res.data.payAmount)
      } else {
        showToast({ message: '暂未收到付款，请稍后再试', type: 'fail' })
      }
    }
  } catch (e) {
    showToast({ message: '查询失败', type: 'fail' })
  }
}

// 返回商户页面（支付成功后点击完成按钮）
const handleReturn = () => {
  redirectToReturnUrl()
}

// 生成二维码图片
const generateQrcodeImage = async (content) => {
  try {
    return await QRCode.toDataURL(content, {
      width: 200,
      margin: 2,
      color: { dark: '#000000', light: '#ffffff' }
    })
  } catch (e) {
    console.error('生成二维码失败:', e)
    return ''
  }
}

// 返回上一页
const goBack = () => {
  if (payData.value.returnUrl) {
    window.location.href = payData.value.returnUrl
  } else {
    window.history.back()
  }
}

onMounted(() => {
  loadPayData()
})

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
  if (heartbeatTimer) clearInterval(heartbeatTimer)
  if (websocket) {
    websocket.close()
  }
})
</script>

<style lang="less" scoped>
/* 通用支付页面样式 - 简洁现代风格 */
.pay-page {
  min-height: 100vh;
  background: #f7f8fa;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
}

/* 加载状态 */
.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  
  .loading-text {
    margin-top: 16px;
    color: #999;
    font-size: 14px;
  }
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #f0f0f0;
  border-top-color: #1890ff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  
  &.small {
    width: 24px;
    height: 24px;
    border-width: 2px;
  }
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 结果页面 */
.result-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  text-align: center;
  
  .result-icon {
    font-size: 56px;
    margin-bottom: 16px;
  }
  
  .result-title {
    font-size: 20px;
    font-weight: 500;
    color: #333;
    margin-bottom: 12px;
  }
  
  .result-info {
    color: #666;
    font-size: 14px;
    margin-bottom: 24px;
    
    p {
      margin: 6px 0;
    }
    
    strong {
      color: #333;
      font-size: 18px;
    }
  }
}

/* 通用按钮 */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 12px 32px;
  font-size: 15px;
  font-weight: 500;
  border-radius: 8px;
  border: none;
  cursor: pointer;
  transition: all 0.2s;
  
  &:active {
    transform: scale(0.98);
  }
}

.btn-primary {
  background: #1890ff;
  color: #fff;
  
  &:hover {
    background: #40a9ff;
  }
}

.btn-outline {
  background: #fff;
  color: #666;
  border: 1px solid #ddd;
  
  &:hover {
    border-color: #1890ff;
    color: #1890ff;
  }
}

/* 支付容器 */
.pay-container {
  max-width: 420px;
  margin: 0 auto;
  padding: 20px 16px;
}

/* 支付卡片 */
.pay-card {
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  text-align: center;
}

.pay-header {
  margin-bottom: 16px;
  
  .shop-name {
    font-size: 16px;
    font-weight: 500;
    color: #333;
    margin-bottom: 4px;
  }
  
  .order-subject {
    font-size: 13px;
    color: #999;
  }
}

.pay-amount {
  margin: 20px 0;
  
  .currency {
    font-size: 20px;
    color: #333;
    vertical-align: top;
    margin-right: 2px;
  }
  
  .amount {
    font-size: 42px;
    font-weight: 600;
    color: #333;
    letter-spacing: -1px;
  }
}

/* 实付金额提示：整页最该被看见的一块，用红底红字压住 */
.amount-alert {
  margin: 0 0 4px;
  padding: 12px 14px;
  background: #fff1f0;
  border: 1px solid #ffccc7;
  border-radius: 10px;
  text-align: left;

  .amount-alert-main {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    font-size: 15px;
    font-weight: 600;
    color: #cf1322;

    strong {
      font-size: 18px;
      font-variant-numeric: tabular-nums;
    }
  }

  .amount-alert-desc {
    margin-top: 6px;
    font-size: 12px;
    line-height: 1.6;
    color: #a8071a;
  }

  .amount-alert-origin {
    margin-top: 6px;
    padding-top: 6px;
    border-top: 1px dashed #ffccc7;
    font-size: 12px;
    line-height: 1.6;
    color: #8c6f00;
  }
}

.copy-btn {
  margin-left: auto;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 500;
  color: #cf1322;
  background: #fff;
  border: 1px solid #ffa39e;
  border-radius: 6px;
  cursor: pointer;

  &:active {
    transform: scale(0.96);
  }
}

/* 成功页里的订单原价，比实付金额弱一档 */
.result-origin {
  font-size: 12px;
  color: #999;
}

/* 二维码区域 */
.qrcode-section {
  margin: 24px 0;
}

.qrcode-box {
  display: inline-block;
  padding: 12px;
  background: #fff;
  border-radius: 12px;
  border: 2px solid #eee;
  
  &.wxpay {
    border-color: #07c160;
  }
  
  &.alipay {
    border-color: #1677ff;
  }
  
  .qrcode-img {
    width: 180px;
    height: 180px;
    display: block;
  }
  
  .qrcode-loading {
    width: 180px;
    height: 180px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
}

.scan-tip {
  margin-top: 8px;
  font-size: 13px;
  font-weight: 500;
  color: #cf1322;
}

.scan-hint {
  margin-top: 16px;
  font-size: 14px;
  color: #666;
  
  .pay-icon {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 12px;
    font-weight: 500;
    margin-right: 4px;
    
    &.wxpay {
      background: #e8f8e8;
      color: #07c160;
    }
    
    &.alipay {
      background: #e6f3ff;
      color: #1677ff;
    }
  }
}

/* 倒计时 */
.countdown {
  margin: 16px 0;
  padding: 10px 16px;
  background: #fffbe6;
  border-radius: 8px;
  font-size: 13px;
  color: #ad6800;
  
  strong {
    color: #d4380d;
  }
}

/* 订单号 */
.order-no {
  font-size: 12px;
  color: #bbb;
  margin-top: 16px;
}

/* 操作按钮 */
.action-buttons {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  
  .btn {
    flex: 1;
  }
}

/* 提示信息 */
.pay-notice {
  margin-top: 20px;
  padding: 16px;
  background: #fff;
  border-radius: 12px;
  
  p {
    font-size: 12px;
    color: #999;
    margin: 6px 0;
    line-height: 1.6;
    
    &.warning {
      color: #ee0a24;
    }
  }
}

/* 响应式适配 */
@media (max-width: 375px) {
  .pay-amount .amount {
    font-size: 36px;
  }
  
  .qrcode-box .qrcode-img,
  .qrcode-box .qrcode-loading {
    width: 160px;
    height: 160px;
  }
}
</style>
