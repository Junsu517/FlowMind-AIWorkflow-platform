<template>
  <div class="node-card fm-glass">
    <!-- 卡片头：类型徽章 + 名称 + nodeKey + 操作 -->
    <div class="node-card__head">
      <span class="type-badge" :class="`type-badge--${node.type}`">
        {{ meta.short }}
      </span>
      <el-input v-model="node.name" class="node-card__name" placeholder="节点名称" />
      <span class="node-card__key fm-mono">{{ node.nodeKey }}</span>
      <div class="node-card__ops">
        <el-tooltip content="上移" placement="top">
          <el-button text :icon="ArrowUp" :disabled="index === 0" @click="emit('move-up')" />
        </el-tooltip>
        <el-tooltip content="下移" placement="top">
          <el-button text :icon="ArrowDown" :disabled="index === total - 1" @click="emit('move-down')" />
        </el-tooltip>
        <el-tooltip content="删除节点" placement="top">
          <el-button text :icon="Delete" class="danger-btn" @click="emit('remove')" />
        </el-tooltip>
      </div>
    </div>

    <!-- 上游来源（input 节点没有上游） -->
    <div v-if="node.type !== 'input'" class="node-card__block">
      <div class="block-title">
        <span>上游来源</span>
        <span class="block-title__hint">决定本节点的数据来源，保存时转换为 edges</span>
      </div>

      <div v-for="(s, i) in node.sources" :key="`src-${i}`" class="source-row">
        <el-select v-model="s.from" placeholder="选择上游节点" class="source-row__from" @change="onSourceChange(s)">
          <el-option
            v-for="n in upstreamCandidates"
            :key="n.nodeKey"
            :label="`${n.name} · ${n.nodeKey}`"
            :value="n.nodeKey"
          />
        </el-select>

        <el-select
          v-if="handlesOf(s.from).length"
          v-model="s.handle"
          placeholder="分支 handle"
          class="source-row__handle"
        >
          <el-option v-for="h in handlesOf(s.from)" :key="h" :label="h" :value="h" />
        </el-select>

        <el-button text :icon="Remove" :disabled="node.sources.length <= 1" @click="removeSource(i)" />
      </div>

      <el-button size="small" text :icon="Plus" class="add-btn" @click="addSource">添加上游</el-button>
    </div>

    <!-- ---------- input：动态字段列表 ---------- -->
    <div v-if="node.type === 'input'" class="node-card__block">
      <div class="block-title">
        <span>入参字段</span>
        <span class="block-title__hint">未填默认值的字段在运行时为必填</span>
      </div>
      <div v-for="(f, i) in node.config.fields" :key="`field-${i}`" class="kv-row">
        <el-input v-model="f.key" placeholder="key（如 city）" class="kv-row__k" />
        <el-input v-model="f.label" placeholder="显示名" class="kv-row__k" />
        <el-select v-model="f.type" class="kv-row__t">
          <el-option label="文本" value="text" />
          <el-option label="数字" value="number" />
        </el-select>
        <el-input v-model="f.default" placeholder="默认值（可空）" class="kv-row__v" />
        <el-button text :icon="Remove" :disabled="node.config.fields.length <= 1" @click="node.config.fields.splice(i, 1)" />
      </div>
      <el-button
        size="small"
        text
        :icon="Plus"
        class="add-btn"
        @click="node.config.fields.push({ key: '', label: '', type: 'text', default: '' })"
      >
        添加字段
      </el-button>
    </div>

    <!-- ---------- llm ---------- -->
    <div v-else-if="node.type === 'llm'" class="node-card__block">
      <div class="grid-2">
        <label class="field">
          <span class="field__label">模型</span>
          <el-select v-model="node.config.model" class="field__control">
            <el-option v-for="m in LLM_MODELS" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </label>
        <label class="field">
          <span class="field__label">输出键 output_key</span>
          <el-input v-model="node.config.output_key" class="field__control" placeholder="text" />
        </label>
      </div>

      <div class="field">
        <div class="field__label field__label--row">
          <span>Prompt（支持 <code v-pre>{{ }}</code> 引用上游字段）</span>
          <el-dropdown trigger="click" @command="insertRef">
            <el-button size="small" text :icon="Aim">插入引用</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="!refOptions.length" disabled>暂无上游字段</el-dropdown-item>
                <el-dropdown-item v-for="o in refOptions" :key="o.ref" :command="o.ref">
                  {{ o.label }} <span class="opt-ref fm-mono">{{ o.ref }}</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <el-input
          ref="promptRef"
          v-model="node.config.prompt"
          type="textarea"
          :rows="4"
          resize="vertical"
          placeholder="例如：请给 {{input_1.city}} 的天气穿衣建议"
        />
      </div>

      <label class="field">
        <span class="field__label">
          温度 temperature <span class="fm-mono temp-value">{{ Number(node.config.temperature).toFixed(2) }}</span>
        </span>
        <el-slider v-model="node.config.temperature" :min="0" :max="1" :step="0.1" show-stops />
      </label>
    </div>

    <!-- ---------- condition：规则 + 默认分支 ---------- -->
    <div v-else-if="node.type === 'condition'" class="node-card__block">
      <div class="block-title">
        <span>分支规则</span>
        <span class="block-title__hint">按顺序匹配，命中则走对应 handle</span>
      </div>
      <div v-for="(r, i) in node.config.rules" :key="`rule-${i}`" class="rule-row">
        <el-input v-model="r.left" placeholder="左值，如 {{input_1.city}}" class="rule-row__left" />
        <el-select v-model="r.operator" class="rule-row__op">
          <el-option v-for="o in CONDITION_OPERATORS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input
          v-model="r.right"
          :disabled="r.operator === 'not_empty'"
          :placeholder="r.operator === 'not_empty' ? '（非空判断无需右值）' : '右值'"
          class="rule-row__right"
        />
        <el-input v-model="r.handle" placeholder="handle" class="rule-row__handle" />
        <el-button text :icon="Remove" :disabled="node.config.rules.length <= 1" @click="node.config.rules.splice(i, 1)" />
      </div>
      <el-button
        size="small"
        text
        :icon="Plus"
        class="add-btn"
        @click="node.config.rules.push({ left: '', operator: 'equals', right: '', handle: `branch_${node.config.rules.length + 1}` })"
      >
        添加规则
      </el-button>

      <label class="field field--inline">
        <span class="field__label">默认分支 default_handle</span>
        <el-input v-model="node.config.default_handle" class="field__control field__control--sm" />
      </label>
    </div>

    <!-- ---------- http：工具 + 参数 ---------- -->
    <div v-else-if="node.type === 'http'" class="node-card__block">
      <div class="grid-2">
        <label class="field">
          <span class="field__label">工具 tool_id</span>
          <el-select
            v-model="node.config.tool_id"
            class="field__control"
            placeholder="选择工具"
            :loading="toolsLoading"
            @change="onToolChange"
          >
            <el-option
              v-for="t in tools"
              :key="t.id"
              :label="`${t.displayName}（${t.name}）`"
              :value="t.id"
            />
          </el-select>
        </label>
        <label class="field">
          <span class="field__label">输出键 output_key</span>
          <el-input v-model="node.config.output_key" class="field__control" placeholder="data" />
        </label>
      </div>

      <div class="block-title block-title--sub">
        <span>请求参数</span>
        <span class="block-title__hint">value 支持 <code v-pre>{{ }}</code> 引用</span>
      </div>
      <div v-for="(p, i) in node.paramsRows" :key="`param-${i}`" class="kv-row">
        <el-input v-model="p.key" placeholder="参数名" class="kv-row__k" />
        <el-input v-model="p.value" placeholder="参数值" class="kv-row__v" />
        <el-dropdown trigger="click" @command="(cmd) => (p.value += cmd)">
          <el-button text :icon="Aim" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="!refOptions.length" disabled>暂无上游字段</el-dropdown-item>
              <el-dropdown-item v-for="o in refOptions" :key="o.ref" :command="o.ref">
                {{ o.label }} <span class="opt-ref fm-mono">{{ o.ref }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button text :icon="Remove" @click="node.paramsRows.splice(i, 1)" />
      </div>
      <el-button size="small" text :icon="Plus" class="add-btn" @click="node.paramsRows.push({ key: '', value: '' })">
        添加参数
      </el-button>

      <p v-if="selectedTool?.description" class="tool-desc">{{ selectedTool.description }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import { ArrowUp, ArrowDown, Delete, Plus, Remove, Aim } from '@element-plus/icons-vue'
import {
  NODE_TYPE_META,
  LLM_MODELS,
  CONDITION_OPERATORS,
  branchHandles,
  upstreamFields
} from '@/utils/graphModel'

const props = defineProps({
  node: { type: Object, required: true },
  index: { type: Number, required: true },
  total: { type: Number, required: true },
  nodes: { type: Array, required: true },
  tools: { type: Array, default: () => [] },
  toolsLoading: { type: Boolean, default: false }
})

const emit = defineEmits(['remove', 'move-up', 'move-down'])

const promptRef = ref(null)

const meta = computed(() => NODE_TYPE_META[props.node.type] || { label: props.node.type, short: '?' })

/** 上游候选：排在前面的节点（表单式按执行顺序，连线只能来自更靠前的节点） */
const upstreamCandidates = computed(() => props.nodes.slice(0, props.index))

const refOptions = computed(() => upstreamFields(props.nodes, props.index))

const selectedTool = computed(() => props.tools.find((t) => t.id === props.node.config.tool_id) || null)

function byKey(key) {
  return props.nodes.find((n) => n.nodeKey === key) || null
}

function handlesOf(key) {
  return branchHandles(byKey(key))
}

function onSourceChange(s) {
  // 上游换了，分支 handle 通常也不再适用
  s.handle = ''
}

function addSource() {
  props.node.sources.push({ from: '', handle: '' })
}

function removeSource(i) {
  props.node.sources.splice(i, 1)
}

/** 选中工具后，按 paramsSchema 预填参数名 */
function onToolChange(toolId) {
  const tool = props.tools.find((t) => t.id === toolId)
  if (!tool) return
  const hasValue = props.node.paramsRows.some((p) => String(p.key || '').trim())
  if (hasValue) return
  props.node.paramsRows = (tool.paramsSchema || []).map((s) => ({ key: s.key, value: '' }))
}

/** 在光标处插入 {{xxx}} 引用 */
function insertRef(text) {
  const value = props.node.config.prompt || ''
  const cmp = promptRef.value
  const el = cmp?.textarea || cmp?.$el?.querySelector?.('textarea') || null
  if (!el) {
    props.node.config.prompt = value + text
    return
  }
  const start = Number.isFinite(el.selectionStart) ? el.selectionStart : value.length
  const end = Number.isFinite(el.selectionEnd) ? el.selectionEnd : value.length
  props.node.config.prompt = value.slice(0, start) + text + value.slice(end)
  nextTick(() => {
    el.focus()
    const pos = start + text.length
    el.setSelectionRange?.(pos, pos)
  })
}
</script>

<style scoped>
.node-card {
  padding: 18px 20px 20px;
  background: var(--surface-glass-strong);
  transition: border-color 0.2s var(--ease), box-shadow 0.2s var(--ease);
}

.node-card:hover {
  border-color: var(--border-glass-strong);
}

.node-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.type-badge {
  flex: none;
  min-width: 40px;
  padding: 4px 8px;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-align: center;
  color: var(--bg-base);
}

.type-badge--input {
  background: var(--node-input);
}
.type-badge--llm {
  background: var(--node-llm);
}
.type-badge--condition {
  background: var(--node-condition);
}
.type-badge--http {
  background: var(--node-http);
}

.node-card__name {
  width: 220px;
  flex: none;
}

.node-card__key {
  font-size: 12px;
  color: var(--text-faint);
}

.node-card__ops {
  margin-left: auto;
  display: flex;
  gap: 2px;
}

.danger-btn:hover {
  color: var(--danger);
}

.node-card__block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--border-glass);
}

.block-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.block-title--sub {
  margin-top: 14px;
}

.block-title__hint {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-faint);
}

.block-title code,
.block-title__hint code {
  font-family: var(--font-mono);
  color: var(--accent-chip-text);
}

.source-row,
.kv-row,
.rule-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.source-row__from {
  flex: 1 1 260px;
  max-width: 340px;
}

.source-row__handle {
  flex: 0 0 160px;
}

.kv-row__k,
.kv-row__v {
  flex: 1 1 150px;
}

.kv-row__t {
  flex: 0 0 100px;
}

.rule-row__left,
.rule-row__right {
  flex: 1 1 160px;
}

.rule-row__op {
  flex: 0 0 110px;
}

.rule-row__handle {
  flex: 0 0 140px;
}

.add-btn {
  padding-left: 0;
  color: var(--accent-chip-text);
}

.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}

.field {
  display: block;
  margin-bottom: 12px;
}

.field--inline {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}

.field__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12.5px;
  color: var(--text-secondary);
}

.field__label--row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.field__control {
  width: 100%;
}

.field__control--sm {
  width: 220px;
}

.temp-value {
  color: var(--accent-chip-text);
}

.tool-desc {
  margin: 10px 0 0;
  font-size: 11.5px;
  color: var(--text-faint);
}

.opt-ref {
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-faint);
}

@media (max-width: 900px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
  .source-row,
  .kv-row,
  .rule-row {
    flex-wrap: wrap;
  }
}
</style>
