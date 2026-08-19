/**
 * Fast 易支付 - 管理后台路由配置
 */
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/index.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '控制台', icon: 'DashboardOutlined' }
      },
      {
        path: 'merchant',
        name: 'Merchant',
        component: () => import('@/views/merchant/index.vue'),
        meta: { title: '商户管理', icon: 'UserOutlined' }
      },
      {
        path: 'shop',
        name: 'Shop',
        component: () => import('@/views/shop/index.vue'),
        meta: { title: '店铺管理', icon: 'ShopOutlined' }
      },
      {
        path: 'channel',
        name: 'Channel',
        component: () => import('@/views/channel/index.vue'),
        meta: { title: '通道管理', icon: 'Connection' }
      },
      {
        path: 'order',
        name: 'Order',
        component: () => import('@/views/order/index.vue'),
        meta: { title: '订单管理', icon: 'OrderedListOutlined' }
      },
      {
        path: 'unmatched-notify',
        name: 'UnmatchedNotify',
        component: () => import('@/views/unmatched/index.vue'),
        meta: { title: '未匹配收款', icon: 'Warning' }
      },
      {
        // 账号设置：改自己的登录账号和密码，从右上角头像菜单进入，不在左侧主菜单里
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '账号设置' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory('/fastpay-admin/'),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  // 设置页面标题
  document.title = to.meta.title ? `${to.meta.title} - FAST 易支付` : 'FAST 易支付'
  
  // 登录页面直接放行
  if (to.path === '/login') {
    next()
    return
  }
  
  // 检查登录状态
  const token = localStorage.getItem('admin_token')
  if (!token) {
    next('/login')
    return
  }
  
  next()
})

export default router
