import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Profile from '@/views/profile/index.vue'

/**
 * 账号设置页 - 首屏三种状态
 *
 * 线上反馈：网慢的时候「当前账号」「上次登录」会先闪一个 "—"，看着像数据丢了。
 * 这组测试盯的就是这件事：没加载完必须是"正在加载"的样子，加载失败必须说人话，
 * 任何时候都不许拿 "—" 冒充"还没加载完"。
 */

const getAdminProfile = vi.fn()
const getPasswordPolicy = vi.fn()

vi.mock('@/api', () => ({
  getAdminProfile: (...args) => getAdminProfile(...args),
  getPasswordPolicy: (...args) => getPasswordPolicy(...args),
  updateAdminUsername: vi.fn(),
  updateAdminPassword: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

const profileData = {
  id: 1,
  username: 'admin@example.com',
  nickname: '超级管理员',
  lastLoginTime: '2026-08-19T10:30:00',
  lastLoginIp: '203.0.113.42'
}

const policyData = {
  minLength: 8,
  maxLength: 64,
  description: '密码长度 8~64 位，必须同时包含字母和数字'
}

/** 造一个"还没 resolve"的请求，用来停在加载中那一帧 */
function pending() {
  let settle
  const promise = new Promise((resolve, reject) => {
    settle = { resolve, reject }
  })
  return { promise, settle }
}

function mountProfile() {
  return mount(Profile, {
    global: {
      stubs: { User: true, Loading: true }
    }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('账号设置页 - 首屏加载状态', () => {
  it('数据没回来之前显示骨架屏和"正在加载"，不显示 — 占位符', async () => {
    const profileReq = pending()
    getAdminProfile.mockReturnValue(profileReq.promise)
    getPasswordPolicy.mockResolvedValue({ data: policyData })

    const wrapper = mountProfile()
    await flushPromises()

    expect(wrapper.find('[data-test="profile-loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="profile-ready"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正在加载账号信息')
    expect(wrapper.text()).not.toContain('—')

    // 「当前账号」输入框此时是空值 + "正在加载…" 提示，而不是一个空白框
    const currentUsername = wrapper.find('input[data-test="current-username"]')
    expect(currentUsername.element.value).toBe('')
    expect(currentUsername.attributes('placeholder')).toBe('正在加载…')

    profileReq.settle.resolve({ data: profileData })
    await flushPromises()
  })

  it('加载成功后显示账号和上次登录时间', async () => {
    getAdminProfile.mockResolvedValue({ data: profileData })
    getPasswordPolicy.mockResolvedValue({ data: policyData })

    const wrapper = mountProfile()
    await flushPromises()

    expect(wrapper.find('[data-test="profile-loading"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="profile-ready"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('admin@example.com')
    expect(wrapper.text()).toContain('2026-08-19 10:30:00')

    const currentUsername = wrapper.find('input[data-test="current-username"]')
    expect(currentUsername.element.value).toBe('admin@example.com')
  })

  it('加载失败时说明白是没加载出来，并给一个重新加载的入口', async () => {
    getAdminProfile.mockRejectedValue(new Error('网络错误'))
    getPasswordPolicy.mockResolvedValue({ data: policyData })

    const wrapper = mountProfile()
    await flushPromises()

    const alert = wrapper.find('[data-test="profile-error"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('账号信息没加载出来')
    expect(wrapper.find('[data-test="profile-ready"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('—')

    const currentUsername = wrapper.find('input[data-test="current-username"]')
    expect(currentUsername.attributes('placeholder')).toContain('重新加载')

    // 点"重新加载"会重新发请求，这次成功就切回正常态
    getAdminProfile.mockResolvedValue({ data: profileData })
    await alert.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="profile-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="profile-ready"]').exists()).toBe(true)
    expect(getAdminProfile).toHaveBeenCalledTimes(2)
  })

  it('加载态和正常态的高度骨架一模一样，切换的时候页面不会往上跳（MTM-168）', async () => {
    // 之前加载提示是资料卡底下额外多出来的一行，正常态没有这一行，
    // 数据一回来卡片矮 28px，整页跟着往上蹿一下。
    // 现在两个状态共用 .profile-state 高度容器，meta 里都是固定的三行，高度必然相等。
    const profileReq = pending()
    getAdminProfile.mockReturnValue(profileReq.promise)
    getPasswordPolicy.mockResolvedValue({ data: policyData })

    const wrapper = mountProfile()
    await flushPromises()

    const loading = wrapper.find('[data-test="profile-loading"]')
    expect(loading.classes()).toContain('profile-state')
    // 「正在加载」这句话必须在 meta 的第三行里，而不是 meta 外面多出来的一块
    expect(loading.find('.meta .line-last-login.loading-tip').exists()).toBe(true)
    const loadingLines = loading.findAll('.meta .line').length

    profileReq.settle.resolve({ data: profileData })
    await flushPromises()

    const ready = wrapper.find('[data-test="profile-ready"]')
    expect(ready.classes()).toContain('profile-state')
    expect(ready.findAll('.meta .line').length).toBe(loadingLines)
    expect(loadingLines).toBe(3)
  })

  it('加载失败那一版也套同一个高度容器，页面同样不跳', async () => {
    getAdminProfile.mockRejectedValue(new Error('网络错误'))
    getPasswordPolicy.mockResolvedValue({ data: policyData })

    const wrapper = mountProfile()
    await flushPromises()

    expect(wrapper.find('[data-test="profile-error-state"]').classes()).toContain('profile-state')
  })

  it('密码规则接口挂了不影响账号资料展示，规则文案退回前端兜底', async () => {
    getAdminProfile.mockResolvedValue({ data: profileData })
    getPasswordPolicy.mockRejectedValue(new Error('规则接口 500'))

    const wrapper = mountProfile()
    await flushPromises()

    expect(wrapper.find('[data-test="profile-ready"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('admin@example.com')
    expect(wrapper.text()).toContain('密码长度 8~64 位')
  })
})
