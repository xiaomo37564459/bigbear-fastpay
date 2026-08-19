// @vitest-environment node
// 这个文件要用 vite 自己的加载器读配置，底层依赖 esbuild，
// 而 esbuild 在 jsdom 环境里跑不起来（jsdom 的 TextEncoder 不是原生 Uint8Array），
// 所以单独指定用 node 环境跑。
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { resolve, dirname } from 'path'
import { loadConfigFromFile } from 'vite'

/**
 * 开发环境的接口转发地址不许写死成 IP。
 *
 * 背景：这里原来写死了一台机器的 IP，别人在自己电脑上拉下代码启动，
 * 根本连不上（连接被拒绝），打开只能看到一张空列表。
 * 改成域名之后谁拉下来都能直接跑，这个测试就是防止以后有人又手滑改回 IP。
 */

const CONFIG_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../vite.config.js')
const BACKEND_DOMAIN = 'https://pay.copliot.cloud'
// 匹配形如 121.4.28.146 的写死 IP
const HARDCODED_IP = /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/

// 用 vite 自己的加载器把配置真正跑一遍，拿到最终生效的转发设置
const loadProxy = async () => {
  const loaded = await loadConfigFromFile(
    { command: 'serve', mode: 'development' },
    CONFIG_PATH
  )
  return loaded.config.server.proxy
}

describe('管理后台 - 开发环境接口转发地址', () => {
  let savedProxyEnv

  beforeEach(() => {
    savedProxyEnv = process.env.VITE_API_PROXY
    delete process.env.VITE_API_PROXY
  })

  afterEach(() => {
    if (savedProxyEnv === undefined) delete process.env.VITE_API_PROXY
    else process.env.VITE_API_PROXY = savedProxyEnv
  })

  it('配置文件里没有写死的 IP', () => {
    const source = readFileSync(CONFIG_PATH, 'utf8')
    expect(source).not.toMatch(HARDCODED_IP)
  })

  it('默认转发到域名，不用改任何配置就能拿到数据', async () => {
    const proxy = await loadProxy()
    expect(proxy['/fastpay-server'].target).toBe(BACKEND_DOMAIN)
  })

  it('本机跑后端时可以用 VITE_API_PROXY 覆盖', async () => {
    process.env.VITE_API_PROXY = 'http://localhost:7001'
    const proxy = await loadProxy()
    expect(proxy['/fastpay-server'].target).toBe('http://localhost:7001')
  })
})
