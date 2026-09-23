/**
 * 表单式编辑器的图模型。
 *
 * 关键约束（来自任务卡）：阶梯 1 是表单式编辑器，但数据结构与画布版完全一致
 * （契约 v1.3 的 nodes / edges），阶梯 2 只换交互层。所以这里所有转换都以契约为唯一事实来源。
 *
 * 表单态节点（仅前端使用）：
 * {
 *   uid, nodeKey, type, name,
 *   config,      // 与契约 config 同构（http 的 params 除外，见下）
 *   paramsRows,  // http 的 params 在契约里是对象，表单里用 [{key,value}] 编辑，保存时转换
 *   sources      // 上游来源：[{ from: 上游 nodeKey, handle: 分支名或 '' }]
 * }
 */

export const NODE_TYPE_META = {
  input: { label: '输入', short: 'IN', desc: '定义流程的入参字段' },
  llm: { label: 'LLM', short: 'AI', desc: '调用大模型生成内容' },
  condition: { label: '条件', short: 'IF', desc: '按条件走不同分支' },
  http: { label: '工具', short: 'HTTP', desc: '调用内置工具 / HTTP 接口' }
}

export const NODE_TYPES = ['input', 'http', 'llm', 'condition']

/** 契约「环境信息」：模型名以 2026-09 实测 GET /models 为准 */
export const LLM_MODELS = [
  { value: 'deepseek-flash', label: 'deepseek-flash（快）' },
  { value: 'deepseek-v4-pro', label: 'deepseek-v4-pro（推理模型，首 token 较慢）' }
]

/** 契约 condition.operator 支持项 */
export const CONDITION_OPERATORS = [
  { value: 'equals', label: '等于' },
  { value: 'not_equals', label: '不等于' },
  { value: 'contains', label: '包含' },
  { value: 'not_empty', label: '非空' }
]

let uidSeq = 0
export function uid() {
  uidSeq += 1
  return `u${Date.now().toString(36)}${uidSeq}`
}

export function defaultName(type) {
  return (
    { input: '输入', llm: 'AI 生成', condition: '条件判断', http: '工具调用' }[type] || '节点'
  )
}

export function defaultConfig(type) {
  switch (type) {
    case 'input':
      // 默认一行空字段：预填 city/城市 会把 placeholder 盖住，新手反而不知道要填什么
      return { fields: [{ key: '', label: '', type: 'text', default: '' }] }
    case 'llm':
      // 契约 v1.4：tools（tools 表 id 列表）+ max_tool_rounds（默认 3，防死循环）为可选字段
      return {
        model: 'deepseek-flash',
        prompt: '',
        temperature: 0.7,
        output_key: 'text',
        tools: [],
        max_tool_rounds: 3
      }
    case 'condition':
      return {
        rules: [{ left: '', operator: 'equals', right: '', handle: 'branch_a' }],
        default_handle: 'other'
      }
    case 'http':
      return { tool_id: null, params: {}, output_key: 'data' }
    default:
      return {}
  }
}

/** 契约 config 可能因后端缺字段，补齐成表单能直接渲染的形状 */
function normalizeConfig(type, config = {}) {
  const base = defaultConfig(type)
  const merged = { ...base, ...config }
  if (type === 'input') {
    merged.fields = Array.isArray(config.fields) && config.fields.length ? config.fields : base.fields
    merged.fields = merged.fields.map((f) => ({ type: 'text', ...f }))
  }
  if (type === 'llm') {
    merged.temperature = Number.isFinite(Number(config.temperature)) ? Number(config.temperature) : 0.7
    merged.tools = Array.isArray(config.tools) ? config.tools.filter((t) => t != null) : []
    const rounds = config.max_tool_rounds
    merged.max_tool_rounds = rounds === '' || rounds === null || rounds === undefined
      ? null
      : Number(rounds)
  }
  if (type === 'condition') {
    merged.rules = Array.isArray(config.rules) && config.rules.length ? config.rules : base.rules
    merged.rules = merged.rules.map((r) => ({ operator: 'equals', ...r }))
    merged.default_handle = merged.default_handle || 'other'
  }
  if (type === 'http') {
    merged.tool_id = config.tool_id ?? null
    merged.output_key = config.output_key || 'data'
  }
  return merged
}

export function paramsToRows(params) {
  if (Array.isArray(params)) return params.map((p) => ({ key: p.key ?? '', value: p.value ?? '' }))
  if (params && typeof params === 'object') {
    return Object.entries(params).map(([key, value]) => ({ key, value: String(value ?? '') }))
  }
  return []
}

export function rowsToParams(rows = []) {
  const out = {}
  for (const r of rows) {
    const key = String(r.key ?? '').trim()
    if (!key) continue
    out[key] = r.value ?? ''
  }
  return out
}

function nextNodeKey(type, nodes = []) {
  const used = new Set(nodes.map((n) => n.nodeKey))
  let i = 1
  while (used.has(`${type}_${i}`)) i += 1
  return `${type}_${i}`
}

export function createNode(type, nodes = []) {
  return {
    uid: uid(),
    nodeKey: nextNodeKey(type, nodes),
    type,
    name: defaultName(type),
    config: normalizeConfig(type, defaultConfig(type)),
    paramsRows: type === 'http' ? [] : undefined,
    sources: type === 'input' ? [] : [{ from: '', handle: '' }]
  }
}

/** 契约 { nodes, edges } -> 表单态节点数组 */
export function graphToForm(graph) {
  const rawNodes = Array.isArray(graph?.nodes) ? graph.nodes : []
  const nodes = rawNodes.map((n) => ({
    uid: uid(),
    nodeKey: n.nodeKey,
    type: n.type,
    name: n.name || defaultName(n.type),
    config: normalizeConfig(n.type, n.config),
    paramsRows: n.type === 'http' ? paramsToRows(n.config?.params) : undefined,
    sources: []
  }))

  const byKey = new Map(nodes.map((n) => [n.nodeKey, n]))
  for (const e of Array.isArray(graph?.edges) ? graph.edges : []) {
    const target = byKey.get(e.targetNodeKey)
    const source = byKey.get(e.sourceNodeKey)
    if (!target || !source) continue
    target.sources.push({ from: e.sourceNodeKey, handle: e.sourceHandle || '' })
  }

  nodes.forEach((n) => {
    if (n.type !== 'input' && !n.sources.length) n.sources.push({ from: '', handle: '' })
  })
  return nodes
}

/** 表单态节点数组 -> 契约 { nodes, edges } */
export function formToGraph(nodes) {
  const outNodes = nodes.map((n, index) => {
    const config = { ...n.config }
    if (n.type === 'http') config.params = rowsToParams(n.paramsRows)
    if (n.type === 'llm') config.temperature = Number(config.temperature ?? 0.7)
    if (n.type === 'condition') {
      config.rules = (config.rules || []).map((r) => ({ ...r }))
    }
    return {
      nodeKey: n.nodeKey,
      type: n.type,
      name: n.name || defaultName(n.type),
      config,
      positionX: 0,
      positionY: index * 180
    }
  })

  const edges = []
  const seen = new Set()
  nodes.forEach((n) => {
    for (const s of n.sources || []) {
      if (!s.from || s.from === n.nodeKey) continue
      const sig = `${s.from}>${n.nodeKey}>${s.handle || ''}`
      if (seen.has(sig)) continue
      seen.add(sig)
      edges.push({
        sourceNodeKey: s.from,
        targetNodeKey: n.nodeKey,
        sourceHandle: s.handle || null
      })
    }
  })

  return { nodes: outNodes, edges }
}

/** 条件节点可选分支：rules[].handle + default_handle */
export function branchHandles(node) {
  if (!node || node.type !== 'condition') return []
  const handles = (node.config?.rules || []).map((r) => r.handle).filter(Boolean)
  if (node.config?.default_handle) handles.push(node.config.default_handle)
  return Array.from(new Set(handles))
}

/**
 * 某个节点可以引用的上游字段（用于 llm prompt / http params 的「插入引用」下拉）。
 * 取该节点之前的所有节点，按类型给出可引用路径。
 * 实测：后端对 {{input.city}} 与 {{input_1.city}} 都能解析，这里统一用 nodeKey 形式。
 */
export function upstreamFields(nodes, index) {
  const out = []
  for (let i = 0; i < index; i += 1) {
    const n = nodes[i]
    if (!n) continue
    if (n.type === 'input') {
      for (const f of n.config?.fields || []) {
        if (!f.key) continue
        out.push({
          nodeName: n.name,
          label: `${n.name} · ${f.label || f.key}`,
          ref: `{{${n.nodeKey}.${f.key}}}`
        })
      }
    } else if (n.type === 'llm' || n.type === 'http') {
      const key = n.config?.output_key || (n.type === 'llm' ? 'text' : 'data')
      out.push({ nodeName: n.name, label: `${n.name} · 输出（${key}）`, ref: `{{${n.nodeKey}.${key}}}` })
    }
  }
  return out
}

/** 模板 1：天气助手（input → http → llm） */
export function weatherTemplate() {
  const input = {
    uid: uid(),
    nodeKey: 'input_1',
    type: 'input',
    name: '输入',
    config: normalizeConfig('input', {
      fields: [{ key: 'city', label: '城市', type: 'text', default: '南昌' }]
    }),
    sources: []
  }
  const http = {
    uid: uid(),
    nodeKey: 'http_1',
    type: 'http',
    name: '查天气',
    config: normalizeConfig('http', { tool_id: 1, params: {}, output_key: 'data' }),
    paramsRows: [{ key: 'city', value: '{{input_1.city}}' }],
    sources: [{ from: 'input_1', handle: '' }]
  }
  const llm = {
    uid: uid(),
    nodeKey: 'llm_1',
    type: 'llm',
    name: 'AI 建议',
    config: normalizeConfig('llm', {
      model: 'deepseek-flash',
      prompt: '城市 {{input_1.city}} 的天气数据：{{http_1.data}}\n请给出 3 条简洁的穿衣建议，分点列出。',
      temperature: 0.7,
      output_key: 'text'
    }),
    sources: [{ from: 'http_1', handle: '' }]
  }
  return [input, http, llm]
}

/** 模板 2：条件分支示例（input → condition → 两条 llm 分支） */
export function branchTemplate() {
  const input = {
    uid: uid(),
    nodeKey: 'input_1',
    type: 'input',
    name: '输入',
    config: normalizeConfig('input', {
      fields: [{ key: 'city', label: '城市', type: 'text', default: '北京' }]
    }),
    sources: []
  }
  const condition = {
    uid: uid(),
    nodeKey: 'condition_1',
    type: 'condition',
    name: '是否北京',
    config: normalizeConfig('condition', {
      rules: [{ left: '{{input_1.city}}', operator: 'equals', right: '北京', handle: 'beijing' }],
      default_handle: 'other'
    }),
    sources: [{ from: 'input_1', handle: '' }]
  }
  const llmBeijing = {
    uid: uid(),
    nodeKey: 'llm_1',
    type: 'llm',
    name: '北京专属建议',
    config: normalizeConfig('llm', {
      model: 'deepseek-flash',
      prompt: '用户所在城市是北京：{{input_1.city}}。请给一句北京出行提醒，不超过 30 字。',
      temperature: 0.7,
      output_key: 'text'
    }),
    sources: [{ from: 'condition_1', handle: 'beijing' }]
  }
  const llmOther = {
    uid: uid(),
    nodeKey: 'llm_2',
    type: 'llm',
    name: '其他城市建议',
    config: normalizeConfig('llm', {
      model: 'deepseek-flash',
      prompt: '用户所在城市不是北京，而是：{{input_1.city}}。请给一句出行提醒，不超过 30 字。',
      temperature: 0.7,
      output_key: 'text'
    }),
    sources: [{ from: 'condition_1', handle: 'other' }]
  }
  return [input, condition, llmBeijing, llmOther]
}

/* ============================================================
   画布层（阶梯 2）
   ------------------------------------------------------------
   只做「契约 nodes/edges ⇄ Vue Flow nodes/edges」的相互转换，
   数据模型与接口完全不变，表单版（formToGraph/graphToForm）继续可用。
   Vue Flow 节点：{ id, type, position, data }
   data = { nodeKey, type, name, config, paramsRows?, status? }
   ============================================================ */

/** Vue Flow 节点类型名加前缀，避开 Vue Flow 自带的 input/output 类型 */
export function canvasNodeType(type) {
  return `fm-${type}`
}

function nextKeyOf(type, keys) {
  const used = new Set(keys)
  let i = 1
  while (used.has(`${type}_${i}`)) i += 1
  return `${type}_${i}`
}

export function createCanvasNode(type, canvasNodes = [], position = { x: 0, y: 0 }) {
  const keys = canvasNodes.map((n) => n.data?.nodeKey).filter(Boolean)
  const nodeKey = nextKeyOf(type, keys)
  return {
    id: nodeKey,
    type: canvasNodeType(type),
    position: { x: Math.round(position.x), y: Math.round(position.y) },
    data: {
      nodeKey,
      type,
      name: defaultName(type),
      config: normalizeConfig(type, defaultConfig(type)),
      paramsRows: type === 'http' ? [] : undefined,
      status: 'pending'
    }
  }
}

/** 契约 { nodes, edges } -> Vue Flow { nodes, edges } */
export function graphToCanvas(graph) {
  const nodes = (Array.isArray(graph?.nodes) ? graph.nodes : []).map((n, i) => ({
    id: n.nodeKey,
    type: canvasNodeType(n.type),
    position: {
      x: Number.isFinite(Number(n.positionX)) ? Number(n.positionX) : 80 + i * 40,
      y: Number.isFinite(Number(n.positionY)) ? Number(n.positionY) : 80 + i * 40
    },
    data: {
      nodeKey: n.nodeKey,
      type: n.type,
      name: n.name || defaultName(n.type),
      config: normalizeConfig(n.type, n.config),
      paramsRows: n.type === 'http' ? paramsToRows(n.config?.params) : undefined,
      status: 'pending'
    }
  }))

  const edges = (Array.isArray(graph?.edges) ? graph.edges : []).map((e, i) => ({
    id: `e-${e.sourceNodeKey}-${e.targetNodeKey}-${e.sourceHandle || 'n'}-${i}`,
    source: e.sourceNodeKey,
    target: e.targetNodeKey,
    sourceHandle: e.sourceHandle || null
  }))

  return { nodes, edges }
}

/** 画布节点 -> 契约 config（http 的 params 由行转对象；llm 的可选字段按需下发） */
function configToWire(node) {
  const { type, config } = node.data
  const out = { ...config }
  if (type === 'http') {
    out.params = rowsToParams(node.data.paramsRows)
  }
  if (type === 'llm') {
    out.temperature = Number(config.temperature ?? 0.7)
    // tools / max_tool_rounds 是契约 v1.4 的可选字段：
    // 只有真正配置了才下发，避免影响后端尚不支持的旧链路
    if (Array.isArray(config.tools) && config.tools.length) out.tools = config.tools
    else delete out.tools
    const rounds = config.max_tool_rounds
    if (rounds === null || rounds === undefined || rounds === '') delete out.max_tool_rounds
    else out.max_tool_rounds = Number(rounds)
  }
  return out
}

/** Vue Flow { nodes, edges } -> 契约 { nodes, edges } */
export function canvasToGraph(canvasNodes, canvasEdges) {
  const nodes = (canvasNodes || []).map((n) => ({
    nodeKey: n.data.nodeKey,
    type: n.data.type,
    name: n.data.name || defaultName(n.data.type),
    config: configToWire(n),
    positionX: Math.round(n.position?.x ?? 0),
    positionY: Math.round(n.position?.y ?? 0)
  }))

  const edges = []
  const seen = new Set()
  for (const e of canvasEdges || []) {
    if (!e.source || !e.target) continue
    const sig = `${e.source}>${e.target}>${e.sourceHandle || ''}`
    if (seen.has(sig)) continue
    seen.add(sig)
    edges.push({
      sourceNodeKey: e.source,
      targetNodeKey: e.target,
      sourceHandle: e.sourceHandle || null
    })
  }
  return { nodes, edges }
}

/** 沿边往前找所有祖先节点（画布版「插入引用」用），自带环保护 */
export function ancestorsOf(nodeKey, canvasNodes, edges) {
  const byId = new Map((canvasNodes || []).map((n) => [n.id, n]))
  const out = []
  const seen = new Set()
  const queue = [nodeKey]
  while (queue.length) {
    const cur = queue.shift()
    for (const e of edges || []) {
      if (e.target !== cur || seen.has(e.source)) continue
      seen.add(e.source)
      const node = byId.get(e.source)
      if (node) {
        out.push(node)
        queue.push(e.source)
      }
    }
  }
  return out
}

/** 某个画布节点可以引用的上游字段（{{nodeKey.字段}}） */
export function refsFrom(nodeKey, canvasNodes, edges) {
  const out = []
  for (const n of ancestorsOf(nodeKey, canvasNodes, edges)) {
    const t = n.data?.type
    if (t === 'input') {
      for (const f of n.data.config?.fields || []) {
        if (!f.key) continue
        out.push({
          nodeName: n.data.name,
          label: `${n.data.name} · ${f.label || f.key}`,
          ref: `{{${n.data.nodeKey}.${f.key}}}`
        })
      }
    } else if (t === 'llm' || t === 'http') {
      const key = n.data.config?.output_key || (t === 'llm' ? 'text' : 'data')
      out.push({
        nodeName: n.data.name,
        label: `${n.data.name} · 输出（${key}）`,
        ref: `{{${n.data.nodeKey}.${key}}}`
      })
    }
  }
  return out
}

function mkCanvasNode(nodeKey, type, name, config, x, y, paramsRows) {
  return {
    id: nodeKey,
    type: canvasNodeType(type),
    position: { x, y },
    data: {
      nodeKey,
      type,
      name,
      config: normalizeConfig(type, config),
      paramsRows,
      status: 'pending'
    }
  }
}

/** 画布模板 1：天气助手（input → http → llm，横向排布） */
export function weatherCanvasGraph() {
  return {
    nodes: [
      mkCanvasNode('input_1', 'input', '输入', {
        fields: [{ key: 'city', label: '城市', type: 'text', default: '南昌' }]
      }, 60, 150),
      mkCanvasNode(
        'http_1',
        'http',
        '查天气',
        { tool_id: 1, params: {}, output_key: 'data' },
        360,
        150,
        [{ key: 'city', value: '{{input_1.city}}' }]
      ),
      mkCanvasNode('llm_1', 'llm', 'AI 建议', {
        model: 'deepseek-flash',
        prompt: '城市 {{input_1.city}} 的天气数据：{{http_1.data}}\n请给出 3 条简洁的穿衣建议，分点列出。',
        temperature: 0.7,
        output_key: 'text'
      }, 660, 150)
    ],
    edges: [
      { id: 'e-input_1-http_1', source: 'input_1', target: 'http_1', sourceHandle: null },
      { id: 'e-http_1-llm_1', source: 'http_1', target: 'llm_1', sourceHandle: null }
    ]
  }
}

/** 画布模板 2：条件分支（input → condition → 两条 llm 分支） */
export function branchCanvasGraph() {
  return {
    nodes: [
      mkCanvasNode('input_1', 'input', '输入', {
        fields: [{ key: 'city', label: '城市', type: 'text', default: '北京' }]
      }, 60, 190),
      mkCanvasNode('condition_1', 'condition', '是否北京', {
        rules: [{ left: '{{input_1.city}}', operator: 'equals', right: '北京', handle: 'beijing' }],
        default_handle: 'other'
      }, 360, 190),
      mkCanvasNode('llm_1', 'llm', '北京专属建议', {
        model: 'deepseek-flash',
        prompt: '用户所在城市是北京：{{input_1.city}}。请给一句北京出行提醒，不超过 30 字。',
        temperature: 0.7,
        output_key: 'text'
      }, 680, 70),
      mkCanvasNode('llm_2', 'llm', '其他城市建议', {
        model: 'deepseek-flash',
        prompt: '用户所在城市不是北京，而是：{{input_1.city}}。请给一句出行提醒，不超过 30 字。',
        temperature: 0.7,
        output_key: 'text'
      }, 680, 320)
    ],
    edges: [
      { id: 'e-input_1-condition_1', source: 'input_1', target: 'condition_1', sourceHandle: null },
      { id: 'e-condition_1-llm_1-beijing', source: 'condition_1', target: 'llm_1', sourceHandle: 'beijing' },
      { id: 'e-condition_1-llm_2-other', source: 'condition_1', target: 'llm_2', sourceHandle: 'other' }
    ]
  }
}
