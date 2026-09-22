# 任务卡：WorkBuddy 前端 · 第 3 周（阶梯 1 收官：表单式编辑器 + 调试面板 + 执行历史）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md + docs/视觉规范.md 一起粘贴给你。
> 后端 SSE 接口本周与你的调试面板并行开发，事件协议以契约 v1.3「执行模块」为准（协议已定死，不会变）。

## 你的领土

- 允许：frontend/ 目录下所有文件
- 禁止：改 backend/、docs/；运行 git；私自改契约

## 背景

阶梯 1 的前端形态是**表单式编辑器**（不是画布，画布是阶梯 2）。但数据结构与画布版完全一致（nodes/edges），所以接口调用和状态管理按"图"来设计，阶梯 2 只换交互层。

## 任务清单

### A. 表单式编辑器（/workflows/:id/edit，替换现在的占位页）

- 页面结构：
  - 顶部：工作流名称（可编辑）+ 保存按钮
  - 主体：**节点卡片列表**，按执行顺序从上到下排列
  - 每张卡片：节点类型徽章 + 节点名 + 类型专属配置表单 + 删除按钮
- 节点类型与配置表单（对应契约 v1.3 节点 config）：
  | 类型 | 表单字段 |
  |------|----------|
  | input | fields 动态列表：key/label/type(text/number)/default（可增删行） |
  | llm | model 下拉（deepseek-flash / deepseek-v4-pro）、prompt 多行文本（支持 {{}} 语法，可点选"插入引用"下拉列出上游节点字段）、temperature 滑杆、output_key 文本 |
  | condition | rules 动态列表：left（{{}} 模板）/operator 下拉/right/handle；default_handle |
  | http | tool_id 下拉（从 GET /api/tools 加载）、params 键值对列表（value 支持 {{}}）、output_key |
- **连线方式（表单式）**：每张非 input 卡片顶部有"上游来源"下拉——列出所有节点；条件节点特殊：每个分支 handle 各一个"上游来源"下拉（值为 handle 名），默认分支一个下拉。连线数据转换为 edges 数组
- 保存：组装 nodes/edges → `PUT /api/workflows/{id}/graph`；加载：`GET /api/workflows/{id}/graph`
- 校验提示：后端返回 INVALID_GRAPH 时把 message 弹出来（如"图中存在环"）
- 至少内置一个"模板"：新建工作流后一键生成"天气助手"示例（input→http→llm），降低演示门槛

### B. 调试面板（编辑器页底部）

- 输入区：根据 input 节点 fields 动态生成表单
- "运行"按钮 → `POST /api/workflows/{id}/execute` → 拿到 executionId → 立即连 `GET /api/executions/{id}/stream`（**fetch + ReadableStream** 消费 SSE，因为要带 Authorization header，不能用 EventSource）
- 展示区：
  - 节点状态列表：每个节点一个状态点（pending 灰 / running 蓝 / success 绿 / failed 红 / skipped 橙），按事件流实时更新
  - LLM 输出区：token 增量滚动拼接显示（打字机效果）
  - 执行结束显示：总 token、耗时、费用；失败显示 error
- 429 限流提示：RATE_LIMITED 时提示"操作过于频繁"

### C. 执行历史页（/executions）

- 表格：工作流名（null 显示"已删除的工作流"）、状态徽章、totalTokens、totalCost、durationMs、startedAt、操作（查看详情）
- 详情抽屉：input/output JSON 格式化展示 + 节点日志时间线（调用 `GET /api/executions/{id}` 和 `/logs`）
- 状态轮询：running 状态的行每 2 秒轮询一次刷新

### D. 视觉规范延续

- 编辑器、调试面板、历史页全部延续《视觉规范》：深色玻璃拟态、渐变主色、状态色（灰/蓝/绿/红/橙）
- 节点状态点用 Element Plus 图标或自绘小圆点 + 发光效果
- token 打字机区域做成代码块风格（等宽字体、深底、滚动条）

## 验收清单（全部打勾）

- [ ] 新建工作流 → 表单式搭一条 input→http→llm 流程 → 保存 → 刷新后图数据一致
- [ ] 条件分支流程（两条分支）保存成功且执行结果正确
- [ ] 调试面板：运行 → 看到节点状态流转 + token 打字机 → 结束显示统计
- [ ] 执行失败（如 http 节点 tool_id 填错）时红色状态 + error 展示
- [ ] 执行历史页：列表 + 详情抽屉完整可用
- [ ] 截图 6 张：编辑器（含条件分支）、调试运行中、调试完成、执行历史、历史详情、错误态
- [ ] 深色主题全局一致，无白屏闪动

## 注意

- SSE 协议字段严格按契约 v1.3，一个字符都别改；发现不合理处上报统筹
- reasoning_content 问题：后端只推 content，首 token 可能等 2-5 秒（v4 是推理模型），前端不要误判为卡死，加"思考中"提示
- 本周完成后阶梯 1 收官：项目就是一个完整可演示的 MVP
