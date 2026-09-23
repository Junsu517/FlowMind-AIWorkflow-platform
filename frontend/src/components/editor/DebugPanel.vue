<template>
  <section class="debug fm-glass">
    <header class="debug__head">
      <div class="debug__title">
        <el-icon class="debug__icon"><VideoPlay /></el-icon>
        <span>调试面板</span>
        <span v-if="degraded" class="debug__degraded">实时连接失败，已切换为轮询刷新</span>
      </div>
      <div class="debug__actions">
        <el-button v-if="!running" class="fm-btn-primary" :icon="VideoPlay" :loading="starting" @click="run">
          运行
        </el-button>
        <el-button v-else :icon="VideoPause" @click="stop">停止</el-button>
        <el-button v-if="hasResult" text :icon="RefreshRight" @click="reset">清空</el-button>
      </div>
    </header>

    <!-- 输入区：按 input 节点 fields 动态生成 -->
    <div class="debug__inputs">
      <template v-if="inputFields.length">
        <label v-for="f in inputFields" :key="f.key" class="debug__field">
          <span class="debug__label">
            {{ f.label || f.key }}
            <span class="fm-mono debug__key">{{ f.key }}</span>
            <span v-if="!hasDefault(f)" class="debug__required">必填</span>
          </span>
          <el-input
            v-if="f.type === 'number'"
            v-model.number="inputValues[f.key]"
            type="number"
            :placeholder="f.default || '请输入数字'"
          />
          <el-input v-else v-model="inputValues[f.key]" :placeholder="f.default || '请输入'" />
        </label>
      </template>
      <p v-else class="debug__empty-input">当前工作流没有 input 节点，请先添加一个输入节点来定义入参。</p>
    </div>

    <!-- 节点状态 -->
    <div v-if="nodeList.length" class="debug__status">
      <div v-for="n in nodeList" :key="n.nodeKey" class="status-item">
        <span class="dot" :class="`dot--${statusOf(n.nodeKey)}`" />
        <span class="status-item__name">{{ n.name }}</span>
        <span class="status-item__type fm-mono">{{ n.nodeKey }}</span>
        <span v-if="isThinking(n)" class="status-item__thinking">思考中…</span>
      </div>
    </div>

    <!-- 输出：token 打字机 -->
    <div ref="scrollRef" class="debug__output">
      <div v-for="b in blocks" :key="b.nodeKey" class="block">
        <div class="block__head">
          <span class="dot" :class="`dot--${statusOf(b.nodeKey)}`" />
          <span class="block__name">{{ b.name }}</span>
          <span v-if="b.thinking" class="block__thinking">思考中…</span>
        </div>
        <pre class="fm-code block__text">{{ b.text }}<span v-if="!b.done" class="caret" /></pre>
      </div>

      <p v-if="!blocks.length && !running && !errorMsg" class="debug__placeholder">
        点击「运行」开始调试，节点状态与模型输出会实时显示在这里。
      </p>
    </div>

    <!-- 结束统计 / 错误 -->
    <footer v-if="errorMsg || stats" class="debug__foot">
      <div v-if="errorMsg" class="debug__error">
        <el-icon><CircleCloseFilled /></el-icon>
        <span>执行失败：{{ errorMsg }}</span>
      </div>
      <div v-if="stats" class="debug__stats">
        <span class="stat"><i>tokens</i><b class="fm-mono">{{ stats.totalTokens ?? 0 }}</b></span>
        <span class="stat"><i>耗时</i><b class="fm-mono">{{ formatDuration(stats.durationMs) }}</b></span>
        <span class="stat"><i>费用</i><b class="fm-mono">{{ formatCost(stats.totalCost) }}</b></span>
        <span class="stat stat--status" :class="`stat--${stats.status}`">
          <i>状态</i><b>{{ statusText(stats.status) }}</b>
        </span>
      </div>
    </footer>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay, VideoPause, RefreshRight, CircleCloseFilled } from '@element-plus/icons-vue'
import { executeWorkflow, getExecution, getExecutionLogs } from '@/api/execution'
import { openExecutionStream } from '@/utils/sse'
import { formatDuration, formatCost } from '@/utils/format'

const props = defineProps({
  workflowId: { type: [String, Number], required: true },
  nodes: { type: Array, default: () => [] }
})

const starting = ref(false)
const running = ref(false)
const degraded = ref(false)
const errorMsg = ref('')
const stats = ref(null)
const inputValues = reactive({})
const statuses = reactive({})
const blocks = ref([])
const scrollRef = ref(null)
const hasResult = ref(false)

let streamCtl = null
let pollTimer = null
let currentId = null

const nodeList = computed(() => props.nodes.filter((n) => n.nodeKey))

const inputNode = computed(() => props.nodes.find((n) => n.type === 'input') || null)
const inputFields = computed(() => (inputNode.value?.config?.fields || []).filter((f) => f.key))

function hasDefault(f) {
  return f.default !== undefined && f.default !== null && f.default !== ''
}

// 字段变化时补齐默认值（保留用户已填内容）
watch(
  inputFields,
  (fields) => {
    const valid = new Set(fields.map((f) => f.key))
    Object.keys(inputValues).forEach((k) => {
      if (!valid.has(k)) delete inputValues[k]
    })
    fields.forEach((f) => {
      if (inputValues[f.key] === undefined) {
        inputValues[f.key] = f.type === 'number' ? f.default ?? null : f.default ?? ''
      }
    })
  },
  { immediate: true, deep: true }
)

/**
 * 状态变更统一走这里：写本地状态 + 抛给父组件（画布节点状态点依赖这个事件）。
 * SSE 解析逻辑本身不变，这里只是多了一个出口。
 */
const emit = defineEmits(['node-status'])

function setStatus(key, status) {
  statuses[key] = status
  emit('node-status', key, status)
}

function statusOf(key) {
  return statuses[key] || 'pending'
}

function isThinking(node) {
  return (
    node.type === 'llm' &&
    statuses[node.nodeKey] === 'running' &&
    !blocks.value.find((b) => b.nodeKey === node.nodeKey && b.text)
  )
}

function buildInput() {
  const out = {}
  for (const f of inputFields.value) {
    const v = inputValues[f.key]
    if (f.type === 'number') out[f.key] = v === '' || v === null ? undefined : Number(v)
    else out[f.key] = v ?? ''
  }
  return out
}

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function resetRuntime() {
  Object.keys(statuses).forEach((k) => delete statuses[k])
  nodeList.value.forEach((n) => {
    setStatus(n.nodeKey, 'pending')
  })
  blocks.value = []
  errorMsg.value = ''
  stats.value = null
  hasResult.value = false
}

function reset() {
  stop()
  resetRuntime()
}

function stop() {
  running.value = false
  starting.value = false
  if (streamCtl) {
    streamCtl.close()
    streamCtl = null
  }
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function run() {
  if (!inputFields.value.length) {
    ElMessage.warning('请先添加 input 节点并配置入参字段')
    return
  }
  for (const f of inputFields.value) {
    if (!hasDefault(f) && (inputValues[f.key] === '' || inputValues[f.key] === null || inputValues[f.key] === undefined)) {
      ElMessage.warning(`请填写必填字段：${f.label || f.key}`)
      return
    }
  }

  starting.value = true
  resetRuntime()
  try {
    const { executionId } = await executeWorkflow(props.workflowId, buildInput())
    currentId = executionId
    running.value = true
    startStream(executionId)
  } catch (e) {
    running.value = false
  } finally {
    starting.value = false
  }
}

function ensureBlock(nodeKey, name) {
  let b = blocks.value.find((x) => x.nodeKey === nodeKey)
  if (!b) {
    b = { nodeKey, name: name || nodeKey, text: '', done: false, thinking: false }
    blocks.value.push(b)
  }
  return b
}

function handleEvent({ event, data }) {
  switch (event) {
    case 'execution_started':
      break
    case 'node_started':
      setStatus(data.nodeKey, 'running')
      ensureBlock(data.nodeKey, data.name).thinking = true
      break
    case 'token': {
      const b = ensureBlock(data.nodeKey, data.name)
      b.thinking = false
      b.text += data.delta ?? ''
      scrollToBottom()
      break
    }
    case 'node_completed': {
      setStatus(data.nodeKey, 'success')
      const b = ensureBlock(data.nodeKey)
      b.thinking = false
      b.done = true
      break
    }
    case 'node_skipped':
      setStatus(data.nodeKey, 'skipped')
      ensureBlock(data.nodeKey).done = true
      break
    case 'execution_completed':
      finish({ status: 'success', totalTokens: data.totalTokens, durationMs: data.durationMs })
      break
    case 'execution_failed':
      errorMsg.value = data.error || '执行失败'
      finish({ status: 'failed' })
      break
    default:
      break
  }
}

function startStream(executionId) {
  streamCtl = openExecutionStream({
    executionId,
    onOpen: () => {
      degraded.value = false
    },
    onEvent: handleEvent,
    onError: (err) => {
      // 401 登录过期：sse.js 已经清 token + 提示 + 跳登录了，这里只收尾，不再降级轮询
      if (err?.kind === 'unauthorized') {
        running.value = false
        stopTimers()
        return
      }
      // 后端 SSE 不可用时降级：轮询执行详情 + 节点日志，保证调试面板仍然可用
      degraded.value = true
      startPolling(executionId)
    },
    onClose: () => {
      if (running.value) finalizeFromServer(executionId)
    }
  })
}

/** 用服务端数据补齐状态（SSE 结束或失败后调用） */
async function finalizeFromServer(executionId) {
  try {
    const [exec, logs] = await Promise.all([
      getExecution(executionId),
      getExecutionLogs(executionId).catch(() => null)
    ])
    applyServerState(exec, logs)
  } catch (e) {
    /* 错误已由拦截器提示 */
  } finally {
    running.value = false
    hasResult.value = true
    stopTimers()
  }
}

function applyServerState(exec, logs) {
  if (logs?.items?.length) {
    for (const item of logs.items) {
      setStatus(item.nodeKey, item.status)
      const node = nodeList.value.find((n) => n.nodeKey === item.nodeKey)
      if (node && (node.type === 'llm' || node.type === 'http') && item.output) {
        const ok = node.config?.output_key || (node.type === 'llm' ? 'text' : 'data')
        const val = item.output?.[ok]
        if (val != null) {
          let text = typeof val === 'string' ? val : JSON.stringify(val, null, 2)
          // 契约 v1.4：HTTP 工具原始输出可达 10KB+，不整段渲染
          if (node.type === 'http' && text.length > 600) {
            text = `${text.slice(0, 600)}\n…（输出较长已截断，完整内容见执行历史）`
          }
          const b = ensureBlock(item.nodeKey, item.nodeName)
          if (!b.text) {
            b.text = text
            b.thinking = false
            b.done = true
          }
        }
      }
    }
  }
  if (exec) {
    if (exec.status === 'failed') {
      errorMsg.value = exec.errorMessage || '执行失败'
      // 日志里通常能定位到具体失败节点
      const failed = logs?.items?.find((i) => i.status === 'failed')
      if (failed) setStatus(failed.nodeKey, 'failed')
    }
    finish({
      status: exec.status,
      totalTokens: exec.totalTokens,
      totalCost: exec.totalCost,
      durationMs: exec.durationMs
    })
  }
}

function finish(patch) {
  stats.value = { ...(stats.value || {}), ...patch }
  hasResult.value = true
}

function stopTimers() {
  if (streamCtl) {
    streamCtl.close()
    streamCtl = null
  }
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling(executionId) {
  if (pollTimer) clearInterval(pollTimer)
  const tick = async () => {
    try {
      const [exec, logs] = await Promise.all([
        getExecution(executionId),
        getExecutionLogs(executionId).catch(() => null)
      ])
      if (logs?.items?.length) {
        for (const item of logs.items) {
          if (statuses[item.nodeKey] === 'success' || statuses[item.nodeKey] === 'failed') continue
          statuses[item.nodeKey] = item.status
        }
      }
      if (exec && exec.status !== 'running') {
        applyServerState(exec, logs)
        running.value = false
        hasResult.value = true
        stopTimers()
      }
    } catch (e) {
      running.value = false
      stopTimers()
    }
  }
  tick()
  pollTimer = setInterval(tick, 1500)
}

function statusText(status) {
  return { success: '成功', failed: '失败', running: '运行中', pending: '等待中' }[status] || status || '-'
}

onBeforeUnmount(() => {
  stop()
})

defineExpose({ reset })
</script>

<style scoped>
.debug {
  padding: 18px 20px 20px;
  background: var(--surface-glass-strong);
}

.debug__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.debug__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}

.debug__icon {
  color: var(--accent-2);
  font-size: 18px;
}

.debug__degraded {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--warn);
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: 999px;
  padding: 2px 10px;
}

.debug__actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.debug__inputs {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px 16px;
  padding: 14px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-md);
  background: var(--overlay-1);
}

.debug__field {
  display: block;
}

.debug__label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 12.5px;
  color: var(--text-secondary);
}

.debug__key {
  font-size: 11px;
  color: var(--text-faint);
}

.debug__required {
  font-size: 10.5px;
  color: var(--warn);
  border: 1px solid var(--warn-border);
  background: var(--warn-soft);
  border-radius: 999px;
  padding: 0 6px;
}

.debug__empty-input {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
}

.debug__status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.status-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--overlay-3);
  border: 1px solid var(--border-glass);
  font-size: 12px;
  color: var(--text-primary);
}

.status-item__type {
  font-size: 11px;
  color: var(--text-faint);
}

.status-item__thinking {
  font-size: 11px;
  color: var(--status-running);
  animation: pulse 1.2s var(--ease) infinite;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  background: var(--status-pending);
}

.dot--pending {
  background: var(--status-pending);
}
.dot--running {
  background: var(--status-running);
  box-shadow: 0 0 10px var(--status-running);
  animation: pulse 1.2s var(--ease) infinite;
}
.dot--success {
  background: var(--status-success);
  box-shadow: 0 0 10px rgba(52, 211, 153, 0.6);
}
.dot--failed {
  background: var(--status-failed);
  box-shadow: 0 0 10px rgba(248, 113, 113, 0.6);
}
.dot--skipped {
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

.debug__output {
  margin-top: 14px;
  max-height: 320px;
  overflow: auto;
  padding-right: 4px;
}

.block {
  margin-bottom: 12px;
}

.block__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12.5px;
  color: var(--text-primary);
}

.block__name {
  font-weight: 600;
}

.block__thinking {
  font-size: 11px;
  color: var(--status-running);
  animation: pulse 1.2s var(--ease) infinite;
}

.block__text {
  max-height: 260px;
  white-space: pre-wrap;
}

.caret {
  display: inline-block;
  width: 7px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--accent-2);
  animation: caret 1s steps(2, start) infinite;
}

@keyframes caret {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

.debug__placeholder {
  margin: 0;
  padding: 26px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-faint);
}

.debug__foot {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-glass);
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.debug__error {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger-border);
  border-radius: var(--radius-md);
  padding: 6px 12px;
}

.debug__stats {
  margin-left: auto;
  display: flex;
  gap: 18px;
}

.stat {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12.5px;
}

.stat i {
  font-style: normal;
  color: var(--text-secondary);
}

.stat b {
  color: var(--text-primary);
  font-weight: 600;
}

.stat--success b {
  color: var(--status-success);
}
.stat--failed b {
  color: var(--status-failed);
}
.stat--running b {
  color: var(--status-running);
}
</style>
