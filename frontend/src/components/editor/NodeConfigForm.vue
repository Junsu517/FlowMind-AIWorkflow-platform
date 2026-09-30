<template>
  <div class="cfg">
    <!-- ---------- input：动态字段列表 ---------- -->
    <template v-if="node.type === 'input'">
      <div class="block-title">
        <span>入参字段</span>
        <span class="block-title__hint">未填默认值的字段在运行时为必填</span>
      </div>
      <div v-for="(f, i) in node.config.fields" :key="`field-${i}`" class="kv-row">
        <el-input v-model="f.key" placeholder="参数名称（如 city）" class="kv-row__k" />
        <el-input v-model="f.label" placeholder="显示名（如 城市）" class="kv-row__k" />
        <el-select v-model="f.type" class="kv-row__t">
          <el-option label="文本" value="text" />
          <el-option label="数字" value="number" />
        </el-select>
        <el-input v-model="f.default" placeholder="默认值（可空）" class="kv-row__v" />
        <el-button
          text
          :icon="Remove"
          :disabled="node.config.fields.length <= 1"
          @click="node.config.fields.splice(i, 1)"
        />
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
    </template>

    <!-- ---------- llm ---------- -->
    <template v-else-if="node.type === 'llm'">
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
          温度 temperature
          <span class="fm-mono temp-value">{{ Number(node.config.temperature).toFixed(2) }}</span>
          <el-tooltip placement="top" content="控制输出随机性（0~1）：越高越有创意、越不稳定；越低越保守、越稳定。默认 0.7">
            <el-icon class="field__help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </span>
        <el-slider v-model="node.config.temperature" :min="0" :max="1" :step="0.1" show-stops />
      </label>

      <!-- 契约 v1.4：Function Calling（阶梯 4） -->
      <div class="grid-2">
        <label class="field">
          <span class="field__label">
            工具 tools
            <span class="field__hint">启用后模型可自主调用这些工具</span>
          </span>
          <el-select
            v-model="node.config.tools"
            class="field__control"
            multiple
            clearable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="2"
            placeholder="不启用工具"
            :loading="toolsLoading"
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
          <span class="field__label">
            最大工具轮数 max_tool_rounds
            <el-tooltip
              placement="top"
              content="模型最多调用几轮工具，达到后强制结束（防死循环）。默认 3，仅在使用工具时生效"
            >
              <el-icon class="field__help"><QuestionFilled /></el-icon>
            </el-tooltip>
            <span class="field__hint">默认 3，留空则用后端默认；未勾选工具时不生效</span>
          </span>
          <el-input-number
            v-model="node.config.max_tool_rounds"
            class="field__control"
            :min="1"
            :max="10"
            :step="1"
            :disabled="!hasTools"
            controls-position="right"
            placeholder="3"
          />
        </label>
      </div>

      <!-- 契约 v1.5：引用知识库（RAG） -->
      <label class="field">
        <span class="field__label">
          引用知识库
          <el-tooltip
            placement="top"
            content="让模型基于知识库文档作答，输出会附带引用来源；不引用时与普通 LLM 节点行为完全一致"
          >
            <el-icon class="field__help"><QuestionFilled /></el-icon>
          </el-tooltip>
          <span class="field__hint">默认不引用；清空即不引用</span>
        </span>
        <el-select
          v-model="node.config.knowledge_base_id"
          class="field__control"
          clearable
          placeholder="不引用知识库"
          :loading="knowledgeLoading"
        >
          <!-- 显式的「不引用」选项，比只能点角落的 × 更直观 -->
          <el-option label="不引用知识库" :value="null" />
          <el-option
            v-for="kb in knowledgeBases"
            :key="kb.id"
            :label="kb.documentCount != null ? `${kb.name}（${kb.documentCount} 篇）` : kb.name"
            :value="kb.id"
          />
        </el-select>
      </label>
    </template>

    <!-- ---------- condition：规则 + 默认分支 ---------- -->
    <template v-else-if="node.type === 'condition'">
      <div class="block-title">
        <span>分支规则</span>
        <span class="block-title__hint">按顺序匹配，命中则走对应分支（handle）</span>
      </div>
      <div v-for="(r, i) in node.config.rules" :key="`rule-${i}`" class="rule">
        <div class="rule__head">
          <span class="rule__index">规则 {{ i + 1 }}</span>
          <el-button
            text
            :icon="Remove"
            :disabled="node.config.rules.length <= 1"
            @click="node.config.rules.splice(i, 1)"
          />
        </div>
        <div class="rule__row">
          <el-input v-model="r.left" placeholder="左值，如 {{input_1.city}}" class="rule__left" />
          <el-select v-model="r.operator" class="rule__op">
            <el-option v-for="o in CONDITION_OPERATORS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </div>
        <div class="rule__row">
          <el-input
            v-model="r.right"
            :disabled="r.operator === 'not_empty'"
            :placeholder="r.operator === 'not_empty' ? '非空判断无需右值' : '右值'"
            class="rule__grow"
          />
          <el-input v-model="r.handle" placeholder="分支标识（如 beijing）" class="rule__grow" />
        </div>
      </div>
      <el-button
        size="small"
        text
        :icon="Plus"
        class="add-btn"
        @click="
          node.config.rules.push({
            left: '',
            operator: 'equals',
            right: '',
            handle: `branch_${node.config.rules.length + 1}`
          })
        "
      >
        添加规则
      </el-button>

      <label class="field field--inline">
        <span class="field__label">
          默认分支 default_handle
          <span class="field__hint">所有规则都没命中时走这个出口</span>
        </span>
        <el-input
          v-model="node.config.default_handle"
          class="field__control field__control--sm"
          placeholder="如 other"
        />
      </label>
    </template>

    <!-- ---------- http：工具 + 参数 ---------- -->
    <template v-else-if="node.type === 'http'">
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
      <el-button
        size="small"
        text
        :icon="Plus"
        class="add-btn"
        @click="node.paramsRows.push({ key: '', value: '' })"
      >
        添加参数
      </el-button>

      <p v-if="selectedTool?.description" class="tool-desc">{{ selectedTool.description }}</p>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Plus, Remove, Aim, QuestionFilled } from '@element-plus/icons-vue'
import { LLM_MODELS, CONDITION_OPERATORS } from '@/utils/graphModel'

/**
 * 节点配置表单：画布右侧面板与旧表单卡片共用同一份实现。
 * 直接就地修改传入的 node（node.config / node.paramsRows），不做副本，避免双向同步问题。
 */
const props = defineProps({
  node: { type: Object, required: true },
  /** 可引用的上游字段：[{ label, ref }] */
  refOptions: { type: Array, default: () => [] },
  tools: { type: Array, default: () => [] },
  toolsLoading: { type: Boolean, default: false },
  /** 知识库列表（GET /api/knowledge-bases），llm 节点引用用 */
  knowledgeBases: { type: Array, default: () => [] },
  knowledgeLoading: { type: Boolean, default: false }
})

const promptRef = ref(null)

const selectedTool = computed(() => props.tools.find((t) => t.id === props.node.config.tool_id) || null)

/** 没勾工具时 max_tool_rounds 不生效（灰置）；清空工具时把轮数一起清掉，避免存了无效配置 */
const hasTools = computed(() => (props.node.config.tools || []).length > 0)

watch(hasTools, (has) => {
  if (!has && props.node.type === 'llm') props.node.config.max_tool_rounds = null
})

/** 选中工具后，按 paramsSchema 预填参数名 */
function onToolChange(toolId) {
  const tool = props.tools.find((t) => t.id === toolId)
  if (!tool) return
  const hasValue = (props.node.paramsRows || []).some((p) => String(p.key || '').trim())
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
.cfg {
  display: block;
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

.kv-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.kv-row__k,
.kv-row__v {
  flex: 1 1 140px;
  min-width: 0;
}

.kv-row__t {
  flex: 0 0 92px;
}

/*
 * 条件规则：一行塞 4 个输入框在 340px 面板里必然溢出，
 * 中间的输入框会被压到 0 宽，导致相邻两个组件的圆角边框叠在一起
 * （用户看到的"重复括号"）。改成每条规则一张两行小卡片。
 */
.rule {
  padding: 10px 12px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-md);
  background: var(--bg-glass);
}

.rule__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.rule__index {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-faint);
}

.rule__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rule__row + .rule__row {
  margin-top: 8px;
}

.rule__left {
  flex: 1 1 auto;
  min-width: 0;
}

.rule__op {
  flex: 0 0 108px;
}

.rule__grow {
  flex: 1 1 120px;
  min-width: 0;
}

.add-btn {
  padding-left: 0;
  color: var(--accent-chip-text);
}

.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 14px;
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

.field__hint {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-faint);
}

.field__help {
  margin-left: 4px;
  font-size: 12px;
  color: var(--text-faint);
  cursor: help;
  vertical-align: -1px;
  transition: color 0.2s var(--ease);
}

.field__help:hover {
  color: var(--accent-2);
}

.field__control {
  width: 100%;
}

.field__control--sm {
  width: 200px;
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
</style>
