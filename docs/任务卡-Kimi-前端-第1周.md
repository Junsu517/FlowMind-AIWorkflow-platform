# 任务卡：Kimi 前端 · 第 1 周（2026-09-20 ~ 09-26）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 后端本周也在开发中，接口可能尚未就绪——**先按契约用 mock 数据开发**，联调放在第 3 周。

## 你的领土

- 只允许创建/修改：`flowmind/frontend/` 目录下的文件
- 禁止：改 backend/、docs/、技术方案.md；运行任何 git 命令；私自改 API 契约

## 技术栈（必须遵守）

- Vite + Vue 3（组合式 API + `<script setup>`）+ Element Plus + Pinia + vue-router + axios
- Node 18+（你本地），样式可参考现代化后台风格（玻璃质感可复用你之前项目经验）
- 目录结构：
```
frontend/src/
├── api/          # axios 实例 + 各模块 API 封装
├── stores/       # Pinia（用户状态）
├── router/       # 路由 + 路由守卫
├── views/        # Login.vue、Register.vue、WorkflowList.vue
├── components/   # 公共组件
└── utils/        # 工具函数
```

## 本周目标

前端骨架 + 登录注册 + 工作流列表页，接口层按契约写好，后端没就绪就用 mock。

## 任务清单

### 1. 骨架
- Vite 初始化、依赖安装（element-plus、pinia、vue-router、axios）
- axios 实例：baseURL `/api`，请求拦截器自动带 `Authorization: Bearer <token>`，响应拦截器统一处理错误（401 → 清 token 跳登录页；弹出错误 message）
- Vite 代理：`/api` → `http://127.0.0.1:8080`（与契约一致）
- Pinia 用户 store：token、userInfo、login/logout 动作
- 路由守卫：未登录跳 /login

### 2. 登录/注册页
- 按契约调 POST /api/auth/register、/api/auth/login
- 表单校验（用户名 3-20 位、密码 ≥6 位）、登录成功存 token 跳工作流列表
- 后端没就绪时：接口封装层支持切换 mock（建一个 `USE_MOCK` 开关，mock 返回契约格式的假数据）

### 3. 工作流列表页
- 顶部：标题 + "新建工作流"按钮（弹窗填名称/描述）
- 卡片或表格列表：名称、描述、节点数（nodeCount）、更新时间、操作（编辑/删除）
- 删除要二次确认
- 分页（page/size，按契约格式）
- 编辑按钮先跳占位路由 /workflows/:id/edit（第 3 周做编辑器，本周占位页写"编辑器开发中"即可）

## 验收清单（全部打勾才算完成）

- [ ] `npm run dev` 启动，/login 页面正常渲染
- [ ] mock 模式下：注册 → 登录 → 列表 → 新建 → 删除全流程页面可操作
- [ ] 切回真实接口模式时代码不用改（只改开关）
- [ ] 401 场景（清 localStorage 后访问列表）自动跳登录页
- [ ] 截图 4 张（登录页、注册页、列表页、新建弹窗）交给统筹

## 已知约定（避免返工）

- API 字段名严格按契约（camelCase：nodeCount、createdAt 等）
- 所有请求基础路径 `/api`（契约里每个接口的路径都要带 /api 前缀）
- 时间展示格式：YYYY-MM-DD HH:mm
- 不要引入 TypeScript（降低协作复杂度，纯 JS）
- 不要动后端接口定义，发现契约不合理 → 提给统筹，由统筹改契约
