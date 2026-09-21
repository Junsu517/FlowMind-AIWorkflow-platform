# FlowMind 前端 · 长期备忘

## 项目约定
- 只能动 frontend/，不跑 git，不改 docs/ 与 API 契约
- mock 开关：.env.development 的 VITE_USE_MOCK（默认 true；联调/验收切 false）
- mock 实现方式：自定义 axios adapter（src/api/mock/adapter.js），不是改业务代码
- 401 判断优先级：响应 code（UNAUTHORIZED/TOKEN_EXPIRED）> 路径白名单
- 后端 127.0.0.1:8080，前端 5173；联调需先确认后端已启动
- 时间展示 YYYY-MM-DD HH:mm（utils/format.js）

## 环境备忘
- Windows + Git Bash；agent-browser 需要 macOS/Windows 桌面浏览器，daemon 会随命令结束退出 →
  浏览器自动化必须把「打开→操作→截图」串在同一条命令里执行
- PowerShell 工具在此环境不回显 stdout；浏览器自动化用 Bash
