// @vitest-environment node
// 这个文件要用 vite 自己的加载器读配置，底层依赖 esbuild，
// 而 esbuild 在 jsdom 环境里跑不起来（jsdom 的 TextEncoder 不是原生 Uint8Array），
// 所以单独指定用 node 环境跑。
import { describe, it, expect, beforeAll, beforeEach, afterEach } from 'vitest'
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
// 匹配任意写死的 IPv4 地址（比如 192.0.2.1 这种）
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

  // 第一次读配置最贵：要现场用 esbuild 把 vite 配置打一次包，再把配置里引用的
  // 那几个插件整个加载进来，机器忙的时候能花掉十几秒；之后再读就走缓存，不到 1 秒。
  // 这十几秒原来是算在第一条用例头上的，于是它一挤就超时变红。
  // 现在把这一次「冷启动」挪到准备阶段（准备阶段的时间上限单独给到 60 秒），
  // 用例本身量到的就只是真正要测的那一小段。
  beforeAll(async () => {
    await loadProxy()
  })

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
