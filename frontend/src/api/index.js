import axios from 'axios'
import { ElMessage } from 'element-plus'
import mockAdapter from './mock/adapter'
import { getToken, clearAuth } from '@/utils/storage'

/**
 * ============================================================
 *  Mock 开关（唯一需要改的地方）
 * ------------------------------------------------------------
 *  默认开启 mock（后端第 1 周还在开发）。
 *  联调时把根目录 .env.development 里的 VITE_USE_MOCK 改成 false，
 *  或直接在 .env.development 里写死，业务代码不用改任何一行。
 * ============================================================
 */
export const USE_MOCK = String(import.meta.env.VITE_USE_MOCK ?? 'true') !== 'false'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

// mock 模式下用自定义 adapter 替换真实网络请求
if (USE_MOCK) {
  request.defaults.adapter = mockAdapter
}

// ---------- 请求拦截器：自动带 token ----------
request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 登录/注册接口返回的 401 是「账号或密码错误」（code=BAD_CREDENTIALS），
// 不能当成登录态失效处理；真正失效的是 code=UNAUTHORIZED。
const AUTH_WHITELIST = ['/auth/login', '/auth/register']
const UNAUTHORIZED_CODES = ['UNAUTHORIZED', 'TOKEN_EXPIRED']

let logoutToastShown = false

function isAuthWhitelist(url = '') {
  return AUTH_WHITELIST.some((p) => String(url).includes(p))
}

/** 401 是否意味着「登录态失效需要重登」 */
function isSessionExpired(status, code, url) {
  if (status !== 401) return false
  // 优先按契约里的业务 code 判断（后端已用 UNAUTHORIZED / BAD_CREDENTIALS 区分）
  if (code) return UNAUTHORIZED_CODES.includes(code)
  // 兜底：没有 code 时按请求路径判断
  return !isAuthWhitelist(url)
}

// ---------- 响应拦截器：统一错误处理 ----------
request.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const data = error?.response?.data
    const url = error?.config?.url || ''

    if (isSessionExpired(status, data?.code, url)) {
      // 登录态失效：清 token + 跳登录页
      clearAuth()
      if (!logoutToastShown) {
        logoutToastShown = true
        ElMessage.warning('登录状态已失效，请重新登录')
        setTimeout(() => {
          logoutToastShown = false
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
          if (!window.location.hash.startsWith('#/login')) {
            window.location.hash = '#/login'
          }
        })
      return Promise.reject(error)
    }

    // 统一错误提示：优先用契约里的 { code, message }
    let message = '请求失败，请稍后重试'
    if (data?.message) message = data.message
    else if (error?.message) message = error.message
    if (status === 403) message = data?.message || '没有权限执行该操作'
    // 契约 RATE_LIMITED：execute 每用户每分钟 10 次
    if (status === 429) message = data?.message || '操作过于频繁，请稍后再试'
    if (error?.code === 'ECONNABORTED') message = '请求超时，请检查网络或后端服务'

    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default request
