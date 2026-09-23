import request from './index'

/**
 * 契约 v1.3「执行模块」
 * POST /api/workflows/{id}/execute          -> 202 { executionId }
 * GET  /api/executions/{id}                 -> 执行记录
 * GET  /api/executions/{id}/logs            -> { items, total }
 * GET  /api/executions?workflowId=&page=&size= -> { items, total, page, size }
 *
 * 注：SSE 走 /api/executions/{id}/stream，因为要带 Authorization header，
 * 不能用 EventSource，实现在 utils/sse.js（fetch + ReadableStream）。
 */

export function executeWorkflow(id, input) {
  return request.post(`/workflows/${id}/execute`, { input }).then((res) => res.data)
}

export function getExecution(id) {
  return request.get(`/executions/${id}`).then((res) => res.data)
}

export function getExecutionLogs(id) {
  return request.get(`/executions/${id}/logs`).then((res) => res.data)
}

export function getExecutions(params = {}) {
  return request.get('/executions', { params }).then((res) => res.data)
}
