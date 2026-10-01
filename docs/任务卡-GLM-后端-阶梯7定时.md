# 任务卡：GLM 后端 · 阶梯 7（Quartz 定时触发）

> 把本任务卡 + docs/阶梯7-定时触发技术方案.md + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> **核心思想**：定时触发完全复用 submit 链路（锁→queued→MQ→执行），不新开执行路径。
> **禁区**：engine 包、LlmNodeExecutor、rag/、GlobalExceptionHandler、ExecutionRecoveryService、ExecutionQueueService、ExecutionRunner（submit 链路只加 trigger_type 参数，不改执行逻辑）。

## 任务清单

### A. 数据与依赖

1. pom 加 spring-boot-starter-quartz；schema.sql 加 workflow_schedules 表 + executions 加 trigger_type 列（见方案 §2，本地执行 DDL）
2. Execution 实体/执行历史 VO 加 triggerType 字段（默认 manual，历史接口带出）

### B. ScheduleService（Quartz 内存模式）

1. CRUD：`get(workflowId)`、`upsert(workflowId, cron, enabled, input)`（cron 校验非法 → 400 SCHEDULE_INVALID，用 CronExpression.isValidExpression）、`delete(workflowId)`
2. 动态注册/注销：upsert 时 scheduleJob（JobDataMap 带 workflowId），delete 时 deleteJob；**JobDetail 身份用固定命名 `sched-{workflowId}`**，更新即替换
3. **启动恢复**：ApplicationReadyEvent 后扫描 enabled 的计划重新注册（内存模式重启丢注册，DB 是真相源）
4. ScheduleJob：JobDataMap 取 workflowId → 查 schedule 归属 userId → `UserContext.set(userId, role)`（finally clear）→ 读 input JSON → 调 submit（带 triggerType=scheduled）→ 更新 last_run_at
5. misfire 策略选 MISFIRE_INSTRUCTION_FIRE_NOW 或 DO_NOTHING，二选一并上报理由
6. GET /api/workflows/{id}/schedule 无计划时的返回形状（方案 §3 给了两个选项），选一个并上报给统筹定契约

### C. submit 链路微改

- `ExecutionService.submit(workflowId, input)` 加一个重载或参数 `triggerType`（默认 manual），只影响落行字段，执行逻辑零改动
- 手动 execute 端点传 manual，ScheduleJob 传 scheduled

### D. 测试

- JUnit：cron 校验（合法/非法）、upsert 动态注册（Scheduler 里有对应 JobKey）、delete 注销、启动恢复注册、ScheduleJob 触发走 submit（stub Scheduler 或直接调 job 方法断言 executionId 产生 + triggerType=scheduled）
- 定时+手动并发撞车 → 锁挡住（这个可以在现有锁测试上加一个用例）
- mvn test 全绿为底线

## 验收清单

- [ ] 手动验收（8081）：PUT 一个 1 分钟后触发的计划 → 等触发 → 执行历史出现 triggerType=scheduled 的执行（真实 LLM）
- [ ] cron 非法 400；删除计划后不再触发
- [ ] 禁区 git diff 为空（submit 只加参数）
- [ ] 上报：misfire 选择、GET schedule 返回形状、任何契约疑问

## 上报规则

改动文件清单、curl 实测、测试结果、需要裁决的问题，老格式。
