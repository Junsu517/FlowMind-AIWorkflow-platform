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

    <!-- 类型专属配置表单（与画布右侧配置面板共用同一份实现） -->
    <div class="node-card__block">
      <NodeConfigForm
        :node="node"
        :ref-options="refOptions"
        :tools="tools"
        :tools-loading="toolsLoading"
        :knowledge-bases="knowledgeBases"
        :knowledge-loading="knowledgeLoading"
      />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowUp, ArrowDown, Delete, Plus, Remove } from '@element-plus/icons-vue'
import NodeConfigForm from '@/components/editor/NodeConfigForm.vue'
import { NODE_TYPE_META, branchHandles, upstreamFields } from '@/utils/graphModel'

const props = defineProps({
  node: { type: Object, required: true },
  index: { type: Number, required: true },
  total: { type: Number, required: true },
  nodes: { type: Array, required: true },
  tools: { type: Array, default: () => [] },
  toolsLoading: { type: Boolean, default: false },
  knowledgeBases: { type: Array, default: () => [] },
  knowledgeLoading: { type: Boolean, default: false }
})

const emit = defineEmits(['remove', 'move-up', 'move-down'])

const meta = computed(() => NODE_TYPE_META[props.node.type] || { label: props.node.type, short: '?' })

/** 上游候选：排在前面的节点（表单式按执行顺序，连线只能来自更靠前的节点） */
const upstreamCandidates = computed(() => props.nodes.slice(0, props.index))

const refOptions = computed(() => upstreamFields(props.nodes, props.index))

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

.block-title__hint {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-faint);
}

.source-row {
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

.add-btn {
  padding-left: 0;
  color: var(--accent-chip-text);
}

@media (max-width: 900px) {
  .source-row {
    flex-wrap: wrap;
  }
}
</style>
