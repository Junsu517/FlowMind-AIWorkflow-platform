# 阶梯 5 · RAG 知识库技术方案

> 统筹（Claude）2026-09-23 定稿。目标：FlowMind 支持"知识库问答"——用户上传文档，LLM 节点引用知识库，回答基于真实资料并带引用来源。
> 简历收益：RAG 检索增强生成、向量检索、LangChain4j、Chroma 实践、文档切分。
> 面试收益：切分策略、向量检索原理、FAISS/Milvus/Chroma 选型差异、何时上向量库。

## 1. 架构

```
上传文档(txt/md/pdf)
   → 解析文本 → 按段落切分 chunk（~500 字，重叠 50 字）
   → SiliconFlow bge-m3 向量化（1024 维，OpenAI 兼容 REST，免费额度）
   → 存入 Chroma（每知识库一个 collection，Python 服务，Java 走 HTTP）

执行时（LLM 节点配置了 knowledge_base_id）：
   → 渲染后的 prompt 作为检索 query → bge-m3 向量化 → Chroma top-K=3 检索
   → chunks 拼装为参考资料注入 messages → DeepSeek 生成
   → output 附带 references（来源文件名 + 片段摘要 + 相似度分数）
```

**关键设计**：RAG 完全在**执行器层**实现（LlmNodeExecutor 内部），引擎无感知——不改 engine 包，契约只加字段和接口。

## 2. 数据模型（新增 2 表）

```sql
CREATE TABLE knowledge_bases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) DEFAULT '',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (user_id)
);
CREATE TABLE knowledge_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    chunk_count INT DEFAULT 0,
    file_size INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kb (kb_id)
);
```
chunk 本体存 Chroma（不落 MySQL），MySQL 只存文档元数据与 chunk_count。

## 3. API 契约新增（v1.5 知识库模块）

| 接口 | 说明 |
|------|------|
| POST /api/knowledge-bases | {name, description?} → 201 {id,name,description,createdAt} |
| GET /api/knowledge-bases | 分页列表（含 documentCount） |
| DELETE /api/knowledge-bases/{id} | 204（级联删 Chroma collection + 文档） |
| POST /api/knowledge-bases/{id}/documents | multipart 上传（txt/md/pdf，≤5MB）→ 201 {id,filename,chunkCount}；同步切分+向量化 |
| GET /api/knowledge-bases/{id}/documents | 文档列表 |
| DELETE /api/knowledge-bases/{id}/documents/{docId} | 204（同时删 Chroma 对应 chunks） |

- 新错误码：KNOWLEDGE_BASE_NOT_FOUND(404)、DOCUMENT_TOO_LARGE(400)、DOCUMENT_PARSE_ERROR(400)、EMBEDDING_ERROR(502，向量化服务不可用时)
- **llm 节点 config 新增可选字段**：`"knowledge_base_id": 1`。配置后输出额外带 `"references": [{filename, snippet(≤80字), score}]`；未配置行为与现在完全一致
- 检索 query 取法：渲染后的 prompt 文本（MVP 简化，够用且可解释）

## 4. 技术选型与理由（面试答案预埋）

| 选型 | 理由 |
|------|------|
| Chroma | 轻量（~200MB 内存），2C2G 服务器扛得住；Python 服务 + REST，Java 集成简单；HR 清单"了解"档匹配 |
| bge-m3（SiliconFlow） | 免费额度、OpenAI 兼容、中文效果好；config 可切其他 embedding 服务 |
| LangChain4j | HR 清单"熟悉"档；用它的 DocumentSplitter/EmbeddingStore；若 chroma 模块集成摩擦大，退路为 OkHttp 直连 Chroma REST（契约不变） |
| 不用 Milvus | 4G 内存起步装不下；**面试讲"小规模嵌入模式、大规模才上集群"是架构判断力加分** |
| FAISS 只了解 | 它是 C++ 库非服务，Java 绑定差；面试讲原理即可 |

## 5. 分工与禁区

- **GLM（backend）**：知识库 CRUD + 上传/切分/向量化/检索 service + LlmNodeExecutor 加 RAG 分支（检索→注入→references）。禁区：engine 包、HttpToolInvoker、GlobalExceptionHandler、ExecutionRecoveryService、DemoWorkflowService 已冻结部分
- **WorkBuddy（frontend）**：知识库管理页 + LLM 配置面板"知识库"下拉 + 调试面板展示 references。禁区：sse.js、api/index.js、session.js、graphModel 契约转换
- **统筹（Claude）**：契约 v1.5、服务器装 Chroma（pip + systemd，127.0.0.1:8000）、embedding key 入 flowmind.env、验收 + 契约脚本扩展 + 上线

## 6. 验收标准

- [ ] 上传一篇 txt（如"宠物喂养指南"）→ 建 LLM 工作流引用该库 → 提问 → 回答基于文档内容且 references 非空
- [ ] 未配置 knowledge_base_id 的 llm 节点行为零变化（回归）
- [ ] Chroma 服务重启后数据仍在（持久化）
- [ ] 契约脚本扩展知识库 5 项检查 ALL PASS
- [ ] 服务器部署 Chroma + 全链路线上实测
- [ ] 简历新增 RAG 条目 + 面试手册新增向量检索章节

## 7. 简历文案（上线后启用）

```
● 实现 RAG 检索增强生成：文档切分（段落+重叠窗口）→ bge-m3 向量化 → Chroma
  向量检索 Top-K → 注入 LLM 生成，回答附带引用来源；基于 LangChain4j 构建
```
