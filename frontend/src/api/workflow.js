import request from './index'

/** 契约：GET /api/workflows?page=&size= -> { items, total, page, size } */
export function getWorkflows(params = {}) {
  return request.get('/workflows', { params }).then((res) => res.data)
}

/** 契约：POST /api/workflows -> 201 workflow */
export function createWorkflow(data) {
  return request.post('/workflows', data).then((res) => res.data)
}

/** 契约：GET /api/workflows/{id} -> 200 workflow */
export function getWorkflow(id) {
  return request.get(`/workflows/${id}`).then((res) => res.data)
}

/** 契约：PUT /api/workflows/{id} -> 200 workflow */
export function updateWorkflow(id, data) {
  return request.put(`/workflows/${id}`, data).then((res) => res.data)
}

/** 契约：DELETE /api/workflows/{id} -> 204 无 body */
export function deleteWorkflow(id) {
  return request.delete(`/workflows/${id}`).then((res) => res.data)
}
