/**
 * Mock 适配器：作为 axios 的自定义 adapter 注入。
 *
 * 为什么要做成 adapter 而不是在 api 层写 if (USE_MOCK)：
 *   - 请求拦截器、响应拦截器（401 处理、错误 message）照常生效；
 *   - 业务代码调用 authApi/workflowApi 的写法与真实接口模式完全一致；
 *   - 切回真实接口只需把 USE_MOCK 改成 false，一行都不用动。
 */
import { getDb, saveDb, nextId, publicUser, publicWorkflow, iso } from './db'

const MOCK_TOKEN_PREFIX = 'mock-jwt.'

function delay(ms = 200) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function parseBody(config) {
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
