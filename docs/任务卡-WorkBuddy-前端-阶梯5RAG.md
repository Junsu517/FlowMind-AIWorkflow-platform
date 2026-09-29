# 任务卡：WorkBuddy 前端 · 阶梯 5（RAG 知识库管理 + 节点配置）

> 把本任务卡 + docs/阶梯5-RAG技术方案.md + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 阶梯 5 是 RAG 知识库功能，后端接口由 GLM 同步开发（契约见方案 §3，接口以契约为准，联调前先用 mock 或等后端）。
> **禁区**：sse.js、api/index.js、utils/session.js（已验收冻结）；graphModel.js 的契约转换函数。本轮允许在 graphModel.js 的 defaultConfig/normalizeConfig 里加 knowledge_base_id 字段。

## 任务清单

### A. 知识库管理页（新页面 + 路由 + 侧边导航入口）

1. 路由 `/knowledge`，导航栏加"知识库"入口（在"工作流"旁）
2. 页面功能：
   - 知识库列表（名称/描述/文档数/创建时间）+ 新建（名称+描述）+ 删除（确认弹窗，提示会级联删除文档）
   - 点进知识库 → 文档列表 + **上传按钮**（txt/md/pdf，前端校验 ≤5MB，超限提示）
   - 文档删除按钮
3. 视觉按现有规范（深色玻璃 + 渐变品牌色），空状态给引导文案（如"上传一篇文档，让 AI 基于你的资料回答问题"）

### B. LLM 节点配置加"引用知识库"

1. NodeConfigForm 的 llm 区块加"知识库"下拉（数据来自 GET /api/knowledge-bases，编辑器进入时加载，与 tools 下拉同一加载方式），可选"不引用"（默认）
2. 选中后 config 里存 `knowledge_base_id`；清空则字段移除（与 tools 按需下发同款语义——**后端没配该字段时行为不变**）
3. 引用知识库后，节点卡片摘要可显示"📚 引用:知识库名"（可选，加分）

### C. 调试面板展示 references

1. 执行完成态：若 llm 节点 output 带 `references` 数组，在节点输出块下方渲染"📚 引用来源"小节：每项显示文件名 + snippet 摘要（≤80 字已由后端截断）
2. SSE 实时路径不用动，只在终态渲染（执行详情和历史页同样处理，抽个小组件复用）

### D. mock 模式补齐（如果 VITE_USE_MOCK 开发模式下用到知识库）

- mock adapter 加 knowledge-bases 的最小 CRUD（本地假数据即可，与现有 mock 风格一致）；若你联调时直接对真实后端，可跳过

## 验收清单

- [ ] 知识库页全流程：建库 → 上传 txt → 列表 → 删除（截图 3 张，.workbuddy/screenshots/ 继续编号）
- [ ] LLM 节点下拉选库 → 保存落库 `"knowledge_base_id":X`；清空后字段移除
- [ ] 真实 RAG 执行后调试面板/历史页显示引用来源（等 GLM 后端可用后联调，截图 2 张）
- [ ] 生产构建 `npm run build` 通过
- [ ] 禁区 git diff 为空

## 上报规则

改动文件清单、截图、与 GLM 联调结论、踩坑，老格式。
