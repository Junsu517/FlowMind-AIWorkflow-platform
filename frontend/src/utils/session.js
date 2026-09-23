/**
 * 登录态失效的统一处理：清本地 token + 提示 + 跳登录页。
 *
 * axios 响应拦截器（src/api/index.js）和 SSE 客户端（src/utils/sse.js）共用这一份，
 * 避免两处行为不一致（曾经就出现过：走 axios 会跳登录，走 SSE 只提示"不可用"）。
 *
 * 契约：401 + code=UNAUTHORIZED / TOKEN_EXPIRED 都表示登录态失效。
 */
import { ElMessage } from 'element-plus'
import { clearAuth } from '@/utils/storage'

let toastShown = false

export function handleSessionExpired(message = '登录状态已失效，请重新登录') {
  clearAuth()

  if (!toastShown) {
    toastShown = true
    ElMessage.warning(message)
    setTimeout(() => {
      toastShown = false
    }, 1500)
  }

  import('@/router')
    .then((m) => {
      const router = m.default
      const current = router.currentRoute?.value
      if (current && current.path !== '/login') {
        router.replace({ path: '/login', query: { redirect: current.fullPath } })
      }
    })
    .catch(() => {
      // 兜底：动态 import 失败时直接改 hash
      if (!window.location.hash.startsWith('#/login')) {
        window.location.hash = '#/login'
      }
    })
}

export default handleSessionExpired
