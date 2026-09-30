# 阶梯 6 · RabbitMQ 异步执行技术方案

> 统筹（Claude）2026-09-29 定稿。目标：执行链路从"进程内线程池"升级为"消息队列可靠投递"。
> 简历收益：RabbitMQ 实践、AI 任务异步解耦、消息可靠投递（confirm/ack/重试）、死信队列处理失败任务、Redis 分布式锁、消费幂等。
> 面试收益：MQ 十连问（幂等/顺序/重复消费/死信/可靠性）、"为什么从线程池升级到 MQ"（有 107 真实事故背书）。

## 1. 背景（面试故事预埋）

阶梯 1 的执行是**进程内线程池**：`submit()` 落 running 行 → `executionThreadPool.execute()`。execution 107 事故（JVM 重启窗口丢任务 → running 孤儿 → 工作流永久 409）暴露了核心弱点：**任务在内存里，进程死任务就死**。阶梯 6 用消息队列根治：任务先落 MQ（持久化），消费者再执行——服务重启后队列里的任务仍在，消费者恢复后继续处理。

## 2. 架构

```
POST /workflows/{id}/execute
  ① Redis 分布式锁（SET NX EX，workflowId 粒度）——替代 DB count 的竞态检查
  ② 落 executions 行（status=queued）
  ③ publish → 队列 executions.queue（消息持久化 + publisher confirm）
  ④ 202 { executionId }（前端零感知）

消费者（spring-amqp @RabbitListener，manual ack）
  ① 查执行行：已 success/failed → 直接 ack（幂等，防重复投递重复执行）
  ② status → running，执行引擎（SSE 事件照旧实时推送）
  ③ 成功 → persist → ack
  ④ 失败 → x-retry-count < 3 ? 重回队列（带计数头）: 拒绝 → 死信队列 executions.dlq
      DLQ 消费者：执行行 → failed（"任务处理失败（已重试 3 次）"）→ SSE 补发 execution_failed

启动恢复（阶梯 1 收尾已有的 ExecutionRecoveryService）语义更新：
  重启时 queued 行 ≠ 孤儿（任务在 MQ 里活着！）——只有 running 行翻 failed
```

## 3. 契约 v1.6 变更（最小化）

- **executions.status 新增 `queued`**：202 后、消费者接手前。前端轮询与 running 同等对待（等待中）
- 其余接口形状、错误码、SSE 协议**全部不变**（前端只有显示层的两处小改）
- 幂等语义：消息重复投递时消费者查库跳过（执行行非 queued 即 ack）

## 4. RabbitMQ 部署（统筹负责，服务器）

- Docker 部署：`rabbitmq:3-management`（Docker 顺带练手，简历可写 Docker 实践）
- 端口只绑 127.0.0.1（5672/15672），安全组不放行；账号 flowmind/强密码入 flowmind.env
- 队列与交换机（代码内自动声明，幂等）：
  - `executions.exchange`（direct）+ `executions.queue`（durable，绑定 routing key `execution.run`）
  - 死信：`executions.dlx` + `executions.dlq`（x-dead-letter-exchange 绑定）
- 服务器内存现状 ~350MB 可用，RabbitMQ 容器约 150-250MB，起容器后观察；必要时降 Java 堆（-Xmx320m→256m）

## 5. 分工与禁区

- **GLM（backend，本轮主力）**：spring-boot-starter-amqp 集成、队列/交换机/DLQ 声明配置、ExecutionQueueService（publish confirm + @RabbitListener manual ack + 重试计数头 + DLQ 消费者）、submit 链路改造（分布式锁→queued→publish）、Redis 分布式锁工具、幂等消费；**前端接口与 SSE 协议零变化**。禁区照旧：engine 包与已冻结文件
- **WorkBuddy（frontend，两处小改）**：执行历史与调试面板把 `queued` 状态显示为"排队中"（等 MQ 就绪后开始消费）
- **统筹（Claude）**：契约 v1.6、服务器装 Docker+RabbitMQ、验收与上线
- 本地开发：GLM 用 Mock（spring-amqp 可 mock ConnectionFactory 或纯单测）；**真实 MQ 联调在统筹部署 RabbitMQ 后**，本地可直连服务器 MQ 隧道（统筹给隧道指引）或等部署后服务器全链路验收

## 6. 验收标准

- [ ] 全量测试绿（新增：队列发布/重试计数/死信流转/幂等消费/锁互斥）
- [ ] 服务器全链路：execute → queued → running → success；人为杀死消费者进程 → 任务不丢（MQ 持久化）→ 重启后继续完成
- [ ] 失败任务重试 3 次后进 DLQ，执行行 failed 且 SSE 收 execution_failed
- [ ] 同工作流并发 execute → 第二个 409 WORKFLOW_RUNNING（分布式锁保证无竞态）
- [ ] 契约脚本 ALL PASS（status 枚举扩展后）
- [ ] 前端"排队中"展示
- [ ] 压测预备：JMeter 打 execute 接口（阶梯 6 附带任务，出 QPS 数据进简历）

## 7. 简历文案（上线后启用）

```
● 执行链路基于 RabbitMQ 异步解耦：发布确认+手动 ack+失败重试+死信队列处理，
  消息持久化保证服务重启任务不丢；Redis 分布式锁防并发重复执行，消费幂等
```
