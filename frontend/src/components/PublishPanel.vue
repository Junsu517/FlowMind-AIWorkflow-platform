<template>
  <el-drawer
    :model-value="visible"
    title="发布为 API"
    size="520px"
    :close-on-click-modal="false"
    @update:model-value="(v) => emit('update:visible', v)"
  >
    <div v-loading="loading" class="panel">
      <!-- ---------- 未发布 ---------- -->
      <template v-if="!info && !secret">
        <div class="intro">
          <div class="intro__icon">
            <el-icon><Link /></el-icon>
          </div>
          <p class="intro__text">
            发布后生成专属 API Key，外部系统可调用此工作流。
            密钥<b>只展示一次</b>，请立即保存；丢失需要重新发布。
          </p>
        </div>
        <el-button type="primary" :icon="Promotion" :loading="publishing" @click="onPublish">
          发布工作流
        </el-button>
        <p class="hint">发布后可在此<b>在线调试</b>：填输入、点调用、看结果，不用再开终端敲 curl。</p>
      </template>

      <!-- ---------- 已发布（含"刚发布"那一次） ---------- -->
      <template v-else>
        <!-- 一次性展示密钥 -->
        <div v-if="secret" class="secret">
          <div class="secret__head">
            <el-icon><Key /></el-icon>
            <span>发布成功，请立即保存下方密钥</span>
          </div>
          <p class="secret__warn">密钥仅此一次展示，请立即保存；丢失需重新发布。</p>

          <label class="kv">
            <span class="kv__k">API Key</span>
            <div class="kv__v">
              <code class="fm-code kv__code">{{ secret.apiKey }}</code>
              <el-button text :icon="DocumentCopy" @click="copy('key', secret.apiKey)">
                {{ copied === 'key' ? '已复制' : '复制' }}
              </el-button>
            </div>
          </label>

          <div class="notice notice--warn">
            <el-icon class="notice__icon"><WarningFilled /></el-icon>
            <div class="notice__text">
              <b>这不是网页链接</b>，别粘到浏览器地址栏打开。它由程序用 <b>POST</b> 方式调用。
              <span class="notice__sub">
                在浏览器地址栏打开只会看到一段使用说明，不会执行工作流。
              </span>
            </div>
          </div>

          <label class="kv">
            <span class="kv__k">
              调用地址
              <em class="kv__once">仅创建时展示一次</em>
            </span>
            <div class="kv__v">
              <code class="fm-code kv__code">{{ secret.runUrl }}</code>
              <el-button text :icon="DocumentCopy" @click="copy('url', secret.runUrl)">
                {{ copied === 'url' ? '已复制' : '复制' }}
              </el-button>
            </div>
          </label>
        </div>

        <!-- 已发布状态条 -->
        <div v-else class="live">
          <div class="live__head">
            <span class="live__dot" />
            <span>已发布</span>
            <span class="live__time fm-mono">{{ formatDateTime(info.createdAt) }}</span>
          </div>

          <div class="notice notice--warn">
            <el-icon class="notice__icon"><WarningFilled /></el-icon>
            <div class="notice__text">
              <b>这不是网页链接</b>，别粘到浏览器地址栏打开。它由程序用 <b>POST</b> 方式调用。
              <span class="notice__sub">
                在浏览器地址栏打开只会看到一段使用说明，不会执行工作流。
              </span>
            </div>
          </div>

          <label class="kv">
            <span class="kv__k">
              调用地址
              <em class="kv__once">地址里已含你的 Key，不要发给别人</em>
            </span>
            <div class="kv__v">
              <code class="fm-code kv__code">{{ info.runUrl }}</code>
              <el-button text :icon="DocumentCopy" @click="copy('url', info.runUrl)">
                {{ copied === 'url' ? '已复制' : '复制' }}
              </el-button>
            </div>
          </label>
          <p class="live__note">
            API Key 不再展示（只在发布那一次出现）；忘记请重新发布，旧 Key 会立即失效。
          </p>
        </div>

        <!-- ---------- 在线调试（阶梯 8.5） ---------- -->
        <div class="debug">
          <div class="debug__head">
            <span class="debug__title">在线调试</span>
            <el-button
              type="primary"
              :icon="VideoPlay"
              :loading="debugRunning"
              @click="onDebugRun"
            >
              {{ debugRunning ? '调用中…' : '调用' }}
            </el-button>
          </div>

          <p v-if="!fields.length" class="debug__empty">
            这个工作流没有 input 节点，将用空入参调用。
          </p>
          <div v-else class="debug__fields">
            <label v-for="f in fields" :key="f.key" class="debug__field">
              <span class="debug__label">
                {{ f.label || f.key }}
                <span class="debug__key fm-mono">{{ f.key }}</span>
              </span>
              <el-input
                v-if="f.type === 'number'"
                v-model.number="form[f.key]"
                type="number"
                :placeholder="String(f.default ?? '')"
              />
              <el-input
                v-else
                v-model="form[f.key]"
                :placeholder="String(f.default ?? '')"
                clearable
              />
            </label>
          </div>

          <p v-if="debugHint" class="debug__hint">{{ debugHint }}</p>

          <div v-if="debugRunning || result" class="debug__result">
            <div class="debug__status">
              <span class="fm-dot" :class="`fm-dot--${resultStatus}`" />
              <span>{{ statusText(resultStatus) }}</span>
              <span v-if="debugRunning" class="debug__polling">每 2 秒自动查询结果…</span>
            </div>

            <pre v-if="outputText" class="debug__text">{{ outputText }}</pre>

            <ReferenceList v-if="references.length" :items="references" />

            <div v-if="result && result.status === 'success'" class="debug__stats">
              <span>tokens <b class="fm-mono">{{ result.totalTokens ?? 0 }}</b></span>
              <span v-if="result.totalCost != null">
                费用 <b class="fm-mono">{{ formatCost(result.totalCost) }}</b>
              </span>
            </div>

            <div v-if="result?.errorMessage" class="debug__error">{{ result.errorMessage }}</div>
          </div>
        </div>

        <!-- 调用示例：curl / Python / JavaScript 一键复制，示例里是真实 Key -->
        <div class="samples">
          <div class="samples__title">
            <span>三种语言的调用示例（已填好你的 Key，可直接复制运行）</span>
          </div>
          <ApiSample :run-url="(secret || info)?.runUrl || ''" :fields="fields" />
        </div>

        <div class="danger">
          <el-button :icon="RefreshRight" :loading="republishing" @click="onRepublish">
            重新发布
          </el-button>
          <el-button :icon="CircleClose" :loading="unpublishing" @click="onUnpublish">
            取消发布
          </el-button>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Link,
  Key,
  Promotion,
  RefreshRight,
  CircleClose,
  DocumentCopy,
  VideoPlay,
  WarningFilled
} from '@element-plus/icons-vue'
import ApiSample from '@/components/PublishApiSample.vue'
import ReferenceList from '@/components/editor/ReferenceList.vue'
import {
  getPublish,
  publish,
  unpublish,
  publicRun,
  publicGetResult,
  apiKeyFromRunUrl
} from '@/api/publish'
import { formatDateTime, formatCost } from '@/utils/format'

const props = defineProps({
  visible: { type: Boolean, default: false },
  workflowId: { type: [String, Number], required: true },
  /** 编辑器里的节点（已归一化成 { nodeKey, type, name, config }），用来渲染调试入参 */
  nodes: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:visible', 'changed'])

const loading = ref(false)
const publishing = ref(false)
const republishing = ref(false)
const unpublishing = ref(false)
const copied = ref('')

const info = ref(null)
const secret = ref(null)

// ---------- 在线调试 ----------
const form = ref({})
const result = ref(null)
const debugRunning = ref(false)
const debugHint = ref('')
let pollTimer = null

const fields = computed(() => {
  const inputNode = (props.nodes || []).find((n) => n.type === 'input')
  return inputNode?.config?.fields || []
})

const llmNode = computed(() => (props.nodes || []).find((n) => n.type === 'llm') || null)

/** 已发布后 GET 不返回 key，只能从 runUrl 里抠出来 */
const apiKey = computed(
  () => secret.value?.apiKey || apiKeyFromRunUrl(info.value?.runUrl) || ''
)

const resultStatus = computed(() => result.value?.status || 'queued')

const outputText = computed(() => {
  const out = result.value?.output
  if (!out) return ''
  const node = llmNode.value
  if (node) {
    const v = out[node.nodeKey]
    if (v != null) {
      const key = node.config?.output_key || 'text'
      return typeof v === 'string' ? v : String(v[key] ?? '')
    }
  }
  // 兜底：取最后一个节点的字符串值
  const last = Object.values(out).pop()
  return typeof last === 'string' ? last : ''
})

const references = computed(() => {
  const node = llmNode.value
  if (!node) return []
  return result.value?.output?.[node.nodeKey]?.references || []
})

function statusText(status) {
  const map = { queued: '排队中', running: '运行中', success: '成功', failed: '失败' }
  return map[status] || status || '-'
}

watch(
  fields,
  (fs) => {
    const next = {}
    for (const f of fs) next[f.key] = f.default ?? ''
    form.value = next
  },
  { immediate: true }
)

watch(
  () => props.visible,
  (v) => {
    if (v) {
      load()
    } else {
      secret.value = null // 关掉抽屉就不再展示密钥
      stopPoll()
    }
  }
)

async function load() {
  loading.value = true
  copied.value = ''
  try {
    info.value = await getPublish(props.workflowId)
  } catch (e) {
    info.value = null
  } finally {
    loading.value = false
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function copy(which, text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      // http 环境下 clipboard 不可用，退回 execCommand
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    copied.value = which
    ElMessage.success('已复制')
    setTimeout(() => {
      if (copied.value === which) copied.value = ''
    }, 2000)
  } catch (e) {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

async function onDebugRun() {
  const key = apiKey.value
  if (!key) {
    debugHint.value = '没有可用的 API Key，请重新发布后再试'
    return
  }
  stopPoll()
  result.value = null
  debugHint.value = ''
  debugRunning.value = true

  const input = {}
  for (const f of fields.value) {
    const v = form.value[f.key]
    if (v !== '' && v !== undefined && v !== null) input[f.key] = v
  }

  try {
    const data = await publicRun(key, input)
    const executionId = data?.executionId
    if (!executionId) {
      debugHint.value = '调用已受理但没拿到 executionId，请稍后重试'
      debugRunning.value = false
      return
    }
    await startPolling(key, executionId)
  } catch (e) {
    const status = e?.response?.status
    const code = e?.response?.data?.code
    if (status === 401 || code === 'INVALID_API_KEY') {
      debugHint.value = 'API Key 已失效，请重新发布后再试'
    } else if (status === 429 || code === 'API_RATE_LIMITED') {
      debugHint.value = '调用太频繁被限流，请稍后再试'
    } else if (status === 409) {
      debugHint.value = '该工作流正在运行中，等这次跑完再试'
    } else {
      // 400 等具体原因（含字段校验）拦截器已弹出后端 message
      debugHint.value = '调用失败，确认工作流已保存后再试'
    }
    debugRunning.value = false
  }
}

/** 202 之后轮询结果直到终态（契约 v1.8 的 public run 没有 SSE） */
function startPolling(key, executionId) {
  return new Promise((resolve) => {
    const tick = async () => {
      try {
        const r = await publicGetResult(key, executionId)
        result.value = r
        if (r?.status === 'success' || r?.status === 'failed') {
          stopPoll()
          debugRunning.value = false
          resolve()
        }
      } catch (e) {
        stopPoll()
        debugRunning.value = false
        debugHint.value = '查询结果失败，请稍后重试'
        resolve()
      }
    }
    tick()
    pollTimer = setInterval(tick, 2000)
  })
}

async function onPublish() {
  publishing.value = true
  try {
    const data = await publish(props.workflowId)
    if (data?.apiKey) {
      secret.value = { apiKey: data.apiKey, runUrl: data.runUrl }
      info.value = null // 让"一次性密钥"这一屏独占
    } else {
      // 服务端说已经发布过（200 只回 runUrl）
      info.value = { published: true, runUrl: data?.runUrl, createdAt: data?.createdAt ?? null }
    }
    emit('changed', true)
    ElMessage.success('发布成功')
  } catch (e) {
    /* 错误提示已在拦截器统一弹出 */
  } finally {
    publishing.value = false
  }
}

async function onRepublish() {
  try {
    await ElMessageBox.confirm(
      '重新发布会吊销当前 API Key 并生成新的 Key，正在使用旧 Key 的调用方会立即失效。确定继续吗？',
      '重新发布确认',
      {
        type: 'warning',
        confirmButtonText: '重新发布',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  republishing.value = true
  try {
    await unpublish(props.workflowId) // 吊销（契约：幂等 204）
    const data = await publish(props.workflowId) // 再发布拿新 key
    secret.value = data?.apiKey ? { apiKey: data.apiKey, runUrl: data.runUrl } : null
    info.value = data?.apiKey ? null : { published: true, runUrl: data?.runUrl, createdAt: null }
    emit('changed', true)
    ElMessage.success('已重新发布')
  } catch (e) {
    /* 错误提示已在拦截器统一弹出 */
  } finally {
    republishing.value = false
  }
}

async function onUnpublish() {
  try {
    await ElMessageBox.confirm(
      '取消发布后该 API 立即失效，外部系统将无法调用。确定取消吗？',
      '取消发布确认',
      {
        type: 'warning',
        confirmButtonText: '确定取消',
        cancelButtonText: '保留',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  unpublishing.value = true
  try {
    await unpublish(props.workflowId)
    info.value = null
    secret.value = null
    stopPoll()
    result.value = null
    emit('changed', false)
    ElMessage.success('已取消发布')
  } catch (e) {
    /* 错误提示已在拦截器统一弹出 */
  } finally {
    unpublishing.value = false
  }
}

onBeforeUnmount(stopPoll)
</script>

<style scoped>
.panel {
  padding-bottom: 8px;
}

/* ---------- 未发布 ---------- */
.intro {
  display: flex;
  gap: 14px;
  padding: 18px;
  margin-bottom: 18px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-glass);
  background: var(--bg-glass);
}

.intro__icon {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: 18px;
  color: var(--on-brand);
  background: var(--gradient-brand);
  box-shadow: var(--shadow-glow);
}

.intro__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.intro__text b {
  color: var(--text-primary);
}

.hint {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-faint);
}

.hint b {
  color: var(--text-secondary);
}

/* ---------- 一次性密钥 ---------- */
.secret {
  padding: 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--status-success);
  background: var(--accent-glow-soft);
}

.secret__head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--status-success);
}

.secret__warn {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--status-failed);
}

/* ---------- 已发布 ---------- */
.live {
  padding: 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--accent-border);
  background: var(--accent-glow-soft);
}

.live__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--accent-chip-text);
}

.live__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--status-success);
  box-shadow: 0 0 8px var(--status-success);
}

.live__time {
  margin-left: auto;
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-faint);
}

.live__note {
  margin: 10px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-faint);
}

/* ---------- 在线调试 ---------- */
.debug {
  margin-top: 20px;
  padding: 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-glass);
  background: var(--bg-glass);
}

.debug__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.debug__title {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--text-primary);
}

.debug__fields {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
}

.debug__field {
  display: block;
}

.debug__label {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 5px;
  font-size: 12px;
  color: var(--text-primary);
}

.debug__key {
  font-size: 10.5px;
  color: var(--text-faint);
}

.debug__empty,
.debug__hint {
  margin: 12px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-faint);
}

.debug__hint {
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  color: var(--status-failed);
  background: rgba(248, 113, 113, 0.1);
}

.debug__result {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border-glass);
}

.debug__status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-primary);
}

.debug__polling {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-faint);
}

.debug__text {
  margin: 10px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--overlay-3);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.debug__stats {
  display: flex;
  gap: 16px;
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--text-secondary);
}

.debug__stats b {
  color: var(--text-primary);
}

.debug__error {
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--status-failed);
}

/* ---------- 醒目提示：这是给小白看的，不能是灰色小字 ---------- */
.notice {
  display: flex;
  gap: 10px;
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--warn-border);
  background: var(--warn-soft);
}

.notice__icon {
  flex: none;
  margin-top: 1px;
  font-size: 16px;
  color: var(--warn);
}

.notice__text {
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--text-primary);
}

.notice__text b {
  color: var(--warn);
}

.notice__sub {
  display: block;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--text-secondary);
}

/* ---------- 通用 ---------- */
.kv {
  display: block;
  margin-top: 14px;
}

.kv__k {
  display: block;
  margin-bottom: 6px;
  font-size: 11.5px;
  color: var(--text-secondary);
}

.kv__once {
  margin-left: 6px;
  font-style: normal;
  font-size: 10.5px;
  color: var(--warn);
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: 999px;
  padding: 1px 8px;
}

.kv__v {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kv__code {
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  white-space: nowrap;
}

.samples {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--border-glass);
}

.samples__title {
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
}

.danger {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border-glass);
}
</style>
