# 任务卡：GLM 后端 · 第 2 周（执行引擎联调）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 引擎核心包（com.flowmind.engine.*）已由统筹完成并通过 17 个单元测试。**引擎包只读，严禁修改**；有疑问上报统筹。

## 你的领土

- 允许：`backend/` 下除 `com.flowmind.engine` 包以外的代码（service/controller/dto/entity/mapper/config/common）
- 禁止：改 engine 包（含 src/test 下的 engine 测试）、frontend、docs；运行 git；私自改契约

## 背景：引擎怎么用（先读代码再动手）

统筹已完成 `com.flowmind.engine` 包，关键类：

| 类 | 作用 |
|----|------|
| `ExecutionEngine` | 核心：`execute(List<GraphNode>, List<GraphEdge>, Map<String,Object> input, ExecutionListener)` → `ExecutionReport` |
| `GraphNode` / `GraphEdge` | 引擎模型：nodeKey/type/name/config(Map)；sourceNodeKey/targetNodeKey/sourceHandle |
| `ExecutionReport` | status(success/failed)、output、nodeResults、totalTokens、durationMs、errorMessage |
| `NodeResult` | 每节点：status/input/output/tokens/durationMs/errorMessage |
| `NodeExecutor` | 接口：`type()` + `execute(NodeExecutionContext)` 返回 `ExecutorResult`（output Map + tokens） |
| `NodeExecutionContext` | config（已渲染）、getContext()（ExecutionContext）、getTokenSink()（流式回调，非 null） |
| `EngineConfig` | 已装配：**你实现的 NodeExecutor 只需声明为 Spring Bean，引擎自动注册** |
| `CycleException` / `TemplateException` | 引擎可能抛的异常 |

## 任务清单

### A. 第 1 周尾巴（3 项，先做）

1. **POST /api/workflows 与 GET /api/workflows/{id} 响应补 `nodeCount`**（契约 v1.2 workflow 统一结构）
2. **列表确认按 updatedAt 倒序**（契约 v1.2 已明确）
3. **密码后端校验补上限 64**（契约 v1.2：password 6-64，配合已有的 ≥6 校验）

### B. 节点执行器（新增 service/executor/ 包）

**LlmNodeExecutor**（type="llm"）：
- 读 `config.model`（默认 `deepseek-v4-flash`）、`config.prompt`（已渲染）、`config.temperature`
- 用 OkHttp 调 `https://api.deepseek.com/v1/chat/completions`，**stream=true** 逐行解析（注意分片截断，按行缓冲）
- 每收到增量 delta 调 `ctx.getTokenSink().accept(delta)`
- 完整文本拼好后返回 `ExecutorResult(Map.of(config.output_key, 文本), usage总token)`
- 超时 60s；Key 从环境变量 `DEEPSEEK_API_KEY` 读
- **开工第一步**：先调 `GET /models` 确认模型名（deepseek-v4-flash / deepseek-v4-pro），不确定就上报统筹

**HttpNodeExecutor**（type="http"）：
- 按 `config.tool_id` 查 tools 表，渲染 url（`{{param}}` 用 config.params 替换）
- OkHttp 同步调用，超时 10s；响应 JSON 解析为 Map
- 返回 `ExecutorResult(Map.of(config.output_key, 解析结果), 0)`

**注意**：两个执行器都实现 `NodeExecutor` 接口、加 `@Component`，引擎会自动注册。Condition/input 由引擎内置，不要写执行器。

### C. 执行服务与接口

**ExecutionService**：
- `execute(workflowId, userId, inputMap)`：
  1. 加载节点/边（entity → 引擎 GraphNode/GraphEdge，config 是 JSON 字符串，用 ObjectMapper 转 Map）
  2. 创建 execution 行（status=running，startedAt=now）
  3. 提交到**线程池**（自定义 `ThreadPoolExecutor`，核心 2 最大 4，队列 100）异步执行
  4. 立即返回 executionId（接口层回 202）
- 异步任务内：调 `executionEngine.execute(...)`（listener 传 null，SSE 第 3 周接入）→ 把 ExecutionReport 落库：execution 行更新（status/output/totalTokens/totalCost/durationMs/errorMessage/finishedAt）+ 逐节点插 execution_node_logs（**一个事务**）
- `CycleException` → execution 标记 failed，errorMessage 用异常消息
- totalCost 按 DeepSeek 官方单价换算（查当前 deepseek-chat 价，写入常量并注释来源）
- 失败节点的 errorMessage 截断到 500 字符防脏数据

**ExecutionController**（严格按契约 v1.2）：
- `POST /api/workflows/{id}/execute` → 202 `{executionId}`；图未保存/非法 → 400 INVALID_GRAPH；workflow 不存在 → 404；**同一工作流已有 running 执行 → 409 WORKFLOW_RUNNING**
- `GET /api/executions/{id}` → 契约格式（含 workflowName）
- `GET /api/executions/{id}/logs` → 契约格式
- `GET /api/executions?workflowId=&page=&size=` → 契约格式（updatedAt 倒序）
- **SSE stream 接口第 3 周做，本周不实现**（契约里有但验收不含）

### D. 测试

- JUnit：ExecutionService 用真实 DB + **真实 DeepSeek** 跑一条 input→llm 流程（每条 1 分钱以内，可接受）；再跑一条 input→http 流程（工具 weather_query）；断言 execution 与 node_logs 落库字段正确
- 409 场景：造一条 running 记录再 execute

## 验收清单（全部打勾）

- [ ] 3 项第 1 周尾巴完成，契约脚本（现有 22 项）仍然全过
- [ ] 引擎 17 个单测未被破坏（`mvn test` 全绿，引擎测试不许动）
- [ ] JUnit：input→llm（真实 DeepSeek）执行成功，execution/node_logs 字段正确
- [ ] JUnit：input→http（weather_query 真实调用）执行成功
- [ ] Postman/curl：execute 202 → 轮询 executions/{id} 到 success → logs 完整
- [ ] 409 WORKFLOW_RUNNING 场景验证
- [ ] 无越界改动（engine 包 git diff 应为空）

## 契约疑问上报规则

引擎行为、契约不一致、DeepSeek 模型名异常等，一律**列清单上报统筹**，不自行改契约、不改引擎。
