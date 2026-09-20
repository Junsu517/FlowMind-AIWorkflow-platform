# 任务卡：GLM 后端 · 第 1 周（2026-09-20 ~ 09-26）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 完成后把"验收清单 + 接口响应示例"交给统筹（人）审核。

## 你的领土

- 只允许创建/修改：`flowmind/backend/` 目录下的文件（除 `backend/src/main/java/com/flowmind/engine/` 包——那是统筹负责的引擎核心，**严禁触碰**）
- 禁止：改 frontend/、docs/、技术方案.md；运行任何 git 命令；私自改 API 契约

## 本周目标

搭好 Spring Boot 骨架 + 完成认证模块 + 工作流 CRUD 和整图保存，用 Postman 全部跑通。

## 任务清单

### 1. 项目骨架
- `backend/` 目录：Maven 项目，Java 17，Spring Boot 3.3.x
- 依赖：spring-boot-starter-web、mybatis-plus-spring-boot3-starter、mysql-connector-j、spring-boot-starter-data-redis、jjwt 0.12.x、spring-security-crypto（只用 BCrypt 编码器，不要引入完整 Spring Security 过滤器链）、okhttp、lombok、spring-boot-starter-validation
- 包结构（包名 `com.flowmind`）：
```
com.flowmind
├── config/      # MyBatis-Plus 分页插件、RedisTemplate、WebMvc 配置
├── controller/  # AuthController、WorkflowController
├── service/     # AuthService、WorkflowService
├── mapper/      # MyBatis-Plus Mapper 接口
├── entity/      # User、Workflow、WorkflowNode、WorkflowEdge、Execution、ExecutionNodeLog、Tool
├── dto/         # 请求/响应 DTO
├── common/      # 统一响应、全局异常处理器、JWT 工具类、AuthInterceptor
└── engine/      # ⛔ 统筹的领地，不要创建也不要改
```
- `application.yml`：端口 8080；MySQL 连接用环境变量 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`，本地默认 localhost:3306/flowmind、root/123456；Redis localhost:6379；`DEEPSEEK_API_KEY` 从环境变量读

### 2. 建表 SQL
- 按 docs/数据库设计（找统筹要建表 SQL，会放在 backend/src/main/resources/schema.sql）
- 本地执行建库建表

### 3. 认证模块（严格按 API 契约）
- POST /api/auth/register：用户名唯一校验、BCrypt 加密、签发 JWT
- POST /api/auth/login：校验密码，签发 JWT
- GET /api/auth/me
- JWT：HS256，payload 含 userId、role，有效期 7 天；用拦截器（HandlerInterceptor）解析 `Authorization: Bearer xxx`，把用户信息放进 ThreadLocal/request attribute
- 错误码按契约：USERNAME_EXISTS、BAD_CREDENTIALS

### 4. 工作流模块（严格按 API 契约）
- workflows / workflow_nodes / workflow_edges 三表 CRUD
- PUT /graph 整图保存：事务内删旧节点边、插新的；**必须做图校验**：节点 key 唯一、边两端节点存在、有且仅有一个 input 节点、无孤立节点（input 除外）、无环（Kahn 拓扑排序检测），失败回滚并返回 INVALID_GRAPH + 具体原因
- 列表接口带 nodeCount（子查询或 JOIN 计数）
- 所有查询必须校验归属（workflow.user_id == 当前用户），否则 403

### 5. 工具表预置数据
- tools 表插入 2 条演示工具：天气查询（GET `https://wttr.in/{{city}}?format=j1`）、一言（GET `https://v1.hitokoto.cn/`），paramsSchema 按契约格式

## 验收清单（全部打勾才算完成）

- [ ] `mvn spring-boot:run` 启动无报错
- [ ] Postman：register → login → me 全通过，token 可用
- [ ] Postman：建工作流 → 保存图（含条件分支的工作流）→ 读图 → 列表（nodeCount 正确）→ 删除
- [ ] 保存环状图被拒绝（INVALID_GRAPH）
- [ ] 访问别人的工作流返回 403
- [ ] 把上述 Postman 请求/响应截图或导出 JSON 交给统筹

## 已知约定（避免返工）

- 统一响应格式见 API 契约"通用约定"章节（成功直接返回数据，失败返回 code/message）
- 分页参数 page/size，响应 items/total/page/size
- 时间一律 ISO 8601 字符串
- LLM/执行相关接口（execute、stream、executions）本周**不用实现**，那是第 2 周任务，但表要建好
- 不要用 Spring Security 全家桶，只用 BCrypt + 拦截器，简单可控
