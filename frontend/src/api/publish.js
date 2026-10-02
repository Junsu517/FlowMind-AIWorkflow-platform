import request from './index'

/**
 * 工作流发布为 HTTP API（契约 v1.8 / docs/阶梯8-HTTPAPI发布技术方案.md §3）
 *
 * GET    /api/workflows/{id}/publish -> { published, runUrl, createdAt }（不返回 key）
 * POST   /api/workflows/{id}/publish -> 201 { apiKey, runUrl }
 *                                       已发布时返回 200 { runUrl }（不重复暴露 key）
 * DELETE /api/workflows/{id}/publish -> 204 吊销（幂等）
 *
 * 密钥只展示一次：只有 POST 的 201 响应里带 apiKey，之后 GET 只回 runUrl；
 * 用户丢失只能「重新发布」（先吊销再发布）。
 * 新错误码：INVALID_API_KEY(401)、API_RATE_LIMITED(429)
 */

export async function getPublish(workflowId) {
  try {
    const data = await request.get(`/workflows/${workflowId}/publish`).then((res) => res.data)
    if (!data || data.published === false || !data.runUrl) return null
    return data
  } catch (e) {
    if (e?.response?.status === 404) return null
    throw e
  }
}

/**
 * 发布（或重新发布）。
 * 服务端已发布时只回 runUrl（200），拿不到新 key——要换 key 必须先吊销再发布
 * （PublishPanel 的「重新发布」就是 DELETE + POST 两步）。
 */
export function publish(workflowId) {
  return request.post(`/workflows/${workflowId}/publish`).then((res) => res.data)
}

export function unpublish(workflowId) {
  return request.delete(`/workflows/${workflowId}/publish`).then((res) => res.data)
}

/**
 * 外部调用两个端点（阶梯 8.5 在线调试用）：
 * POST /api/public/run/{apiKey}                  -> 202 { executionId }
 * GET  /api/public/run/{apiKey}/executions/{id}  -> { status, output?, errorMessage?, totalTokens? }
 *
 * 注意：这两个端点不需要登录态，请求头里的 JWT 会被后端忽略；
 * 401 的业务 code 是 INVALID_API_KEY（不是 UNAUTHORIZED），
 * 所以拦截器不会把它误判成"登录过期"去清 token。
 */
export function publicRun(apiKey, input) {
  return request.post(`/public/run/${apiKey}`, { input: input || {} }).then((res) => res.data)
}

export function publicGetResult(apiKey, executionId) {
  return request
    .get(`/public/run/${apiKey}/executions/${executionId}`)
    .then((res) => res.data)
}

/** runUrl 形如 http://host/api/public/run/<apiKey>，把 key 抠出来（已发布后 GET 不再给 key） */
export function apiKeyFromRunUrl(runUrl) {
  const text = String(runUrl || '').trim()
  if (!text) return ''
  return text.split('/').pop()
}
