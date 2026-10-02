import request from './index'

/**
 * 统计仪表盘（契约 v1.9 / docs/阶梯9-统计仪表盘技术方案.md §3）
 *
 * GET /api/stats/dashboard -> {
 *   summary: { totalExecutions, successRate, totalTokens, totalCost,
 *              activeWorkflows, knowledgeDocs, publishedApis, enabledSchedules },
 *   daily: [ { date, executions, tokens } ... 近 30 天（无数据的日期补 0）],
 *   sourceDist: { manual, scheduled, api },
 *   topWorkflows: [ { workflowId, name, executions } ... 最多 5 条 ]
 * }
 *
 * 成功率口径：success / (success + failed)，进行中（queued/running）不计入分母。
 */
export function getDashboard() {
  return request.get('/stats/dashboard').then((res) => res.data)
}
