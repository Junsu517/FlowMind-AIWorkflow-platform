/**
 * 时间格式化：ISO 8601 字符串 -> YYYY-MM-DD HH:mm
 * 契约约定后端返回 ISO 8601（如 2026-09-20T14:30:00）
 */
export function formatDateTime(value) {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 毫秒耗时 -> 人类可读（1.2s / 1m05s） */
export function formatDuration(ms) {
  if (ms == null) return '-'
  const n = Number(ms)
  if (!Number.isFinite(n) || n < 0) return '-'
  if (n < 1000) return `${Math.round(n)}ms`
  const totalSec = n / 1000
  if (totalSec < 60) return `${totalSec.toFixed(1)}s`
  const min = Math.floor(totalSec / 60)
  const sec = Math.round(totalSec % 60)
  return `${min}m${String(sec).padStart(2, '0')}s`
}

/** 费用：成本很小，保留 4~6 位有效数字 */
export function formatCost(value) {
  if (value == null) return '-'
  const n = Number(value)
  if (!Number.isFinite(n)) return '-'
  if (n === 0) return '¥0'
  if (n < 0.0001) return `¥${n.toExponential(2)}`
  return `¥${n.toFixed(4)}`
}

/** 相对时间兜底展示（列表页 hover 提示用） */
export function fromNow(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  return formatDateTime(value)
}
