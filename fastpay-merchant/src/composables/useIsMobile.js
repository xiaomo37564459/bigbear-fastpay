/**
 * Fast 易支付 - 「现在是不是手机屏」的统一判定
 *
 * 光靠 CSS 的 @media 只能改样子，改不了结构。商户中心有几张表在手机上
 * 无论怎么调 CSS 都读不了（9~10 列塞进 375px，右侧固定的「操作」列还会
 * 浮上来把左边整片内容盖掉），只能换一套结构 —— 表格换成卡片。
 * 换结构要在 JS 里判断，就有了这个文件。
 *
 * 断点必须和 src/assets/styles/main.scss 里的 $mobile-max 是同一个数，
 * 否则会出现「样式按手机走、结构按电脑走」的错位，比不适配还难看。
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'

/** 小于等于这个宽度算手机；跟 main.scss 的 $mobile-max 对齐 */
export const MOBILE_MAX_WIDTH = 767

/**
 * 给定一个页面宽度，判断算不算手机。
 * 拿不到宽度（0 / undefined / 服务端渲染）时一律按电脑兜底：
 * 宁可在手机上多滚一下，也不要在电脑上莫名其妙变成卡片。
 */
export function isMobileWidth(width) {
  return typeof width === 'number' && width > 0 && width <= MOBILE_MAX_WIDTH
}

/** 当前页面宽度。clientWidth 才是去掉滚动条之后的可用宽度，拿不到时退回 innerWidth */
export function currentViewportWidth() {
  if (typeof document === 'undefined' || typeof window === 'undefined') return 0
  return document.documentElement.clientWidth || window.innerWidth || 0
}

/**
 * 组件里用：const { isMobile } = useIsMobile()
 * 转屏、拖窗口都会跟着变。
 */
export function useIsMobile() {
  const isMobile = ref(isMobileWidth(currentViewportWidth()))

  const sync = () => {
    isMobile.value = isMobileWidth(currentViewportWidth())
  }

  onMounted(() => {
    sync()
    window.addEventListener('resize', sync)
  })

  onBeforeUnmount(() => {
    if (typeof window !== 'undefined') window.removeEventListener('resize', sync)
  })

  return { isMobile, syncIsMobile: sync }
}
