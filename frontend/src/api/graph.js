import request from './index'

/**
 * 图数据（契约 v1.3「工作流模块」）
 * GET  /api/workflows/{id}/graph -> { nodes, edges }
 * PUT  /api/workflows/{id}/graph -> { saved: true }（整图全量替换）
 * 校验失败返回 400 INVALID_GRAPH，message 说明原因（如"图中存在环"）
 */

export function getGraph(id) {
  return request.get(`/workflows/${id}/graph`).then((res) => res.data)
}

export function saveGraph(id, data) {
  return request.put(`/workflows/${id}/graph`, data).then((res) => res.data)
}
