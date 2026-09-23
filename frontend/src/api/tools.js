import request from './index'

/**
 * 契约：GET /api/tools -> { items: [{ id, name, displayName, description, method, paramsSchema }] }
 * http 节点的 tool_id 下拉从这里加载。
 */
export function getTools() {
  return request.get('/tools').then((res) => res.data)
}
