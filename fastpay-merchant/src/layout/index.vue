<template>
  <div class="developer-layout">
    <!-- 顶部导航栏 -->
    <header class="developer-header">
      <div class="header-left">
        <div class="logo" @click="router.push('/')">
          <div class="logo-icon">
            <svg viewBox="0 0 40 40" width="32" height="32" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="logo-grad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" style="stop-color:#93c5fd"/>
                  <stop offset="50%" style="stop-color:#60a5fa"/>
                  <stop offset="100%" style="stop-color:#3b82f6"/>
                </linearGradient>
              </defs>
              <rect x="2" y="2" width="36" height="36" rx="10" ry="10" fill="url(#logo-grad)"/>
              <circle cx="20" cy="20" r="14" fill="none" stroke="rgba(255,255,255,0.2)" stroke-width="1"/>
              <circle cx="20" cy="20" r="11" fill="none" stroke="rgba(255,255,255,0.15)" stroke-width="0.5"/>
              <circle cx="8" cy="8" r="1.5" fill="rgba(255,255,255,0.4)"/>
              <circle cx="32" cy="8" r="1.5" fill="rgba(255,255,255,0.4)"/>
              <circle cx="8" cy="32" r="1.5" fill="rgba(255,255,255,0.4)"/>
              <circle cx="32" cy="32" r="1.5" fill="rgba(255,255,255,0.4)"/>
              <text x="20" y="26" text-anchor="middle" font-family="STKaiti, KaiTi, SimSun, serif" font-size="20" font-weight="bold" fill="white">易</text>
            </svg>
          </div>
          <span class="logo-text">FAST 易支付</span>
          <span class="logo-badge">商户中心</span>
        </div>

        <!-- 电脑端主菜单：手机上整条收起来，改用底部标签栏 -->
        <nav class="nav-menu">
          <div 
            v-for="item in navItems" 
            :key="item.path"
            class="nav-item"
            :class="{ active: isActive(item.path) }"
            @click="router.push(item.path)"
          >
            {{ item.title }}
          </div>
        </nav>
      </div>

      <div class="header-right">
        <div class="header-action" title="开发文档" @click="handleOpenDocs">
          <el-icon><Document /></el-icon>
        </div>

        <el-dropdown trigger="click" @command="handleCommand">
          <div class="user-dropdown">
            <!-- 手机上宽度不够，只留头像；电脑上仍旧显示完整商户名 -->
            <span class="user-avatar">{{ userInitial }}</span>
            <span class="user-name">{{ merchantName }}</span>
            <el-icon class="user-caret"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">
                <el-icon><User /></el-icon>
                商户信息
              </el-dropdown-item>
              <el-dropdown-item command="docs">
                <el-icon><Document /></el-icon>
                开发文档
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- 主内容区 -->
    <main class="developer-main">
      <div class="developer-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>
    </main>

    <!-- 手机端底部标签栏：四个主菜单挪到拇指够得着的地方，只在窄屏出现 -->
    <nav class="mobile-tabbar" aria-label="主菜单">
      <button
        v-for="item in navItems"
        :key="item.path"
        type="button"
        class="tabbar-item"
        :class="{ active: isActive(item.path) }"
        :aria-current="isActive(item.path) ? 'page' : undefined"
        @click="router.push(item.path)"
      >
        <span class="tabbar-icon">
          <el-icon><component :is="item.icon" /></el-icon>
        </span>
        <span class="tabbar-label">{{ item.title }}</span>
      </button>
    </nav>
  </div>
</template>

<script setup>
/**
 * Fast 易支付 - 商户平台布局组件
 * 参考微信/支付宝开发者平台设计
 */
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Odometer, Shop, Connection, Setting } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()

// 导航菜单
// icon 只给手机端底部标签栏用（电脑端顶栏仍然是纯文字，样子不变），
// 图标和控制台「快捷操作」里的那几个保持一致，避免同一个功能两种符号。
const navItems = [
  { path: '/console/dashboard', title: '控制台', icon: Odometer },
  { path: '/console/shop', title: '店铺管理', icon: Shop },
  { path: '/console/channel', title: '通道管理', icon: Connection },
  { path: '/console/config', title: '开发配置', icon: Setting }
]

// 商户信息
const merchantInfo = computed(() => {
  const info = localStorage.getItem('merchant_info')
  return info ? JSON.parse(info) : {}
})

// 商户显示名
const merchantName = computed(() => {
  return merchantInfo.value.nickname || merchantInfo.value.merchantName || '商户'
})

// 头像里的那个字：手机端顶栏放不下完整商户名，用首字顶替
const userInitial = computed(() => merchantName.value.trim().charAt(0) || '商')

// 判断是否激活
const isActive = (path) => {
  return route.path === path || route.path.startsWith(path + '/')
}

// 打开文档
const handleOpenDocs = () => {
  router.push('/console/docs')
}

// 下拉菜单命令
const handleCommand = (command) => {
  if (command === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(() => {
      localStorage.removeItem('merchant_token')
      localStorage.removeItem('merchant_info')
      router.push('/login')
    }).catch(() => {})
  } else if (command === 'docs') {
    handleOpenDocs()
  } else if (command === 'profile') {
    router.push('/console/config')
  }
}
</script>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
