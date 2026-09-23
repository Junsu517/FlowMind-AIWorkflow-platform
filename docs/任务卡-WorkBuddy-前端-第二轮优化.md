# 任务卡：WorkBuddy 前端 · 第二轮用户体验优化（用户实测 6 项反馈）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 用户刚刚实测了线上站，总体满意，给了 6 条前端优化意见（第 8 条默认工作流由 GLM 做后端部分）。
> **禁区**：sse.js / api/index.js / utils/session.js（401 逻辑已验收冻结）；graphModel.js 的契约转换函数（graphToCanvas/canvasToGraph/configToWire 不许动）；DebugPanel 的 SSE 解析。本轮允许动 graphModel.js 里 `defaultConfig('input')` 这一处默认值。

## 任务清单（按用户反馈原话归纳）

### 1. 输入节点默认字段改成空行 + 占位提示（用户：默认 city/城市 新手不懂要填什么）

- 现状：`graphModel.js` 的 `defaultConfig('input')` 返回预填 `{key:'city', label:'城市'}` 的一行字段，把 NodeConfigForm 里已有的 placeholder（"key（如 city）"）盖住了
- 改法：
  - `defaultConfig('input')` 改为**一行空字段** `{ key: '', label: '', type: 'text', default: '' }`（placeholder 自然显示）
  - placeholder 文案润色：key → `参数名称（如 city）`、label → `显示名（如 城市）`、default → `默认值（可空）`
  - **保存/运行前校验**：input 字段的 key 不能为空、不能重复（参照 WorkflowEdit.vue 里条件分支 handle 的校验写法，约 336 行附近，加同款校验）；label 为空时自动用 key 兜底
- 注意：契约里"无 default 的字段必填"语义不变，空 default 的字段运行时要求用户填，符合预期
- 改完自查：表单版（?mode=form）和画布版共用 defaultConfig，两边都要过一遍

### 2. 连线上手体验（用户：方块很难点、拖起来不方便）

- 现状：Vue Flow 默认 Strict 连接模式（必须精确落到目标 handle），handle 视觉是方块、命中区小
- 改法：
  - `WorkflowEdit.vue` 的 VueFlow 组件加 `:connection-mode="ConnectionMode.Loose"`——连线拖到目标节点**任意位置**即可吸附（这是体验质变）
  - handle 热区扩大：视觉 12px 圆点、CSS 命中区 ≥ 20px（padding 或 ::after 扩大），hover 时放大+高亮（品牌渐变）
  - 条件节点的多个分支 handle 同样处理，并确保分支名标签仍然可读
- 改完自查：拖连线不再需要精确对准圆点；条件节点从右侧任意分支 handle 拉出仍保留 sourceHandle 语义

### 3. 费用显示格式化（用户：浮点数不适合非计科阅读）

- 现状：DebugPanel 有 `formatCost`，但执行历史页（Executions.vue 第 41 行"费用"列）和详情页可能显示原始小数（如 0.001923456）
- 改法：统一费用显示——`$` 符号 + 最多 4 位小数（如 `$0.0019`）；值为 0 显示 `$0`；所有出现费用的地方（执行历史列表、详情、调试面板统计条）用同一个格式化函数
- 自查：跑一次执行，三处费用显示一致且可读

### 4. temperature 加解释（用户：不知道这个变量有什么用）

- NodeConfigForm 的 temperature 输入框旁加问号图标（el-tooltip）：
  `控制输出随机性（0~2）：越高越有创意、越不稳定；越低越保守、越稳定。默认 0.7`
- 触发方式 hover 即可；深色主题下可读

### 5. max_tool_rounds 加解释（用户：试了有用但需要解释）

- 同款问号图标 tooltip：`模型最多调用几轮工具，达到后强制结束（防死循环）。默认 3，仅在使用工具时生效`
- 建议：未勾选工具时该字段灰置（现在是否已经灰置你确认一下，没有就顺手做了）

### 6. 节点面板加用途说明（用户：新手引导不过关的一部分）

- 左侧节点面板（NodePalette）每类节点名称下加一行小字用途说明，如：
  - input：`接收用户填写的参数`
  - llm：`调用 AI 生成文本（支持工具调用）`
  - http：`调用外部 API（如天气查询）`
  - condition：`按条件选择走哪条分支`
- 保持视觉规范：小字号、次级文字色

## 验收清单

- [ ] 新拖入的输入节点显示空字段 + 占位提示；空 key 保存被拦截
- [ ] 连线可拖到目标节点任意位置吸附；handle 好点
- [ ] 三处费用显示格式化一致
- [ ] 两个 tooltip + 面板说明截图（4 张，老截图目录 .workbuddy/screenshots/）
- [ ] 生产构建 `npm run build` 通过
- [ ] 禁区 git diff 为空（sse/api/session/契约转换）
- [ ] 表单版（?mode=form）回归：输入字段空行不影响表单版使用

## 上报规则

改动文件清单 + 截图 + 踩坑，老格式。改完告诉我，统筹会重新打包上线（生产构建由统筹做，你只做本地验证）。
