/**
 * Mock 版 SSE（仅在 USE_MOCK = true 时由 utils/sse.js 调用）
 *
 * 真实接口走 fetch + ReadableStream，而 mock 模式下 axios adapter 拦不到 fetch，
 * 所以这里在 SSE 工具层做等价替换——对上层页面来说事件协议完全一致。
 * 事件字段严格按契约 v1.3，不做任何增删改。
 */
import { getDb, saveDb, iso } from './db'

const MOCK_ANSWER = [
  '根据当前天气情况，给你三点建议：',
  '1. 早晚温差较大，建议采用"薄外套 + 短袖"的洋葱式穿法，方便随时增减。',
  '2. 湿度偏高、有短时阵雨的可能，出门记得带一把折叠伞。',
  '3. 紫外线中等，长时间户外建议做好防晒并及时补水。',
  '（本结果由 MOCK 模式生成，未真实调用大模型）'
].join('\n')

/**
 * mock 的 RAG 引用来源：取该知识库下的真实文档名（假数据里建的），
 * 让「引用来源」在 mock 模式下也能端到端看到效果。
 */
function mockReferences(kbId) {
  if (kbId == null) return []
  const db = getDb()
  const docs = (db.kbDocuments || []).filter((d) => d.kbId === Number(kbId)).slice(0, 3)
  if (!docs.length) {
    return [
      { filename: '示例文档.txt', snippet: '（mock）这是知识库里的一段示例内容，用于演示引用来源。', score: 0.88 }
    ]
  }
  return docs.map((d, i) => ({
    filename: d.filename,
    snippet: `（mock）来自 ${d.filename} 的第 ${i + 1} 个片段：${d.chunkCount} 个切片中最相关的一段。`,
    score: Number((0.92 - i * 0.06).toFixed(2))
  }))
}

function chunkText(text, size = 6) {
  const out = []
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size))
  return out
}

export function openMockStream({ executionId, onEvent, onError, onOpen, onClose }) {
  const db = getDb()
  const exec = db.executions.find((e) => e.id === Number(executionId))
  if (!exec) {
    setTimeout(() => onError?.({ kind: 'http', status: 404, body: '{"code":"EXECUTION_NOT_FOUND"}' }), 150)
    return { close() {} }
  }

  let closed = false
  const timers = []
  const emit = (event, data) => {
    if (!closed) onEvent?.({ event, data })
  }
  const wait = (ms) =>
    new Promise((resolve) => {
      const t = setTimeout(resolve, ms)
      timers.push(t)
    })

  ;(async () => {
    await wait(180)
    if (closed) return
    onOpen?.()

    const nodes = exec.snapshot || []
    emit('execution_started', { executionId: exec.id, totalNodes: nodes.length })

    const output = {}
    let totalTokens = 0
    let totalMs = 0

    for (const node of nodes) {
      if (closed) return
      emit('node_started', { nodeKey: node.nodeKey, name: node.name })
      await wait(320)

      if (node.type === 'llm') {
        const chunks = chunkText(MOCK_ANSWER)
        for (const delta of chunks) {
          if (closed) return
          emit('token', { nodeKey: node.nodeKey, delta })
          await wait(28)
        }
        const tokens = 180 + Math.floor(Math.random() * 120)
        const durationMs = 900 + chunks.length * 28
        totalTokens += tokens
        totalMs += durationMs
        const out = { [node.config?.output_key || 'text']: MOCK_ANSWER }
        // 契约 v1.5：引用了知识库的 llm 节点，输出附带 references
        const refs = mockReferences(node.config?.knowledge_base_id)
        if (refs.length) out.references = refs
        output[node.nodeKey] = out
        emit('node_completed', { nodeKey: node.nodeKey, durationMs })
      } else if (node.type === 'http') {
        await wait(460)
        const durationMs = 520
        totalMs += durationMs
        output[node.nodeKey] = {
          [node.config?.output_key || 'data']: {
            mock: true,
            city: exec.input?.city ?? '南昌',
            temp_C: 26,
            weatherDesc: '多云转晴'
          }
        }
        emit('node_completed', { nodeKey: node.nodeKey, durationMs })
      } else if (node.type === 'condition') {
        await wait(220)
        totalMs += 12
        emit('node_completed', { nodeKey: node.nodeKey, durationMs: 12 })
      } else {
        await wait(180)
        totalMs += 6
        output[node.nodeKey] = { ...(exec.input || {}) }
        emit('node_completed', { nodeKey: node.nodeKey, durationMs: 6 })
      }
    }

    if (closed) return
    exec.output = { ...output, input: exec.input || {} }
    exec.status = 'success'
    exec.totalTokens = totalTokens
    exec.totalCost = Number((totalTokens * 0.00000021).toFixed(6))
    exec.durationMs = totalMs + 120
    exec.finishedAt = iso()
    exec.logs = (exec.snapshot || []).map((n) => ({
      nodeKey: n.nodeKey,
      nodeName: n.name,
      nodeType: n.type,
      status: 'success',
      input: n.type === 'llm' ? { prompt: n.config?.prompt || '' } : exec.input || {},
      output: output[n.nodeKey] || {},
      tokens: n.type === 'llm' ? totalTokens : 0,
      durationMs: n.type === 'llm' ? exec.durationMs : 300,
      errorMessage: null
    }))
    saveDb()

    emit('execution_completed', {
      output: exec.output,
      totalTokens: exec.totalTokens,
      durationMs: exec.durationMs
    })
    onClose?.()
  })()

  return {
    close() {
      closed = true
      timers.forEach((t) => clearTimeout(t))
    }
  }
}

export default openMockStream
