# 任务卡：GLM 后端 · 收尾迭代 2（孤儿 running 执行启动恢复）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 前一轮 GlobalExceptionHandler 修复已验收。本轮**不许动**：engine 包、LlmNodeExecutor、HttpToolInvoker、GlobalExceptionHandler（已验收冻结）。
> 本轮只做一个功能：**应用启动时回收孤儿 running 执行**。

## 你的领土

- 允许：backend/ 下除 com.flowmind.engine 包以外
- 禁止：改 engine 包、frontend、docs；运行 git；私自改契约

## 背景（统筹诊断结论，有线程转储为证）

WorkBuddy 实测发现 execution 107 卡 running 16 分钟+、0 节点日志、工作流 94 永久 409。统筹诊断：

1. **复现排查**：同工作流 105/106 正常完成；统筹在诊断时恰好杀旧后端起新后端（18:49-18:52 窗口），107（18:51:39 提交）的异步任务随 JVM 死亡而丢失
2. **线程转储证据**：当前 JVM 里 `flowmind-exec-1` 空闲在 `ThreadPoolExecutor.getTask()`，队列为空，无任何引擎线程——107 的任务根本不在内存里
3. **健康复验**：当前实例跑 109 号执行（同样无 SSE 订阅者）12 秒成功、日志完整——执行链路本身没 bug（WorkBuddy 猜的"无消费者阻塞队列"不成立：无 emitter 时 `SseEmitterRegistry.send` 直接返回，已看代码确认）
4. **真 bug 是运维级的**：`submit()` 先落 running 行再交线程池，若 JVM 在这两步之间/任务执行中死亡，running 行成为孤儿——**永远 running、该工作流永远 409、直到人工改库**。生产服务器每次 `systemctl restart` 都有这个风险（马上要部署，一定会触发）

## 任务清单

### A. 应用启动恢复（核心）

在应用启动完成后（`ApplicationReadyEvent` 或 CommandLineRunner 皆可），执行一次：

```sql
UPDATE executions SET status='failed', error_message='服务重启，执行中断' WHERE status='running'
```

- 用 MyBatis-Plus 写（不建议裸 SQL，除非现有 mapper 没有合适的写法——用 `LambdaUpdateWrapper` 即可）
- 位置建议：新建 `service/ExecutionRecoveryService.java`（或放 ExecutionService 里的 `@EventListener(ApplicationReadyEvent.class)` 方法，你定，保持和现有代码风格一致）
- 语义：服务重启意味着 JVM 内一切在途任务已死，running 行必然是孤儿；把它翻成 failed 让用户能重新执行，而不是 409 到天荒地老
- error_message 用上面这句（或等价的中文），执行历史页会展示

### B. 测试

- JUnit：往 executions 表插 2 行 running + 1 行 success（用现成的 mapper），调恢复方法，断言 2 行变 failed（error_message 正确）、success 行不动
- 手动验收（统筹会复核）：起服务 → 故意插一条 running 行 → 重启服务 → 查库该行已 failed

### C. 顺手确认（不改代码，只上报）

- 读一下 `SseEmitterRegistry.send`：客户端连上但停读（TCP 不消费）时 `emitter.send()` 会阻塞到 emitter 5 分钟超时——引擎线程会被拖住但最终自愈。这个边界**不用修**，知悉并写进你的上报即可（面试可以讲：SSE 背压有 5 分钟超时兜底）

## 验收清单

- [ ] 启动恢复逻辑生效（手动验收步骤见 B）
- [ ] mvn test 全绿（新增测试在内）
- [ ] 禁区 git diff 为空
- [ ] 端口：本机 8080 目前有统筹起的新代码实例在跑（供 WorkBuddy 联调），你测试用 8081，测完停掉

## 上报规则

改动文件清单、测试结果、C 项读代码的结论——按老格式汇报。
