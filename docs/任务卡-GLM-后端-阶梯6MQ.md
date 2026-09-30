# 任务卡：GLM 后端 · 阶梯 6（RabbitMQ 异步执行）

> 把本任务卡 + docs/阶梯6-MQ异步执行技术方案.md + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 方案由统筹定稿，本卡是你的实施范围。**前端接口形状与 SSE 协议零变化**（status 枚举加 queued 是唯一契约变更）。
> **禁区**：engine 包、HttpToolInvoker、GlobalExceptionHandler、ExecutionRecoveryService（除方案 §2 指定的语义更新）、DemoWorkflowService、LlmNodeExecutor 与 rag/ 已验收部分。

## 你的领土

backend/ 下除 engine 包外。本轮新增：amqp 相关 config/service/consumer + 分布式锁工具；改：ExecutionService 的 submit/runAsync 链路。

## 任务清单

### A. RabbitMQ 集成（spring-boot-starter-amqp）

1. 依赖 + yml 配置（`spring.rabbitmq.host/port/username/password` 从环境变量读：`RABBITMQ_HOST` 等，默认 127.0.0.1:5672；本地无 MQ 时**应用必须能正常启动**——RabbitListener 自动声明失败不能拖死启动，用 `RabbitListenerConfigurer`/`SimpleRabbitListenerContainerFactory` 配置容错，或声明放在消费者首次连接时）
2. 队列拓扑（durable，代码内声明幂等）：
   - `executions.exchange`（direct）+ `executions.queue`（durable，`x-dead-letter-exchange=executions.dlx`，routing key `execution.run`）
   - `executions.dlx` + `executions.dlq`
3. `ExecutionQueueService`：
   - `publish(executionId)`：`CorrelationData` + publisher confirm（confirm 失败要处理：执行行回置 failed 并告警日志）
   - `@RabbitListener(executions.queue)` manual ack：幂等检查（执行行非 queued 直接 ack）→ status running → 跑引擎（现有 runAsync 逻辑迁移进来，SSE 事件照旧）→ 成功 persist + ack
   - 失败：消息头 `x-retry-count` < 3 → `basicNack(requeue=true)` 带计数头重新发布（或用 TTL 延迟队列做退避，你选）；≥3 → reject → 进 DLQ
   - `@RabbitListener(executions.dlq)`：执行行 → failed（error_message="任务处理失败（已重试 3 次）"）+ SSE 补发 execution_failed + complete emitter

### B. submit 链路改造（ExecutionService）

1. **Redis 分布式锁**：`SET NX EX 60`（key=lock:execute:{workflowId}，value=executionId 或随机串防误删），拿不到 → 409 WORKFLOW_RUNNING（保留现有 DB count 检查作为兜底或删掉——你定，锁是主防线）
2. 执行行 status 初始值 **queued**（不是 running）；publish 成功后返回 202（形状不变）
3. 锁释放：执行终态后（消费者里）DEL（带 value 校验防误删别人的锁）
4. ExecutionRecoveryService 语义更新：启动恢复只翻 running → failed；**queued 不动**（任务在 MQ 里活着）

### C. 测试

- JUnit：锁互斥（两个并发 acquire 只有一个成功）、幂等消费（非 queued 行 ack 跳过）、重试计数递增、消息转换（Jackson 序列化 executionId）
- Mock MQ 场景下 submit→publish 链路（mock RabbitTemplate 或自定义 ConnectionFactory stub）
- 现有测试全绿是回归底线；status 枚举加 queued 后，受影响断言同步修

### D. 上报

- 真实 MQ 联调：统筹部署服务器 RabbitMQ 后，本地连服务器 MQ 隧道做一次全链路（统筹会给隧道指引）；若你想本地装 Docker/直接起 RabbitMQ 也可以
- 上报：重试退避方案选择与理由、confirm 失败的补偿策略、锁续期问题（执行超过 60s 怎么办——建议锁 TTL 设大或用执行期间检查，给出你的方案）

## 验收清单

- [ ] mvn test 全绿（新增测试在内）
- [ ] 无 MQ 环境应用可正常启动（优雅降级）
- [ ] 禁区 git diff 为空（ExecutionRecoveryService 只有语义更新，diff 明确可见）
- [ ] 上报 C/D 项

## 上报规则

改动文件清单、测试结果、方案选择与理由、踩坑，老格式。统筹负责服务器 Docker+RabbitMQ 部署与最终全链路验收。
