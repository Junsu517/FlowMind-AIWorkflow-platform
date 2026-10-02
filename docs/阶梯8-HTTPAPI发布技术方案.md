# 阶梯 8 · 工作流发布为 HTTP API 技术方案

> 统筹（Claude）2026-10-01 定稿。目标：把工作流"发布"成可被外部 HTTP 调用的接口——用户获得专属 URL + API Key，外部系统可触发工作流并查询结果。
> 简历收益：API 对外发布、API Key 鉴权、平台化能力、对外接口设计。
> 面试收益：API Key vs OAuth 对比、密钥管理（明文 vs 哈希）、对外接口的限流与幂等、为什么用轮询不用长轮询。

## 1. 架构

```
发布：POST /api/workflows/{id}/publish
  → 生成 32 字节随机 API Key（响应中仅此一次完整展示）
  → workflow_api_keys 表落库（key 存明文 MVP，代码注释说明生产应存哈希）
  → 返回 { apiKey, runUrl }

外部调用（无需登录，API Key 鉴权）：
  POST /api/public/run/{apiKey}   body={"input":{...}}
    → 校验 key → 查 workflow → 归属者身份 UserContext → 复用 submit（triggerType='api'）
    → Redis 限流（每 key 每分钟 10 次）→ 202 { executionId }
  GET  /api/public/run/{apiKey}/executions/{executionId}
    → 校验 key 与 execution 归属一致 → 返回状态 + output（完成时）
    → 调用方轮询此接口拿结果（同步阻塞调用是二期）

吊销：DELETE /api/workflows/{id}/publish → 204（key 失效，public 接口 401）
```

**关键设计**：发布只加一层"外部入口"，执行仍然走 submit 全链路（锁→queued→MQ→引擎→SSE 事件照旧内部可见）——队列、重试、死信全部免费继承。

## 2. 数据模型（新增 1 表 + executions.trigger_type 扩展）

```sql
CREATE TABLE workflow_api_keys (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    api_key VARCHAR(64) NOT NULL,        -- 32 字节 hex
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow (workflow_id),
    UNIQUE KEY uk_key (api_key)
);
-- executions.trigger_type 增加取值 'api'（manual/scheduled/api）
```

## 3. 契约 v1.8 变更

| 接口 | 说明 |
|------|------|
| POST /api/workflows/{id}/publish | 201 {apiKey, runUrl}；已发布 → 200 返回现有 runUrl（不重复暴露 key） |
| GET /api/workflows/{id}/publish | 200 {published, runUrl, createdAt}（不返回 key） |
| DELETE /api/workflows/{id}/publish | 204 吊销（幂等） |
| POST /api/public/run/{apiKey} | body {"input":{}} → 202 {executionId}；错误：401 INVALID_API_KEY、429 API_RATE_LIMITED、400 INVALID_INPUT |
| GET /api/public/run/{apiKey}/executions/{id} | 200 {status, output?, errorMessage?, totalTokens?}；401/404 校验 |

- 新错误码：`INVALID_API_KEY(401)`、`API_RATE_LIMITED(429)`
- public 前缀在 AuthInterceptor 白名单（不走 JWT，走 key）
- triggerType 显示：前端历史页来源标签加"API"

## 4. 安全设计（面试答案预埋）

- **为什么用 API Key 而不是 OAuth**：机器对机器的调用不需要用户授权流程，API Key 简单直接；OAuth 用于第三方代表用户操作——本项目是"工作流所有者授权外部系统调用自己"，API Key 语义匹配
- **密钥只展示一次**：发布响应完整返回 key，之后 GET 只回 runUrl——用户丢失只能重新发布（吊销重发）
- **明文 vs 哈希**：MVP 存明文（代码注释说明），生产标准做法是存 SHA-256 哈希、按 key 哈希索引查找——面试讲这个差异就是加分
- **限流独立于用户限流**：每 key 10 次/分钟（Redis Lua 复用限流组件，key 前缀 rl:api:{key}）

## 5. 分工与禁区

- **GLM（backend）**：workflow_api_keys 表、PublishService、PublicRunController（key 鉴权+限流+submit api 触发+结果查询）、AuthInterceptor 白名单 /api/public/**、trigger_type='api' 落库与历史带出；**禁区照旧**（engine、已冻结文件；submit 只加 triggerType 值）
- **WorkBuddy（frontend）**：编辑器"发布"抽屉（发布按钮→一次性展示 apiKey+runUrl+复制、已发布状态、吊销）、执行历史来源加"API"标签
- **统筹（Claude）**：契约 v1.8、服务器建表、验收上线

## 6. 验收标准

- [ ] 发布 → curl 外部调用（无 JWT 仅 key）→ 202 → 轮询到 success + output
- [ ] 错误 key → 401 INVALID_API_KEY；吊销后原 key 401
- [ ] key 限流 12 连发 → 10 放行 2 拒（API_RATE_LIMITED）
- [ ] 契约脚本扩展 publish 6 项检查 ALL PASS
- [ ] mvn test 全绿、禁区零改动

## 7. 简历文案（上线后启用）

```
● 工作流发布为 HTTP API：生成 API Key 对外提供服务，外部系统可直接调用
  工作流获取结果；按 Key 独立限流与鉴权，形成"编排即服务"闭环
```
