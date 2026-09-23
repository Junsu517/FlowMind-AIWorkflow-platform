# 任务卡：GLM 后端 · 第二轮优化（新用户注册自动创建默认示例工作流）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 用户实测反馈第 8 条："每一个新手注册的时候都应该有一个默认的工作流帮助他们上手"。前端 6 条优化由 WorkBuddy 做，本卡只有这一件事。
> **禁区**：engine 包、LlmNodeExecutor、HttpToolInvoker、GlobalExceptionHandler、ExecutionRecoveryService（都已验收冻结）。只动注册流程 + 一个新增 service。

## 背景

新用户注册后进入列表页是空的，虽然有引导文案，但用户希望**注册即送一个可运行的示例工作流**，让新手直接点开就能跑、就能学。

## 任务清单

### A. 注册时自动创建默认工作流（天气助手模板）

1. 新建 `service/DemoWorkflowService.java`（或并入 AuthService 的注册方法，你定，保持风格一致）
2. 在 `POST /api/auth/register` 注册成功后（事务内或紧跟其后，失败不影响注册本身——建议 try/catch 包住，创建失败只 log.warn 不阻断注册），为新用户创建：
   - 工作流：名称 `天气助手（示例）`，description：`官方示例：输入城市 → 查询实时天气 → AI 穿衣建议。点开编辑器可自由修改`
   - 三个节点（nodeKey：input_1 / http_1 / llm_1，type：input / http / llm，positions 建议横向 85/418/752, y=112，与画布模板一致）：
     - input_1 名称 `输入：城市`，config：`{"fields":[{"key":"city","label":"城市","type":"text","default":"南昌"}]}`（给默认值，新手上手可直接跑）
     - http_1 名称 `查询天气`，config：`{"tool_id":1,"params":{"city":"{{input.city}}"},"output_key":"data"}`
     - llm_1 名称 `AI 穿衣建议`，config：`{"model":"deepseek-flash","prompt":"城市 {{input.city}} 的天气数据：{{http_1.data}}。请给出一条简洁的穿衣建议。","temperature":0.7,"output_key":"text"}`
   - 两条边：input_1→http_1（sourceHandle null）、http_1→llm_1（sourceHandle null）
3. 幂等保护：若用户已存在同名工作流也照建（名字可加时间戳后缀或直接允许重名——列表按名字展示，重名无碍，你定）
4. 注意：注册接口的响应结构**不变**（契约没动）

### B. 测试

- JUnit（MockMvc 或直接调 service）：注册新用户 → 断言该用户自动拥有 1 个工作流 + 3 节点 + 2 边 + config 内容正确
- 手动验收：起 8081 → curl 注册新用户 → 用返回 token 查 GET /api/workflows 应看到"天气助手（示例）"→ 直接 POST execute 跑通（真实 DeepSeek）→ 清理测试用户
- 回归：契约脚本 29 项必须仍 ALL PASS（注册多了一个副作用，别破坏 201 响应结构）

## 验收清单

- [ ] 注册即得示例工作流（可执行跑通）
- [ ] 注册失败/已存在用户不受影响；示例创建失败不阻断注册
- [ ] 契约脚本 ALL PASS；mvn test 全绿
- [ ] 禁区 git diff 为空
- [ ] 8081 测试实例停掉、端口干净

## 上报规则

改动文件清单、curl 实测原文、契约脚本结果，老格式。
