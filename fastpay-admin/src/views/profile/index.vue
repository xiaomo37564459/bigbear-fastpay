<template>
  <div class="profile-page">
    <!-- 顶部资料卡：账号 / 昵称 / 最后登录
         数据没回来之前走骨架屏，别让人以为账号信息丢了；失败了明确说一句并给重试按钮。
         三种状态（加载中 / 正常 / 失败）都套同一个 .profile-state 高度容器，
         切换的时候卡片高度不变，页面不会往上蹿一下。 -->
    <el-card class="profile-card" shadow="never">
      <el-skeleton :loading="profileLoading" animated>
        <template #template>
          <div class="profile-state profile-header" aria-busy="true" data-test="profile-loading">
            <el-skeleton-item variant="circle" class="avatar-skeleton" />
            <div class="meta">
              <el-skeleton-item variant="h3" class="line line-username" style="width: 180px" />
              <el-skeleton-item variant="text" class="line line-nickname" style="width: 108px" />
              <!-- 「正在加载」这句话就摆在"上次登录"那一行的位置上，
                   它是正常态里本来就有的第三行，不是额外多出来的一行 -->
              <div class="line line-last-login loading-tip">
                <el-icon class="is-loading"><Loading /></el-icon>
                正在加载账号信息…
              </div>
            </div>
          </div>
        </template>

        <template #default>
          <div v-if="profileError" class="profile-state" data-test="profile-error-state">
            <el-alert
              class="load-error"
              type="warning"
              show-icon
              :closable="false"
              title="账号信息没加载出来"
              data-test="profile-error"
            >
              <div class="load-error-body">
                <span>网络或服务暂时没响应。下面的改账号、改密码还能正常用。</span>
                <el-button type="primary" link @click="loadProfile">重新加载</el-button>
              </div>
            </el-alert>
          </div>

          <div v-else class="profile-state profile-header" data-test="profile-ready">
            <div class="avatar">
              <el-icon :size="40"><User /></el-icon>
            </div>
            <div class="meta">
              <div class="line line-username username">{{ profile.username || '—' }}</div>
              <div class="line line-nickname nickname">{{ profile.nickname || '超级管理员' }}</div>
              <div class="line line-last-login last-login">
                上次登录：{{ lastLoginTimeText }}
                <span v-if="lastLoginIpText"> · {{ lastLoginIpText }}</span>
              </div>
            </div>
          </div>
        </template>
      </el-skeleton>
    </el-card>

    <div class="two-column">
      <!-- 改账号 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="section-title">
            <span class="section-title-text">修改登录账号</span>
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
            <el-input
              :model-value="currentUsernameText"
              :placeholder="currentUsernamePlaceholder"
              disabled
              data-test="current-username"
            >
              <template v-if="profileLoading" #suffix>
                <el-icon class="is-loading"><Loading /></el-icon>
              </template>
            </el-input>
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
            <el-button
              type="primary"
              :loading="usernameSubmitting"
              :disabled="profileLoading"
              @click="submitUsername"
            >
              保存新账号
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 改密码 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="section-title">
            <span class="section-title-text">修改登录密码</span>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
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

// 页面初始数据的加载状态。首屏默认就是"加载中"，避免在数据回来之前
// 先把「当前账号」「上次登录」渲染成一个空占位符（看起来像数据丢了）。
const profileLoading = ref(true)
const profileError = ref(false)

// 「当前账号」输入框：加载中和加载失败都不显示值，改用占位文案说明当前状态
const currentUsernameText = computed(() =>
  profileLoading.value || profileError.value ? '' : profile.username
)
const currentUsernamePlaceholder = computed(() => {
  if (profileLoading.value) return '正在加载…'
  if (profileError.value) return '没加载出来，点上方「重新加载」再试一次'
  return ''
})

// 时间格式化：后端 LocalDateTime 默认走 ISO（yyyy-MM-ddTHH:mm:ss.SSSSSS），
// 直接展示对管理员不友好，这里统一裁成秒的 yyyy-MM-dd HH:mm:ss。
const lastLoginTimeText = computed(() => {
  if (!profile.lastLoginTime) return '—'
  const parsed = dayjs(profile.lastLoginTime)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : profile.lastLoginTime
})

// IP 展示：本机访问会打出 0:0:0:0:0:0:0:1 / ::1 / 127.0.0.1，对管理员没意义，替换成"本机"
const lastLoginIpText = computed(() => {
  const raw = (profile.lastLoginIp || '').trim()
  if (!raw) return ''
  if (['127.0.0.1', 'localhost', '::1', '0:0:0:0:0:0:0:1'].includes(raw)) {
    return '本机'
  }
  return raw
})

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
/**
 * 拉资料和密码规则。两个请求各算各的（allSettled）：
 * 密码规则挂了不该把账号资料一起拖成"加载失败"，前端本来就有一份兜底规则文案。
 */
const loadProfile = async () => {
  profileLoading.value = true
  profileError.value = false
  const [profileRes, policyRes] = await Promise.allSettled([
    getAdminProfile(),
    getPasswordPolicy()
  ])

  if (profileRes.status === 'fulfilled') {
    Object.assign(profile, profileRes.value?.data || {})
  } else {
    // request 拦截器已经统一弹过错误提示，这里只负责把页面切到"加载失败"的样子
    profileError.value = true
    console.error('加载账号资料失败:', profileRes.reason)
  }

  if (policyRes.status === 'fulfilled' && policyRes.value?.data) {
    Object.assign(policy, policyRes.value.data)
    policyText.value = policyRes.value.data.description || policyText.value
  } else if (policyRes.status === 'rejected') {
    console.error('加载密码规则失败，继续用前端兜底文案:', policyRes.reason)
  }

  profileLoading.value = false
}

onMounted(loadProfile)
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

/* 三种状态共用的高度容器。
   加载中 / 正常 / 失败切换时，卡片高度必须一动不动——之前加载态比正常态高 28px，
   数据一回来整页就往上蹿一下（原来这里注释写的是"不跳动"，但实测是跳的）。
   高度锁在 76px：头像 72px 放得下，三行文字加起来也正好 76px（28+4 / 20+6 / 18）。 */
.profile-state {
  min-height: 76px;
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

/* meta 里的三行：行高写死，加载态的骨架块照着同一套尺寸摆。
   两个状态的行数、行高、行间距完全一致，所以总高度必然相等。 */
.profile-header .meta .line {
  display: block;
}

.profile-header .meta .line-username {
  height: 28px;
  line-height: 28px;
  margin-bottom: 4px;
}

.profile-header .meta .line-nickname {
  height: 20px;
  line-height: 20px;
  margin-bottom: 6px;
}

.profile-header .meta .line-last-login {
  height: 18px;
  line-height: 18px;
}

.profile-header .meta .username {
  font-size: 20px;
  font-weight: 600;
  color: #1f2937;
}

.profile-header .meta .nickname {
  font-size: 14px;
  color: #6b7280;
}

.profile-header .meta .last-login {
  font-size: 12px;
  color: #9ca3af;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.avatar-skeleton {
  width: 72px;
  height: 72px;
  flex-shrink: 0;
}

/* 「正在加载账号信息…」占的就是"上次登录"那一行，不额外撑高 */
.loading-tip {
  font-size: 12px;
  color: #9ca3af;
  display: flex;
  align-items: center;
  gap: 6px;
}

.load-error {
  width: 100%;
}

.load-error-body {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

/* 两张卡片的标题高度要一致，下面的输入框才在同一条水平线上。
   右侧「修改登录密码」的说明比左侧长、会折成两行，直接排版会把右侧表单整体压低约 17px。
   这里让 .two-column 显式分成"标题行 + 表单行"两行，两张卡片各占这两行（subgrid），
   标题行的高度由两张卡片里更高的那个决定，说明折不折行都不再影响表单起始位置。 */
.two-column {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: auto auto;
  gap: 20px;
}

.section-card {
  border: 1px solid #ebeef5;
  grid-row: span 2;
  display: grid;
  grid-template-rows: subgrid;
}

/* 老浏览器不支持 subgrid 时的兜底：给说明文字留出固定两行的高度 */
@supports not (grid-template-rows: subgrid) {
  .section-desc {
    min-height: 34px;
  }
}

/* 卡片标题：标题一行、说明另一行，宽度受限时说明自动折行、不会挤压标题。
   之前用 flex + justify-content:space-between + baseline，右侧卡片
   （"修改登录密码"标题短、说明长）会把标题挤成两行，看起来跟左侧不齐。 */
.section-title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.section-title-text {
  font-weight: 600;
  color: #1f2937;
}

.section-desc {
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.4;
  white-space: normal;
  word-break: break-word;
}

.hint {
  margin-left: 12px;
  font-size: 12px;
  color: #9ca3af;
}

/* 窄屏改成上下排布，两张卡片不再并排，也就不需要对齐了 */
@media (max-width: 1024px) {
  .two-column {
    grid-template-columns: 1fr;
    grid-template-rows: none;
  }

  .section-card {
    grid-row: auto;
    display: block;
  }
}
</style>
