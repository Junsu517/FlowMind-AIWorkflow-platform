<template>
  <el-drawer
    :model-value="visible"
    title="发布为 API"
    size="480px"
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
      </template>

      <!-- ---------- 发布成功：一次性展示密钥 ---------- -->
      <template v-else-if="secret">
        <div class="secret">
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

          <label class="kv">
            <span class="kv__k">调用地址</span>
            <div class="kv__v">
              <code class="fm-code kv__code">{{ secret.runUrl }}</code>
              <el-button text :icon="DocumentCopy" @click="copy('url', secret.runUrl)">
                {{ copied === 'url' ? '已复制' : '复制' }}
              </el-button>
            </div>
          </label>
        </div>

        <CurlSample :run-url="secret.runUrl" />
      </template>

      <!-- ---------- 已发布（再次打开） ---------- -->
      <template v-else>
        <div class="live">
          <div class="live__head">
            <span class="live__dot" />
            <span>已发布</span>
            <span class="live__time fm-mono">{{ formatDateTime(info.createdAt) }}</span>
          </div>

          <label class="kv">
            <span class="kv__k">调用地址</span>
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

        <CurlSample :run-url="info.runUrl" />

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
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Link,
  Key,
  Promotion,
  RefreshRight,
  CircleClose,
  DocumentCopy
} from '@element-plus/icons-vue'
import CurlSample from '@/components/PublishCurlSample.vue'
import { getPublish, publish, unpublish } from '@/api/publish'
import { formatDateTime } from '@/utils/format'

const props = defineProps({
  visible: { type: Boolean, default: false },
  workflowId: { type: [String, Number], required: true }
})

const emit = defineEmits(['update:visible', 'changed'])

const loading = ref(false)
const publishing = ref(false)
const republishing = ref(false)
const unpublishing = ref(false)
const copied = ref('')

/** 已发布信息（GET 结果，不含 key） */
const info = ref(null)
/** 刚刚发布出来的一次性密钥，只活在本次打开期间 */
const secret = ref(null)

watch(
  () => props.visible,
  (v) => {
    if (v) load()
    else secret.value = null // 关掉抽屉就不再展示密钥
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
    emit('changed', false)
    ElMessage.success('已取消发布')
  } catch (e) {
    /* 错误提示已在拦截器统一弹出 */
  } finally {
    unpublishing.value = false
  }
}
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
  margin-bottom: 20px;
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

.danger {
  display: flex;
  gap: 8px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--border-glass);
}
</style>
