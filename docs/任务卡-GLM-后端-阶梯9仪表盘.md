# 任务卡：GLM 后端 · 阶梯 9（统计仪表盘后端）

> 把本任务卡 + docs/阶梯9-统计仪表盘技术方案.md + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> **禁区**：engine 包、LlmNodeExecutor、rag/、GlobalExceptionHandler、ExecutionRecoveryService、ExecutionQueueService、ExecutionRunner、ScheduleService、PublishService、PublicRunController（全部已验收冻结）。

## 任务清单

### A. GET /api/stats/dashboard 聚合接口（新，方案 §2 结构）

1. StatsService 或新建 DashboardService（你定，保持现有 stats 风格）：
   - summary：总执行数、成功率（success/(success+failed)，进行中不计）、总 tokens、总费用（decimal 求和）、活跃工作流数（有执行记录的 distinct workflow_id，或 user 的 workflows 总数——用"用户的工作流总数"更直观）、知识库文档数、已发布 API 数、启用定时计划数
   - daily：近 30 天（含今天）按 `DATE_FORMAT(started_at, '%Y-%m-%d')` 聚合执行数+tokens，**无数据日期补 0**（后端补齐，返回 30 项）
   - sourceDist：GROUP BY trigger_type 三键（manual/scheduled/api，缺的补 0）
   - topWorkflows：按执行次数 Top 5，JOIN workflows 取 name（工作流已删的执行历史不 join 出来）
2. **DDL**：executions 加组合索引 `KEY idx_user_started (user_id, started_at)`（schema.sql + 本地执行）
3. JUnit：成功率口径（含进行中不计）、30 天补零、来源分布补零、Top 排序（真实 DB 插数断言）

### B. 契约

- 接口与响应结构按方案 §2；字段名一个都不许改（前端按契约写死）

## 验收清单

- [ ] curl 实测原文（构造几条 manual/scheduled/api 执行后查 dashboard，各块数字对得上）
- [ ] mvn test 全绿；禁区 git diff 为空
- [ ] 上报：聚合实现方式（单 SQL vs 分查询）、任何口径疑问

## 上报规则

改动文件清单、curl 实测、测试结果，老格式。
