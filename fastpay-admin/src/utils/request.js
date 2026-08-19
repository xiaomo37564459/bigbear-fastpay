/**
 * Fast 易支付 - HTTP 请求工具
 * 基于 axios 封装，统一处理请求和响应
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

// 创建 axios 实例
const request = axios.create({
  baseURL: '/fastpay-server/api',
  timeout: 15000
})

// 请求拦截器
request.interceptors.request.use(
  config => {
    // 添加 Token
    const token = localStorage.getItem('admin_token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data
    
    // 判断响应状态
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      
      // 401 未授权，跳转登录
      if (res.code === 401) {
        localStorage.removeItem('admin_token')
        localStorage.removeItem('admin_user')
        router.push('/login')
      }
      
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    
    return res
  },
  error => {
    // axios 自己的报错是英文的（"Request failed with status code 500"、"timeout of 15000ms exceeded"），
    // 直接弹给管理员看不懂，这里统一换成中文。业务错误走上面的分支，用的是后端返回的中文提示。
    ElMessage.error(describeRequestError(error))
    return Promise.reject(error)
  }
)

/** 把 axios 的英文报错翻译成管理员看得懂的一句话 */
export function describeRequestError(error) {
  if (error?.code === 'ECONNABORTED' || /timeout/i.test(error?.message || '')) {
    return '请求超时了，请稍后再试'
  }
  const status = error?.response?.status
  if (!status) return '网络连接失败，请检查网络后重试'
  if (status === 401) return '登录状态已失效，请重新登录'
  if (status === 403) return '没有权限执行这个操作'
  if (status === 404) return '请求的内容不存在'
  if (status >= 500) return `服务暂时没响应（${status}），请稍后再试`
  return `请求失败（${status}），请稍后再试`
}

export default request
