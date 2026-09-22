# FlowMind API 契约 v1.0（单一事实来源）

> 前后端开发必须严格按本文件实现。任何修改先改本文件，双方同步后再生效。
> 本文件由统筹（Claude）维护。

## 通用约定

- 基础路径：`/api`（前端 axios baseURL 固定 `/api`，本地开发 Vite 代理到 `http://127.0.0.1:8080`）
- 鉴权：请求头 `Authorization: Bearer <token>`（除注册/登录外的所有接口）；未携带或无效 token → `401 UNAUTHORIZED`；token 过期 → `401 TOKEN_EXPIRED`。前端对两个 code 统一处理：清 token 跳登录页
- 时间格式：ISO 8601 字符串，如 `2026-09-20T14:30:00`（服务器本地时间，无时区后缀；前端按本地时间解析展示）
- 成功响应：直接返回数据（删除接口返回 204 无 body）
- 失败响应：HTTP 4xx/5xx + body：
```json
{ "code": "WORKFLOW_NOT_FOUND", "message": "工作流不存在" }
```
- 错误码总表（新增错误码需统筹批准）：
  | code | HTTP | 场景 |
  |------|------|------|
  | UNAUTHORIZED | 401 | token 缺失/无效 |
  | TOKEN_EXPIRED | 401 | token 已过期 |
  | BAD_CREDENTIALS | 401 | 用户名或密码错误 |
  | FORBIDDEN | 403 | 访问/操作他人资源 |
  | USERNAME_EXISTS | 400 | 注册用户名已存在 |
  | VALIDATION_ERROR | 400 | 参数校验失败 |
  | WORKFLOW_NOT_FOUND | 404 | 工作流不存在 |
  | INVALID_GRAPH | 400 | 图校验失败（message 说明具体原因） |
  | INVALID_INPUT | 400 | 执行入参不满足 input 节点字段定义 |
  | WORKFLOW_RUNNING | 409 | 同一工作流已有执行在运行 |
  | EXECUTION_NOT_FOUND | 404 | 执行记录不存在 |
  | INTERNAL_ERROR | 500 | 未预期异常 |
- 分页参数：`page`（从 1 开始）、`size`（默认 10）；**size 超过 50 时后端截断为 50**（不报错）
- 分页响应：
```json
{ "items": [], "total": 0, "page": 1, "size": 10 }
```

## 认证模块

### POST /api/auth/register
```json
请求: { "username": "tom", "password": "12345678", "email": "tom@x.com" }
201: { "token": "jwt...", "user": { "id": 1, "username": "tom", "email": "tom@x.com", "role": "user", "createdAt": "..." } }
错误: 400 USERNAME_EXISTS；400 VALIDATION_ERROR
```
> 字段规则：username 3-20 位、仅字母数字下划线；password 6-64 位（后端同时校验上下限）；email 必填、格式校验、不要求唯一（登录只用 username）。前端后端校验规则必须一致。

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

> **workflow 对象统一结构**（列表/创建/详情/更新响应均一致）：
> `{ "id", "userId", "name", "description", "status", "nodeCount", "createdAt", "updatedAt" }`
> - `userId`：归属用户，前端可用于判断资源归属
> - `nodeCount`：当前图的节点数（列表用子查询，详情/创建实时统计）
> - 列表排序：**updatedAt 倒序**
> - DELETE 不存在的 id → `404 WORKFLOW_NOT_FOUND`

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
> 删除语义：级联删除该工作流的节点和边；**执行历史（executions/node_logs）保留**，作为历史记录独立存在。

### PUT /api/workflows/{id}/graph （整图全量替换，事务内先删后插）

图校验规则（违反均返回 400 INVALID_GRAPH，message 说明原因）：
1. 节点 key 唯一、type 合法
2. 有且仅有一个 input 节点
3. 边的两端节点必须存在
4. 无重复边（source+target+sourceHandle 完全相同）
5. 无孤立节点（无任何边，input 除外）
6. 无不可达节点（入度为 0 且非 input）
7. 无环（拓扑排序检测）

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
> **input 字段规则**：input 节点的 fields 中**无 default 的字段必填**（缺失 → 400 INVALID_INPUT）；有 default 的字段缺省时自动填充默认值。

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
错误: 404 EXECUTION_NOT_FOUND
```
> `workflowName` 在工作流被删除后为 **null**（执行历史保留，名称不冗余存储）；前端对 null 显示"已删除的工作流"。

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
> 排序：**startedAt 倒序，同时间按 id 倒序**。

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
- **模型名**（2026-09 实测 `GET /models` 返回）：`deepseek-flash`、`deepseek-v4-pro`。LLM 节点默认模型 = **`deepseek-flash`**（旧名 `deepseek-v4-flash` 是服务端别名，也可用）
- **v4 是推理模型**：流式 delta 含 `reasoning_content`（思考过程，占 token 大头）。后端只向 tokenSink 推送 `content`（最终答案）；SSE 阶段首 token 延迟明显属正常
- **计费**：totalCost 按中值单价估算（输入/输出算术平均，代码注释标明来源与日期）；精确分输入输出计费为二期（需引擎支持拆分 token）
- 数据库：MySQL `flowmind` 库，连接串从环境变量 `DB_URL` 读（本地开发默认 `jdbc:mysql://localhost:3306/flowmind`）
- Redis：localhost:6379
- 后端端口：8080

## 修改流程

1. 任何改动提议 → 先提给统筹（人）→ 统筹改本文件 → 通知双方 agent
2. 严禁 agent 私自改接口
