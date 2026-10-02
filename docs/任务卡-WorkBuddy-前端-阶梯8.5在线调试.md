# 任务卡：WorkBuddy 前端 · 阶梯 8.5（发布面板内置在线调试器）

> 把本任务卡 + docs/阶梯8-HTTPAPI发布技术方案.md + docs/进度同步.md 一起粘贴给你。
> 用户实测反馈：发布 API 后只能用终端 curl 调用，体验不好（还踩了 executionId 看错的坑）。
> 目标：**发布抽屉内直接调试**——填输入、点按钮、看结果，全程零终端。
> **禁区照旧**：sse.js、api/index.js、session.js、graphModel 契约转换。

## 任务清单

### A. PublishPanel 已发布态加"在线调试"区

1. **输入表单**：读取当前工作流 input 节点的 fields（编辑器内存里已有 graph 数据），动态渲染输入控件（text/number），label 用字段的 label（如"城市"），带 default 的预填
2. **发起调用**：POST `/api/public/run/{apiKey}`（api/publish.js 加 `publicRun(apiKey, input)` 与 `publicGetResult(apiKey, executionId)` 两个函数）
3. **自动轮询**：拿到 executionId 后每 2 秒查一次结果，展示状态流转（排队中/运行中/成功/失败），成功时渲染：
   - llm 节点输出文本（换行保留，等宽或正文样式，好看为主）
   - tokens / 费用（复用 formatCost）
   - 有 references 时复用 ReferenceList 组件
4. 未发布态提示"发布后可在此在线调试"
5. 错误处理：401（key 失效提示重新发布）、429（限流提示稍后再试）、409（工作流正在运行）、400（字段校验）

### B. curl 示例区保留但降级

- 原 PublishCurlSample 保留折叠在"高级"里（给程序员用），默认展开的是在线调试器

### C. mock 模式

- mock 的 public run 两端点已存在，确认与真实行为一致（轮询到终态）

## 验收清单

- [ ] 未发布提示 / 填城市点调用 / 状态流转 / 成功渲染 AI 文本 / 429 限流提示，截图 4 张（.workbuddy/screenshots/ 继续编号）
- [ ] 等 GLM 后端 runUrl 补丁上线后，用真实 key 走一遍在线调试（截图 1 张）
- [ ] 生产构建通过；禁区零改动

## 上报规则

改动文件清单、截图、踩坑，老格式。
