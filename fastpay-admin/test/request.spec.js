import { describe, it, expect } from 'vitest'
import { describeRequestError } from '@/utils/request'

/**
 * 请求失败时弹给管理员的那句话必须是中文。
 * axios 原生报错长这样："Request failed with status code 500"、"timeout of 15000ms exceeded"，
 * 直接弹出去等于没说。
 */
describe('请求失败提示 - 说人话', () => {
  it('断网 / 服务没起来时说网络连不上', () => {
    expect(describeRequestError(new Error('Network Error'))).toBe('网络连接失败，请检查网络后重试')
    expect(describeRequestError(undefined)).toBe('网络连接失败，请检查网络后重试')
  })

  it('超时单独说超时', () => {
    expect(describeRequestError({ code: 'ECONNABORTED', message: 'timeout of 15000ms exceeded' }))
      .toBe('请求超时了，请稍后再试')
  })

  it('常见 HTTP 状态码各有各的中文说法', () => {
    expect(describeRequestError({ response: { status: 401 } })).toBe('登录状态已失效，请重新登录')
    expect(describeRequestError({ response: { status: 403 } })).toBe('没有权限执行这个操作')
    expect(describeRequestError({ response: { status: 404 } })).toBe('请求的内容不存在')
    expect(describeRequestError({ response: { status: 500 } })).toBe('服务暂时没响应（500），请稍后再试')
    expect(describeRequestError({ response: { status: 502 } })).toBe('服务暂时没响应（502），请稍后再试')
    expect(describeRequestError({ response: { status: 400 } })).toBe('请求失败（400），请稍后再试')
  })

  it('任何情况下都不会把英文原文丢给用户', () => {
    const cases = [
      new Error('Request failed with status code 500'),
      { message: 'Network Error' },
      { response: { status: 503 }, message: 'Request failed with status code 503' }
    ]
    cases.forEach(err => {
      expect(describeRequestError(err)).not.toMatch(/[A-Za-z]{4,}/)
    })
  })
})
