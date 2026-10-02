# 任务卡：GLM 后端 · 阶梯 8（工作流发布为 HTTP API）

> 把本任务卡 + docs/阶梯8-HTTPAPI发布技术方案.md + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> **核心思想**：发布只是加一层"外部入口"（API Key 鉴权），执行仍走 submit 全链路（锁→queued→MQ），队列/重试/死信免费继承。
> **禁区**：engine 包、LlmNodeExecutor、rag/、GlobalExceptionHandler、ExecutionRecoveryService、ExecutionQueueService、ExecutionRunner、ScheduleService（已验收冻结；submit 只加 triggerType 取值 'api'）。

## 任务清单

### A. 数据与依赖

1. schema.sql 加 workflow_api_keys 表（方案 §2，本地执行）；executions.trigger_type 注释更新为 manual/scheduled/api

### B. PublishService

1. `publish(workflowId)`：已发布 → 返回现有（不含 key 重暴露）；未发布 → 生成 32 字节随机 hex key（SecureRandom）→ 落库 → 返回含 key 的响应
2. `get(workflowId)`：{published, runUrl, createdAt}，runUrl 形如 `POST http://<host>/api/public/run/{apiKey}` 不含 key（只展示 URL 模板+调用说明）——host 取请求 Host 头或配置，你定并上报
3. `revoke(workflowId)`：删行（幂等 204）
4. key 明文存储处加注释：生产应存 SHA-256 哈希（面试预埋）

### C. PublicRunController（/api/public/run/**）

1. AuthInterceptor 白名单加 `/api/public/run`（不走 JWT）
2. `POST /api/public/run/{apiKey}`：
   - key 查库 → 无 → 401 INVALID_API_KEY（新错误码）
   - 限流：复用限流组件，key 前缀 `rl:api:{apiKey}`，10 次/分钟 → 429 API_RATE_LIMITED（新错误码）
   - UserContext.set(ownerId, ownerRole)（finally clear）→ `executionService.submit(workflowId, input, "api")`
   - 202 {executionId}；INVALID_INPUT/409 等业务错误照常透出
3. `GET /api/public/run/{apiKey}/executions/{id}`：
   - key 有效 + execution 属于该 key 的 workflow（防横向越权）→ 200 {status, output?, errorMessage?, totalTokens?}
   - 否则 404 EXECUTION_NOT_FOUND
4. 结果获取是轮询（调用方循环查），同步阻塞二期——注释写明

### D. 测试

- JUnit：key 生成格式、发布幂等（二次不重发 key）、吊销后 401、key 限流（复用现有限流测试模式）、越权查询（key A 查 key B 的执行 → 404）、submit api 触发（triggerType='api' 落库）
- 手动验收（8081，贴原文）：发布 → curl 外部调用 202 → 轮询 success → 错误 key 401 → 吊销后 401
- mvn test 全绿；契约脚本由统筹扩展后复跑

## 验收清单

- [ ] 全链路 curl 实测原文（含 key 一次性展示验证）
- [ ] 禁区 git diff 为空
- [ ] 上报：runUrl 的 host 取法、任何契约疑问

## 上报规则

改动文件清单、curl 实测、测试结果，老格式。
