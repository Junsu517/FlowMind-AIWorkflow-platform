/**
 * Mock 数据源：localStorage 持久化，保证刷新页面后注册/新建的数据还在。
 * 只在 USE_MOCK = true 时生效，真实接口模式下这个文件完全不参与。
 */
const DB_KEY = 'flowmind_mock_db_v2'

function iso(minusMinutes = 0) {
  const d = new Date(Date.now() - minusMinutes * 60000)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function seed() {
  return {
    users: [
      {
        id: 1,
        username: 'tom',
        password: '12345678',
        email: 'tom@x.com',
        role: 'user',
        createdAt: iso(60 * 24 * 3)
      }
    ],
    workflows: [
      {
        id: 1,
        ownerId: 1,
        name: '天气助手',
        description: '查询城市天气并给出穿衣建议',
        status: 'draft',
        nodeCount: 3,
        createdAt: iso(60 * 24 * 2),
        updatedAt: iso(180)
      },
      {
        id: 2,
        ownerId: 1,
        name: '客服问答机器人',
        description: '基于知识库回答常见问题，答不上来转人工',
        status: 'draft',
        nodeCount: 6,
        createdAt: iso(60 * 24),
        updatedAt: iso(45)
      },
      {
        id: 3,
        ownerId: 1,
        name: '周报自动生成',
        description: '汇总本周提交记录，调用大模型生成周报草稿',
        status: 'draft',
        nodeCount: 3,
        createdAt: iso(300),
        updatedAt: iso(20)
      }
    ],
    // 图数据：{ [workflowId]: { nodes: [...], edges: [...] } }
    graphs: {},
    // 执行记录（契约 GET /executions/{id} 的结构）
    executions: [],
    // 阶梯 5：知识库 + 文档（契约 v1.5）
    knowledgeBases: [],
    kbDocuments: [],
    // 阶梯 7：定时计划（契约 v1.7），按 workflowId 存
    schedules: {},
    // 阶梯 8：发布 API Key（契约 v1.8），按 workflowId 存 { apiKey, createdAt }
    apiKeys: {},
    // 外部调用限流用的时间戳（mock 模拟后端限流）
    apiCallLog: [],
    idSeq: { user: 1, workflow: 3, execution: 0, knowledgeBase: 0, kbDoc: 0 }
  }
}

/** 契约：GET /api/tools 的内置工具 */
const TOOLS = [
  {
    id: 1,
    name: 'weather_query',
    displayName: '天气查询',
    description: '查询指定城市的实时天气情况',
    method: 'GET',
    paramsSchema: [{ key: 'city', label: '城市', type: 'text', required: true }]
  },
  {
    id: 2,
    name: 'hitokoto',
    displayName: '一言',
    description: '随机获取一句名人名言或语录',
    method: 'GET',
    paramsSchema: []
  }
]

let cache = null

function normalize(db) {
  if (!db.tools) db.tools = TOOLS
  if (!db.graphs) db.graphs = {}
  if (!db.executions) db.executions = []
  if (!db.knowledgeBases) db.knowledgeBases = []
  if (!db.kbDocuments) db.kbDocuments = []
  if (!db.schedules) db.schedules = {}
  if (!db.apiKeys) db.apiKeys = {}
  if (!db.idSeq) db.idSeq = { user: 1, workflow: 1, execution: 0, knowledgeBase: 0, kbDoc: 0 }
  if (db.idSeq.execution == null) db.idSeq.execution = 0
  return db
}

export function getDb() {
  if (cache) return cache
  try {
    const raw = localStorage.getItem(DB_KEY)
    cache = raw ? normalize(JSON.parse(raw)) : seed()
  } catch {
    cache = seed()
  }
  if (!cache || !cache.users || !cache.workflows) cache = seed()
  saveDb()
  return cache
}

export function saveDb() {
  if (!cache) return
  localStorage.setItem(DB_KEY, JSON.stringify(cache))
}

export function resetDb() {
  cache = seed()
  saveDb()
  return cache
}

export function nextId(kind) {
  const db = getDb()
  db.idSeq[kind] = (db.idSeq[kind] || 0) + 1
  saveDb()
  return db.idSeq[kind]
}

export function publicUser(u) {
  return {
    id: u.id,
    username: u.username,
    email: u.email,
    role: u.role,
    createdAt: u.createdAt
  }
}

export function publicWorkflow(w) {
  return {
    id: w.id,
    name: w.name,
    description: w.description,
    status: w.status,
    nodeCount: w.nodeCount,
    createdAt: w.createdAt,
    updatedAt: w.updatedAt
  }
}

export function getTools() {
  return TOOLS
}

export { iso }
