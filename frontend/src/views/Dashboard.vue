<template>
  <div>
    <AppHeader />

    <div class="fm-page">
      <header class="page-head">
        <div>
          <h1 class="page-head__title">仪表盘</h1>
          <p class="page-head__desc">近 30 天的使用统计：执行量、成功率、消耗与来源分布</p>
        </div>
        <el-button text :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </header>

      <div v-loading="loading" class="board">
        <!-- ---------- 空状态 ---------- -->
        <div v-if="!loading && isEmpty" class="empty fm-glass">
          <div class="empty__icon">
            <el-icon><DataLine /></el-icon>
          </div>
          <h3 class="empty__title">还没有统计数据</h3>
          <p class="empty__desc">
            发布并运行一个工作流后，这里会出现你的使用统计：执行量趋势、成功率、tokens 与费用消耗。
          </p>
          <el-button type="primary" :icon="Promotion" @click="router.push('/workflows')">
            去我的工作流
          </el-button>
        </div>

        <template v-else-if="summary">
          <!-- ---------- 指标卡 ---------- -->
          <section class="cards">
            <article v-for="c in cards" :key="c.key" class="card fm-glass">
              <span class="card__icon" :class="`card__icon--${c.tone}`">
                <el-icon><component :is="c.icon" /></el-icon>
              </span>
              <div class="card__body">
                <span class="card__label">{{ c.label }}</span>
                <b class="card__value fm-mono">{{ c.value }}</b>
              </div>
            </article>
          </section>

          <!-- ---------- 趋势图 ---------- -->
          <section class="panel fm-glass">
            <header class="panel__head">
              <h2 class="panel__title">近 30 天趋势</h2>
              <el-radio-group v-model="metric" size="small">
                <el-radio-button value="executions">执行数</el-radio-button>
                <el-radio-button value="tokens">Tokens</el-radio-button>
              </el-radio-group>
            </header>
            <div ref="lineRef" class="chart chart--line" />
          </section>

          <div class="grid-2">
            <!-- ---------- 来源分布 ---------- -->
            <section class="panel fm-glass">
              <header class="panel__head">
                <h2 class="panel__title">执行来源分布</h2>
              </header>
              <div ref="pieRef" class="chart chart--pie" />
            </section>

            <!-- ---------- Top 工作流 ---------- -->
            <section class="panel fm-glass">
              <header class="panel__head">
                <h2 class="panel__title">执行最多的工作流</h2>
              </header>
              <ul v-if="top.length" class="top">
                <li v-for="(t, i) in top" :key="t.workflowId ?? i" class="top__item">
                  <span class="top__rank fm-mono">{{ i + 1 }}</span>
                  <span class="top__name">{{ t.name || `工作流 #${t.workflowId}` }}</span>
                  <span class="top__bar">
                    <i :style="{ width: percentOf(t.executions) }" />
                  </span>
                  <span class="top__count fm-mono">{{ t.executions }}</span>
                </li>
              </ul>
              <p v-else class="top__empty">还没有执行记录</p>
            </section>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh,
  DataLine,
  VideoPlay,
  CircleCheck,
  Coin,
  Money,
  FolderOpened,
  Document,
  Link,
  Timer,
  Promotion
} from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import AppHeader from '@/components/AppHeader.vue'
import { getDashboard } from '@/api/stats'
import { formatCost } from '@/utils/format'

const router = useRouter()

const loading = ref(false)
const data = ref(null)
const metric = ref('executions')
const lineRef = ref(null)
const pieRef = ref(null)
let lineChart = null
let pieChart = null

const summary = computed(() => data.value?.summary || null)
const daily = computed(() => data.value?.daily || [])
const sourceDist = computed(() => data.value?.sourceDist || {})
const top = computed(() => data.value?.topWorkflows || [])

const isEmpty = computed(() => {
  const s = summary.value
  return !s || !s.totalExecutions
})

/** 千分位 */
function num(value) {
  const n = Number(value || 0)
  return n.toLocaleString('en-US')
}

const cards = computed(() => {
  const s = summary.value || {}
  return [
    { key: 'executions', label: '总执行数', value: num(s.totalExecutions), icon: VideoPlay, tone: 'brand' },
    {
      key: 'rate',
      label: '成功率',
      value: s.totalExecutions ? `${Math.round((s.successRate || 0) * 100)}%` : '-',
      icon: CircleCheck,
      tone: 'success'
    },
    { key: 'tokens', label: '总 tokens', value: num(s.totalTokens), icon: Coin, tone: 'cyan' },
    { key: 'cost', label: '总费用', value: formatCost(s.totalCost), icon: Money, tone: 'warn' },
    { key: 'wf', label: '活跃工作流', value: num(s.activeWorkflows), icon: FolderOpened, tone: 'brand' },
    { key: 'docs', label: '知识库文档', value: num(s.knowledgeDocs), icon: Document, tone: 'cyan' },
    { key: 'apis', label: '已发布 API', value: num(s.publishedApis), icon: Link, tone: 'warn' },
    { key: 'sched', label: '启用定时计划', value: num(s.enabledSchedules), icon: Timer, tone: 'success' }
  ]
})

function percentOf(count) {
  const max = Math.max(...top.value.map((t) => t.executions || 0), 1)
  return `${Math.round(((count || 0) / max) * 100)}%`
}

// ---------- 图表（深色主题，配色对齐视觉规范） ----------
const AXIS_TEXT = '#8b93a7'
const SPLIT_LINE = 'rgba(255,255,255,0.06)'
const SOURCE_COLORS = { manual: '#8b93a7', scheduled: '#6366f1', api: '#22d3ee' }

function lineOption() {
  const rows = daily.value
  const isTokens = metric.value === 'tokens'
  return {
    grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(17,21,29,0.95)',
      borderColor: 'rgba(255,255,255,0.08)',
      textStyle: { color: '#e8ebf2', fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(99,102,241,0.5)' } }
    },
    xAxis: {
      type: 'category',
      data: rows.map((r) => String(r.date).slice(5)),
      axisLine: { lineStyle: { color: SPLIT_LINE } },
      axisTick: { show: false },
      axisLabel: { color: AXIS_TEXT, fontSize: 11, interval: 4 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: SPLIT_LINE } },
      axisLabel: { color: AXIS_TEXT, fontSize: 11 }
    },
    series: [
      {
        name: isTokens ? 'tokens' : '执行数',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: rows.map((r) => (isTokens ? r.tokens || 0 : r.executions || 0)),
        lineStyle: { width: 2, color: '#6366f1' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(99,102,241,0.45)' },
            { offset: 1, color: 'rgba(99,102,241,0.02)' }
          ])
        }
      }
    ]
  }
}

function pieOption() {
  const dist = sourceDist.value
  const label = { manual: '手动', scheduled: '定时', api: 'API' }
  const items = ['manual', 'scheduled', 'api']
    .map((k) => ({ name: label[k], value: dist[k] || 0, itemStyle: { color: SOURCE_COLORS[k] } }))
    .filter((i) => i.value > 0)
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(17,21,29,0.95)',
      borderColor: 'rgba(255,255,255,0.08)',
      textStyle: { color: '#e8ebf2', fontSize: 12 },
      formatter: '{b}: {c} 次 ({d}%)'
    },
    legend: {
      bottom: 0,
      textStyle: { color: AXIS_TEXT, fontSize: 11 },
      itemWidth: 10,
      itemHeight: 10
    },
    series: [
      {
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#0b0e14', borderWidth: 2 },
        label: { show: false },
        data: items.length ? items : [{ name: '暂无数据', value: 1, itemStyle: { color: '#2a3040' } }]
      }
    ]
  }
}

function renderCharts() {
  if (isEmpty.value) return
  nextTick(() => {
    if (lineRef.value) {
      lineChart = lineChart || echarts.init(lineRef.value)
      lineChart.setOption(lineOption(), true)
    }
    if (pieRef.value) {
      pieChart = pieChart || echarts.init(pieRef.value)
      pieChart.setOption(pieOption(), true)
    }
  })
}

function resizeCharts() {
  lineChart?.resize()
  pieChart?.resize()
}

watch(metric, renderCharts)

async function load() {
  loading.value = true
  try {
    data.value = await getDashboard()
    renderCharts()
  } catch (e) {
    data.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  lineChart?.dispose()
  pieChart?.dispose()
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

.page-head .el-button {
  margin-left: auto;
}

.board {
  min-height: 320px;
}

/* ---------- 指标卡 ---------- */
.cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}

.card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  background: var(--surface-glass-strong);
}

.card__icon {
  flex: none;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: 17px;
  color: var(--on-brand);
  background: var(--gradient-brand);
}

.card__icon--success {
  background: linear-gradient(135deg, #34d399, #22d3ee);
}
.card__icon--cyan {
  background: linear-gradient(135deg, #22d3ee, #6366f1);
}
.card__icon--warn {
  background: linear-gradient(135deg, #fb923c, #f87171);
}

.card__body {
  min-width: 0;
}

.card__label {
  display: block;
  font-size: 11.5px;
  color: var(--text-secondary);
}

.card__value {
  display: block;
  margin-top: 3px;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- 面板 ---------- */
.panel {
  padding: 16px 18px 12px;
  background: var(--surface-glass-strong);
  margin-bottom: 18px;
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.panel__title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}

.chart {
  width: 100%;
}

.chart--line {
  height: 260px;
}

.chart--pie {
  height: 240px;
}

/* ---------- Top 列表 ---------- */
.top {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.top__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid var(--border-glass);
}

.top__item:last-child {
  border-bottom: none;
}

.top__rank {
  flex: none;
  width: 18px;
  font-size: 11.5px;
  color: var(--text-faint);
}

.top__name {
  flex: none;
  max-width: 130px;
  font-size: 12.5px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top__bar {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: var(--overlay-4);
  overflow: hidden;
}

.top__bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--gradient-brand);
}

.top__count {
  flex: none;
  font-size: 12px;
  color: var(--text-secondary);
}

.top__empty {
  margin: 24px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-faint);
}

/* ---------- 空状态 ---------- */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 72px 24px 80px;
}

.empty__icon {
  width: 76px;
  height: 76px;
  border-radius: 22px;
  display: grid;
  place-items: center;
  font-size: 34px;
  color: var(--on-brand);
  background: var(--gradient-brand);
  box-shadow: var(--shadow-glow), var(--shadow-pop);
  margin-bottom: 22px;
}

.empty__title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

.empty__desc {
  margin: 0 0 24px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
  max-width: 420px;
}

@media (max-width: 1100px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
