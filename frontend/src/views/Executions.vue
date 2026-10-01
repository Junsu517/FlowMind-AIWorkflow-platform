<template>
  <div>
    <AppHeader />

    <div class="fm-page">
      <header class="page-head">
        <div>
          <h1 class="page-head__title">执行历史</h1>
          <p class="page-head__desc">
            共 <span class="fm-mono">{{ total }}</span> 条记录
            <span v-if="hasRunning" class="page-head__live">· 有执行正在进行，每 2 秒自动刷新</span>
          </p>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </header>

      <div class="table-wrap fm-glass">
        <el-table v-loading="loading" :data="items" style="width: 100%" empty-text="还没有执行记录">
          <el-table-column prop="workflowName" label="工作流" min-width="180">
            <template #default="{ row }">
              <span v-if="row.workflowName">{{ row.workflowName }}</span>
              <span v-else class="deleted">已删除的工作流</span>
            </template>
          </el-table-column>

          <!-- 契约 v1.7：执行来源（旧数据没有 triggerType，按手动显示） -->
          <el-table-column label="来源" width="96">
            <template #default="{ row }">
              <span class="src" :class="`src--${triggerType(row)}`">
                <el-icon class="src__icon">
                  <Timer v-if="triggerType(row) === 'scheduled'" />
                  <Mouse v-else />
                </el-icon>
                {{ triggerText(row) }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <span class="status-badge" :class="`status-badge--${row.status}`">
                <span class="dot" />
                {{ statusText(row.status) }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="Tokens" width="110" align="right">
            <template #default="{ row }">
              <span class="fm-mono">{{ row.totalTokens ?? 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="费用" width="110" align="right">
            <template #default="{ row }">
              <span class="fm-mono">{{ formatCost(row.totalCost) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ row }">
              <span class="fm-mono">{{ formatDuration(row.durationMs) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="开始时间" width="150">
            <template #default="{ row }">
              <span class="fm-mono">{{ formatDateTime(row.startedAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="110" align="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openDetail(row)">查看详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="total > size" class="pager">
          <el-pagination
            background
            layout="prev, pager, next"
            :current-page="page"
            :page-size="size"
            :total="total"
            @current-change="onPageChange"
          />
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" :title="`执行详情 #${detail?.id ?? ''}`" size="640px" destroy-on-close>
      <div v-loading="detailLoading" class="detail">
        <div class="detail__meta">
          <div class="meta-item">
            <span class="meta-item__k">工作流</span>
            <span class="meta-item__v">{{ detail?.workflowName || '已删除的工作流' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-item__k">状态</span>
            <span class="meta-item__v">
              <span class="status-badge" :class="`status-badge--${detail?.status}`">
                <span class="dot" />{{ statusText(detail?.status) }}
              </span>
            </span>
          </div>
          <div class="meta-item">
            <span class="meta-item__k">来源</span>
            <span class="meta-item__v">
              <span class="src" :class="`src--${triggerType(detail)}`">
                <el-icon class="src__icon">
                  <Timer v-if="triggerType(detail) === 'scheduled'" />
                  <Mouse v-else />
                </el-icon>
                {{ triggerText(detail) }}
              </span>
            </span>
          </div>
          <div class="meta-item">
            <span class="meta-item__k">开始 / 结束</span>
            <span class="meta-item__v fm-mono">
              {{ formatDateTime(detail?.startedAt) }} → {{ formatDateTime(detail?.finishedAt) }}
            </span>
          </div>
          <div class="meta-item">
            <span class="meta-item__k">Tokens / 费用</span>
            <span class="meta-item__v fm-mono">
              {{ detail?.totalTokens ?? 0 }} · {{ formatCost(detail?.totalCost) }}
            </span>
          </div>
        </div>

        <div v-if="detail?.errorMessage" class="detail__error">
          <el-icon><CircleCloseFilled /></el-icon>
          <span>{{ detail.errorMessage }}</span>
        </div>

        <section class="detail__section">
          <h3 class="detail__h3">输入 input</h3>
          <pre class="fm-code json-block">{{ pretty(detail?.input) }}</pre>
        </section>

        <section class="detail__section">
          <h3 class="detail__h3">输出 output</h3>
          <pre class="fm-code json-block">{{ pretty(detail?.output) }}</pre>
        </section>

        <section class="detail__section">
          <h3 class="detail__h3">节点日志（{{ logs.length }}）</h3>
          <el-timeline v-if="logs.length">
            <el-timeline-item
              v-for="(log, i) in logs"
              :key="`${log.nodeKey}-${i}`"
              :color="timelineColor(log.status)"
              :hollow="log.status === 'skipped'"
            >
              <div class="log">
                <div class="log__head">
                  <span class="log__name">{{ log.nodeName }}</span>
                  <span class="log__type fm-mono">{{ log.nodeType }}</span>
                  <span class="status-badge" :class="`status-badge--${log.status}`">
                    <span class="dot" />{{ statusText(log.status) }}
                  </span>
                  <span class="log__stat fm-mono">
                    {{ formatDuration(log.durationMs) }}
                    <template v-if="log.tokens"> · {{ log.tokens }} tokens</template>
                  </span>
                </div>
                <p v-if="log.errorMessage" class="log__error">{{ log.errorMessage }}</p>
                <pre v-if="log.output && Object.keys(log.output || {}).length" class="fm-code log__io">{{ shortJson(log.output) }}</pre>
                <ReferenceList v-if="log.output?.references?.length" :items="log.output.references" />
              </div>
            </el-timeline-item>
          </el-timeline>
          <p v-else class="detail__empty">暂无节点日志</p>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Refresh, CircleCloseFilled, Timer, Mouse } from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import ReferenceList from '@/components/editor/ReferenceList.vue'
import { getExecutions, getExecution, getExecutionLogs } from '@/api/execution'
import { formatDateTime, formatDuration, formatCost } from '@/utils/format'

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

const drawer = ref(false)
const detail = ref(null)
const logs = ref([])
const detailLoading = ref(false)

let timer = null

// 契约 v1.6：queued（排队中）与 running 一样属于"还没结束"，都要继续轮询
const hasRunning = computed(() =>
  items.value.some((r) => r.status === 'running' || r.status === 'queued')
)

function statusText(status) {
  return {
    success: '成功',
    failed: '失败',
    running: '运行中',
    queued: '排队中',
    pending: '等待中',
    skipped: '已跳过'
  }[status] || status || '-'
}

function pretty(value) {
  if (value == null) return '-'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** 节点日志的 output 可能非常大（如天气原始 JSON），抽屉里截断展示 */
function shortJson(value) {
  // references 由下方「引用来源」单独渲染，避免同一份数据出现两次
  let target = value
  if (value && Array.isArray(value.references)) {
    const { references, ...rest } = value
    target = rest
  }
  const text = pretty(target)
  return text.length > 800 ? `${text.slice(0, 800)}\n… （已截断，完整数据见上方 output）` : text
}

/** 契约 v1.7：triggerType 缺省按 manual 处理，兼容没有该字段的历史数据 */
function triggerType(row) {
  return row?.triggerType === 'scheduled' ? 'scheduled' : 'manual'
}

function triggerText(row) {
  return triggerType(row) === 'scheduled' ? '定时' : '手动'
}

function timelineColor(status) {
  const map = {
    success: '#34d399',
    failed: '#f87171',
    running: '#60a5fa',
    queued: '#a78bfa',
    skipped: '#fb923c'
  }
  return map[status] || '#8b93a7'
}

async function load() {
  loading.value = true
  try {
    const data = await getExecutions({ page: page.value, size: size.value })
    items.value = data.items || []
    total.value = data.total || 0
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

function onPageChange(p) {
  page.value = p
  load()
}

async function openDetail(row) {
  drawer.value = true
  detail.value = row
  logs.value = []
  detailLoading.value = true
  try {
    const [exec, logData] = await Promise.all([
      getExecution(row.id),
      getExecutionLogs(row.id).catch(() => null)
    ])
    detail.value = exec
    logs.value = logData?.items || []
    // 列表行同步最新状态
    const idx = items.value.findIndex((x) => x.id === row.id)
    if (idx >= 0) items.value[idx] = exec
  } catch (e) {
    /* 错误由拦截器提示 */
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  load()
  // 契约：running 的行每 2 秒轮询刷新
  timer = setInterval(() => {
    if (hasRunning.value) load()
  }, 2000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
}

.page-head__title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.page-head__desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.page-head__live {
  color: var(--status-running);
}

.page-head > .el-button {
  margin-left: auto;
}

.table-wrap {
  padding: 8px 8px 4px;
  background: var(--surface-glass-strong);
  overflow: hidden;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 8px;
}

.deleted {
  color: var(--text-faint);
  font-style: italic;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--overlay-3);
  border: 1px solid var(--border-glass);
  color: var(--text-primary);
}

/* ---------- 执行来源（手动 / 定时） ---------- */
.src {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  border: 1px solid var(--border-glass);
  color: var(--text-secondary);
  background: var(--overlay-3);
}

.src__icon {
  font-size: 12px;
}

.src--scheduled {
  color: var(--accent-chip-text);
  border-color: var(--accent-border);
  background: var(--accent-soft);
}

.status-badge .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--status-pending);
}

.status-badge--success .dot {
  background: var(--status-success);
  box-shadow: 0 0 8px rgba(52, 211, 153, 0.6);
}
.status-badge--running .dot {
  background: var(--status-running);
  animation: pulse 1.2s var(--ease) infinite;
}
/* 排队中：紫色 + 更慢的呼吸，避免和"运行中"混淆 */
.status-badge--queued .dot {
  background: var(--status-queued);
  animation: pulse 2.4s var(--ease) infinite;
}
.status-badge--failed .dot {
  background: var(--status-failed);
  box-shadow: 0 0 8px rgba(248, 113, 113, 0.6);
}
.status-badge--skipped .dot {
  background: var(--status-skipped);
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

:deep(.el-table) {
  --el-table-border-color: var(--border-glass);
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--overlay-3);
  background: transparent;
}

:deep(.el-table th.el-table__cell) {
  background: transparent;
  color: var(--text-secondary);
  font-weight: 600;
}

:deep(.el-table td.el-table__cell) {
  border-bottom: 1px solid var(--border-glass);
}

/* ---------- 抽屉 ---------- */
.detail {
  padding-right: 4px;
}

.detail__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
  padding: 14px 16px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-md);
  background: var(--overlay-1);
}

.meta-item__k {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.meta-item__v {
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}

.detail__error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger-border);
  font-size: 13px;
}

.detail__section {
  margin-top: 22px;
}

.detail__h3 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.json-block {
  max-height: 240px;
}

.detail__empty {
  font-size: 12.5px;
  color: var(--text-faint);
}

.log__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 6px;
}

.log__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.log__type {
  font-size: 11px;
  color: var(--text-faint);
}

.log__stat {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--text-secondary);
}

.log__error {
  margin: 0 0 6px;
  font-size: 12.5px;
  color: var(--danger);
}

.log__io {
  max-height: 160px;
  font-size: 11.5px;
}

:deep(.el-drawer__body) {
  padding-top: 12px;
}
</style>
