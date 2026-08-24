<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <div class="logo">
          <svg viewBox="0 0 40 40" width="64" height="64" xmlns="http://www.w3.org/2000/svg">
            <defs>
              <linearGradient id="login-admin-logo-grad" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" style="stop-color:#93c5fd"/>
                <stop offset="50%" style="stop-color:#60a5fa"/>
                <stop offset="100%" style="stop-color:#3b82f6"/>
              </linearGradient>
            </defs>
            <rect x="2" y="2" width="36" height="36" rx="10" ry="10" fill="url(#login-admin-logo-grad)"/>
            <circle cx="20" cy="20" r="14" fill="none" stroke="rgba(255,255,255,0.2)" stroke-width="1"/>
            <circle cx="20" cy="20" r="11" fill="none" stroke="rgba(255,255,255,0.15)" stroke-width="0.5"/>
            <circle cx="8" cy="8" r="1.5" fill="rgba(255,255,255,0.4)"/>
            <circle cx="32" cy="8" r="1.5" fill="rgba(255,255,255,0.4)"/>
            <circle cx="8" cy="32" r="1.5" fill="rgba(255,255,255,0.4)"/>
            <circle cx="32" cy="32" r="1.5" fill="rgba(255,255,255,0.4)"/>
            <text x="20" y="26" text-anchor="middle" font-family="STKaiti, KaiTi, SimSun, serif" font-size="20" font-weight="bold" fill="white">易</text>
          </svg>
        </div>
        <h1 class="title">FAST 易支付</h1>
        <p class="subtitle">管理后台</p>
      </div>
      
      <el-form
        ref="formRef"
        :model="formState"
        :rules="rules"
        class="login-form"
        @submit.prevent="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="formState.username"
            size="large"
            placeholder="请输入用户名"
            :prefix-icon="User"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="formState.password"
            type="password"
            size="large"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 登录失败提示（MTM-162 登录限次）：
           后端在密码错时会附上「还可以尝试 X 次」，在被锁时给出「请 X 分钟后再试」；
           这里做成常驻的红字提示，不像 toast 3 秒就消失，方便真的输错的人看清楚。 -->
      <div v-if="loginError" class="login-error-hint">{{ loginError }}</div>
    </div>
  </div>
</template>

<script setup>
/**
 * Fast 易支付 - 管理后台登录页面
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '@/api'

const router = useRouter()
const formRef = ref()

// 表单数据
const formState = reactive({
  username: '',
  password: ''
})

// 表单校验规则
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 加载状态
const loading = ref(false)

// 登录失败常驻提示（MTM-162）：把后端的「还剩几次 / 请 X 分钟后再试」在表单下方持续显示，
// 用户不用去追那闪三秒就消失的顶部 toast。
const loginError = ref('')

// 登录处理
const handleLogin = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid) => {
    if (!valid) return

    loading.value = true
    loginError.value = ''
    try {
      const res = await login(formState)
      // 保存 Token 和用户信息
      localStorage.setItem('admin_token', res.data.token)
      localStorage.setItem('admin_user', JSON.stringify(res.data))
      ElMessage.success('登录成功')
      router.push('/dashboard')
    } catch (error) {
      // request.js 已经在 toast 上报过一次；这里再钉在表单下方留着，MTM-162 要求「明确提示」。
      // 走 axios 分支（后端返回非 200）能拿到 response.data.message；
      // 网络层报错（超时/断网）保底显示 error.message，避免空提示。
      loginError.value = error?.message || '登录失败，请稍后再试'
      console.error('登录失败:', error)
    } finally {
      loading.value = false
    }
  })
}
</script>

