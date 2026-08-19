<template>
  <div class="profile-page">
    <!-- 顶部资料卡：账号 / 昵称 / 最后登录 -->
    <el-card class="profile-card" shadow="never">
      <div class="profile-header">
        <div class="avatar">
          <el-icon :size="40"><User /></el-icon>
        </div>
        <div class="meta">
          <div class="username">{{ profile.username || '—' }}</div>
          <div class="nickname">{{ profile.nickname || '超级管理员' }}</div>
          <div class="last-login">
            上次登录：{{ profile.lastLoginTime || '—' }}
            <span v-if="profile.lastLoginIp"> · {{ profile.lastLoginIp }}</span>
          </div>
        </div>
      </div>
    </el-card>

    <div class="two-column">
      <!-- 改账号 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="section-title">
            <span>修改登录账号</span>
            <span class="section-desc">支持普通字符串或邮箱格式（例如 name@example.com）</span>
          </div>
        </template>
        <el-form
          ref="usernameFormRef"
          :model="usernameForm"
          :rules="usernameRules"
          label-width="100px"
          @submit.prevent="submitUsername"
        >
          <el-form-item label="当前账号">
            <el-input :model-value="profile.username" disabled />
          </el-form-item>
          <el-form-item label="新账号" prop="newUsername">
            <el-input
              v-model="usernameForm.newUsername"
              placeholder="请输入新账号"
              maxlength="100"
              show-word-limit
              autocomplete="off"
            />
          </el-form-item>
          <el-form-item label="当前密码" prop="currentPassword">
            <el-input
              v-model="usernameForm.currentPassword"
              type="password"
              placeholder="请输入当前登录密码，用于二次确认"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="usernameSubmitting" @click="submitUsername">
              保存新账号
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 改密码 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="section-title">
            <span>修改登录密码</span>
            <span class="section-desc">{{ policyText }}</span>
          </div>
        </template>
        <el-form
          ref="passwordFormRef"
          :model="passwordForm"
          :rules="passwordRules"
          label-width="100px"
          @submit.prevent="submitPassword"
        >
          <el-form-item label="旧密码" prop="oldPassword">
            <el-input
              v-model="passwordForm.oldPassword"
              type="password"
              placeholder="请输入当前密码"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="passwordForm.newPassword"
              type="password"
              placeholder="8-64 位，包含字母和数字"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="passwordForm.confirmPassword"
              type="password"
              placeholder="再输入一次新密码"
              show-password
              autocomplete="new-password"
              @keyup.enter="submitPassword"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="passwordSubmitting" @click="submitPassword">
              保存新密码
            </el-button>
            <span class="hint">保存成功后将自动跳回登录页，请使用新密码重新登录。</span>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup>
/**
 * 账号设置页面
 * 两块：改登录账号、改密码。改任一项都会让旧 token 失效——
 * 改账号后前端就地替换 token 并刷新展示；改密码后前端主动清 token 并跳登录页，
 * 让用户"用新密码重新登录"这件事有明确的动作反馈。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getAdminProfile,
  getPasswordPolicy,
  updateAdminPassword,
  updateAdminUsername
} from '@/api'

const router = useRouter()

const profile = reactive({
  id: null,
  username: '',
  nickname: '',
  avatar: '',
  lastLoginTime: '',
  lastLoginIp: ''
})

const policy = reactive({
  minLength: 8,
  maxLength: 64,
  description: '密码长度 8~64 位，必须同时包含字母和数字，且不能是 123456、admin123 这类常见弱口令'
})
const policyText = ref(policy.description)

// ---------- 改账号 ----------
const usernameFormRef = ref()
const usernameForm = reactive({
  newUsername: '',
  currentPassword: ''
})
const usernameSubmitting = ref(false)
const usernameRules = {
  newUsername: [
    { required: true, message: '请输入新账号', trigger: 'blur' },
    { max: 100, message: '账号长度不能超过 100 个字符', trigger: 'blur' }
  ],
  currentPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' }
  ]
}

const submitUsername = async () => {
  if (!usernameFormRef.value) return
  await usernameFormRef.value.validate(async (valid) => {
    if (!valid) return
    if (usernameForm.newUsername.trim() === profile.username) {
      ElMessage.warning('新账号与当前账号一致，无需修改')
      return
    }
    usernameSubmitting.value = true
    try {
      const res = await updateAdminUsername({
        newUsername: usernameForm.newUsername.trim(),
        currentPassword: usernameForm.currentPassword
      })
      // 后端返回了同版本号的新 token，就地替换本地缓存，用户不用被踢下线
      localStorage.setItem('admin_token', res.data.token)
      const cachedUser = JSON.parse(localStorage.getItem('admin_user') || '{}')
      cachedUser.username = res.data.username
      cachedUser.token = res.data.token
      localStorage.setItem('admin_user', JSON.stringify(cachedUser))
      profile.username = res.data.username
      usernameForm.newUsername = ''
      usernameForm.currentPassword = ''
      ElMessage.success('账号已更新')
    } catch (err) {
      // request 拦截器已经统一提示了错误，这里不重复提示
      console.error('修改账号失败:', err)
    } finally {
      usernameSubmitting.value = false
    }
  })
}

// ---------- 改密码 ----------
const passwordFormRef = ref()
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})
const passwordSubmitting = ref(false)
const validateNewPassword = (rule, value, cb) => {
  if (!value) return cb(new Error('请输入新密码'))
  if (value.length < policy.minLength || value.length > policy.maxLength) {
    return cb(new Error(`密码长度必须在 ${policy.minLength}~${policy.maxLength} 位之间`))
  }
  if (!/[A-Za-z]/.test(value) || !/[0-9]/.test(value)) {
    return cb(new Error('密码必须同时包含字母和数字'))
  }
  cb()
}
const validateConfirmPassword = (rule, value, cb) => {
  if (!value) return cb(new Error('请再次输入新密码'))
  if (value !== passwordForm.newPassword) return cb(new Error('两次输入的新密码不一致'))
  cb()
}
const passwordRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [{ required: true, trigger: 'blur', validator: validateNewPassword }],
  confirmPassword: [{ required: true, trigger: 'blur', validator: validateConfirmPassword }]
}

const submitPassword = async () => {
  if (!passwordFormRef.value) return
  await passwordFormRef.value.validate(async (valid) => {
    if (!valid) return
    passwordSubmitting.value = true
    try {
      await updateAdminPassword({
        oldPassword: passwordForm.oldPassword,
        newPassword: passwordForm.newPassword,
        confirmPassword: passwordForm.confirmPassword
      })
      ElMessage.success('密码已更新，请使用新密码重新登录')
      // 改密码之后原凭证已失效：清掉本地 token/用户信息，跳回登录页
      localStorage.removeItem('admin_token')
      localStorage.removeItem('admin_user')
      passwordForm.oldPassword = ''
      passwordForm.newPassword = ''
      passwordForm.confirmPassword = ''
      router.push('/login')
    } catch (err) {
      console.error('修改密码失败:', err)
    } finally {
      passwordSubmitting.value = false
    }
  })
}

// ---------- 页面初始化 ----------
onMounted(async () => {
  try {
    const [profileRes, policyRes] = await Promise.all([
      getAdminProfile(),
      getPasswordPolicy()
    ])
    Object.assign(profile, profileRes.data || {})
    if (policyRes && policyRes.data) {
      Object.assign(policy, policyRes.data)
      policyText.value = policyRes.data.description || policyText.value
    }
  } catch (err) {
    console.error('加载账号资料失败:', err)
  }
})
</script>

<style scoped>
.profile-page {
  padding: 20px;
  max-width: 1100px;
  margin: 0 auto;
}

.profile-card {
  border: 1px solid #ebeef5;
  margin-bottom: 20px;
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-header .avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: linear-gradient(135deg, #93c5fd 0%, #3b82f6 100%);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.profile-header .meta .username {
  font-size: 20px;
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 4px;
}

.profile-header .meta .nickname {
  font-size: 14px;
  color: #6b7280;
  margin-bottom: 6px;
}

.profile-header .meta .last-login {
  font-size: 12px;
  color: #9ca3af;
}

.two-column {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.section-card {
  border: 1px solid #ebeef5;
}

.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.section-title > span:first-child {
  font-weight: 600;
  color: #1f2937;
}

.section-desc {
  font-size: 12px;
  color: #9ca3af;
  margin-left: 12px;
}

.hint {
  margin-left: 12px;
  font-size: 12px;
  color: #9ca3af;
}

@media (max-width: 1024px) {
  .two-column {
    grid-template-columns: 1fr;
  }
}
</style>
