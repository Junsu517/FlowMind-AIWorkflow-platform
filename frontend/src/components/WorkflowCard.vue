<template>
  <div class="wf-card fm-glass" @click="$emit('edit', workflow)">
    <div class="wf-card__head">
      <h3 class="wf-card__title" :title="workflow.name">{{ workflow.name }}</h3>
      <el-tag :type="statusType" size="small" effect="light" round>
        {{ statusText }}
      </el-tag>
    </div>

    <p class="wf-card__desc">
      {{ workflow.description || '暂无描述' }}
    </p>

    <div class="wf-card__meta">
      <span class="meta-badge">
        <el-icon><Connection /></el-icon>
        <b class="fm-mono">{{ workflow.nodeCount ?? 0 }}</b>
        <span>节点</span>
      </span>
      <span class="meta-time">
        <el-icon><Clock /></el-icon>
        <span class="fm-mono">{{ formatDateTime(workflow.updatedAt) }}</span>
      </span>
    </div>

    <div class="wf-card__actions" @click.stop>
      <el-button size="small" :icon="Edit" @click="$emit('edit', workflow)">编辑</el-button>
      <el-button size="small" type="danger" plain :icon="Delete" @click="$emit('delete', workflow)">
        删除
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Edit, Delete, Connection, Clock } from '@element-plus/icons-vue'
import { formatDateTime } from '@/utils/format'

const props = defineProps({
  workflow: { type: Object, required: true }
})

defineEmits(['edit', 'delete'])

const STATUS_MAP = {
  draft: { text: '草稿', type: 'info' },
  published: { text: '已发布', type: 'success' },
  archived: { text: '已归档', type: 'warning' }
}

const statusText = computed(() => STATUS_MAP[props.workflow.status]?.text || props.workflow.status || '草稿')
const statusType = computed(() => STATUS_MAP[props.workflow.status]?.type || 'info')
</script>

<style scoped>
.wf-card {
  position: relative;
  padding: 20px 20px 16px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  min-height: 186px;
  overflow: hidden;
  transition: transform 0.2s var(--ease), box-shadow 0.2s var(--ease),
    border-color 0.2s var(--ease), background-color 0.2s var(--ease);
}

/* 悬停：描边变亮 + 轻微上浮 */
.wf-card:hover {
  transform: translateY(-2px);
  border-color: var(--border-glass-strong);
  background-color: var(--overlay-4);
  box-shadow: var(--shadow-card), 0 0 30px var(--accent-soft);
}

/* 顶部一道品牌渐变高光 */
.wf-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 2px;
  background: var(--gradient-brand);
  opacity: 0;
  transition: opacity 0.2s var(--ease);
}

.wf-card:hover::before {
  opacity: 0.9;
}

.wf-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.wf-card__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-card__desc {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 44px;
}

.wf-card__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-secondary);
}

.meta-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  color: var(--accent-chip-text);
}

.meta-badge b {
  font-weight: 700;
  color: var(--text-primary);
}

.meta-time {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  opacity: 0.85;
}

/* 操作按钮：悬停/聚焦时浮现 */
.wf-card__actions {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border-glass);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.2s var(--ease), transform 0.2s var(--ease);
}

.wf-card:hover .wf-card__actions,
.wf-card:focus-within .wf-card__actions {
  opacity: 1;
  transform: translateY(0);
}

.wf-card__actions :deep(.el-button) {
  background: var(--overlay-3);
  border-color: var(--border-glass);
  color: var(--text-primary);
}

.wf-card__actions :deep(.el-button:hover) {
  border-color: var(--border-glass-strong);
  background: var(--overlay-hover);
}

.wf-card__actions :deep(.el-button--danger.is-plain) {
  color: var(--danger);
  border-color: var(--danger-border);
  background: var(--danger-soft);
}

.wf-card__actions :deep(.el-button--danger.is-plain:hover) {
  background: var(--danger-soft-strong);
  border-color: var(--danger-border-strong);
}
</style>
