import request from './index'

/**
 * 定时计划（契约 v1.7 / docs/阶梯7-定时触发技术方案.md §3）
 *
 * GET    /api/workflows/{id}/schedule -> { configured, cron, enabled, input }
 *                                        （无计划时后端可能返回 { configured: false }，也可能 404）
 * PUT    /api/workflows/{id}/schedule -> { cron, enabled, input }（cron 非法 → 400 SCHEDULE_INVALID）
 * DELETE /api/workflows/{id}/schedule -> 204
 *
 * 前端只做粗校验，cron 合法性以后端 SCHEDULE_INVALID 为准（400 时拦截器会把后端 message 弹出来）。
 */

/** 取计划；没有计划时统一返回 null（吞掉 404，兼容 GLM 的两种返回习惯） */
export async function getSchedule(workflowId) {
  try {
    const data = await request.get(`/workflows/${workflowId}/schedule`).then((res) => res.data)
    if (!data) return null
    if (data.configured === false) return null
    if (!data.cron) return null
    return data
  } catch (e) {
    if (e?.response?.status === 404) return null
    throw e
  }
}

export function saveSchedule(workflowId, payload) {
  return request.put(`/workflows/${workflowId}/schedule`, payload).then((res) => res.data)
}

export function deleteSchedule(workflowId) {
  return request.delete(`/workflows/${workflowId}/schedule`).then((res) => res.data)
}

/** 常用 cron 示例（6 段：秒 分 时 日 月 周） */
export const CRON_PRESETS = [
  { label: '每小时整点', cron: '0 0 * * * ?' },
  { label: '每天 9 点', cron: '0 0 9 * * ?' },
  { label: '每周一 9 点', cron: '0 0 9 ? * MON' },
  { label: '每 5 分钟', cron: '0 */5 * * * ?' }
]

/** 前端粗校验：必须是 6 段，且只出现 cron 允许的字符集 */
export function checkCron(cron) {
  const text = String(cron || '').trim()
  if (!text) return '请填写 cron 表达式'
  const parts = text.split(/\s+/)
  if (parts.length !== 6) return 'cron 需要 6 段：秒 分 时 日 月 周'
  if (!/^[0-9*,?\-\/LW#]+$/i.test(parts.join(''))) {
    return 'cron 含有非法字符（只允许数字 * , ? - / L W #）'
  }
  return ''
}
