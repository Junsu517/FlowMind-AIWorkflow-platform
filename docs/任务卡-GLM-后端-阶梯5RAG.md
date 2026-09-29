# 任务卡：GLM 后端 · 阶梯 5（RAG 知识库）

> 把本任务卡 + docs/阶梯5-RAG技术方案.md + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 技术方案由统筹定稿，本卡是你的实施范围。**RAG 全部在执行器层实现，engine 包零改动**。
> **禁区**：engine 包、HttpToolInvoker、GlobalExceptionHandler、ExecutionRecoveryService、DemoWorkflowService（已验收冻结部分不许碰）。

## 你的领土

backend/ 下除 engine 包外（本轮新增：knowledge 相关 controller/service/mapper/entity + LlmNodeExecutor 的 RAG 分支）

## 任务清单

### A. 知识库模块（新）

1. 建表（2 张，见方案 §2）+ 实体/Mapper：knowledge_bases、knowledge_documents
2. REST 接口（见方案 §3，6 个接口 + 4 个新错误码，错误码表由统筹预批）：
   - POST /api/knowledge-bases 创建（同时创建 Chroma collection，collection 名建议 `kb_{id}`）
   - GET /api/knowledge-bases 分页列表（带 documentCount）
   - DELETE /api/knowledge-bases/{id} 级联删除
   - POST /api/knowledge-bases/{id}/documents multipart 上传：txt/md 直接读；**pdf 用 PDFBox 提取文本**（先加依赖，解析失败返回 DOCUMENT_PARSE_ERROR）；≤5MB 否则 DOCUMENT_TOO_LARGE
   - GET /api/knowledge-bases/{id}/documents 列表
   - DELETE /api/knowledge-bases/{id}/documents/{docId}
3. 文档处理管线（核心，放在新 service，如 RagService / EmbeddingService）：
   - **切分**：按段落切，目标 ~500 字/chunk、相邻重叠 ~50 字（LangChain4j 的 DocumentSplitter 或手写——你定，手写也不难）
   - **向量化**：POST SiliconFlow `https://api.siliconflow.cn/v1/embeddings`，model=`BAAI/bge-m3`，Bearer key 从环境变量 `EMBEDDING_API_KEY` 读（本地开发先随便填，统筹部署时给真 key；请求失败抛 EMBEDDING_ERROR）
   - **入库**：写 Chroma。**主路径 LangChain4j**（依赖 langchain4j + langchain4j-chroma，自定义 EmbeddingModel 适配 SiliconFlow REST）；**若 chroma 模块集成摩擦大，允许退路**：OkHttp 直连 Chroma REST（chroma 默认端口 8000，端点 /api/v2/tenants/default_tenant/databases/default_database/collections/{name}/add 和 /query），接口契约不变，退路需在汇报里说明理由
   - Chroma 地址从配置读：`chroma.base-url`（默认 http://127.0.0.1:8000），本地开发期间 Chroma 未起时，**上传/检索接口返回 502 EMBEDDING_ERROR 而不是 500**，测试用 Mock 或跳过集成、单元测试测切分与 prompt 拼装

### B. LlmNodeExecutor 加 RAG 分支（只加，不改现有路径）

1. 读 `config.knowledge_base_id`（可选）；未配置 → 现有逻辑一字不动
2. 配置了时：渲染后的 prompt 作为 query → 向量化 → Chroma 检索 top-K=3 → 取 chunk 文本，拼成 system 消息注入：
   ```
   system: 以下是参考资料，请仅依据资料回答，资料未涉及的内容请说明"资料中未提及"：
   【资料 1】...
   【资料 2】...
   【资料 3】...
   ```
   user 消息仍是原 prompt
3. 输出 Map 额外带 `"references"`：[{filename, snippet(≤80字), score}]（来自检索结果）
4. 与 tools（Function Calling）同时配置时：RAG 注入与 tools 循环**兼容共存**（先注入后照常走代理循环）
5. token 统计不变（只统计 LLM 调用，embedding/检索不计）

### C. 测试

- JUnit：切分逻辑（长文本→chunk 数/重叠正确）、prompt 拼装（references 注入格式）、无 knowledge_base_id 时逻辑完全不变（现有测试全绿就是证明）
- 真实链路测试（embedding key 可用后跑，现在先写 @EnabledIfEnvironmentVariable）：上传一篇 txt → 建工作流引用 → 提问 → references 非空且回答基于文档
- 契约脚本扩展：统筹会加知识库检查项，等统筹更新脚本后跑 ALL PASS

## 验收清单

- [ ] 6 接口 + 4 错误码行为正确（curl 实测贴原文）
- [ ] RAG 执行链路跑通（真实 embedding + Chroma + DeepSeek）
- [ ] 无 knowledge_base_id 回归零变化；mvn test 全绿
- [ ] 禁区 git diff 为空
- [ ] 上报：LangChain4j 用没用成（退路说明）、Chroma REST 踩坑、切分参数实测效果、embedding 维度 1024 确认

## 上报规则

改动文件清单、curl 实测、测试结果、需要统筹裁决的问题，老格式。
