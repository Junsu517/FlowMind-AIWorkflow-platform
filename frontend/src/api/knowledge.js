import request from './index'

/**
 * 知识库模块（契约 v1.5 / docs/阶梯5-RAG技术方案.md §3）
 *
 * POST   /api/knowledge-bases                        -> 201 {id,name,description,createdAt}
 * GET    /api/knowledge-bases                        -> 分页列表（含 documentCount）
 * DELETE /api/knowledge-bases/{id}                   -> 204（级联删 Chroma collection + 文档）
 * POST   /api/knowledge-bases/{id}/documents         -> multipart（txt/md/pdf，≤5MB）-> 201 {id,filename,chunkCount}
 * GET    /api/knowledge-bases/{id}/documents         -> 文档列表
 * DELETE /api/knowledge-bases/{id}/documents/{docId} -> 204
 *
 * 新错误码：KNOWLEDGE_BASE_NOT_FOUND(404) / DOCUMENT_TOO_LARGE(400) /
 *          DOCUMENT_PARSE_ERROR(400) / EMBEDDING_ERROR(502)
 * 错误提示统一由 axios 拦截器弹出，这里不做额外处理。
 *
 * 注：multipart 字段名约定为 `file`（方案里只写了"multipart 上传"未指定字段名，
 * 已按惯例取 file，如后端不同需同步改这里）。
 */

export const MAX_DOCUMENT_SIZE = 5 * 1024 * 1024
export const ACCEPTED_EXTENSIONS = ['txt', 'md', 'pdf']

export function getKnowledgeBases(params = {}) {
  return request.get('/knowledge-bases', { params }).then((res) => res.data)
}

export function createKnowledgeBase(data) {
  return request.post('/knowledge-bases', data).then((res) => res.data)
}

export function deleteKnowledgeBase(id) {
  return request.delete(`/knowledge-bases/${id}`).then((res) => res.data)
}

export function getDocuments(kbId, params = {}) {
  return request.get(`/knowledge-bases/${kbId}/documents`, { params }).then((res) => res.data)
}

/** 上传文档（同步切分 + 向量化，后端耗时较长，放宽超时） */
export function uploadDocument(kbId, file) {
  const form = new FormData()
  form.append('file', file)
  return request
    .post(`/knowledge-bases/${kbId}/documents`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000
    })
    .then((res) => res.data)
}

export function deleteDocument(kbId, docId) {
  return request.delete(`/knowledge-bases/${kbId}/documents/${docId}`).then((res) => res.data)
}
