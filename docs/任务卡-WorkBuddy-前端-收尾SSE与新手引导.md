# 任务卡：WorkBuddy 前端 · 收尾迭代（SSE 修复 + 新手引导）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 阶梯 2 画布已验收，**本轮不许动**：WorkflowEdit.vue 画布逻辑、canvas/ 组件、graphModel.js 的画布转换、DebugPanel 的 SSE 解析。
> 本轮只做三件事：① sse.js 一个 bug 修复 ② 流式 401 区分处理 ③ 新手引导。

## 你的领土

- 允许：frontend/ 下所有文件（上述禁区除外）
- 禁止：改 backend、docs、engine 包；运行 git；私自改契约

## 背景（统筹诊断结论，已实测）

用户反馈线上站"SSE 不可用，已切换为轮询刷新"频繁出现。统筹诊断三个根因，本轮你修两个：

- **根因 A（主因，在你这边）**：`frontend/src/utils/sse.js` 第 23 行硬编码 `const BASE = '/api'`。部署站在 `/fm/` 路径下（nginx 前缀），SSE 请求打到了别的服务 → 404 → 每次执行都降级。轮询走 axios（baseURL 配置正确）所以能用，这就是"经常显示降级提示"的原因。
- **根因 C**：sse.js 的 onError 不区分错误类型，401（登录过期）也显示"SSE 不可用"。
- 根因 B 在后端（GLM 修），你不用管。

## 任务清单

### A. sse.js 路径修复（一行，但很关键）

- `const BASE = '/api'` 改为读环境变量，与 `src/api/index.js` 的 `baseURL` 完全同款写法：`import.meta.env.VITE_API_BASE || '/api'`
- 自查：`import.meta.env` 在 sse.js 里可用（vite 直接支持）；确认 mock 模式不受影响（mock 分支不经过 BASE）

### B. 流式 401 区分处理

sse.js 的 `onError({ kind: 'http', status, body })` 时：

1. 若 `status === 401`：解析 body JSON 拿 `code`（UNAUTHORIZED / TOKEN_EXPIRED 都在契约错误码表里）
   - 不再走 degraded 轮询降级（轮询也会 401，没有意义）
   - 清除本地 token（用你们现有的 storage 工具）、提示"登录已过期，请重新登录"、跳转登录页
2. 其他错误（404/500/网络）：维持现在的降级轮询行为不变
3. 修复后自查：DebugPanel 的 `degraded` 提示文案可以改成更准确的"实时连接失败，已切换为轮询刷新"（保留现有样式）

### C. 新手引导（用户明确反馈"很多地方不知道要做什么"）

三处，克制一点，不要大改：

1. **工作流列表页空状态**（没有任何工作流时）：居中引导文案 + 主按钮"创建第一个工作流"。有数据时不显示
2. **编辑器首次进入提示**：localStorage 记录"看过引导"，首次进入画布编辑器显示 2-3 步提示条（①从左栏拖节点到画布 ②连接节点 ③点右上角运行），可关闭，之后不再出现
3. **模板按钮醒目化**：工具栏"天气助手/条件分支"模板按钮加个视觉强调（渐变描边即可），让新用户一眼看到"从模板开始"的入口

空画布引导你已经做了，保留即可。

## 验收清单

- [ ] 本地 `npm run dev` 联调：sse.js 走 VITE_API_BASE 后本地 SSE 仍正常（本地 base 是 /api，行为不变）
- [ ] 生产构建 `npm run build` 通过
- [ ] 人为造 401：把 localStorage 的 token 改坏 → 运行调试 → 应提示登录过期并跳登录，而不是"SSE 不可用"
- [ ] 新手引导三处截图（空列表/首进提示/模板按钮）
- [ ] 禁区文件 git diff 为空（画布/转换/SSE 解析零改动）

## 上报规则

改了什么文件、每处的验收证据（截图路径或现象描述）、踩坑——按老格式汇报。
