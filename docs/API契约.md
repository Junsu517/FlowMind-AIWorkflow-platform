# FlowMind API 契约 v1.0（单一事实来源）

> 前后端开发必须严格按本文件实现。任何修改先改本文件，双方同步后再生效。
> 本文件由统筹（Claude）维护。

## 通用约定

- 基础路径：`/api`（前端 axios baseURL 固定 `/api`，本地开发 Vite 代理到 `http://127.0.0.1:8080`）
- 鉴权：请求头 `Authorization: Bearer <token>`（除注册/登录外的所有接口）
- 时间格式：ISO 8601 字符串，如 `2026-09-20T14:30:00`
- 成功响应：直接返回数据（删除接口返回 204 无 body）
- 失败响应：HTTP 4xx/5xx + body：
```json
{ "code": "WORKFLOW_NOT_FOUND", "message": "工作流不存在" }
```
- 分页参数：`page`（从 1 开始）、`size`（默认 10，最大 50）
- 分页响应：
```json
{ "items": [], "total": 0, "page": 1, "size": 10 }
```

## 认证模块

### POST /api/auth/register
```json
请求: { "username": "tom", "password": "12345678", "email": "tom@x.com" }
201: { "token": "jwt...", "user": { "id": 1, "username": "tom", "email": "tom@x.com", "role": "user", "createdAt": "..." } }
错误: 400 USERNAME_EXISTS
```

### POST /api/auth/login
```json
请求: { "username": "tom", "password": "12345678" }
200: { "token": "jwt...", "user": { ...同上 } }
错误: 401 BAD_CREDENTIALS
```

### GET /api/auth/me
```json
200: { "id": 1, "username": "tom", "email": "...", "role": "user", "createdAt": "..." }
```

## 工作流模块

### GET /api/workflows?page=&size=
```json
200: { "items": [
  { "id": 1, "name": "天气助手", "description": "查天气给建议", "status": "draft",
    "nodeCount": 4, "createdAt": "...", "updatedAt": "..." }
], "total": 1, "page": 1, "size": 10 }
```

### POST /api/workflows
```json
请求: { "name": "天气助手", "description": "查天气给建议" }
201: { "id": 1, "name": "...", "description": "...", "status": "draft", "createdAt": "...", "updatedAt": "..." }
```

### GET /api/workflows/{id}
```json
200: { "id": 1, "name": "...", "description": "...", "status": "draft", "createdAt": "...", "updatedAt": "..." }
错误: 404 WORKFLOW_NOT_FOUND；403（访问他人工作流）
```

### PUT /api/workflows/{id}
```json
请求: { "name": "...", "description": "..." }（可只传要改的字段）
200: 更新后的 workflow 对象
```

### DELETE /api/workflows/{id}
```
204 无 body
```

### PUT /api/workflows/{id}/graph （整图全量替换，事务内先删后插）
```json
请求:
{
  "nodes": [
    { "nodeKey": "input_1", "type": "input", "name": "输入",
      "config": { "fields": [{"key":"city","label":"城市","type":"text","default":"南昌"}] },
      "positionX": 0, "positionY": 0 },
    { "nodeKey": "llm_1", "type": "llm", "name": "AI 建议",
      "config": { "model": "deepseek-v4-flash", "prompt": "请给 {{input.city}} 的天气穿衣建议", "temperature": 0.7, "output_key": "text" },
      "positionX": 200, "positionY": 0 }
  ],
  "edges": [
    { "sourceNodeKey": "input_1", "targetNodeKey": "llm_1", "sourceHandle": null }
  ]
}
200: { "saved": true }
错误: 400 INVALID_GRAPH（含循环/孤立节点等校验失败，message 说明原因）
```

### GET /api/workflows/{id}/graph
```json
200: { "nodes": [...], "edges": [...] }（结构与保存时一致）
```

### 节点类型与 config 定义（阶梯 1）

| type | config 结构 |
|------|-------------|
| input | `{ "fields": [{"key","label","type"(text/number),"default"}] }` |
| llm | `{ "model", "prompt"(支持 {{xxx}} 模板), "temperature", "output_key" }` |
| condition | `{ "rules": [{"left":"{{input.city}}","operator":"equals","right":"北京","handle":"beijing"}], "default_handle":"other" }`（operator 支持 equals/not_equals/contains/not_empty） |
| http | `{ "tool_id": 1, "params": {"city":"{{input.city}}"}, "output_key": "data" }` |

## 执行模块

### POST /api/workflows/{id}/execute
```json
请求: { "input": { "city": "南昌" } }
202: { "executionId": 12 }
错误: 400 INVALID_INPUT（缺少 input 必填字段）；400 INVALID_GRAPH；409 WORKFLOW_RUNNING（同一工作流已有执行在跑，阶梯 1 简化限制）
```

### GET /api/executions/{id}/stream （SSE，text/event-stream）
```
event: execution_started   data: {"executionId":12,"totalNodes":4}
event: node_started        data: {"nodeKey":"llm_1","name":"AI 建议"}
event: token               data: {"nodeKey":"llm_1","delta":"今天"}
event: node_completed      data: {"nodeKey":"llm_1","durationMs":3200}
event: node_skipped        data: {"nodeKey":"http_2","reason":"条件分支未命中"}
event: execution_completed data: {"output":{"llm_1":{"text":"..."}},"totalTokens":1234,"durationMs":5210}
event: execution_failed    data: {"error":"节点 llm_1 调用超时"}
```
规则：每次事件以空行分隔；连接断开不影响执行（执行结果落库，可查详情）。

### GET /api/executions/{id}
```json
200: { "id": 12, "workflowId": 1, "workflowName": "天气助手", "status": "success",
  "input": {"city":"南昌"}, "output": {"llm_1":{"text":"..."}},
  "totalTokens": 1234, "totalCost": 0.0006, "durationMs": 5210,
  "errorMessage": null, "startedAt": "...", "finishedAt": "..." }
```

### GET /api/executions/{id}/logs
```json
200: { "items": [
  { "nodeKey": "input_1", "nodeName": "输入", "nodeType": "input", "status": "success",
    "input": {"city":"南昌"}, "output": {"city":"南昌"}, "tokens": 0, "durationMs": 1, "errorMessage": null },
  { "nodeKey": "llm_1", "nodeName": "AI 建议", "nodeType": "llm", "status": "success",
    "input": {"prompt":"请给 南昌 的天气穿衣建议"}, "output": {"text":"..."},
    "tokens": 1234, "durationMs": 5200, "errorMessage": null }
], "total": 2 }
```

### GET /api/executions?workflowId=&page=&size=
```json
200: { "items": [ 执行记录对象（同 GET /executions/{id}） ], "total": 0, "page": 1, "size": 10 }
```

## 工具与统计

### GET /api/tools
```json
200: { "items": [
  { "id": 1, "name": "weather_query", "displayName": "天气查询",
    "description": "查询指定城市的实时天气",
    "method": "GET", "paramsSchema": [{"key":"city","label":"城市","type":"text","required":true}] }
] }
```

### GET /api/stats/usage
```json
200: { "totalExecutions": 5, "totalTokens": 6210, "totalCost": 0.0031,
  "executionsByDay": [ {"date":"2026-09-18","count":2}, {"date":"2026-09-19","count":3} ] }
```

## 环境信息（给 GLM 后端）

- DeepSeek：baseUrl `https://api.deepseek.com/v1`，Key 从环境变量 `DEEPSEEK_API_KEY` 读
- **模型名**：先调 `GET /models` 确认可用模型名（当前返回 `deepseek-v4-flash`、`deepseek-v4-pro`），LLM 节点默认用 flash
- 数据库：MySQL `flowmind` 库，连接串从环境变量 `DB_URL` 读（本地开发默认 `jdbc:mysql://localhost:3306/flowmind`）
- Redis：localhost:6379
- 后端端口：8080

## 修改流程

1. 任何改动提议 → 先提给统筹（人）→ 统筹改本文件 → 通知双方 agent
2. 严禁 agent 私自改接口
