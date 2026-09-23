/**
 * SSE 客户端（契约 v1.3 执行模块 / GET /api/executions/{id}/stream）
 *
 * 为什么不用 EventSource：
 *   - EventSource 不能自定义请求头，而本接口要求 Authorization: Bearer <token>；
 *   - 所以用 fetch + ReadableStream 自己按 SSE 帧格式解析（事件以空行分隔）。
 *
 * 事件协议（严格按契约，字段名一个都不改）：
 *   event: execution_started   data: {"executionId":12,"totalNodes":4}
 *   event: node_started        data: {"nodeKey":"llm_1","name":"AI 建议"}
 *   event: token               data: {"nodeKey":"llm_1","delta":"今天"}
 *   event: node_completed      data: {"nodeKey":"llm_1","durationMs":3200}
 *   event: node_skipped        data: {"nodeKey":"http_2","reason":"条件分支未命中"}
 *   event: execution_completed data: {"output":{...},"totalTokens":1234,"durationMs":5210}
 *   event: execution_failed    data: {"error":"节点 llm_1 调用超时"}
 *
 * @returns {{ close: () => void }} 主动断开（组件卸载/停止时调用）
 */
import { USE_MOCK } from '@/api'
import { openMockStream } from '@/api/mock/stream'
import { getToken } from '@/utils/storage'
import { handleSessionExpired } from '@/utils/session'

/**
 * 与 src/api/index.js 的 axios baseURL 完全同款写法。
 * 部署站挂在 /fm/ 前缀下（VITE_API_BASE=/fm/api），这里如果写死 '/api'
 * 会把 SSE 打到别的服务上 → 404 → 每次执行都降级成轮询（线上就是这个症状）。
 */
const BASE = import.meta.env.VITE_API_BASE || '/api'

/** 解析一个 SSE 帧（不含结尾空行） */
function parseFrame(raw) {
  let event = 'message'
  const dataLines = []
  for (const line of String(raw).split('\n')) {
    if (!line) continue
    if (line.startsWith(':')) continue // 注释/心跳
    const idx = line.indexOf(':')
    const field = idx < 0 ? line : line.slice(0, idx)
    let value = idx < 0 ? '' : line.slice(idx + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
  }
  if (!dataLines.length) return null
  const rawData = dataLines.join('\n')
  let data = rawData
  try {
    data = JSON.parse(rawData)
  } catch {
    /* 非 JSON 时保留字符串 */
  }
  return { event, data }
}

/**
 * @param {object} opts
 * @param {number|string} opts.executionId
 * @param {(evt:{event:string,data:any})=>void} opts.onEvent
 * @param {(err:any)=>void} [opts.onError]  连接不可用。
 *   kind='http'         —— 非 401 的 HTTP 错误（404/500…），调用方降级轮询
 *   kind='content-type' —— 响应不是 text/event-stream
 *   kind='network'      —— 网络异常/连接中断
 *   kind='unauthorized' —— 401 登录过期（已统一清 token + 提示 + 跳登录，调用方只收尾、别再降级）
 * @param {()=>void} [opts.onOpen]
 * @param {()=>void} [opts.onClose]         流正常结束（连接断开不影响执行，结果已落库）
 */
export function openExecutionStream(opts) {
  const { executionId, onEvent, onError, onOpen, onClose } = opts

  if (USE_MOCK) {
    return openMockStream({ executionId, onEvent, onError, onOpen, onClose })
  }

  const controller = new AbortController()
  let closed = false

  function close() {
    if (closed) return
    closed = true
    controller.abort()
  }

  async function safeBody(res) {
    try {
      return await res.text()
    } catch {
      return ''
    }
  }

  fetch(`${BASE}/executions/${executionId}/stream`, {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      Authorization: `Bearer ${getToken() || ''}`
    },
    signal: controller.signal
  })
    .then(async (res) => {
      const contentType = res.headers.get('content-type') || ''
      if (!res.ok) {
        const body = await safeBody(res)
        if (res.status === 401) {
          // 契约：401（UNAUTHORIZED / TOKEN_EXPIRED）= 登录态失效。
          // 这种情况下轮询同样会 401，降级没有意义 → 直接按"登录过期"处理，
          // 抛一个 kind='unauthorized' 让调用方只收尾、不显示降级提示。
          handleSessionExpired('登录已过期，请重新登录')
          onError?.({ kind: 'unauthorized', status: res.status, body })
          return
        }
        onError?.({ kind: 'http', status: res.status, body })
        return
      }
      if (!contentType.includes('text/event-stream')) {
        const body = await safeBody(res)
        onError?.({ kind: 'content-type', status: res.status, contentType, body })
        return
      }
      onOpen?.()

      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      try {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
          let sep
          while ((sep = buffer.indexOf('\n\n')) >= 0) {
            const raw = buffer.slice(0, sep)
            buffer = buffer.slice(sep + 2)
            const frame = parseFrame(raw)
            if (frame) onEvent?.(frame)
          }
        }
        // 流结束时若还有残留（服务端没以空行结尾）
        const tail = parseFrame(buffer)
        if (tail) onEvent?.(tail)
      } catch (err) {
        if (err?.name !== 'AbortError') onError?.({ kind: 'network', message: err?.message })
        return
      }
      if (!closed) onClose?.()
    })
    .catch((err) => {
      if (err?.name === 'AbortError') return
      onError?.({ kind: 'network', message: err?.message || '连接失败' })
    })

  return { close }
}

export default openExecutionStream
