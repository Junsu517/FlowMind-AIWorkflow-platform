<template>
  <aside class="config">
    <template v-if="node">
      <header class="config__head">
        <span class="config__badge" :class="`config__badge--${node.data.type}`">
          {{ meta.short }}
        </span>
        <div class="config__titles">
          <el-input v-model="node.data.name" class="config__name" placeholder="节点名称" />
          <span class="config__key fm-mono">{{ node.data.nodeKey }}</span>
        </div>
        <el-tooltip content="删除节点" placement="top">
          <el-button text :icon="Delete" class="config__del" @click="emit('delete')" />
        </el-tooltip>
      </header>

      <div class="config__body">
        <NodeConfigForm
          :node="node.data"
          :ref-options="refOptions"
          :tools="tools"
          :tools-loading="toolsLoading"
          :knowledge-bases="knowledgeBases"
          :knowledge-loading="knowledgeLoading"
        />
      </div>
    </template>

    <div v-else class="config__empty">
      <el-icon class="config__empty-icon"><Aim /></el-icon>
      <p class="config__empty-title">未选中节点</p>
      <p class="config__empty-desc">点击画布上的节点进行配置，或从左侧拖入新节点。</p>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { Delete, Aim } from '@element-plus/icons-vue'
import NodeConfigForm from '@/components/editor/NodeConfigForm.vue'
import { NODE_TYPE_META } from '@/utils/graphModel'

const props = defineProps({
  /** 画布节点对象（Vue Flow node），直接就地修改其 data */
  node: { type: Object, default: null },
  tools: { type: Array, default: () => [] },
  toolsLoading: { type: Boolean, default: false },
  knowledgeBases: { type: Array, default: () => [] },
  knowledgeLoading: { type: Boolean, default: false },
  refOptions: { type: Array, default: () => [] }
})

const emit = defineEmits(['delete'])

const meta = computed(() =>
  props.node ? NODE_TYPE_META[props.node.data.type] || { label: props.node.data.type, short: '?' } : null
)
</script>

<style scoped>
.config {
  flex: none;
  width: 340px;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--border-glass);
  background: var(--overlay-1);
  overflow: hidden;
}

.config__head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border-glass);
}

.config__badge {
  flex: none;
  min-width: 34px;
  padding: 4px 7px;
  border-radius: 8px;
  font-size: 10.5px;
  font-weight: 700;
  text-align: center;
  color: var(--bg-base);
}

.config__badge--input {
  background: var(--node-input);
}
.config__badge--llm {
  background: var(--node-llm);
}
.config__badge--condition {
  background: var(--node-condition);
}
.config__badge--http {
  background: var(--node-http);
}

.config__titles {
  min-width: 0;
  flex: 1;
}

.config__name {
  width: 100%;
}

.config__key {
  display: block;
  margin-top: 3px;
  font-size: 10.5px;
  color: var(--text-faint);
}

.config__del:hover {
  color: var(--danger);
}

.config__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
}

.config__empty {
  margin: auto;
  padding: 24px;
  text-align: center;
  color: var(--text-secondary);
}

.config__empty-icon {
  font-size: 30px;
  color: var(--accent-chip-text);
  opacity: 0.8;
}

.config__empty-title {
  margin: 12px 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.config__empty-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-faint);
}
</style>
