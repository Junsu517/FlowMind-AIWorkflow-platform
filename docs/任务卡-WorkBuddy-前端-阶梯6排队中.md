# 任务卡：WorkBuddy 前端 · 阶梯 6 配套（小卡：queued 排队中展示）

> 把本任务卡 + docs/阶梯6-MQ异步执行技术方案.md 一起粘贴给你。
> 阶梯 6 是后端 MQ 化（GLM 主力），前端只有两处小改，其余一律不动。

## 背景

执行提交后状态从"立刻 running"变为 **queued（排队中）→ running（消费者接手）→ success/failed**。契约 v1.6 唯一变更：executions.status 枚举新增 `queued`。前端接口形状与 SSE 协议零变化。

## 任务清单

1. **执行历史页**（Executions.vue）：status 为 `queued` 时显示"排队中"状态点/文案（与 running 视觉可区分，比如灰色脉冲或时钟图标）
2. **调试面板**：若轮询/SSE 期间收到 queued 状态，同样显示"排队中"（现有 running 逻辑把 queued 当等待处理即可，别打断轮询）
3. mock 模式顺手加 queued 过渡态（可选）

## 验收清单

- [ ] queued 状态两处展示截图
- [ ] 生产构建通过
- [ ] 禁区零改动

## 上报规则

改动文件清单、截图，老格式。本卡很小，等 GLM 后端 ready 后联调一次即可。
