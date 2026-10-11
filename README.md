# FlowMind · AI 智能体工作流平台

让不懂代码的人，也能拖拽搭出自己的 AI 应用。

> 在线演示：http://43.108.111.175/fm/ ｜ 源码：https://gitee.com/junsu517/flowmind-ai-work-platform

FlowMind 是一个对标 Dify/Coze 的可视化 AI 工作流平台：像画流程图一样，把「输入 → LLM → 条件判断 → HTTP 调用」等节点拖拽连线，平台负责执行编排、流式输出与用量统计。个人项目，独立开发，已上线公网。

## 能做什么

| 能力 | 说明 |
|------|------|
| 拖拽式编排 | Vue Flow 画布自由连线，支持输入 / LLM / 条件 / HTTP 四类节点，自动校验环、孤立节点等非法图 |
| 并行执行引擎 | 无依赖的节点同时执行，条件分支运行时路由，执行过程节点级可视化 |
| Function Calling | LLM 自动调用 HTTP 工具，多轮循环直到任务完成（基于 DeepSeek 流式接口） |
| RAG 知识库 | 上传文档即可检索问答：切分 → 向量化 → 语义检索 Top-K，回答附带引用来源 |
| 流式输出 | SSE 实时推送，token 级打字机效果 + 节点状态流转 |
| 可靠异步执行 | 基于 RabbitMQ 的队列执行：失败重试、死信队列、进程重启任务不丢 |
| 定时触发 | 按 cron 表达式定时自动运行工作流 |
| 发布为 HTTP API | 一键把工作流变成可调用的 API，API Key 鉴权 + 限流 + 在线调试器 |
| 统计仪表盘 | 执行数、成功率、token 消耗、费用等 8 项指标，30 天趋势与来源分布 |

## 技术栈

**后端**：Java 17 · Spring Boot 3 · MyBatis-Plus · MySQL 8 · Redis · RabbitMQ · Quartz · JWT

**AI**：DeepSeek API（LLM，OkHttp 流式）· SiliconFlow bge-m3（向量化）· Chroma（向量检索）

**前端**：Vue 3 · Vue Flow · Element Plus · Pinia · ECharts · Vite

**部署**：阿里云 ECS · Nginx · systemd

## 架构一览

```
浏览器 (Vue 3)
   │ HTTP / SSE
nginx（静态托管 + 反向代理）
   └─ Spring Boot 3 单体分层
        ├─ MySQL        工作流与执行数据
        ├─ Redis        限流 / 分布式锁
        ├─ RabbitMQ     异步执行队列（可靠投递）
        ├─ Quartz       定时触发
        └─ DeepSeek / Chroma    LLM 调用 / 向量检索
```

## 快速开始（本地运行）

### 环境要求

- JDK 17+、MySQL 8、Redis、Node.js 18+
- RabbitMQ 可选：不安装时执行引擎走进程内线程池，核心功能完整可用；安装后设置 `EXECUTION_MODE=mqueue` 启用队列执行
- RAG 知识库需要：Chroma（本地 8001 端口）+ 硅基流动 API Key（Embedding）

### 步骤

1. **建库**：创建数据库 `flowmind`，导入 `backend/src/main/resources/schema.sql`

2. **配置环境变量**（后端启动前设置）：

   | 变量 | 必填 | 说明 |
   |------|------|------|
   | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 是 | MySQL 连接（有本地默认值） |
   | `DEEPSEEK_API_KEY` | 是 | LLM 节点必需 |
   | `EMBEDDING_API_KEY` | RAG 时 | 硅基流动 Embedding |
   | `CHROMA_BASE_URL` | RAG 时 | 默认 `http://127.0.0.1:8001` |
   | `RABBITMQ_HOST` 等 | MQ 模式时 | 有本地默认值 |

3. **启动后端**：

   ```bash
   cd backend
   mvn spring-boot:run        # 监听 8080
   ```

4. **启动前端**：

   ```bash
   cd frontend
   npm install
   npm run dev                # http://localhost:5173，/api 已代理到 8080
   ```

5. 浏览器打开 http://localhost:5173，注册账号后即可拖拽搭建第一个工作流

## 项目结构

```
flowmind/
├── backend/     Spring Boot 3 后端（engine/ 为并行执行引擎核心）
└── frontend/    Vue 3 + Vue Flow 前端
```

## 性能与质量

- JMeter 压测：提交链路 10 并发 p95 240ms
- 56+ 项接口契约自动化验收，对生产环境 ALL PASS
- 执行引擎 48 个单元测试覆盖边界语义（汇聚节点、条件路由、fail-fast）

## License

[木兰宽松许可证，第 2 版（MulanPSL-2.0）](http://license.coscl.org.cn/MulanPSL2)
