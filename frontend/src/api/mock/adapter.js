/**
 * Mock 适配器：作为 axios 的自定义 adapter 注入。
 *
 * 为什么要做成 adapter 而不是在 api 层写 if (USE_MOCK)：
 *   - 请求拦截器、响应拦截器（401 处理、错误 message）照常生效；
 *   - 业务代码调用 authApi/workflowApi 的写法与真实接口模式完全一致；
 *   - 切回真实接口只需把 USE_MOCK 改成 false，一行都不用动。
 */
import { getDb, saveDb, nextId, publicUser, publicWorkflow, getTools, iso } from './db'

const MOCK_TOKEN_PREFIX = 'mock-jwt.'

function delay(ms = 200) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function parseBody(config) {
  // 阶梯 5：文档上传走 multipart，FormData 不能按 JSON 解析
  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    const out = { __formData: true }
    config.data.forEach((value, key) => {
      out[key] = value
    })
    return out
  }
  if (config.data == null || config.data === '') return {}
  if (typeof config.data === 'string') {
    try {
      return JSON.parse(config.data)
    } catch {
      return {}
    }
  }
  if (typeof config.data === 'object') return config.data
  return {}
}

function normalizePath(url = '') {
  let path = String(url)
  // 兼容某些情况下 url 已带上 baseURL
  if (path.startsWith('http')) {
    try {
      path = new URL(path).pathname
    } catch {
      /* ignore */
    }
  }
  if (path.startsWith('/api')) path = path.slice(4)
  if (!path.startsWith('/')) path = `/${path}`
  return path.replace(/\/+$/, '') || '/'
}

function headerValue(config, name) {
  const headers = config.headers || {}
  const direct = headers[name] ?? headers[name.toLowerCase()] ?? headers[name.toUpperCase()]
  if (direct != null) return String(direct)
  if (typeof headers.get === 'function') {
    const v = headers.get(name)
    return v == null ? '' : String(v)
  }
  return ''
}

function settle(config, status, data) {
  const validateStatus = config.validateStatus || ((s) => s >= 200 && s < 300)
  const response = {
    data,
    status,
    statusText: String(status),
    headers: {},
    config,
    request: { mock: true }
  }
  if (validateStatus(status)) return Promise.resolve(response)
  return Promise.reject(buildError(config, status, data))
}

function buildError(config, status, body) {
  const message = (body && body.message) || '请求失败'
  const err = new Error(message)
  err.isAxiosError = true
  err.config = config
  err.response = {
    data: body && body.code ? body : { code: 'UNKNOWN_ERROR', message },
    status,
    statusText: String(status),
    headers: {},
    config
  }
  return err
}

function fail(config, status, code, message) {
  return Promise.reject(buildError(config, status, { code, message }))
}

/** 从 mock token 里解析用户 id */
function currentUser(config) {
  const auth = headerValue(config, 'Authorization')
  if (!auth.startsWith('Bearer ')) return null
  const token = auth.slice(7)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return null
  const id = Number(token.slice(MOCK_TOKEN_PREFIX.length).split('.')[0])
  if (!Number.isFinite(id)) return null
  return getDb().users.find((u) => u.id === id) || null
}

function issueToken(user) {
  return `${MOCK_TOKEN_PREFIX}${user.id}.${Math.random().toString(36).slice(2)}`
}

function authPayload(user) {
  return { token: issueToken(user), user: publicUser(user) }
}

function publicKnowledgeBase(k) {
  return {
    id: k.id,
    name: k.name,
    description: k.description,
    createdAt: k.createdAt
  }
}

function publicDocument(d) {
  return {
    id: d.id,
    kbId: d.kbId,
    filename: d.filename,
    chunkCount: d.chunkCount,
    fileSize: d.fileSize,
    createdAt: d.createdAt
  }
}

/** 执行记录对外结构（内部字段 snapshot/logs 不透出到 list/detail） */
function publicExecution(e) {
  return {
    id: e.id,
    workflowId: e.workflowId,
    workflowName: e.workflowName ?? null,
    status: e.status,
    input: e.input,
    output: e.output,
    totalTokens: e.totalTokens,
    totalCost: e.totalCost,
    durationMs: e.durationMs,
    errorMessage: e.errorMessage ?? null,
    startedAt: e.startedAt,
    finishedAt: e.finishedAt
  }
}

function paginate(list, page, size) {
  const total = list.length
  const start = (page - 1) * size
  return {
    items: list.slice(start, start + size),
    total,
    page,
    size
  }
}

const routes = [
  // ---------- 认证 ----------
  {
    method: 'post',
    pattern: /^\/auth\/register$/,
    handler(config, body) {
      const db = getDb()
      const username = (body.username || '').trim()
      const password = body.password || ''
      if (!username || !password) return fail(config, 400, 'INVALID_PARAM', '用户名和密码不能为空')
      if (db.users.some((u) => u.username === username)) {
        return fail(config, 400, 'USERNAME_EXISTS', '用户名已存在')
      }
      const user = {
        id: nextId('user'),
        username,
        password,
        email: body.email || '',
        role: 'user',
        createdAt: iso()
      }
      db.users.push(user)
      saveDb()
      return settle(config, 201, authPayload(user))
    }
  },
  {
    method: 'post',
    pattern: /^\/auth\/login$/,
    handler(config, body) {
      const db = getDb()
      const user = db.users.find((u) => u.username === (body.username || '').trim())
      if (!user || user.password !== body.password) {
        return fail(config, 401, 'BAD_CREDENTIALS', '用户名或密码错误')
      }
      return settle(config, 200, authPayload(user))
    }
  },
  {
    method: 'get',
    pattern: /^\/auth\/me$/,
    handler(config) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      return settle(config, 200, publicUser(user))
    }
  },

  // ---------- 工作流 ----------
  {
    method: 'get',
    pattern: /^\/workflows$/,
    handler(config) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const list = db.workflows
        .filter((w) => w.ownerId === user.id)
        .sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt))
        .map(publicWorkflow)
      const params = config.params || {}
      const page = Number(params.page) > 0 ? Number(params.page) : 1
      let size = Number(params.size) > 0 ? Number(params.size) : 10
      size = Math.min(size, 50)
      return settle(config, 200, paginate(list, page, size))
    }
  },
  {
    method: 'post',
    pattern: /^\/workflows$/,
    handler(config, body) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const name = (body.name || '').trim()
      if (!name) return fail(config, 400, 'INVALID_PARAM', '工作流名称不能为空')
      const db = getDb()
      const now = iso()
      const wf = {
        id: nextId('workflow'),
        ownerId: user.id,
        name,
        description: body.description || '',
        status: 'draft',
        nodeCount: 0,
        createdAt: now,
        updatedAt: now
      }
      db.workflows.push(wf)
      saveDb()
      return settle(config, 201, publicWorkflow(wf))
    }
  },
  {
    method: 'get',
    pattern: /^\/workflows\/(\d+)$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const wf = db.workflows.find((w) => w.id === Number(match[1]))
      if (!wf) return fail(config, 404, 'WORKFLOW_NOT_FOUND', '工作流不存在')
      if (wf.ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权访问该工作流')
      return settle(config, 200, publicWorkflow(wf))
    }
  },
  {
    method: 'put',
    pattern: /^\/workflows\/(\d+)$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const wf = db.workflows.find((w) => w.id === Number(match[1]))
      if (!wf) return fail(config, 404, 'WORKFLOW_NOT_FOUND', '工作流不存在')
      if (wf.ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权修改该工作流')
      if (body.name !== undefined) wf.name = body.name
      if (body.description !== undefined) wf.description = body.description
      wf.updatedAt = iso()
      saveDb()
      return settle(config, 200, publicWorkflow(wf))
    }
  },
  {
    method: 'delete',
    pattern: /^\/workflows\/(\d+)$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const idx = db.workflows.findIndex((w) => w.id === Number(match[1]))
      if (idx < 0) return fail(config, 404, 'WORKFLOW_NOT_FOUND', '工作流不存在')
      if (db.workflows[idx].ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权删除该工作流')
      db.workflows.splice(idx, 1)
      saveDb()
      return settle(config, 204, undefined)
    }
  },

  // ---------- 图 ----------
  {
    method: 'get',
    pattern: /^\/workflows\/(\d+)\/graph$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const wf = db.workflows.find((w) => w.id === Number(match[1]))
      if (!wf) return fail(config, 404, 'WORKFLOW_NOT_FOUND', '工作流不存在')
      if (wf.ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权访问该工作流')
      const graph = db.graphs[wf.id] || { nodes: [], edges: [] }
      return settle(config, 200, graph)
    }
  },
  {
    method: 'put',
    pattern: /^\/workflows\/(\d+)\/graph$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const wf = db.workflows.find((w) => w.id === Number(match[1]))
      if (!wf) return fail(config, 404, 'WORKFLOW_NOT_FOUND', '工作流不存在')
      if (wf.ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权修改该工作流')

      const nodes = Array.isArray(body.nodes) ? body.nodes : []
      const edges = Array.isArray(body.edges) ? body.edges : []

      // 图校验（与契约 v1.3 的 7 条规则对齐）
      const keys = nodes.map((n) => n.nodeKey)
      if (new Set(keys).size !== keys.length) {
        return fail(config, 400, 'INVALID_GRAPH', '节点 key 必须唯一')
      }
      const inputs = nodes.filter((n) => n.type === 'input')
      if (inputs.length !== 1) {
        return fail(config, 400, 'INVALID_GRAPH', '图中必须且只能有一个 input 节点')
      }
      const keySet = new Set(keys)
      for (const e of edges) {
        if (!keySet.has(e.sourceNodeKey) || !keySet.has(e.targetNodeKey)) {
          return fail(config, 400, 'INVALID_GRAPH', '边的两端节点必须存在')
        }
      }
      const seen = new Set()
      for (const e of edges) {
        const sig = `${e.sourceNodeKey}>${e.targetNodeKey}>${e.sourceHandle ?? ''}`
        if (seen.has(sig)) return fail(config, 400, 'INVALID_GRAPH', '存在重复边')
        seen.add(sig)
      }

      db.graphs[wf.id] = { nodes, edges }
      wf.nodeCount = nodes.length
      wf.updatedAt = iso()
      saveDb()
      return settle(config, 200, { saved: true })
    }
  },

  // ---------- 工具 ----------
  {
    method: 'get',
    pattern: /^\/tools$/,
    handler(config) {
      if (!currentUser(config)) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      return settle(config, 200, { items: getTools() })
    }
  },

  // ---------- 执行 ----------
  {
    method: 'post',
    pattern: /^\/workflows\/(\d+)\/execute$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const wf = db.workflows.find((w) => w.id === Number(match[1]))
      if (!wf) return fail(config, 404, 'WORKFLOW_NOT_FOUND', '工作流不存在')
      if (wf.ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权执行该工作流')

      const graph = db.graphs[wf.id]
      if (!graph || !graph.nodes.length) {
        return fail(config, 400, 'INVALID_GRAPH', '工作流图为空，请先添加节点')
      }
      const inputNode = graph.nodes.find((n) => n.type === 'input')
      const fields = inputNode?.config?.fields || []
      const input = body.input || {}
      for (const f of fields) {
        const hasDefault = f.default !== undefined && f.default !== null && f.default !== ''
        if (!hasDefault && (input[f.key] === undefined || input[f.key] === '')) {
          return fail(config, 400, 'INVALID_INPUT', `缺少必填字段：${f.label || f.key}`)
        }
      }
      for (const f of fields) {
        const hasDefault = f.default !== undefined && f.default !== null && f.default !== ''
        if (input[f.key] === undefined && hasDefault) input[f.key] = f.default
      }

      const exec = {
        id: nextId('execution'),
        workflowId: wf.id,
        workflowName: wf.name,
        status: 'running',
        input,
        output: {},
        totalTokens: 0,
        totalCost: 0,
        durationMs: 0,
        errorMessage: null,
        startedAt: iso(),
        finishedAt: null,
        snapshot: JSON.parse(JSON.stringify(graph.nodes)),
        logs: []
      }
      db.executions.unshift(exec)
      saveDb()
      return settle(config, 202, { executionId: exec.id })
    }
  },
  {
    method: 'get',
    pattern: /^\/executions$/,
    handler(config) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const mine = new Set(db.workflows.filter((w) => w.ownerId === user.id).map((w) => w.id))
      const params = config.params || {}
      let list = db.executions.filter((e) => mine.has(e.workflowId))
      if (params.workflowId) list = list.filter((e) => String(e.workflowId) === String(params.workflowId))
      const page = Number(params.page) > 0 ? Number(params.page) : 1
      const size = Math.min(Number(params.size) > 0 ? Number(params.size) : 10, 50)
      return settle(config, 200, paginate(list.map(publicExecution), page, size))
    }
  },
  {
    method: 'get',
    pattern: /^\/executions\/(\d+)$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const exec = db.executions.find((e) => e.id === Number(match[1]))
      if (!exec) return fail(config, 404, 'EXECUTION_NOT_FOUND', '执行记录不存在')
      const wf = db.workflows.find((w) => w.id === exec.workflowId)
      if (!wf || wf.ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权查看该执行记录')
      return settle(config, 200, publicExecution(exec))
    }
  },
  {
    method: 'get',
    pattern: /^\/executions\/(\d+)\/logs$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const exec = db.executions.find((e) => e.id === Number(match[1]))
      if (!exec) return fail(config, 404, 'EXECUTION_NOT_FOUND', '执行记录不存在')
      const wf = db.workflows.find((w) => w.id === exec.workflowId)
      if (!wf || wf.ownerId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权查看该执行记录')
      return settle(config, 200, { items: exec.logs || [], total: (exec.logs || []).length })
    }
  },

  // ---------- 知识库（契约 v1.5 / 阶梯 5）----------
  {
    method: 'get',
    pattern: /^\/knowledge-bases$/,
    handler(config) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const list = db.knowledgeBases
        .filter((k) => k.userId === user.id)
        .map((k) => ({
          ...publicKnowledgeBase(k),
          documentCount: db.kbDocuments.filter((d) => d.kbId === k.id).length
        }))
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
      const params = config.params || {}
      const page = Number(params.page) > 0 ? Number(params.page) : 1
      const size = Math.min(Number(params.size) > 0 ? Number(params.size) : 10, 50)
      return settle(config, 200, paginate(list, page, size))
    }
  },
  {
    method: 'post',
    pattern: /^\/knowledge-bases$/,
    handler(config, body) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const name = String(body.name || '').trim()
      if (!name) return fail(config, 400, 'VALIDATION_ERROR', '知识库名称不能为空')
      const db = getDb()
      const kb = {
        id: nextId('knowledgeBase'),
        userId: user.id,
        name,
        description: body.description || '',
        createdAt: iso()
      }
      db.knowledgeBases.push(kb)
      saveDb()
      return settle(config, 201, publicKnowledgeBase(kb))
    }
  },
  {
    method: 'delete',
    pattern: /^\/knowledge-bases\/(\d+)$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const idx = db.knowledgeBases.findIndex((k) => k.id === Number(match[1]))
      if (idx < 0) return fail(config, 404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在')
      if (db.knowledgeBases[idx].userId !== user.id) {
        return fail(config, 403, 'FORBIDDEN', '无权删除该知识库')
      }
      const kbId = db.knowledgeBases[idx].id
      db.knowledgeBases.splice(idx, 1)
      // 级联删除文档（真实后端还会删 Chroma collection）
      db.kbDocuments = db.kbDocuments.filter((d) => d.kbId !== kbId)
      saveDb()
      return settle(config, 204, undefined)
    }
  },
  {
    method: 'get',
    pattern: /^\/knowledge-bases\/(\d+)\/documents$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const kb = db.knowledgeBases.find((k) => k.id === Number(match[1]))
      if (!kb) return fail(config, 404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在')
      if (kb.userId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权访问该知识库')
      const list = db.kbDocuments
        .filter((d) => d.kbId === kb.id)
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
        .map(publicDocument)
      return settle(config, 200, { items: list, total: list.length })
    }
  },
  {
    method: 'post',
    pattern: /^\/knowledge-bases\/(\d+)\/documents$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const kb = db.knowledgeBases.find((k) => k.id === Number(match[1]))
      if (!kb) return fail(config, 404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在')
      if (kb.userId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权向该知识库上传文档')

      const file = body.file
      if (!file) return fail(config, 400, 'VALIDATION_ERROR', '请选择要上传的文件')
      const filename = file.name || 'unknown.txt'
      const ext = String(filename).split('.').pop().toLowerCase()
      if (!['txt', 'md', 'pdf'].includes(ext)) {
        return fail(config, 400, 'DOCUMENT_PARSE_ERROR', '仅支持 txt / md / pdf 文档')
      }
      const size = file.size || 1024
      if (size > 5 * 1024 * 1024) {
        return fail(config, 400, 'DOCUMENT_TOO_LARGE', '文档不能超过 5MB')
      }
      // 假装有切分：约每 500 字一个 chunk
      const chunkCount = Math.max(1, Math.min(99, Math.round(size / 500)))
      const doc = {
        id: nextId('kbDoc'),
        kbId: kb.id,
        filename,
        chunkCount,
        fileSize: size,
        createdAt: iso()
      }
      db.kbDocuments.push(doc)
      saveDb()
      return settle(config, 201, publicDocument(doc))
    }
  },
  {
    method: 'delete',
    pattern: /^\/knowledge-bases\/(\d+)\/documents\/(\d+)$/,
    handler(config, body, match) {
      const user = currentUser(config)
      if (!user) return fail(config, 401, 'UNAUTHORIZED', '未登录或登录已过期')
      const db = getDb()
      const kb = db.knowledgeBases.find((k) => k.id === Number(match[1]))
      if (!kb) return fail(config, 404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在')
      if (kb.userId !== user.id) return fail(config, 403, 'FORBIDDEN', '无权操作该知识库')
      const idx = db.kbDocuments.findIndex(
        (d) => d.id === Number(match[2]) && d.kbId === Number(match[1])
      )
      if (idx < 0) return fail(config, 404, 'KNOWLEDGE_BASE_NOT_FOUND', '文档不存在')
      db.kbDocuments.splice(idx, 1)
      saveDb()
      return settle(config, 204, undefined)
    }
  }
]

export async function mockAdapter(config) {
  await delay(180)
  const path = normalizePath(config.url)
  const method = String(config.method || 'get').toLowerCase()
  const body = parseBody(config)
  for (const route of routes) {
    if (route.method !== method) continue
    const match = path.match(route.pattern)
    if (match) return route.handler(config, body, match)
  }
  return fail(config, 404, 'NOT_FOUND', `mock 未实现的接口：${method.toUpperCase()} ${path}`)
}

export default mockAdapter
