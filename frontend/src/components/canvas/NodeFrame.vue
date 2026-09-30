<template>
  <div class="fm-node" :class="[`fm-node--${nodeType}`, { 'is-selected': selected }]">
    <Handle v-if="nodeType !== 'input'" type="target" :position="Position.Left" />
    <Handle v-if="nodeType !== 'condition'" type="source" :position="Position.Right" />

    <div class="fm-node__head">
      <span class="fm-node__badge">{{ meta.short }}</span>
      <div class="fm-node__titles">
        <div class="fm-node__name">{{ data.name }}</div>
        <div class="fm-node__key fm-mono">{{ data.nodeKey }}</div>
      </div>
      <!-- idle（pending）时不渲染状态点：灰色圆点会被误认为可交互 -->
      <span
        v-if="data.status && data.status !== 'pending'"
        class="fm-dot"
        :class="`fm-dot--${data.status}`"
      />
    </div>

    <div class="fm-node__summary">{{ summary }}</div>

    <!-- 条件节点：每个分支一个出口 handle，并标出分支名 -->
    <div v-if="nodeType === 'condition'" class="fm-node__branches">
      <div v-for="h in handles" :key="h" class="fm-node__branch">
        <span class="fm-node__branch-name">{{ h }}</span>
        <Handle :id="h" type="source" :position="Position.Right" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, ref } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NODE_TYPE_META, branchHandles } from '@/utils/graphModel'

// 注意：prop 不能叫 type —— Vue Flow 的插槽属性里也有 type（值为 fm-input 之类的画布类型），
// 会通过 attrs 透传覆盖掉同名 prop。
defineOptions({ inheritAttrs: false })

const props = defineProps({
  data: { type: Object, required: true },
  selected: { type: Boolean, default: false },
  nodeType: { type: String, required: true }
})

// 由编辑器页注入，避免节点组件再各自请求一次
const tools = inject('fm-tools', ref([]))
const knowledgeBases = inject('fm-knowledge', ref([]))

const meta = computed(() => NODE_TYPE_META[props.nodeType] || { label: props.nodeType, short: '?' })

// 去重：规则 handle 与默认 handle 同名时，画布上不该出现两个一样的出口
const handles = computed(() =>
  props.nodeType === 'condition' ? [...new Set(branchHandles(props.data))] : []
)

const summary = computed(() => {
  const c = props.data.config || {}
  switch (props.nodeType) {
    case 'input': {
      const n = (c.fields || []).length
      return n ? `${n} 个入参字段` : '未配置字段'
    }
    case 'llm': {
      const toolCount = Array.isArray(c.tools) ? c.tools.length : 0
      const parts = [c.model || '未选模型']
      if (toolCount) parts.push(`${toolCount} 个工具`)
      const kb = (knowledgeBases.value || []).find((k) => k.id === c.knowledge_base_id)
      if (kb) parts.push(`引用：${kb.name}`)
      return parts.join(' · ')
    }
    case 'condition': {
      const n = (c.rules || []).length
      return `${n} 条规则 · 默认 ${c.default_handle || '-'}`
    }
    case 'http': {
      const tool = (tools.value || []).find((t) => t.id === c.tool_id)
      return tool ? `${tool.displayName} · ${tool.method}` : '未选择工具'
    }
    default:
      return ''
  }
})
</script>

<style scoped>
.fm-node {
  position: relative;
  width: 208px;
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid var(--border-glass);
  background: var(--surface-glass-strong);
  backdrop-filter: blur(18px) saturate(140%);
  -webkit-backdrop-filter: blur(18px) saturate(140%);
  box-shadow: var(--shadow-card);
  color: var(--text-primary);
  transition: border-color 0.2s var(--ease), box-shadow 0.2s var(--ease), transform 0.18s var(--ease);
  animation: fm-node-in 0.18s var(--ease);
}

@keyframes fm-node-in {
  from {
    opacity: 0;
    transform: scale(0.94);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.fm-node:hover {
  border-color: var(--border-glass-strong);
}

/* 选中态：渐变描边 + 发光 */
.fm-node.is-selected {
  border: 1.6px solid transparent;
  background: linear-gradient(var(--surface-solid), var(--surface-solid)) padding-box,
    var(--gradient-brand) border-box;
  box-shadow: var(--shadow-glow), var(--shadow-card);
}

.fm-node__head {
  display: flex;
  align-items: center;
  gap: 9px;
}

.fm-node__badge {
  flex: none;
  min-width: 30px;
  padding: 3px 6px;
  border-radius: 7px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-align: center;
  color: var(--bg-base);
}

.fm-node--input .fm-node__badge {
  background: var(--node-input);
}
.fm-node--llm .fm-node__badge {
  background: var(--node-llm);
}
.fm-node--condition .fm-node__badge {
  background: var(--node-condition);
}
.fm-node--http .fm-node__badge {
  background: var(--node-http);
}

.fm-node__titles {
  min-width: 0;
  flex: 1;
}

.fm-node__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fm-node__key {
  margin-top: 1px;
  font-size: 10.5px;
  color: var(--text-faint);
}

.fm-node__summary {
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fm-node__branches {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-glass);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.fm-node__branch {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding-right: 12px;
}

.fm-node__branch-name {
  font-size: 10.5px;
  color: var(--accent-chip-text);
  font-family: var(--font-mono);
}
</style>
