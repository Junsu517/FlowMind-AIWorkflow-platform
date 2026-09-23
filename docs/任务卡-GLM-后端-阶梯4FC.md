# 任务卡：GLM 后端 · 阶梯 4（Function Calling 多轮工具调用）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 阶梯 2（画布）由 WorkBuddy 做、阶梯 3（并行调度）由统筹改引擎——**你只做本卡内容，别碰引擎**。
> 本轮你的改动全在 `service/executor/LlmNodeExecutor.java` 及配套工具代码。

## 你的领土

- 允许：backend/ 下除 com.flowmind.engine 包以外
- 禁止：改 engine 包、frontend、docs；运行 git；私自改契约

## 背景

LLM 节点目前是单次调用。契约 v1.4 新增可选配置：`"tools": [tool_id...]` + `"max_tool_rounds": 3`（默认 3）。配置了 tools 后，LLM 节点升级为**代理循环**：

```
第 1 轮：调 DeepSeek（带 tools 参数）
  → finish_reason == "tool_calls"？
    是：解析模型要调的工具名+参数 → 执行工具（HTTP）→ role=tool 结果回填 messages → 第 2 轮
    否：得到最终文本 → 结束
轮数达到 max_tool_rounds 仍要调工具 → 强制结束，提示截断
```

## 任务清单

### A. LlmNodeExecutor 改造

1. 读 `config.tools`（List<Integer>，工具表 id）与 `config.max_tool_rounds`（默认 3）
2. tools 非空时：
   - 从 tools 表加载工具，转换为 OpenAI 兼容 `tools` 参数格式：
     ```json
     {"type": "function", "function": {"name": "weather_query",
       "description": "...", "parameters": {...}}}
     ```
     parameters 由 tools.params_schema 转换（JSON 数组 → JSON Schema 对象：`{"type":"object","properties":{...},"required":[...]}`）
   - 每轮请求 messages 追加：assistant 消息（含 tool_calls）→ tool 消息（role="tool"，tool_call_id 对应，content=工具执行结果 JSON 字符串）
   - 工具执行：复用现有 HttpNodeExecutor 里的 HTTP 调用逻辑（建议抽一个公共 HttpToolInvoker 供两者复用，不要复制粘贴两份）
   - 工具执行失败（超时/非 200）：把错误信息作为 tool 消息 content 回填（如 `{"error":"调用超时"}`），让模型自己决定怎么处理，而不是直接失败整个节点
3. 流式：所有轮次中，只有**最终轮**的 content 增量推给 tokenSink；中间轮的工具调用不推
4. token 统计：所有轮次的 usage 求和返回
5. 节点日志：output 里额外带 `tool_calls` 数组（每项含 toolName/参数摘要/耗时），面试演示和排障都要看

### B. 测试

- JUnit：mock 或真实 DeepSeek 走通一条"查天气 → 模型决定调工具 → 回填 → 最终建议"的完整链路（真实调用更佳，成本几分钱）
- 轮数上限：造一个工具永远被调的场景，验证 max_tool_rounds 截断生效且不报错

## 验收清单

- [ ] 演示工作流（input→llm with tools=[天气工具]，prompt 引导模型查天气后给建议）跑通多轮工具调用
- [ ] 节点日志 tool_calls 数组完整
- [ ] 轮数截断场景验证
- [ ] mvn test 全绿（引擎测试不许动）
- [ ] 无越界（engine 包 git diff 为空）

## 上报规则

DeepSeek tools 参数格式的实测行为（比如参数校验严格度）、模型拒绝调用工具的 case 处理、任何契约疑问——列清单上报统筹。
