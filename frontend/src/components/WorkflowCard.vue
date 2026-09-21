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
      <span class="meta-item">
        <el-icon><Connection /></el-icon>
        {{ workflow.nodeCount ?? 0 }} 个节点
      </span>
      <span class="meta-item">
        <el-icon><Clock /></el-icon>
        {{ formatDateTime(workflow.updatedAt) }}
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
  padding: 20px 20px 16px;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
  display: flex;
  flex-direction: column;
  min-height: 178px;
}

.wf-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--fm-shadow-lg);
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
  color: var(--fm-text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-card__desc {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--fm-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 44px;
}

.wf-card__meta {
  display: flex;
  gap: 16px;
  margin-top: 14px;
  font-size: 12px;
  color: #8b93a7;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.wf-card__actions {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--fm-line);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
