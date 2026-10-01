<template>
  <el-drawer
    :model-value="visible"
    title="定时计划"
    size="440px"
    :close-on-click-modal="false"
    @update:model-value="(v) => emit('update:visible', v)"
  >
    <div v-loading="loading" class="panel">
      <p class="panel__lead">
        给这个工作流配一个 cron 计划，到点自动按下面的入参执行一次。
      </p>

      <label class="field">
        <span class="field__label">
          cron 表达式
          <span class="field__hint">6 段 cron：秒 分 时 日 月 周</span>
        </span>
        <el-input
          v-model="form.cron"
          class="fm-mono"
          placeholder="0 0 9 * * ?"
          maxlength="64"
          @change="cronError = ''"
        />
        <span v-if="cronError" class="field__error">{{ cronError }}</span>
      </label>

      <label class="field">
        <span class="field__label">常用示例</span>
        <el-select v-model="picked" placeholder="选一个示例自动填充" class="field__control">
          <el-option v-for="p in CRON_PRESETS" :key="p.cron" :label="p.label" :value="p.cron">
            <span>{{ p.label }}</span>
            <span class="opt-cron fm-mono">{{ p.cron }}</span>
          </el-option>
        </el-select>
      </label>

      <label class="field field--inline">
        <span class="field__label">
          启用计划
          <span class="field__hint">关闭后计划保留，但不会触发</span>
        </span>
        <el-switch v-model="form.enabled" class="field__switch" />
      </label>

      <label class="field">
        <span class="field__label">
          执行入参（JSON）
          <span class="field__hint">留空则用 input 节点的字段默认值</span>
        </span>
        <el-input
          v-model="form.inputText"
          type="textarea"
          :rows="4"
          class="fm-mono"
          placeholder='{"city":"南昌"}'
          @change="inputError = ''"
        />
        <span v-if="inputError" class="field__error">{{ inputError }}</span>
      </label>

      <p v-if="current" class="panel__current">
        当前计划：<span class="fm-mono">{{ current.cron }}</span>
        <span :class="current.enabled ? 'on' : 'off'">
          {{ current.enabled ? '已启用' : '已停用' }}
        </span>
      </p>
      <p v-else class="panel__current panel__current--none">当前还没有定时计划</p>
    </div>

    <template #footer>
      <div class="foot">
        <el-button
          v-if="current"
          text
          type="danger"
          :loading="deleting"
          @click="onDelete"
        >
          删除计划
        </el-button>
        <span class="foot__spacer" />
        <el-button @click="emit('update:visible', false)">取 消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保 存</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSchedule, saveSchedule, deleteSchedule, CRON_PRESETS, checkCron } from '@/api/schedule'

const props = defineProps({
  visible: { type: Boolean, default: false },
  workflowId: { type: [String, Number], required: true }
})

const emit = defineEmits(['update:visible', 'saved'])

const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const current = ref(null)
const picked = ref('')
const cronError = ref('')
const inputError = ref('')

const form = reactive({ cron: '', enabled: true, inputText: '' })

watch(
  () => props.visible,
  (v) => {
    if (v) load()
  }
)

async function load() {
  loading.value = true
  cronError.value = ''
  inputError.value = ''
  try {
    const s = await getSchedule(props.workflowId)
    current.value = s || null
    form.cron = s?.cron || ''
    form.enabled = s ? s.enabled !== false : true
    form.inputText = s?.input ? JSON.stringify(s.input, null, 2) : ''
  } catch (e) {
    current.value = null
  } finally {
    loading.value = false
  }
}

// 选示例 → 填进输入框（下拉本身不留值，避免误以为选中即生效）
watch(picked, (val) => {
  if (!val) return
  form.cron = val
  cronError.value = ''
  picked.value = ''
})

function parseInput() {
  const text = String(form.inputText || '').trim()
  if (!text) return { ok: true, value: null }
  try {
    const value = JSON.parse(text)
    if (value === null || Array.isArray(value) || typeof value !== 'object') {
      return { ok: false, error: '入参需要是一个 JSON 对象，如 {"city":"南昌"}' }
    }
    return { ok: true, value }
  } catch (e) {
    return { ok: false, error: `JSON 解析失败：${e.message}` }
  }
}

async function onSave() {
  const cronMsg = checkCron(form.cron)
  if (cronMsg) {
    cronError.value = cronMsg
    return
  }
  const parsed = parseInput()
  if (!parsed.ok) {
    inputError.value = parsed.error
    return
  }

  saving.value = true
  try {
    const saved = await saveSchedule(props.workflowId, {
      cron: String(form.cron).trim(),
      enabled: form.enabled,
      input: parsed.value
    })
    current.value = saved
    ElMessage.success(form.enabled ? '定时计划已保存并启用' : '定时计划已保存（未启用）')
    emit('saved', saved)
  } catch (e) {
    // cron 非法（400 SCHEDULE_INVALID）等后端 message 由拦截器统一弹出
  } finally {
    saving.value = false
  }
}

async function onDelete() {
  try {
    await ElMessageBox.confirm('确定删除这个定时计划吗？删除后不会再自动执行。', '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  deleting.value = true
  try {
    await deleteSchedule(props.workflowId)
    current.value = null
    form.cron = ''
    form.inputText = ''
    ElMessage.success('已删除定时计划')
    emit('saved', null)
  } catch (e) {
    /* 错误提示已在拦截器统一弹出 */
  } finally {
    deleting.value = false
  }
}
</script>

<style scoped>
.panel__lead {
  margin: 0 0 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
}

.field {
  display: block;
  margin-bottom: 16px;
}

.field--inline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.field__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}

.field__hint {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  font-weight: 400;
  color: var(--text-faint);
}

.field__error {
  display: block;
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--danger);
}

.field__control {
  width: 100%;
}

.field__switch {
  flex: none;
}

.opt-cron {
  float: right;
  font-size: 11px;
  color: var(--text-faint);
}

.panel__current {
  margin: 20px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-glass);
  font-size: 12px;
  color: var(--text-secondary);
}

.panel__current--none {
  color: var(--text-faint);
}

.panel__current .on {
  margin-left: 8px;
  color: var(--status-success);
}

.panel__current .off {
  margin-left: 8px;
  color: var(--text-faint);
}

.foot {
  display: flex;
  align-items: center;
  gap: 8px;
}

.foot__spacer {
  margin-left: auto;
}
</style>
