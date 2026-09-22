# 任务卡：GLM 后端 · 第 3 周（阶梯 1 收官：SSE + 限流 + 统计）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> engine 包依旧只读。SSE 实现依赖引擎的 ExecutionListener 接口——先读 `com.flowmind.engine.ExecutionListener` 与 `ExecutionEngine.execute` 签名，事件回调已预留。

## 你的领土

- 允许：backend/ 下除 com.flowmind.engine 包以外
- 禁止：改 engine 包、frontend、docs；运行 git；私自改契约

## 任务清单

### A. SSE 流式执行接口（核心）

`GET /api/executions/{id}/stream`，事件协议严格按契约 v1.3「执行模块」章节：

```
event: execution_started   data: {"executionId":12,"totalNodes":4}
event: node_started        data: {"nodeKey":"llm_1","name":"AI 建议"}
event: token               data: {"nodeKey":"llm_1","delta":"今天"}
event: node_completed      data: {"nodeKey":"llm_1","durationMs":3200}
event: node_skipped        data: {"nodeKey":"http_2","reason":"上游分支未激活"}
event: execution_completed data: {"output":{...},"totalTokens":1234,"durationMs":5210}
event: execution_failed    data: {"error":"节点 llm_1 调用超时"}
```

实现要点：
1. `ExecutionService.execute` 里把 `ExecutionListener` 实现传入引擎（现在传的 null 换成 SSE 监听器）：
   - `onNodeStarted/onNodeCompleted/onNodeSkipped/onNodeFailed` → 发给 SseEmitter
   - `onToken` → `event: token`
2. **SseEmitter 生命周期**：一个 execution 一个 emitter，保存在内存 Map<executionId, SseEmitter>；执行结束/失败后 `complete()`；超时 5 分钟（SseEmitter 构造参数）；客户端断开（onCompletion/onTimeout/onError 回调）时从 Map 移除
3. **先执行后订阅**：客户端可能晚于执行开始才连 stream。连接时若执行已结束 → 直接返回 execution_completed/execution_failed 一个事件然后 complete
4. **并发安全**：emitter 的 send 由多个执行线程调用，SseEmitter 本身线程安全，但要注意执行线程和 controller 线程对 Map 的并发访问（ConcurrentHashMap）
5. 事件 JSON 用 ObjectMapper 序列化；data 中不要带敏感信息（prompt 全文不进事件，token 增量除外）

### B. Redis 限流（execute 接口）

- 规则：每用户每分钟最多 **10 次** execute 调用
- Key：`rl:execute:{userId}`，固定窗口 INCR + 首次 EXPIRE 60
- **必须用 Lua 脚本保证原子性**（INCR 后值为 1 时 EXPIRE），脚本放 resources/lua/rate_limit.lua
- 超限 → 429 + `{"code":"RATE_LIMITED","message":"操作过于频繁，请稍后再试"}`
- 错误码 RATE_LIMITED 由统筹已批准（契约 v1.3 见——若总表没有请上报，由统筹补）

### C. 用量统计接口

`GET /api/stats/usage`，按契约 v1.3：
```json
{ "totalExecutions": 5, "totalTokens": 6210, "totalCost": 0.0031,
  "executionsByDay": [ {"date":"2026-09-18","count":2} ] }
```
- 统计当前用户全部执行记录；executionsByDay 近 14 天，SQL 用 DATE(started_at) GROUP BY

### D. 契约 v1.3 已定的补充确认

- executions 列表 startedAt DESC, id DESC（你已实现，无需改）
- workflowName 为 null 时字段不输出（保持现状）
- 模型默认值改为 `deepseek-flash`（改 LlmNodeExecutor 默认值常量）

## 验收清单（全部打勾）

- [ ] curl 连 stream 能看到完整事件序列（含 token 流），执行结束后连接正常关闭
- [ ] 执行完成后才连 stream → 立刻收到 execution_completed 并关闭
- [ ] 事件 JSON 与契约逐字段一致
- [ ] execute 1 分钟内第 11 次调用 → 429 RATE_LIMITED
- [ ] stats/usage 字段正确、executionsByDay 近 14 天
- [ ] mvn test 全绿（引擎 17 个不许动）
- [ ] 无越界（engine 包 git diff 为空）

## 上报规则

SSE 与前端联调问题、事件协议不合理处、限流参数建议——列清单上报统筹。
